package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{DatabaseDef, DatabaseStore, OpsStore, Store}

import java.time.Instant
import java.util.UUID

/** meta: 作成日時・作成者・最終更新・最後に編集した人（自動の列用）、cover: 本文の最初の画像、excerpt: 本文の冒頭（ギャラリー用） */
final case class RowMetaDto(createdAt: Instant,
                            createdBy: Option[String],
                            editedAt: Instant,
                            editedBy: Option[String],
                            cover: Option[String],
                            excerpt: Option[String]) derives Encoder.AsObject
final case class DatabaseRowDto(page: PageDto, values: Json, meta: Option[RowMetaDto] = None) derives Encoder.AsObject

/** 関連先のデータベース（閲覧できる項目だけ） */
final case class RelatedRowDto(id: UUID, title: String, icon: Option[String], values: Json) derives Encoder.AsObject
final case class RelatedDbDto(pageId: UUID, title: String, props: Json, rows: List[RelatedRowDto])
  derives Encoder.AsObject

/** props: 列の定義、views: ビューの定義、rows: 項目（閲覧できる子ページ）、users: 値に出てくるユーザーの名前、targets: 「関連」の列の先のデータベース */
final case class DatabaseDto(pageId: UUID,
                             props: Json,
                             views: Json,
                             rows: List[DatabaseRowDto],
                             users: Map[String, String],
                             canEdit: Boolean,
                             targets: Map[String, RelatedDbDto] = Map.empty) derives Encoder.AsObject
final case class BulkRowsReq(rows: List[NewRowReq]) derives Decoder
final case class SetDatabaseReq(props: Json, views: Json) derives Decoder
final case class DatabaseRefDto(pageId: UUID, title: String, props: Json) derives Encoder.AsObject

/** 項目の値。database: 親がデータベースならその列の定義 */
final case class PagePropsDto(database: Option[DatabaseRefDto],
                              values: Json,
                              users: Map[String, String],
                              canEdit: Boolean,
                              targets: Map[String, RelatedDbDto] = Map.empty) derives Encoder.AsObject
final case class PatchPropsReq(values: JsonObject) derives Decoder

/** parentId: サブ項目にするときの親の項目 */
final case class NewRowReq(title: Option[String], values: Option[JsonObject], parentId: Option[UUID] = None)
  derives Decoder

