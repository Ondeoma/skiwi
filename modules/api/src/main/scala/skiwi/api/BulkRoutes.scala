package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.Store

import java.util.UUID

/** ラベルの正規化: 前後の空白・# を除き、40文字まで、大文字小文字の違いで重複させない（最大20個）。 */
object Labels:
  def normalize(ls: List[String]): List[String] =
    ls.map(_.trim.stripPrefix("#").trim.replaceAll("\\s+", " ").take(40)).filter(_.nonEmpty).distinctBy(_.toLowerCase).take(20)

/** action: move | delete | labels | owner | status
  *  - move: parentId（null=ワークスペース直下）、workspaceId（別のワークスペースへ）。選んだ順に末尾へ並べる
  *  - labels: addLabels / removeLabels
  *  - owner: ownerId（null=外す）、status: status（null=外す） */
final case class BulkReq(
    ids: List[UUID], action: String,
    parentId: Option[Option[UUID]] = None, workspaceId: Option[UUID] = None,
    addLabels: List[String] = Nil, removeLabels: List[String] = Nil,
    ownerId: Option[Option[UUID]] = None, status: Option[Option[String]] = None
)
object BulkReq:
  given Decoder[BulkReq] = Decoder.instance { c =>
    for
      ids    <- c.get[List[UUID]]("ids")
      action <- c.get[String]("action")
      parent <- tri[UUID](c, "parentId")
      ws     <- c.get[Option[UUID]]("workspaceId")
      add    <- c.get[Option[List[String]]]("addLabels")
      rm     <- c.get[Option[List[String]]]("removeLabels")
      owner  <- tri[UUID](c, "ownerId")
      status <- tri[String](c, "status")
    yield BulkReq(ids, action, parent, ws, add.getOrElse(Nil), rm.getOrElse(Nil), owner, status)
  }
final case class BulkFailDto(id: UUID, title: Option[String], error: String) derives Encoder.AsObject
/** done: 処理したページ（子ページごと移動・削除したものは、選んだページだけ）。skipped: 選んだ祖先と一緒に処理されたページ */
final case class BulkResultDto(done: List[UUID], skipped: List[UUID], failed: List[BulkFailDto]) derives Encoder.AsObject

