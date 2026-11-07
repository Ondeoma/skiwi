package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.Store

import java.time.Instant
import java.util.UUID

final case class LockUserDto(id: UUID, name: String) derives Encoder.AsObject
/** canEdit: 保護中でも編集できる（自分が保護した・所有者・指定された）。canChange: 保護の設定・解除ができる */
final case class LockDto(
    locked: Boolean, lockedAt: Option[Instant], lockedBy: Option[String], reason: Option[String],
    editors: List[LockUserDto], canEdit: Boolean, canChange: Boolean
) derives Encoder.AsObject
final case class SetLockReq(reason: Option[String], editors: Option[List[UUID]]) derives Decoder

/** ページの保護（編集のロック）。保護中は、保護した人・所有者・指定した人だけが編集できる（ほかの編集者は閲覧のみ）。
  * 保護の設定・解除: 保護中でも編集できる人と管理者。保護されていないページは編集者なら誰でも保護できる。 */
final class LockRoutes(protected val store: Store, protected val sessions: SessionAuth, audit: Audit, accessChanged: AccessChanged = IO.unit) extends PageRouteSupport:

  private type Access = Option[(Page, Role)]

  /** 保護の状態と、保護を考えない権限・実効の権限 */
  private def load(id: UUID, s: AuthSession): IO[(Option[skiwi.db.PageLock], Access, Access)] =
    (store.pageLock(id), store.rawAndPageAccess(id, s)).mapN { case (lock, (raw, eff)) => (lock, raw, eff) }

  private def render(s: AuthSession, lock: Option[skiwi.db.PageLock], raw: Access, eff: Access): IO[LockDto] =
    val rawEdit = raw.exists(_._2 == Role.Edit)
    val effEdit = eff.exists(_._2 == Role.Edit)
    lock match
      case None => IO.pure(LockDto(false, None, None, None, Nil, effEdit, rawEdit))
      case Some(l) =>
        store.userNames(l.editors).map { names =>
          LockDto(true, Some(l.lockedAt), l.lockedBy, l.reason, l.editors.flatMap(u => names.get(u).map(LockUserDto(u, _))),
            effEdit, effEdit || s.isAdmin)
        }

  /** 変更後の状態（読み直す） */
  private def state(id: UUID, s: AuthSession): IO[LockDto] =
    load(id, s).flatMap(render(s, _, _, _))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "lock" =>
      authed(req) { s =>
        load(id, s).flatMap {
          case (_, _, None)     => err(Status.NotFound, "page not found")
          case (lock, raw, eff) => render(s, lock, raw, eff).flatMap(Ok(_))
        }
      }

    // 保護する（保護中なら理由・編集できる人を変える）
    case req @ PUT -> Root / "api" / "pages" / UUIDVar(id) / "lock" =>
      authed(req) { s =>
        load(id, s).flatMap {
          case (_, _, None) => err(Status.NotFound, "page not found")
          case (lock, raw, Some((p, r))) =>
            val allowed = r == Role.Edit || (lock.isDefined && s.isAdmin && raw.isDefined)
            if !allowed then err(Status.Forbidden, "insufficient permission")
            else
              body[SetLockReq](req) { b =>
                val reason = b.reason.map(_.trim).filter(_.nonEmpty).map(_.take(500))
                val eds = b.editors.getOrElse(lock.fold(Nil)(_.editors)).distinct.take(50)
                store.setPageLock(id, Some(s.user.id), reason, eds) *>
                  audit.log(s, if lock.isEmpty then "page.lock" else "page.lock.update", "page", id,
                    Json.obj("title" -> p.title.asJson, "reason" -> reason.asJson, "editors" -> eds.asJson)) *>
                  accessChanged *> state(id, s).flatMap(Ok(_))
              }
        }
      }

    case req @ DELETE -> Root / "api" / "pages" / UUIDVar(id) / "lock" =>
      authed(req) { s =>
        (store.pageAccess(id, s), store.pageLock(id)).flatMapN {
          case (None, _)    => err(Status.NotFound, "page not found")
          case (_, None)    => NoContent()
          case (Some((p, r)), Some(_)) =>
            if r != Role.Edit && !s.isAdmin then err(Status.Forbidden, "insufficient permission")
            else
              store.setPageLock(id, None, None, Nil) *>
                audit.log(s, "page.unlock", "page", id, Json.obj("title" -> p.title.asJson)) *> accessChanged *> NoContent()
        }
      }
  }
