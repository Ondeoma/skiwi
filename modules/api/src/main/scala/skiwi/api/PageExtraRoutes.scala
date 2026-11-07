package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{DocStore, OpsStore, Store}

import java.time.Instant
import java.util.UUID

final case class LabelsDto(labels: List[String]) derives Encoder.AsObject, Decoder
final case class RecentDto(page: PageDto, workspaceName: String, at: Instant) derives Encoder.AsObject
final case class NotificationDto(
    id: Long,
    kind: String,
    actors: List[String],
    role: Option[String],
    pageId: Option[UUID],
    pageTitle: Option[String],
    pageIcon: Option[String],
    workspaceId: Option[UUID],
    workspaceName: Option[String],
    createdAt: Instant,
    read: Boolean,
    excerpt: Option[String] = None,
    threadId: Option[String] = None,
    due: Option[String] = None,
    requestId: Option[String] = None
) derives Encoder.AsObject
final case class NotificationsDto(unread: Int, items: List[NotificationDto]) derives Encoder.AsObject
final case class MarkReadReq(ids: Option[List[Long]]) derives Decoder
final case class FavoritePageDto(page: PageDto, workspaceName: String) derives Encoder.AsObject
final case class TaskDto(pageId: UUID, workspaceId: UUID, workspaceName: String, pageTitle: String, pageIcon: Option[String],
                         seq: Int, text: String, checked: Boolean, due: Option[java.time.LocalDate]) derives Encoder.AsObject
/** reason: missing（ページが存在しない）| deleted（ゴミ箱）| no_access（閲覧できない）。閲覧できない先のタイトルは出さない */
/** incoming: 本文からのリンク数（ほかのページから）。lastEditedAt: 本文・タイトルの最終更新 */
final case class PageHealthDto(page: PageDto, incoming: Long, lastEditedAt: Instant, lastEditor: Option[String], children: Long) derives Encoder.AsObject
final case class BrokenLinkDto(sourceId: UUID, sourceTitle: String, targetId: UUID, targetTitle: Option[String], reason: String) derives Encoder.AsObject