/** データベースのAPI。項目は子ページなので、権限はページの権限による。 */
final class DatabaseRoutes(protected val store: Store,
                           dbs: DatabaseStore,
                           ops: OpsStore,
                           protected val sessions: SessionAuth,
                           audit: Audit,
                           automations: Option[Automations] = None) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "database" => getPageDatabase(req, id)
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "database" => putPageDatabase(req, id)
    case req @ DELETE -> Root / "api" / "pages" / UUIDVar(id) / "database" => deletePageDatabase(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "database" / "rows" => postPageDatabaseRows(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "database" / "rows" / "bulk" =>
      postPageDatabaseRowsBulk(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "props" => getPageProps(req, id)
    case req @ PATCH -> Root / "api" / "pages" / UUIDVar(id) / "props" => patchPageProps(req, id)
  }

  private def getPageDatabase(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.View) {
      (s, p, r) =>
        dbs.get(id).flatMap {
          case None => err(ErrorCode.NotADatabasePage)
          case Some(d) => full(s, d, p.workspaceId, r).flatMap(Ok(_))
        }
    }

  private def putPageDatabase(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (s, p, r) =>
        body[SetDatabaseReq](req) { b =>
          (DatabaseSchema.props(b.props), DatabaseSchema.views(b.views)).tupled match {
            case Left(m) => err(ErrorCode.InvalidInput, m)
            case Right((ps, _)) =>
              dbs.get(id).flatMap { before =>
                dbs.upsert(id, b.props.noSpaces, b.views.noSpaces).flatMap { d =>
                  val propsChanged = before.forall(x => parseOrNull(x.props) != b.props)
                  (if propsChanged then
                     audit.log(
                       s,
                       if before.isEmpty then "page.database.create" else "page.database.props",
                       "page",
                       id,
                       Json.obj("title" -> p.title.asJson, "props" -> ps.map(x => s"${x.name}(${x.tpe})").asJson)
                     )
                   else IO.unit) *>
                    full(s, d, p.workspaceId, r).flatMap(Ok(_))
                }
              }
          }
        }
    }

  private def deletePageDatabase(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (s, p, _) =>
        dbs.delete(id).flatMap(removed =>
          (if removed then audit.log(s, "page.database.delete", "page", id, Json.obj("title" -> p.title.asJson))
           else IO.unit) *> NoContent()
        )
    }

  private def postPageDatabaseRows(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (s, p, _) =>
        body[NewRowReq](req) { b =>
          dbs.get(id).flatMap {
            case None => err(ErrorCode.NotADatabasePage)
            case Some(d) =>
              DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps =>
                DatabaseSchema.patch(ps, b.values.getOrElse(JsonObject.empty))
              ) match {
                case Left(m) => err(ErrorCode.InvalidInput, m)
                case Right(vals) =>
                  // サブ項目: 親はこのデータベースの項目
                  val parentOk = b.parentId.fold(IO.pure(true))(pid => dbs.isRow(id, pid))
                  parentOk.flatMap {
                    case false => err(ErrorCode.DatabaseParentNotRow)
                    case true =>
                      // サブ項目は親の項目の下に作るので、親の項目も編集できること（保護中・個別に権限を絞った項目の下には作らせない）
                      b.parentId.fold(IO.pure(true))(pid =>
                        store.pageAccess(pid, s).map(_.exists(_._2 == Role.Edit))
                      ).ifM(createRow(s, p, id, b, vals), err(ErrorCode.PermissionDenied))
                  }
              }
          }
        }
    }

  /** データベース id に項目（parentId があればそのサブ項目）を作る（権限の確認は呼び出し側で行う） */
  private def createRow(s: AuthSession, p: Page, id: UUID, b: NewRowReq, vals: JsonObject): IO[Response[IO]] =
    store.createPage(
      p.workspaceId,
      Some(b.parentId.getOrElse(id)),
      Placement.Last,
      b.title.map(_.trim.take(MaxPageTitle)).getOrElse(""),
      None,
      s.user.id,
      Json.fromJsonObject(vals).noSpaces
    ).flatMap {
      case Left(e) => fail(e)
      case Right(np) =>
        for {
          _ <- dbs.ensureSeq(id)
          v <- dbs.values(np.id)
          _ <- ops.setWatch(np.id, s.user.id, on = true)
          _ <- automations.traverse_(_.fire(AutoEvent(
            "page_created",
            np.workspaceId,
            np.id,
            s.user.name
          )))
          _ <- audit.log(
            s,
            "page.create",
            "page",
            np.id,
            Json.obj(
              "title" -> np.title.asJson,
              "workspaceId" -> np.workspaceId.toString.asJson,
              "database" -> id.toString.asJson
            )
          )
          resp <- Created(DatabaseRowDto(
            PageDto.of(np, Role.Edit),
            v.map(parseOrNull).getOrElse(Json.obj()),
            Some(RowMetaDto(np.createdAt, Some(s.user.name), np.updatedAt, None, None, None))
          ))
        } yield resp
    }

  private def postPageDatabaseRowsBulk(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (s, p, _) =>
        body[BulkRowsReq](req) {
          case b if b.rows.size > 2000 => err(ErrorCode.DatabaseTooManyRows)
          case b =>
            dbs.get(id).flatMap {
              case None => err(ErrorCode.NotADatabasePage)
              case Some(d) =>
                val checked = DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps =>
                  b.rows.traverse(r => DatabaseSchema.patch(ps, r.values.getOrElse(JsonObject.empty)).map(r -> _))
                )
                checked match {
                  case Left(m) => err(ErrorCode.InvalidInput, m)
                  case Right(rows) =>
                    val items = rows.map((r, vals) =>
                      (r.title.map(_.trim.take(MaxPageTitle)).getOrElse(""), Json.fromJsonObject(vals).noSpaces)
                    )
                    store.createPages(p.workspaceId, id, items, s.user.id).map(_.getOrElse(0)).flatTap(_ =>
                      dbs.ensureSeq(id)
                    ).flatMap { n =>
                      audit.log(
                        s,
                        "page.database.import",
                        "page",
                        id,
                        Json.obj("title" -> p.title.asJson, "rows" -> n.asJson)
                      ) *>
                        Ok(Json.obj("created" -> n.asJson))
                    }
                }
            }
        }
    }

  private def getPageProps(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.View) {
      (s, _, r) =>
        for {
          pdef <- dbs.databaseOf(id).flatMap(_.flatTraverse(d =>
            store.pageAccess(d.pageId, s).map(_.map((pp, _) =>
              (d, DatabaseRefDto(d.pageId, pp.title, parseOrNull(d.props)))
            ))
          ))
          vals <- dbs.values(id).map(_.map(parseOrNull).getOrElse(Json.obj()))
          tg <- pdef.fold(IO.pure(Map.empty[String, RelatedDbDto]))((d, _) => targets(s, d))
          us <- userNames(List(vals) ++ tg.values.flatMap(_.rows.map(_.values)))
          resp <- Ok(PagePropsDto(pdef.map(_._2), vals, us, r == Role.Edit, tg))
        } yield resp
    }

  private def patchPageProps(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (s, p, _) =>
        body[PatchPropsReq](req) { b =>
          dbs.databaseOf(id).flatMap {
            case None => err(ErrorCode.ParentNotDatabase)
            case Some(d) =>
              DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps => DatabaseSchema.patch(ps, b.values)) match {
                case Left(m) => err(ErrorCode.InvalidInput, m)
                case Right(vals) =>
                  dbs.patchValues(id, Json.fromJsonObject(vals).noSpaces).flatMap {
                    case None => err(ErrorCode.PageNotFound)
                    case Some(v) =>
                      val parsed = parseOrNull(v)
                      automations.traverse_(_.fire(AutoEvent(
                        "value_changed",
                        p.workspaceId,
                        id,
                        s.user.name,
                        JsonObject("database" -> d.pageId.toString.asJson, "values" -> Json.fromJsonObject(vals))
                      ))) *> {
                        userNames(List(parsed)).flatMap(us => Ok(PagePropsDto(None, parsed, us, true)))
                      }
                  }
              }
          }
        }
    }

  /** ワークスペース内の見えるページと権限（項目ごとに権限を調べないよう、まとめて解決する） */
  private def visibleIn(wsId: UUID, s: AuthSession): IO[Map[UUID, (Page, Role)]] =
    store.listPages(wsId, s).map(_.map(x => x._1.id -> x).toMap)

  /** 値に出てくるユーザーの名前 */
  private def userNames(values: List[Json]): IO[Map[String, String]] = {
    val ids = values.flatMap(_.asObject.toList.flatMap(_.values)).flatMap(_.asArray.toList.flatten).flatMap(_.asString)
      .flatMap(uuidOpt).distinct
    store.userNames(ids.take(500)).map(_.map((k, v) => k.toString -> v))
  }

  /** 本文の最初の画像（ギャラリーの表紙） */
  private def firstImage(content: Option[String]): Option[String] =
    content.flatMap(c => io.circe.parser.parse(c).toOption).flatMap(firstImageSrc)

  /** wsId: データベースのページのワークスペース（項目も同じワークスペースにある） */
  private def full(s: AuthSession, d: DatabaseDef, wsId: UUID, role: Role): IO[DatabaseDto] =
    (dbs.ensureSeq(d.pageId) *> dbs.rowsWithMeta(d.pageId), visibleIn(wsId, s)).flatMapN {
      (rows, vis) =>
        val visible = rows.flatMap(r =>
          vis.get(r.id).map((p, rr) =>
            DatabaseRowDto(
              PageDto.of(p, rr),
              parseOrNull(r.values),
              Some(RowMetaDto(
                r.createdAt,
                r.createdBy,
                r.editedAt,
                r.editedBy,
                firstImage(r.content),
                r.excerpt.filter(_.trim.nonEmpty)
              ))
            )
          )
        )
        (store.pageMeta(visible.map(_.page.id)), targets(s, d, Map(wsId -> vis))).flatMapN {
          (meta, tg) =>
            val withMeta = visible.map(r => r.copy(page = r.page.withMeta(meta.get(r.page.id))))
            userNames(withMeta.map(_.values) ++ tg.values.flatMap(_.rows.map(_.values))).map(us =>
              DatabaseDto(d.pageId, parseOrNull(d.props), parseOrNull(d.views), withMeta, us, role == Role.Edit, tg)
            )
        }
    }

  /** 「関連」の列の先のデータベース（閲覧できるものと、その閲覧できる項目） */
  /** known: すでに解決済みの、ワークスペースごとの見えるページ（読み直さない） */
  private def targets(s: AuthSession,
                      d: DatabaseDef,
                      known: Map[UUID, Map[UUID, (Page, Role)]] = Map.empty): IO[Map[String, RelatedDbDto]] = {
    val ids = DatabaseSchema.props(parseOrNull(d.props)).toOption.toList.flatten.flatMap(_.target).distinct.take(10)
    store.pagesAccess(ids, s).flatMap { acc =>
      // 見えるページの解決はワークスペースごとに 1 回だけ（関連先はたいてい同じワークスペースにある）
      val wsIds = ids.flatMap(acc.get).map(_._1.workspaceId).distinct.filterNot(known.contains)
      wsIds.traverse(w => visibleIn(w, s).map(w -> _)).map(known ++ _).flatMap { visByWs =>
        ids.traverse { t =>
          dbs.get(t).flatMap(td =>
            (acc.get(t), td) match {
              case (Some((tp, _)), Some(td)) =>
                dbs.rows(t).map { rows =>
                  val vis = visByWs.getOrElse(tp.workspaceId, Map.empty)
                  val rs = rows.take(2000).flatMap(r =>
                    vis.get(r.id).map((p, _) => RelatedRowDto(p.id, p.title, p.icon, parseOrNull(r.values)))
                  )
                  Some(t.toString -> RelatedDbDto(t, tp.title, parseOrNull(td.props), rs))
                }
              case _ => IO.pure(None)
            }
          )
        }
      }
    }.map(_.flatten.toMap)
  }

}
