package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{Periodic, Store}
import skiwi.storage.BlobStore

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

final case class TrashedPageDto(id: UUID, title: String, icon: Option[String], deletedAt: Instant, deletedBy: Option[String], count: Long, purgeAt: Instant) derives Encoder.AsObject
final case class TrashedWorkspaceDto(id: UUID, name: String, deletedAt: Instant, deletedBy: Option[String], purgeAt: Instant) derives Encoder.AsObject
final case class RenameWorkspaceReq(name: String) derives Decoder

/** ワークスペースの名前変更・削除とゴミ箱。削除はゴミ箱へ移し、保存期間（既定30日）を過ぎたら完全に削除する。 */
final class TrashRoutes(
    protected val store: Store,
    protected val sessions: SessionAuth,
    audit: Audit,
    blobs: Option[BlobStore] = None,
    retention: FiniteDuration = 30.days,
    accessChanged: IO[Unit] = IO.unit
) extends PageRouteSupport:

  private def purgeAt(at: Instant) = at.plusMillis(retention.toMillis)

  /** 添付ファイルの実体を消す（失敗してもDB側の削除は戻さない）。 */
  private def dropBlobs(keys: List[String]): IO[Unit] =
    blobs.traverse_(b => keys.traverse_(k => b.delete(k).handleErrorWith(e => IO.println(s"[trash] failed to delete blob $k: $e"))))

  /** 期限切れのゴミ箱を完全に削除する（各ノードで動いても冪等）。 */
  def purgeLoop: IO[Unit] =
    Periodic.run(1.hour, 1.hour)(e => s"[trash] purge failed: $e") {
      IO.realTimeInstant.flatMap(now => store.purgeExpired(now.minusMillis(retention.toMillis)).flatMap(dropBlobs))
    }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ ワークスペース
    case req @ PATCH -> Root / "api" / "workspaces" / UUIDVar(id) =>
      withWs(req, id, Role.Edit) { (s, ws, _) =>
        req.as[RenameWorkspaceReq].attempt.flatMap {
          case Right(b) if b.name.trim.nonEmpty && b.name.trim.length <= 200 =>
            store.renameWorkspace(id, b.name.trim).flatMap {
              case None => err(Status.NotFound, "workspace not found")
              case Some(w) =>
                audit.log(s, "workspace.rename", "workspace", id, Json.obj("from" -> ws.name.asJson, "to" -> w.name.asJson)) *>
                  Ok(Json.obj("id" -> w.id.asJson, "name" -> w.name.asJson))
            }
          case _ => err(Status.BadRequest, "name is required")
        }
      }

    case req @ DELETE -> Root / "api" / "workspaces" / UUIDVar(id) =>
      withWs(req, id, Role.Edit) { (s, ws, _) =>
        store.deleteWorkspace(id, s.user.id).flatMap { ok =>
          if !ok then err(Status.NotFound, "workspace not found")
          else audit.log(s, "workspace.delete", "workspace", id, Json.obj("name" -> ws.name.asJson)) *> accessChanged *> NoContent()
        }
      }

    // ------------------------------------------------------------ ページのゴミ箱
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(id) / "trash" =>
      withVisibleWs(req, id) { s =>
        store.trashedPages(id).flatMap { items =>
          // 編集権限のあるものだけ（ページ単位で共有されている人もいるため1件ずつ判定する）
          store.pagesAccessAny(items.map(_.page.id), s).flatMap { acc =>
            val ok = items.filter(t => acc.get(t.page.id).exists(_._2 >= Role.Edit))
            Ok(ok.map(t => TrashedPageDto(t.page.id, t.page.title, t.page.icon, t.deletedAt, t.deletedBy, t.count, purgeAt(t.deletedAt))))
          }
        }
      }

    case req @ POST -> Root / "api" / "trash" / "pages" / UUIDVar(id) / "restore" =>
      authed(req) { s =>
        store.pageAccessAny(id, s).flatMap {
          case Some((page, r)) if r >= Role.Edit =>
            // 元の親が削除済みだと直下に移り、ワークスペース全体の権限を継承する（制限していた内容が広く見えるようになる）。
            // そのため直下にページを作れる人だけが戻せる
            store.restoreMovesToRoot(id).flatMap(toRoot => if toRoot then canEditUnder(s, page.workspaceId, None) else IO.pure(Right(()))).flatMap {
              case Left(_) => err(Status.Forbidden, "元の親ページが削除されているため、ワークスペースの直下に戻す権限が要ります（先に親ページを戻してください）")
              case Right(_) =>
                store.restorePage(id).flatMap {
                  case Left(_) => err(Status.NotFound, "trashed page not found")
                  case Right(p) =>
                    audit.log(s, "page.restore_from_trash", "page", id, Json.obj("title" -> p.title.asJson)) *> accessChanged *>
                      Ok(Json.obj("id" -> p.id.asJson, "workspaceId" -> p.workspaceId.asJson, "parentId" -> p.parentId.asJson))
                }
            }
          case Some(_) => err(Status.Forbidden, "insufficient permission")
          case None    => err(Status.NotFound, "trashed page not found")
        }
      }

    case req @ DELETE -> Root / "api" / "trash" / "pages" / UUIDVar(id) =>
      authed(req) { s =>
        store.pageAccessAny(id, s).flatMap {
          case Some((p, r)) if r >= Role.Edit =>
            // 元に戻せないので、いっしょに消える子孫もすべて編集できる人だけ
            uneditableDescendants(s, id, inTrash = true).flatMap {
              case n if n > 0 => err(Status.Forbidden, descendantsBlocked(n))
              case _ =>
                store.purgePage(id).flatMap {
                  case None => err(Status.NotFound, "trashed page not found")
                  case Some(keys) =>
                    dropBlobs(keys) *> audit.log(s, "page.purge", "page", id, Json.obj("title" -> p.title.asJson)) *> NoContent()
                }
            }
          case Some(_) => err(Status.Forbidden, "insufficient permission")
          case None    => err(Status.NotFound, "trashed page not found")
        }
      }

    // ------------------------------------------------------------ ワークスペースのゴミ箱
    case req @ GET -> Root / "api" / "trash" / "workspaces" =>
      authed(req)(s => store.trashedWorkspaces(s).flatMap(ws =>
        Ok(ws.map(w => TrashedWorkspaceDto(w.id, w.name, w.deletedAt, w.deletedBy, purgeAt(w.deletedAt))))))

    case req @ POST -> Root / "api" / "trash" / "workspaces" / UUIDVar(id) / "restore" =>
      authed(req) { s =>
        store.workspaceRoleAny(id, s).flatMap {
          case Some((_, r)) if r >= Role.Edit =>
            store.restoreWorkspace(id).flatMap {
              case None => err(Status.NotFound, "trashed workspace not found")
              case Some(w) =>
                audit.log(s, "workspace.restore", "workspace", id, Json.obj("name" -> w.name.asJson)) *> accessChanged *>
                  Ok(Json.obj("id" -> w.id.asJson, "name" -> w.name.asJson))
            }
          case _ => err(Status.NotFound, "trashed workspace not found")
        }
      }

    case req @ DELETE -> Root / "api" / "trash" / "workspaces" / UUIDVar(id) =>
      authed(req) { s =>
        store.workspaceRoleAny(id, s).flatMap {
          case Some((w, r)) if r >= Role.Edit =>
            store.purgeWorkspace(id).flatMap {
              case None => err(Status.NotFound, "trashed workspace not found")
              case Some(keys) => dropBlobs(keys) *> audit.log(s, "workspace.purge", "workspace", id, Json.obj("name" -> w.name.asJson)) *> NoContent()
            }
          case _ => err(Status.NotFound, "trashed workspace not found")
        }
      }
  }