/** ページまわりの補助機能: ラベル、被リンク、最近見た/編集したページ、画面内の通知。 */
final class PageExtraRoutes(protected val store: Store, docs: DocStore, ops: OpsStore, protected val sessions: SessionAuth, audit: Audit, automations: Option[Automations] = None) extends PageRouteSupport:

  private def wsNames(s: AuthSession): IO[Map[UUID, String]] = store.listWorkspaces(s).map(_.map((w, _) => w.id -> w.name).toMap)

  /** 見えるページ（順序は保つ）と、そのワークスペース名。 */
  private def visibleWithWs(s: AuthSession, ids: List[UUID]): IO[List[(PageDto, String)]] =
    (store.visiblePages(ids, s), wsNames(s)).parMapN((ps, ws) => ps.flatMap((p, r) => ws.get(p.workspaceId).map(PageDto.of(p, r) -> _)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ ラベル
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "labels" =>
      withPage(req, id, Role.View)((_, _, _) => store.labels(id).flatMap(ls => Ok(LabelsDto(ls))))

    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "labels" =>
      withPage(req, id, Role.Edit) { (s, p, _) =>
        body[LabelsDto](req) { b =>
          val ls = Labels.normalize(b.labels)
          store.labels(id).flatMap { before =>
            store.setLabels(id, ls) *>
              (if before.sorted == ls.sorted then IO.unit
               else audit.log(s, "page.labels", "page", id, Json.obj("title" -> p.title.asJson, "labels" -> ls.asJson))) *>
              {
                val added = ls.filterNot(l => before.exists(_.equalsIgnoreCase(l)))
                if added.isEmpty then IO.unit
                else automations.traverse_(_.fire(AutoEvent("label_added", p.workspaceId, id, s.user.name, io.circe.JsonObject("labels" -> added.asJson))))
              } *>
              Ok(LabelsDto(ls.sortBy(_.toLowerCase)))
          }
        }
      }

    // ------------------------------------------------------------ 被リンク
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "backlinks" =>
      withPage(req, id, Role.View) { (s, _, _) =>
        docs.backlinks(id).flatMap(ids => store.visiblePages(ids.take(200), s)).flatMap(ps => Ok(ps.map(PageDto.of)))
      }

    // ------------------------------------------------------------ 最近
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "visit" =>
      withPage(req, id, Role.View)((s, _, _) => ops.recordVisit(s.user.id, id) *> NoContent())

    case req @ GET -> Root / "api" / "recent" =>
      authed(req) { s =>
        val kind  = req.uri.query.params.get("kind").filter(_ == "edited").getOrElse("visited")
        val limit = req.uri.query.params.get("limit").flatMap(_.toIntOption).map(_.max(1).min(50)).getOrElse(20)
        for
          rows <- ops.recentPages(s.user.id, kind, limit)
          ps   <- visibleWithWs(s, rows.map(_._1))
          at    = rows.toMap
          resp <- Ok(ps.map((p, n) => RecentDto(p, n, at(p.id))))
        yield resp
      }

    // ------------------------------------------------------------ ページのお気に入り
    case req @ GET -> Root / "api" / "favorites" / "pages" =>
      authed(req) { s =>
        for
          ps   <- ops.favoritePages(s.user.id).flatMap(visibleWithWs(s, _))
          resp <- Ok(ps.map((p, n) => FavoritePageDto(p, n)))
        yield resp
      }

    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "favorite" =>
      withPage(req, id, Role.View) { (s, _, _) =>
        body[FavoriteDto](req) { b =>
          ops.setPageFavorite(s.user.id, id, b.favorite) *> Ok(b)
        }
      }

    // ------------------------------------------------------------ 取り込み（空のページに本文を入れる）
    // 本文の Yjs 状態はクライアントが作る（サーバーは Yjs を解釈しない）。既に本文があるページには入れない
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "import" =>
      withPage(req, id, Role.Edit) { (s, p, _) =>
        body[DocStateReq](req) {
          case b if b.content.isObject =>
            decodeYdoc(b.ydoc) match
              case None => err(Status.BadRequest, "invalid ydoc")
              case Some(y) =>
                docs.isEmpty(id).flatMap {
                  case false => err(Status.Conflict, "page already has content")
                  case true =>
                    docs.saveCompaction(id, y, b.content, b.text, Set.empty, Set.empty, s.user.id, Set(s.user.id)) *>
                      ops.enqueuePageEdited(id, Set(s.user.id), 300, 1800) *>
                      audit.log(s, "page.import", "page", id, Json.obj("title" -> p.title.asJson)) *> NoContent()
                }
          case _ => err(Status.BadRequest, "invalid body")
        }
      }

    // ------------------------------------------------------------ ToDo（自分に割り当てられたもの）
    case req @ GET -> Root / "api" / "tasks" =>
      authed(req) { s =>
        val done = req.uri.query.params.get("done").contains("true")
        for
          rows <- ops.myTasks(s.user.id, done)
          okWs <- (store.pagesAccess(rows.map(_.pageId), s).map(_.keySet), wsNames(s)).parTupled
          (ok, ws) = okWs
          resp <- Ok(rows.filter(t => ok(t.pageId)).map(t =>
                    TaskDto(t.pageId, t.workspaceId, ws.getOrElse(t.workspaceId, ""), t.pageTitle, t.pageIcon, t.seq, t.text, t.checked, t.due)))
        yield resp
      }

    // ------------------------------------------------------------ リンク切れ（見えるページから出ているもの）
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "broken-links" =>
      authed(req) { s =>
        store.listPages(id, s).flatMap { visible =>
          if visible.isEmpty then store.workspaceRole(id, s).flatMap(r => if r.isEmpty then err(Status.NotFound, "workspace not found") else Ok(List.empty[BrokenLinkDto]))
          else
            val sources = visible.map(_._1.id).toSet
            store.linkTargets(id).flatMap { rows =>
              val mine = rows.filter(r => sources(r.sourceId))
              (store.pagesAccess(mine.filter(r => r.targetExists && !r.targetDeleted).map(_.targetId), s),
               store.pagesAccessAny(mine.filter(_.targetDeleted).map(_.targetId), s)).flatMapN { (canSee, deleted) =>
                Ok(mine.flatMap { r =>
                  if !r.targetExists then Some(BrokenLinkDto(r.sourceId, r.sourceTitle, r.targetId, None, "missing"))
                  else if r.targetDeleted then Some(BrokenLinkDto(r.sourceId, r.sourceTitle, r.targetId, deleted.get(r.targetId).map(_._1.title), "deleted"))
                  else if !canSee.contains(r.targetId) then Some(BrokenLinkDto(r.sourceId, r.sourceTitle, r.targetId, None, "no_access"))
                  else None
                })
              }
            }
        }
      }

    // ------------------------------------------------------------ 点検（孤立ページ・長く更新されていないページ）。見えるページだけ
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "health" =>
      authed(req) { s =>
        store.listPages(id, s).flatMap { visible =>
          if visible.isEmpty then store.workspaceRole(id, s).flatMap(r => if r.isEmpty then err(Status.NotFound, "workspace not found") else Ok(List.empty[PageHealthDto]))
          else
            (store.pageHealth(id), store.pageMeta(visible.map(_._1.id))).flatMapN { (rows, meta) =>
              val byId = rows.map(r => r.pageId -> r).toMap
              Ok(visible.flatMap((p, r) => byId.get(p.id).map(h =>
                PageHealthDto(PageDto.of(p, r).withMeta(meta.get(p.id)),
                  h.incoming, h.lastEditedAt, h.lastEditor, h.children))))
            }
        }
      }

    // ------------------------------------------------------------ 通知
    case req @ GET -> Root / "api" / "notifications" =>
      authed(req) { s =>
        for
          rows  <- ops.notifications(s.user.id, 50)
          // 今は見られないページの通知は出さない
          canSee <- store.pagesAccess(rows.flatMap(_.pageId), s)
          ok     = rows.filter(_.pageId.forall(canSee.contains))
          count <- ops.unreadNotifications(s.user.id)
          items  = ok.map { n =>
                     val detail = io.circe.parser.parse(n.detail).toOption
                     NotificationDto(n.id, n.kind, n.actors, detail.flatMap(_.hcursor.get[String]("role").toOption),
                       n.pageId, n.pageTitle, n.pageIcon, n.pageWorkspaceId.orElse(n.workspaceId),
                       n.workspaceName.orElse(detail.flatMap(_.hcursor.get[String]("workspace").toOption)), n.createdAt, n.readAt.isDefined,
                       detail.flatMap(_.hcursor.get[String]("excerpt").toOption).filter(_.nonEmpty),
                       detail.flatMap(_.hcursor.get[String]("threadId").toOption),
                       detail.flatMap(_.hcursor.get[String]("due").toOption),
                       detail.flatMap(_.hcursor.get[String]("requestId").toOption))
                   }
          resp  <- Ok(NotificationsDto(count, items))
        yield resp
      }

    case req @ POST -> Root / "api" / "notifications" / "read" =>
      authed(req) { s =>
        body[MarkReadReq](req) { b =>
          ops.markNotificationsRead(s.user.id, b.ids) *> ops.unreadNotifications(s.user.id).flatMap(n => Ok(Json.obj("unread" -> n.asJson)))
        }
      }
  }
