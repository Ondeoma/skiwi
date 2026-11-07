package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json, JsonObject}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{DatabaseDef, DatabaseStore, OpsStore, Store}

import java.time.{Instant, LocalDate}
import java.util.UUID

/** meta: 作成日時・作成者・最終更新・最後に編集した人（自動の列用）、cover: 本文の最初の画像、excerpt: 本文の冒頭（ギャラリー用） */
final case class RowMetaDto(createdAt: Instant, createdBy: Option[String], editedAt: Instant, editedBy: Option[String], cover: Option[String], excerpt: Option[String]) derives Encoder.AsObject
final case class DatabaseRowDto(page: PageDto, values: Json, meta: Option[RowMetaDto] = None) derives Encoder.AsObject
/** 関連先のデータベース（閲覧できる項目だけ） */
final case class RelatedRowDto(id: UUID, title: String, icon: Option[String], values: Json) derives Encoder.AsObject
final case class RelatedDbDto(pageId: UUID, title: String, props: Json, rows: List[RelatedRowDto]) derives Encoder.AsObject
/** props: 列の定義、views: ビューの定義、rows: 項目（閲覧できる子ページ）、users: 値に出てくるユーザーの名前、targets: 「関連」の列の先のデータベース */
final case class DatabaseDto(pageId: UUID, props: Json, views: Json, rows: List[DatabaseRowDto], users: Map[String, String], canEdit: Boolean,
                             targets: Map[String, RelatedDbDto] = Map.empty) derives Encoder.AsObject
final case class BulkRowsReq(rows: List[NewRowReq]) derives Decoder
final case class SetDatabaseReq(props: Json, views: Json) derives Decoder
final case class DatabaseRefDto(pageId: UUID, title: String, props: Json) derives Encoder.AsObject
/** 項目の値。database: 親がデータベースならその列の定義 */
final case class PagePropsDto(database: Option[DatabaseRefDto], values: Json, users: Map[String, String], canEdit: Boolean,
                              targets: Map[String, RelatedDbDto] = Map.empty) derives Encoder.AsObject
final case class PatchPropsReq(values: JsonObject) derives Decoder
/** parentId: サブ項目にするときの親の項目 */
final case class NewRowReq(title: Option[String], values: Option[JsonObject], parentId: Option[UUID] = None) derives Decoder

