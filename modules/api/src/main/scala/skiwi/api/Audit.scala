package skiwi.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import skiwi.core.*
import skiwi.db.{AuditEvent, OpsStore}

/** 監査ログの記録。記録の失敗で本処理は失敗させない（ログ出力のみ）。閲覧は記録しない。 */
final class Audit(ops: OpsStore) {

  def log(s: AuthSession, action: String, targetType: String, targetId: Any, detail: Json = Json.obj()): IO[Unit] = {
    val d = s.via match {
      case AuthVia.Token(id, _) => detail.deepMerge(Json.obj("tokenId" -> id.toString.asJson))
      case AuthVia.Session => detail
    }
    write(AuditEvent(
      Some(s.user.id),
      Some(s.user.name),
      s.viaString,
      action,
      Some(targetType),
      Some(targetId.toString),
      d.noSpaces
    ))
  }

  def system(via: String, action: String, targetType: String, targetId: Any, detail: Json = Json.obj()): IO[Unit] =
    write(AuditEvent(None, None, via, action, Some(targetType), Some(targetId.toString), detail.noSpaces))

  def user(u: User, via: String, action: String, detail: Json = Json.obj()): IO[Unit] =
    write(AuditEvent(Some(u.id), Some(u.name), via, action, Some("user"), Some(u.id.toString), detail.noSpaces))

  private def write(e: AuditEvent): IO[Unit] =
    ops.audit(e).handleErrorWith(err =>
      sbirds.observe.Log.report(ErrorCode.AuditRecordFailed, s"${e.action}: $err", err)
    )
}
