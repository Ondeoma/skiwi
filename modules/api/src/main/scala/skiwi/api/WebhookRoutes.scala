package skiwi.api

import cats.effect.*
import io.circe.{Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{Store, Webhook, WebhookStore}

import java.util.UUID

/** ワークスペースの Webhook の設定（ワークスペースの編集権限が必要）。 */
final class WebhookRoutes(protected val store: Store,
                          hooks: WebhookStore,
                          svc: WebhookService,
                          protected val sessions: SessionAuth,
                          audit: Audit) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(wsId) / "webhooks" => getWorkspaceWebhooks(req, wsId)
    case req @ POST -> Root / "api" / "workspaces" / UUIDVar(wsId) / "webhooks" => postWorkspaceWebhooks(req, wsId)
    case req @ PATCH -> Root / "api" / "webhooks" / UUIDVar(id) => patchWebhook(req, id)
    case req @ DELETE -> Root / "api" / "webhooks" / UUIDVar(id) => deleteWebhook(req, id)
    case req @ POST -> Root / "api" / "webhooks" / UUIDVar(id) / "test" => postWebhookTest(req, id)
  }

  private def getWorkspaceWebhooks(req: Request[IO], wsId: UUID): IO[Response[IO]] =
    withWs(req, wsId, Role.Edit)((_, _, _) => hooks.list(wsId).flatMap(hs => Ok(hs.map(dto(_)))))

  private def postWorkspaceWebhooks(req: Request[IO], wsId: UUID): IO[Response[IO]] =
    withWs(req, wsId, Role.Edit) {
      (s, _, _) =>
        body[CreateWebhookReq](req) { b =>
          validate(Some(b.name), Some(b.url), Some(b.format), Some(b.events)) match {
            case Some(m) => err(ErrorCode.InvalidInput, m)
            case None =>
              val secret = sbirds.auth.Crypto.randomToken(24)
              hooks.create(wsId, b.name.trim, b.url.trim, b.format, b.events.distinct, secret, s.user.id).flatMap {
                h =>
                  audit.log(
                    s,
                    "webhook.create",
                    "workspace",
                    wsId,
                    Json.obj("name" -> h.name.asJson, "url" -> h.url.asJson, "events" -> h.events.asJson)
                  ) *>
                    Created(dto(h, withSecret = true))
              }
          }
        }
    }

  private def patchWebhook(req: Request[IO], id: UUID): IO[Response[IO]] =
    withHook(req, id) {
      (s, _, h) =>
        body[UpdateWebhookReq](req) { b =>
          validate(b.name, b.url, b.format, b.events) match {
            case Some(m) => err(ErrorCode.InvalidInput, m)
            case None =>
              hooks.update(
                id,
                b.name.map(_.trim),
                b.url.map(_.trim),
                b.format,
                b.events.map(_.distinct),
                b.enabled
              ).flatMap {
                case None => err(ErrorCode.WebhookNotFound)
                case Some(nh) =>
                  audit.log(
                    s,
                    "webhook.update",
                    "workspace",
                    h.workspaceId,
                    Json.obj(
                      "name" -> nh.name.asJson,
                      "url" -> nh.url.asJson,
                      "events" -> nh.events.asJson,
                      "enabled" -> nh.enabled.asJson
                    )
                  ) *> Ok(dto(nh))
              }
          }
        }
    }

  private def deleteWebhook(req: Request[IO], id: UUID): IO[Response[IO]] =
    withHook(req, id) {
      (s, _, h) =>
        hooks.delete(id) *> audit.log(
          s,
          "webhook.delete",
          "workspace",
          h.workspaceId,
          Json.obj("name" -> h.name.asJson, "url" -> h.url.asJson)
        ) *> NoContent()
    }

  private def postWebhookTest(req: Request[IO], id: UUID): IO[Response[IO]] =
    withHook(req, id) {
      (s, ws, h) =>
        svc.test(h, ws.name, s.user.name).flatMap((st, e) => Ok(WebhookTestDto(st.exists(_ / 100 == 2), st, e)))
    }

  private def dto(h: Webhook, withSecret: Boolean = false) =
    WebhookDto(
      h.id,
      h.name,
      h.url,
      h.format,
      h.events,
      h.enabled,
      h.createdAt,
      h.createdBy,
      h.lastStatus,
      h.lastError,
      h.lastAt,
      Option.when(withSecret)(h.secret)
    )

  private def withHook(req: Request[IO],
                       id: UUID)(f: (AuthSession, Workspace, Webhook) => IO[Response[IO]]): IO[Response[IO]] =
    withWsItem(req, hooks.get(id), "Webhook")(_.workspaceId)(f)

  private val Formats = Set("generic", "slack", "teams")
  private def validate(name: Option[String],
                       url: Option[String],
                       format: Option[String],
                       events: Option[List[String]]): Option[String] =
    if name.exists(validName(_).isEmpty) then Some("名前は1〜100文字にしてください")
    else if url.exists(u => !svc.validUrl(u.trim)) then Some("URL が正しくありません（https:// だけ使えます）")
    else if format.exists(f => !Formats(f)) then Some("形式が正しくありません")
    else if events.exists(es => es.isEmpty || es.exists(e => !WebhookStore.Events.contains(e))) then
      Some("イベントが正しくありません")
    else None

}