/** ページツリーの一括操作（移動・削除・ラベル・所有者・状態）。ページごとに権限を確認し、できなかったものは理由を返す。 */
final class BulkRoutes(protected val store: Store, protected val sessions: SessionAuth, audit: Audit, accessChanged: AccessChanged = IO.unit, webhooks: Option[WebhookService] = None, automations: Option[Automations] = None) extends PageRouteSupport:


  private val MaxPages = 500

  /** 親（なければワークスペース直下）に子を作れるか（作れなければ理由） */
  private def targetError(s: AuthSession, wsId: UUID, parent: Option[UUID]): IO[Option[String]] =
    canEditUnder(s, wsId, parent).map(_.swap.toOption.map {
      case EditUnderError.Forbidden         => "移動先の権限がありません"
      case EditUnderError.ParentNotFound    => "移動先のページが見つかりません"
      case EditUnderError.WorkspaceNotFound => "ワークスペースが見つかりません"
    })

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "pages" / "bulk" =>
      authed(req) { s =>
        body[BulkReq](req) {
          case b if b.ids.isEmpty || b.ids.size > MaxPages => err(Status.BadRequest, s"ページを1〜${MaxPages}件選んでください")
          case b if !Set("move", "delete", "labels", "owner", "status").contains(b.action) => err(Status.BadRequest, "アクションが正しくありません")
          case b if b.status.flatten.exists(st => !UpdatePageReq.Statuses(st)) => err(Status.BadRequest, "状態が正しくありません")
          case b => run(s, b)
        }
      }
  }

  private def run(s: AuthSession, b: BulkReq): IO[Response[IO]] =
    val ids = b.ids.distinct
    store.pagesAccess(ids, s).flatMap { found =>
      val notFound = ids.filterNot(found.contains).map(id => BulkFailDto(id, None, "ページが見つかりません"))
      val wsIds = found.values.map(_._1.workspaceId).toSet
      if wsIds.size > 1 then err(Status.BadRequest, "同じワークスペースのページを選んでください")
      else if found.isEmpty then Ok(BulkResultDto(Nil, Nil, notFound))
      else
        val wsId = wsIds.head
        // 選んだページの祖先も選ばれていれば、そのページは祖先と一緒に処理される（移動・削除のみ）
        store.listPages(wsId, s).flatMap { all =>
          val parentOf = all.map((p, _) => p.id -> p.parentId).toMap
          def underSelected(id: UUID): Boolean =
            Iterator.iterate(parentOf.get(id).flatten)(_.flatMap(p => parentOf.get(p).flatten)).takeWhile(_.isDefined).flatten.exists(found.contains)
          val treeAction = b.action == "move" || b.action == "delete"
          val (skipped, targets) = ids.filter(found.contains).partition(id => treeAction && underSelected(id))
          val denied = targets.filter(id => found(id)._2 == Role.View).map(id => BulkFailDto(id, Some(found(id)._1.title), "権限がありません"))
          val todo = targets.filterNot(id => found(id)._2 == Role.View)
          perform(s, b, wsId, todo.map(found(_)._1)).flatMap { (done, failed) =>
            val detail = Json.obj("action" -> b.action.asJson, "count" -> done.size.asJson, "titles" -> todo.filter(done.contains).take(50).map(found(_)._1.title).asJson)
            (if done.nonEmpty then audit.log(s, s"page.bulk.${b.action}", "workspace", wsId, detail) else IO.unit) *>
              (if done.nonEmpty && treeAction then accessChanged else IO.unit) *>
              Ok(BulkResultDto(done, skipped, notFound ++ denied ++ failed))
          }
        }
    }

  private def perform(s: AuthSession, b: BulkReq, wsId: UUID, pages: List[Page]): IO[(List[UUID], List[BulkFailDto])] =
    def each(f: Page => IO[Option[String]]): IO[(List[UUID], List[BulkFailDto])] =
      pages.traverse(p => f(p).handleError(e => Some(e.getMessage)).map(p -> _)).map { rs =>
        (rs.collect { case (p, None) => p.id }, rs.collect { case (p, Some(e)) => BulkFailDto(p.id, Some(p.title), e) })
      }
    b.action match
      case "move" =>
        val target = b.workspaceId.getOrElse(wsId)
        val parent = b.parentId.flatten
        targetError(s, target, parent).flatMap {
          case Some(e) => IO.pure((Nil, pages.map(p => BulkFailDto(p.id, Some(p.title), e))))
          case None =>
            each { p =>
              (if target != wsId then store.moveToWorkspace(p.id, target, parent, Placement.Last)
               else store.movePage(p.id, parent, Placement.Last)).map(_.left.toOption.map(_.message))
            }
        }
      case "delete" =>
        each { p =>
          uneditableDescendants(s, p.id, inTrash = false).flatMap {
            case n if n > 0 => IO.pure(Some(descendantsBlocked(n)))
            case _ => store.deletePage(p.id, Some(s.user.id)) *> webhooks.traverse_(_.emit("page.deleted", p.id, s.user.name)).as(None)
          }
        }
      case "labels" =>
        val add = Labels.normalize(b.addLabels)
        val rm = Labels.normalize(b.removeLabels).map(_.toLowerCase).toSet
        each { p =>
          store.labels(p.id).flatMap { cur =>
            val next = Labels.normalize(cur.filterNot(l => rm(l.toLowerCase)) ++ add)
            val added = next.filterNot(l => cur.exists(_.equalsIgnoreCase(l)))
            if next.sorted == cur.sorted then IO.pure(None)
            else store.setLabels(p.id, next) *>
              (if added.isEmpty then IO.unit else automations.traverse_(_.fire(AutoEvent("label_added", wsId, p.id, s.user.name, io.circe.JsonObject("labels" -> added.asJson))))).as(None)
          }
        }
      case "owner"  => each(p => store.setPageMeta(p.id, b.ownerId.orElse(Some(None)), None).map(ok => Option.when(!ok)("所有者が見つかりません")))
      case "status" => each(p => store.setPageMeta(p.id, None, b.status.orElse(Some(None))).flatMap { ok =>
          (if ok then automations.traverse_(_.fire(AutoEvent("status_changed", wsId, p.id, s.user.name, io.circe.JsonObject("status" -> b.status.flatten.asJson)))) else IO.unit)
            .as(Option.when(!ok)("ページが見つかりません"))
        })
      case _        => IO.pure((Nil, Nil))