/** データベースの定義（列・ビュー）と項目の値の検証。 */
object DatabaseSchema:
  val Types = Set("text", "number", "select", "multi_select", "date", "person", "checkbox", "url", "relation", "rollup",
    "formula", "created_time", "edited_time", "created_by", "edited_by", "unique_id")
  /** 値を持たない（計算・自動で決まる）列 */
  val Computed = Set("rollup", "formula", "created_time", "edited_time", "created_by", "edited_by", "unique_id")
  val RollupFns = Set("count", "count_values", "sum", "avg", "min", "max", "checked")
  val ViewTypes = Set("table", "board", "calendar", "list", "gallery", "timeline")
  private val Id = "^[A-Za-z0-9_-]{1,24}$".r

  /** target: 関連先のデータベース（relation） */
  final case class Prop(id: String, name: String, tpe: String, options: Set[String], target: Option[UUID] = None)

  private def str(o: JsonObject, k: String) = o(k).flatMap(_.asString)

  /** 列の定義を確かめる。Right: 列の一覧 */
  def props(j: Json): Either[String, List[Prop]] =
    j.asArray.toRight("props must be an array").flatMap { arr =>
      if arr.size > 50 then Left("too many props (max 50)")
      else
        arr.toList.traverse { p =>
          p.asObject.toRight("invalid prop").flatMap { o =>
            (str(o, "id"), str(o, "name"), str(o, "type")) match
              case (Some(id), Some(name), Some(t)) if Id.matches(id) && name.trim.nonEmpty && name.length <= 100 && Types(t) =>
                val opts = o("options").flatMap(_.asArray).getOrElse(Vector.empty)
                if opts.size > 100 then Left("too many options (max 100)")
                else
                  val target = str(o, "target").flatMap(x => Either.catchNonFatal(UUID.fromString(x)).toOption)
                  if t == "relation" && target.isEmpty then Left(s"$name: relation needs a target database")
                  else if t == "rollup" && !(str(o, "relation").exists(Id.matches) && str(o, "fn").exists(RollupFns)) then Left(s"$name: invalid rollup")
                  else if t == "formula" && !str(o, "expr").exists(_.length <= 2000) then Left(s"$name: formula needs an expression")
                  else
                    opts.toList.traverse(x => x.asObject.flatMap(oo => (str(oo, "id"), str(oo, "name")).tupled)
                      .filter((i, n) => Id.matches(i) && n.trim.nonEmpty && n.length <= 100).map(_._1).toRight("invalid option"))
                      .map(ids => Prop(id, name, t, ids.toSet, target))
              case _ => Left("invalid prop")
          }
        }.flatMap(ps => if ps.map(_.id).distinct.size != ps.size then Left("duplicate prop id") else Right(ps))
    }

  def views(j: Json): Either[String, Unit] =
    j.asArray.toRight("views must be an array").flatMap { arr =>
      if arr.size > 20 then Left("too many views (max 20)")
      else if j.noSpaces.length > 100000 then Left("views too large")
      else arr.toList.traverse_ { v =>
        v.asObject.flatMap(o => (str(o, "id"), str(o, "name"), str(o, "type")).tupled)
          .filter((id, n, t) => Id.matches(id) && n.length <= 100 && ViewTypes(t)).toRight("invalid view").void
      }
    }

  /** 値を確かめて正規化する（null は「消す」）。 */
  def value(p: Prop, v: Json): Either[String, Json] =
    if v.isNull then Right(Json.Null)
    else p.tpe match
      case "text"     => v.asString.filter(_.length <= 5000).map(Json.fromString).toRight(s"${p.name}: text expected")
      case "url"      => v.asString.map(_.trim).filter(s => s.isEmpty || (s.length <= 2000 && s.matches("(?i)^(https?://|mailto:).+"))).map(s => if s.isEmpty then Json.Null else Json.fromString(s)).toRight(s"${p.name}: invalid url")
      case "number"   => v.asNumber.flatMap(_.toBigDecimal).map(Json.fromBigDecimal).toRight(s"${p.name}: number expected")
      case "checkbox" => v.asBoolean.map(Json.fromBoolean).toRight(s"${p.name}: boolean expected")
      case "date"     => v.asString.filter(s => Either.catchNonFatal(LocalDate.parse(s)).isRight).map(Json.fromString).toRight(s"${p.name}: date (YYYY-MM-DD) expected")
      case "select"   => v.asString.filter(p.options).map(Json.fromString).toRight(s"${p.name}: unknown option")
      case "multi_select" =>
        v.asArray.map(_.flatMap(_.asString).distinct).filter(xs => xs.forall(p.options) && xs.size <= 100).map(xs => Json.fromValues(xs.map(Json.fromString))).toRight(s"${p.name}: unknown option")
      case "relation" =>
        v.asArray.map(_.flatMap(_.asString).distinct).filter(xs => xs.size <= 100 && xs.forall(uuidOpt(_).isDefined))
          .map(xs => Json.fromValues(xs.map(Json.fromString))).toRight(s"${p.name}: page ids expected")
      case t if Computed(t) => Left(s"${p.name}: this column is computed")
      case "person" =>
        v.asArray.map(_.flatMap(_.asString).distinct).filter(xs => xs.size <= 20 && xs.forall(uuidOpt(_).isDefined))
          .map(xs => Json.fromValues(xs.map(Json.fromString))).toRight(s"${p.name}: user ids expected")
      case _ => Left("unknown type")

  def patch(ps: List[Prop], values: JsonObject): Either[String, JsonObject] =
    values.toList.traverse { (k, v) =>
      ps.find(_.id == k).toRight(s"unknown prop: $k").flatMap(p => value(p, v)).map(k -> _)
    }.map(JsonObject.fromIterable)

