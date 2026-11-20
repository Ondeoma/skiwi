package skiwi.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.Store
import sbirds.storage.BlobStore

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final case class TrashedPageDto(id: UUID,
                                title: String,
                                icon: Option[String],
                                deletedAt: Instant,
                                deletedBy: Option[String],
                                count: Long,
                                purgeAt: Instant) derives Encoder.AsObject
final case class TrashedWorkspaceDto(id: UUID,
                                     name: String,
                                     deletedAt: Instant,
                                     deletedBy: Option[String],
                                     purgeAt: Instant) derives Encoder.AsObject
final case class RenameWorkspaceReq(name: String) derives Decoder

/** ワークスペースの名前変更・削除とゴミ箱。削除はゴミ箱へ移し、保存期間（既定30日）を過ぎたら完全に削除する。 */
final class TrashRoutes(protected val store: Store,
                        protected val sessions: SessionAuth,
                        audit: Audit,
                        blobs: Option[BlobStore] = None,
                        retention: FiniteDuration = 30.days,
                        accessChanged: IO[Unit] = IO.unit) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ ワークスペース
    case req @ PATCH -> Root / "api" / "workspaces" / UUIDVar(id) => patchWorkspace(req, id)
    case req @ DELETE -> Root / "api" / "workspaces" / UUIDVar(id) => deleteWorkspace(req, id)
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "trash" => getWorkspaceTrash(req, id)
    case req @ POST -> Root / "api" / "trash" / "pages" / UUIDVar(id) / "restore" => postTrashPageRestore(req, id)
    case req @ DELETE -> Root / "api" / "trash" / "pages" / UUIDVar(id) => deleteTrashPage(req, id)
    case req @ GET -> Root / "api" / "trash" / "workspaces" => getTrashWorkspaces(req)
    case req @ POST -> Root / "api" / "trash" / "workspaces" / UUIDVar(id) / "restore" =>
      postTrashWorkspaceRestore(req, id)
    case req @ DELETE -> Root / "api" / "trash" / "workspaces" / UUIDVar(id) => deleteTrashWorkspace(req, id)
  }

  private def patchWorkspace(req: Request[IO], id: UUID): IO[Response[IO]] =
    withWs(req, id, Role.Edit) {
      (s, ws, _) =>
        decodeBody[RenameWorkspaceReq](req).flatMap {
          case Right(b) if validName(b.name, MaxWorkspaceName).isDefined =>
            store.renameWorkspace(id, b.name.trim).flatMap {
              case None => err(ErrorCode.WorkspaceNotFound)
              case Some(w) =>
                audit.log(
                  s,
                  "workspace.rename",
                  "workspace",
                  id,
                  Json.obj("from" -> ws.name.asJson, "to" -> w.name.asJson)
                ) *>
                  Ok(Json.obj("id" -> w.id.asJson, "name" -> w.name.asJson))
            }
          case _ => err(ErrorCode.WorkspaceNameInvalid)
        }
    }

  private def deleteWorkspace(req: Request[IO], id: UUID): IO[Response[IO]] =
    withWs(req, id, Role.Edit) {
      (s, ws, _) =>
        store.deleteWorkspace(id, s.user.id).flatMap { ok =>
          if !ok then err(ErrorCode.WorkspaceNotFound)
          else
            audit.log(s, "workspace.delete", "workspace", id, Json.obj("name" -> ws.name.asJson)) *> accessChanged *>
              NoContent()
        }
    }

  private def getWorkspaceTrash(req: Request[IO], id: UUID): IO[Response[IO]] =
    withVisibleWs(req, id) { s =>
      store.trashedPages(id).flatMap { items =>
        // 編集権限のあるものだけ（ページ単位で共有されている人もいるため1件ずつ判定する）
        store.pagesAccessAny(items.map(_.page.id), s).flatMap { acc =>
          val ok = items.filter(t => acc.get(t.page.id).exists(_._2 >= Role.Edit))
          Ok(ok.map(t =>
            TrashedPageDto(
              t.page.id,
              t.page.title,
              t.page.icon,
              t.deletedAt,
              t.deletedBy,
              t.count,
              purgeAt(t.deletedAt)
            )
          ))
        }
      }
    }

  private def postTrashPageRestore(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req) { s =>
      store.pageAccessAny(id, s).flatMap {
        case Some((page, r)) if r >= Role.Edit =>
          // 元の親が削除済みだと直下に移り、ワークスペース全体の権限を継承する（制限していた内容が広く見えるようになる）。
          // そのため直下にページを作れる人だけが戻せる
          store.restoreMovesToRoot(id).flatMap(toRoot =>
            if toRoot then canEditUnder(s, page.workspaceId, None) else IO.pure(Right(()))
          ).flatMap {
            case Left(_) => err(ErrorCode.TrashRestoreNeedsWorkspaceEdit)
            case Right(_) =>
              store.restorePage(id).flatMap {
                case Left(_) => err(ErrorCode.TrashedPageNotFound, "ゴミ箱にページが見つかりません")
                case Right(p) =>
                  audit.log(s, "page.restore_from_trash", "page", id, Json.obj("title" -> p.title.asJson)) *>
                    accessChanged *>
                    Ok(Json.obj(
                      "id" -> p.id.asJson,
                      "workspaceId" -> p.workspaceId.asJson,
                      "parentId" -> p.parentId.asJson
                    ))
              }
          }
        case Some(_) => err(ErrorCode.PermissionDenied)
        case None => err(ErrorCode.TrashedPageNotFound, "ゴミ箱にページが見つかりません")
      }
    }

  private def deleteTrashPage(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req) { s =>
      store.pageAccessAny(id, s).flatMap {
        case Some((p, r)) if r >= Role.Edit =>
          // 元に戻せないので、いっしょに消える子孫もすべて編集できる人だけ
          uneditableDescendants(s, id, inTrash = true).flatMap {
            case n if n > 0 => err(ErrorCode.DescendantsNotEditable, descendantsBlocked(n))
            case _ =>
              store.purgePage(id).flatMap {
                case None => err(ErrorCode.TrashedPageNotFound, "ゴミ箱にページが見つかりません")
                case Some(keys) =>
                  dropBlobs(keys) *> audit.log(s, "page.purge", "page", id, Json.obj("title" -> p.title.asJson)) *>
                    NoContent()
              }
          }
        case Some(_) => err(ErrorCode.PermissionDenied)
        case None => err(ErrorCode.TrashedPageNotFound, "ゴミ箱にページが見つかりません")
      }
    }

  private def getTrashWorkspaces(req: Request[IO]): IO[Response[IO]] =
    authed(req)(s =>
      store.trashedWorkspaces(s).flatMap(ws =>
        Ok(ws.map(w => TrashedWorkspaceDto(w.id, w.name, w.deletedAt, w.deletedBy, purgeAt(w.deletedAt))))
      )
    )

  private def postTrashWorkspaceRestore(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req) { s =>
      store.workspaceRoleAny(id, s).flatMap {
        case Some((_, r)) if r >= Role.Edit =>
          store.restoreWorkspace(id).flatMap {
            case None => err(ErrorCode.TrashedWorkspaceNotFound)
            case Some(w) =>
              audit.log(s, "workspace.restore", "workspace", id, Json.obj("name" -> w.name.asJson)) *>
                accessChanged *>
                Ok(Json.obj("id" -> w.id.asJson, "name" -> w.name.asJson))
          }
        case _ => err(ErrorCode.TrashedWorkspaceNotFound)
      }
    }

  private def deleteTrashWorkspace(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req) { s =>
      store.workspaceRoleAny(id, s).flatMap {
        case Some((w, r)) if r >= Role.Edit =>
          store.purgeWorkspace(id).flatMap {
            case None => err(ErrorCode.TrashedWorkspaceNotFound)
            case Some(keys) => dropBlobs(keys) *>
                audit.log(s, "workspace.purge", "workspace", id, Json.obj("name" -> w.name.asJson)) *> NoContent()
          }
        case _ => err(ErrorCode.TrashedWorkspaceNotFound)
      }
    }

  private def purgeAt(at: Instant) = at.plusMillis(retention.toMillis)

  /** 添付ファイルの実体を消す（失敗してもDB側の削除は戻さない）。 */
  private def dropBlobs(keys: List[String]): IO[Unit] =
    blobs.traverse_(b =>
      keys.traverse_(k =>
        b.delete(k).handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.TrashBlobDeleteFailed, s"$k: $e", e))
      )
    )

  /** 期限切れのゴミ箱を完全に削除する（各ノードで動いても冪等）。 */
  def purgeLoop: IO[Unit] =
    Periodic.run(1.hour, 1.hour, ErrorCode.TrashPurgeFailed) {
      IO.realTimeInstant.flatMap(now => store.purgeExpired(now.minusMillis(retention.toMillis)).flatMap(dropBlobs))
    }

}