/** データベースのAPI。項目は子ページなので、権限はページの権限による。 */
final class DatabaseRoutes(protected val store: Store, dbs: DatabaseStore, ops: OpsStore, protected val sessions: SessionAuth, audit: Audit, automations: Option[Automations] = None) extends PageRouteSupport:

  /** ワークスペース内の見えるページと権限（項目ごとに権限を調べないよう、まとめて解決する） */
  private def visibleIn(wsId: UUID, s: AuthSession): IO[Map[UUID, (Page, Role)]] =
    store.listPages(wsId, s).map(_.map(x => x._1.id -> x).toMap)

  /** 値に出てくるユーザーの名前 */
  private def userNames(values: List[Json]): IO[Map[String, String]] =
    val ids = values.flatMap(_.asObject.toList.flatMap(_.values)).flatMap(_.asArray.toList.flatten).flatMap(_.asString)
      .flatMap(uuidOpt).distinct
    store.userNames(ids.take(500)).map(_.map((k, v) => k.toString -> v))

  /** 本文の最初の画像（ギャラリーの表紙） */
  private def firstImage(content: Option[String]): Option[String] =
    def find(j: Json): Option[String] =
      j.asObject.flatMap { o =>
        if o("type").flatMap(_.asString).contains("image") then o("attrs").flatMap(_.hcursor.get[String]("src").toOption)
        else o("content").flatMap(_.asArray).flatMap(_.collectFirstSome(find))
      }
    content.flatMap(c => io.circe.parser.parse(c).toOption).flatMap(find)

  /** wsId: データベースのページのワークスペース（項目も同じワークスペースにある） */
  private def full(s: AuthSession, d: DatabaseDef, wsId: UUID, role: Role): IO[DatabaseDto] =
    (dbs.ensureSeq(d.pageId) *> dbs.rowsWithMeta(d.pageId), visibleIn(wsId, s)).flatMapN { (rows, vis) =>
      val visible = rows.flatMap(r => vis.get(r.id).map((p, rr) => DatabaseRowDto(PageDto.of(p, rr), parseOrNull(r.values),
        Some(RowMetaDto(r.createdAt, r.createdBy, r.editedAt, r.editedBy, firstImage(r.content), r.excerpt.filter(_.trim.nonEmpty))))))
      (store.pageMeta(visible.map(_.page.id)), targets(s, d)).flatMapN { (meta, tg) =>
        val withMeta = visible.map(r => r.copy(page = r.page.withMeta(meta.get(r.page.id))))
        userNames(withMeta.map(_.values) ++ tg.values.flatMap(_.rows.map(_.values))).map(us =>
          DatabaseDto(d.pageId, parseOrNull(d.props), parseOrNull(d.views), withMeta, us, role == Role.Edit, tg))
      }
    }

  /** 「関連」の列の先のデータベース（閲覧できるものと、その閲覧できる項目） */
  private def targets(s: AuthSession, d: DatabaseDef): IO[Map[String, RelatedDbDto]] =
    val ids = DatabaseSchema.props(parseOrNull(d.props)).toOption.toList.flatten.flatMap(_.target).distinct.take(10)
    store.pagesAccess(ids, s).flatMap(acc => ids.traverse { t =>
      dbs.get(t).flatMap(td => (acc.get(t), td) match
        case (Some((tp, _)), Some(td)) =>
          (dbs.rows(t), visibleIn(tp.workspaceId, s)).mapN { (rows, vis) =>
            val rs = rows.take(2000).flatMap(r => vis.get(r.id).map((p, _) => RelatedRowDto(p.id, p.title, p.icon, parseOrNull(r.values))))
            Some(t.toString -> RelatedDbDto(t, tp.title, parseOrNull(td.props), rs))
          }
        case _ => IO.pure(None)
      )
    }).map(_.flatten.toMap)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "database" =>
      withPage(req, id, Role.View) { (s, p, r) =>
        dbs.get(id).flatMap {
          case None    => err(Status.NotFound, "not a database")
          case Some(d) => full(s, d, p.workspaceId, r).flatMap(Ok(_))
        }
      }

    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "database" =>
      withPage(req, id, Role.Edit) { (s, p, r) =>
        body[SetDatabaseReq](req) { b =>
          (DatabaseSchema.props(b.props), DatabaseSchema.views(b.views)).tupled match
            case Left(m) => err(Status.BadRequest, m)
            case Right((ps, _)) =>
              dbs.get(id).flatMap { before =>
                dbs.upsert(id, b.props.noSpaces, b.views.noSpaces).flatMap { d =>
                  val propsChanged = before.forall(x => parseOrNull(x.props) != b.props)
                  (if propsChanged then audit.log(s, if before.isEmpty then "page.database.create" else "page.database.props", "page", id,
                     Json.obj("title" -> p.title.asJson, "props" -> ps.map(x => s"${x.name}(${x.tpe})").asJson)) else IO.unit) *>
                    full(s, d, p.workspaceId, r).flatMap(Ok(_))
                }
              }
        }
      }

    // データベースをやめる（子ページと値はそのまま残る）
    case req @ DELETE -> Root / "api" / "pages" / UUIDVar(id) / "database" =>
      withPage(req, id, Role.Edit) { (s, p, _) =>
        dbs.delete(id).flatMap(removed =>
          (if removed then audit.log(s, "page.database.delete", "page", id, Json.obj("title" -> p.title.asJson)) else IO.unit) *> NoContent())
      }

    // 項目を追加する（子ページを作って値を入れる）
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "database" / "rows" =>
      withPage(req, id, Role.Edit) { (s, p, _) =>
        body[NewRowReq](req) { b =>
          dbs.get(id).flatMap {
            case None => err(Status.NotFound, "not a database")
            case Some(d) =>
              DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps => DatabaseSchema.patch(ps, b.values.getOrElse(JsonObject.empty))) match
                case Left(m) => err(Status.BadRequest, m)
                case Right(vals) =>
                  // サブ項目: 親はこのデータベースの項目
                  val parentOk = b.parentId.fold(IO.pure(true))(pid => dbs.isRow(id, pid))
                  parentOk.flatMap {
                  case false => err(Status.BadRequest, "parent is not an item of this database")
                  case true =>
                  store.createPage(p.workspaceId, Some(b.parentId.getOrElse(id)), Placement.Last, b.title.map(_.trim.take(500)).getOrElse(""), None, s.user.id,
                    Json.fromJsonObject(vals).noSpaces).flatMap {
                    case Left(e) => fromStore(e)
                    case Right(np) =>
                      for
                        _    <- dbs.ensureSeq(id)
                        v    <- dbs.values(np.id)
                        _    <- ops.setWatch(np.id, s.user.id, on = true)
                        _    <- automations.traverse_(_.fire(AutoEvent("page_created", np.workspaceId, np.id, s.user.name)))
                        _    <- audit.log(s, "page.create", "page", np.id, Json.obj("title" -> np.title.asJson, "workspaceId" -> np.workspaceId.toString.asJson, "database" -> id.toString.asJson))
                        resp <- Created(DatabaseRowDto(PageDto.of(np, Role.Edit), v.map(parseOrNull).getOrElse(Json.obj()),
                                  Some(RowMetaDto(np.createdAt, Some(s.user.name), np.updatedAt, None, None, None))))
                      yield resp
                  }
                  }
          }
        }
      }

    // 項目をまとめて追加する（CSVの取り込み用。最大2000件）
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "database" / "rows" / "bulk" =>
      withPage(req, id, Role.Edit) { (s, p, _) =>
        body[BulkRowsReq](req) {
          case b if b.rows.size > 2000 => err(Status.BadRequest, "too many rows (max 2000)")
          case b =>
            dbs.get(id).flatMap {
              case None => err(Status.NotFound, "not a database")
              case Some(d) =>
                val checked = DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps => b.rows.traverse(r => DatabaseSchema.patch(ps, r.values.getOrElse(JsonObject.empty)).map(r -> _)))
                checked match
                  case Left(m) => err(Status.BadRequest, m)
                  case Right(rows) =>
                    val items = rows.map((r, vals) => (r.title.map(_.trim.take(500)).getOrElse(""), Json.fromJsonObject(vals).noSpaces))
                    store.createPages(p.workspaceId, id, items, s.user.id).map(_.getOrElse(0)).flatTap(_ => dbs.ensureSeq(id)).flatMap { n =>
                      audit.log(s, "page.database.import", "page", id, Json.obj("title" -> p.title.asJson, "rows" -> n.asJson)) *>
                        Ok(Json.obj("created" -> n.asJson))
                    }
            }
        }
      }

    // 項目の値（親がデータベースなら、その列の定義と一緒に）
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "props" =>
      withPage(req, id, Role.View) { (s, _, r) =>
        for
          pdef   <- dbs.databaseOf(id).flatMap(_.flatTraverse(d => store.pageAccess(d.pageId, s).map(_.map((pp, _) => (d, DatabaseRefDto(d.pageId, pp.title, parseOrNull(d.props)))))))
          vals   <- dbs.values(id).map(_.map(parseOrNull).getOrElse(Json.obj()))
          tg     <- pdef.fold(IO.pure(Map.empty[String, RelatedDbDto]))((d, _) => targets(s, d))
          us     <- userNames(List(vals) ++ tg.values.flatMap(_.rows.map(_.values)))
          resp   <- Ok(PagePropsDto(pdef.map(_._2), vals, us, r == Role.Edit, tg))
        yield resp
      }

    case req @ PATCH -> Root / "api" / "pages" / UUIDVar(id) / "props" =>
      withPage(req, id, Role.Edit) { (s, p, _) =>
        body[PatchPropsReq](req) { b =>
          dbs.databaseOf(id).flatMap {
            case None => err(Status.BadRequest, "parent is not a database")
            case Some(d) =>
              DatabaseSchema.props(parseOrNull(d.props)).flatMap(ps => DatabaseSchema.patch(ps, b.values)) match
                case Left(m) => err(Status.BadRequest, m)
                case Right(vals) =>
                  dbs.patchValues(id, Json.fromJsonObject(vals).noSpaces).flatMap {
                    case None => err(Status.NotFound, "page not found")
                    case Some(v) =>
                      val parsed = parseOrNull(v)
                      automations.traverse_(_.fire(AutoEvent("value_changed", p.workspaceId, id, s.user.name,
                        JsonObject("database" -> d.pageId.toString.asJson, "values" -> Json.fromJsonObject(vals))))) *> {
                      userNames(List(parsed)).flatMap(us => Ok(PagePropsDto(None, parsed, us, true)))
                      }
                  }
          }
        }
      }
  }
