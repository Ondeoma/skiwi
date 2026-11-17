package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{OutboxResult, Periodic, Store, Webhook, WebhookItem, WebhookStore}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.{Duration, Instant}
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*

/** Webhook の送信。送信時点のページのタイトル・URLで本文を組み立てる。個別に権限を絞ったページ・削除されたページ（削除の通知を除く）は送らない。
  * allowHttp: http:// の送り先を許す（開発・テスト用。既定は https のみ）
  * allowPrivate: 社内のアドレス（10.x・192.168.x・127.0.0.1 など）にも送る。既定は送らない（編集者にサーバーから社内へ接続させない） */
final class WebhookService(hooks: WebhookStore, store: Store, publicUrl: String, allowHttp: Boolean = false, interval: FiniteDuration = 10.seconds,
    allowPrivate: Boolean = false):
  private val base = publicUrl.stripSuffix("/")
  private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()

  def validUrl(u: String): Boolean =
    Either.catchNonFatal(URI.create(u)).toOption.exists { uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      Option(uri.getHost).exists(_.nonEmpty) && (scheme.contains("https") || (allowHttp && scheme.contains("http"))) && u.length <= 2000
    }

  /** 出来事を積む（失敗してもリクエストは止めない） */
  def emit(event: String, pageId: UUID, actor: String, extra: Json = Json.obj(), delay: Int = 0): IO[Unit] =
    hooks.enqueue(event, pageId, Json.obj("actor" -> actor.asJson).deepMerge(extra).noSpaces, delay).void
      .handleErrorWith(e => skiwi.observe.Log.println(s"[webhook] failed to enqueue $event: $e"))

  private def sign(secret: String, body: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString

  private final case class Msg(event: String, text: String, title: String, url: Option[String], workspace: (UUID, String), page: Option[(UUID, String)], actor: Option[String], extra: Json)

  /** 送る内容（文面）。None: 送らない */
  private def message(it: WebhookItem): IO[Either[String, Msg]] =
    val d = io.circe.parser.parse(it.detail).toOption.map(_.hcursor)
    def f(k: String) = d.flatMap(_.get[String](k).toOption).filter(_.nonEmpty)
    val title = it.pageTitle.filter(_.trim.nonEmpty).getOrElse("無題")
    val link = it.pageId.map(id => s"$base/w/${it.workspaceId}/p/$id")
    val ws = it.workspaceName
    val actor = f("actor")
    val who = actor.fold("")(a => s"${a}さんが")
    if it.event != "test" && it.restricted then IO.pure(Left("restricted page"))
    else if it.event != "test" && it.event != "page.deleted" && it.pageDeleted then IO.pure(Left("page deleted"))
    else
      val page = it.pageId.map(_ -> title)
      def msg(text: String, url: Option[String] = link, extra: Json = Json.obj()) = Right(Msg(it.event, text, title, url, (it.workspaceId, ws), page, actor, extra))
      it.event match
        case "test" => IO.pure(Right(Msg("test", s"✅ skiwi からのテスト送信です（ワークスペース「$ws」）", "テスト", Some(base), (it.workspaceId, ws), None, actor, Json.obj())))
        case "page.created" => IO.pure(msg(s"📄 ${who}ページ「$title」を作成しました（$ws）"))
        case "page.deleted" => IO.pure(msg(s"🗑 ${who}ページ「$title」をゴミ箱に移しました（$ws）", None))
        case "comment.created" =>
          val ex = f("excerpt")
          IO.pure(msg(s"💬 ${who}「$title」にコメントしました（$ws）${ex.fold("")(e => s"\n> $e")}", link.map(_ + f("threadId").fold("")(t => s"?comment=$t")), Json.obj("excerpt" -> ex.asJson)))
        case "page.published" =>
          val note = f("note")
          IO.pure(msg(s"🌐 ${who}「$title」をゲスト向けに公開しました（$ws）${note.fold("")(n => s"\n> $n")}", extra = Json.obj("note" -> note.asJson, "publicUrl" -> f("publicUrl").asJson)))
        case "automation" => IO.pure(msg(f("text").getOrElse(s"⚙ 自動化ルール: 「$title」"), extra = Json.obj("rule" -> f("rule").asJson)))
        case "page.updated" =>
          val ids = d.flatMap(_.downField("editors").as[List[String]].toOption).getOrElse(Nil).flatMap(uuidOpt)
          store.userNames(ids).map { names =>
            val eds = ids.flatMap(names.get).distinct
            msg(s"✏️ 「$title」が更新されました${if eds.isEmpty then "" else s"（${eds.map(_ + "さん").mkString("、")}）"}（$ws）", extra = Json.obj("editors" -> eds.asJson))
          }
        case other => IO.pure(Left(s"unknown event: $other"))

  private def payload(format: String, m: Msg, deliveryId: Long): Json = format match
    case "slack" =>
      val t = m.url.fold(m.text)(u => s"${m.text}\n<$u|${m.title.replace("|", "｜").replace(">", "＞")}>")
      Json.obj("text" -> t.asJson)
    case "teams" =>
      // Teams のワークフロー（Webhook を受け取ってチャネルに投稿する）向けの Adaptive Card
      val body = Json.arr(Json.obj("type" -> "TextBlock".asJson, "text" -> m.text.asJson, "wrap" -> true.asJson))
      val actions = m.url.toList.map(u => Json.obj("type" -> "Action.OpenUrl".asJson, "title" -> "skiwi で開く".asJson, "url" -> u.asJson))
      Json.obj(
        "type" -> "message".asJson,
        "attachments" -> Json.arr(Json.obj(
          "contentType" -> "application/vnd.microsoft.card.adaptive".asJson,
          "content" -> Json.obj(
            "$schema" -> "http://adaptivecards.io/schemas/adaptive-card.json".asJson, "type" -> "AdaptiveCard".asJson, "version" -> "1.4".asJson,
            "body" -> body, "actions" -> Json.fromValues(actions))
        ))
      )
    case _ =>
      Json.obj(
        "id" -> deliveryId.asJson, "event" -> m.event.asJson, "at" -> Instant.now().asJson, "text" -> m.text.asJson,
        "workspace" -> Json.obj("id" -> m.workspace._1.asJson, "name" -> m.workspace._2.asJson),
        "page" -> m.page.fold(Json.Null)((id, t) => Json.obj("id" -> id.asJson, "title" -> t.asJson, "url" -> m.url.asJson)),
        "actor" -> m.actor.asJson
      ).deepMerge(m.extra).dropNullValues

  /** 送る。戻り値: (HTTPステータス, エラー) */
  private def post(url: String, format: String, secret: String, event: String, deliveryId: Long, body: String): IO[(Option[Int], Option[String])] =
    if !validUrl(url) then IO.pure((None, Some("invalid url")))
    else (if allowPrivate then IO.pure(true) else NetGuard.publicHost(URI.create(url).getHost)).flatMap {
      case false => IO.pure((None, Some("社内のアドレスには送れません（WEBHOOK_ALLOW_PRIVATE）")))
      case true =>
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
          .header("Content-Type", "application/json; charset=utf-8").header("User-Agent", "skiwi-webhook/1")
          .POST(HttpRequest.BodyPublishers.ofString(body))
        val req = (if format == "generic" then b.header("X-Skiwi-Event", event).header("X-Skiwi-Delivery", deliveryId.toString)
                     .header("X-Skiwi-Signature", s"sha256=${sign(secret, body)}") else b).build()
        IO.fromCompletableFuture(IO(client.sendAsync(req, HttpResponse.BodyHandlers.discarding())))
          .map(r => (Some(r.statusCode()), Option.when(r.statusCode() / 100 != 2)(s"HTTP ${r.statusCode()}")))
          .timeout(15.seconds)
          .handleError(e => (None, Some(Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(300))))
    }

  def deliver(it: WebhookItem): IO[OutboxResult] =
    skiwi.observe.Telemetry.span("webhook.deliver", "webhook.event" -> it.event)(deliverImpl(it)).flatTap { r =>
      skiwi.observe.Metrics.count("skiwi.webhook.deliveries", "result" -> (r match
        case OutboxResult.Sent     => "sent"
        case _: OutboxResult.Retry => "retry"
        case _                     => "skip"))
    }

  private def deliverImpl(it: WebhookItem): IO[OutboxResult] =
    if !it.enabled then IO.pure(OutboxResult.Skip("disabled"))
    else message(it).flatMap {
      case Left(reason) => IO.pure(OutboxResult.Skip(reason))
      case Right(m) =>
        post(it.url, it.format, it.secret, it.event, it.id, payload(it.format, m, it.id).noSpaces).flatMap { (st, e) =>
          hooks.recordResult(it.webhookId, st, e).as {
            st match
              case Some(s) if s / 100 == 2               => OutboxResult.Sent
              case Some(s) if s / 100 == 4 && s != 429 => OutboxResult.Skip(s"HTTP $s")
              case _                                     => OutboxResult.Retry(e.getOrElse("error"))
          }
        }
    }

  /** テスト送信（すぐに送って結果を返す） */
  def test(h: Webhook, wsName: String, actor: String): IO[(Option[Int], Option[String])] =
    val m = Msg("test", s"✅ skiwi からのテスト送信です（ワークスペース「$wsName」）", "テスト", Some(base), (h.workspaceId, wsName), None, Some(actor), Json.obj())
    post(h.url, h.format, h.secret, "test", 0L, payload(h.format, m, 0L).noSpaces).flatTap((st, e) => hooks.recordResult(h.id, st, e))

  val runOnce: IO[Int] = hooks.processDue(20)(deliver)

  val loop: IO[Nothing] =
    Periodic.run(interval, interval)(e => s"[webhook] worker failed: $e")(runOnce)

  val purgeLoop: IO[Nothing] =
    Periodic.run(1.hour, 1.hour)(e => s"[webhook] purge failed: $e")(hooks.purgeOld())

// ---------------------------------------------------------------- 設定のAPI

/** secret は作成時だけ返す（署名の確認用） */
final case class WebhookDto(
    id: UUID, name: String, url: String, format: String, events: List[String], enabled: Boolean,
    createdAt: Instant, createdBy: Option[String], lastStatus: Option[Int], lastError: Option[String], lastAt: Option[Instant],
    secret: Option[String] = None
) derives Encoder.AsObject
final case class CreateWebhookReq(name: String, url: String, format: String, events: List[String]) derives Decoder
final case class UpdateWebhookReq(name: Option[String], url: Option[String], format: Option[String], events: Option[List[String]], enabled: Option[Boolean]) derives Decoder
final case class WebhookTestDto(ok: Boolean, status: Option[Int], error: Option[String]) derives Encoder.AsObject

/** ワークスペースの Webhook の設定（ワークスペースの編集権限が必要）。 */
final class WebhookRoutes(protected val store: Store, hooks: WebhookStore, svc: WebhookService, protected val sessions: SessionAuth, audit: Audit) extends PageRouteSupport:

  private def dto(h: Webhook, withSecret: Boolean = false) =
    WebhookDto(h.id, h.name, h.url, h.format, h.events, h.enabled, h.createdAt, h.createdBy, h.lastStatus, h.lastError, h.lastAt, Option.when(withSecret)(h.secret))

  private def withHook(req: Request[IO], id: UUID)(f: (AuthSession, Workspace, Webhook) => IO[Response[IO]]): IO[Response[IO]] =
    withWsItem(req, hooks.get(id), "webhook")(_.workspaceId)(f)

  private val Formats = Set("generic", "slack", "teams")
  private def validate(name: Option[String], url: Option[String], format: Option[String], events: Option[List[String]]): Option[String] =
    if name.exists(n => n.trim.isEmpty || n.length > 100) then Some("name must be 1-100 characters")
    else if url.exists(u => !svc.validUrl(u.trim)) then Some("invalid url (https only)")
    else if format.exists(f => !Formats(f)) then Some("invalid format")
    else if events.exists(es => es.isEmpty || es.exists(e => !WebhookStore.Events.contains(e))) then Some("invalid events")
    else None

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(wsId) / "webhooks" =>
      withWs(req, wsId, Role.Edit)((_, _, _) => hooks.list(wsId).flatMap(hs => Ok(hs.map(dto(_)))))

    case req @ POST -> Root / "api" / "workspaces" / UUIDVar(wsId) / "webhooks" =>
      withWs(req, wsId, Role.Edit) { (s, _, _) =>
        body[CreateWebhookReq](req) { b =>
          validate(Some(b.name), Some(b.url), Some(b.format), Some(b.events)) match
            case Some(m) => err(Status.BadRequest, m)
            case None =>
              val secret = skiwi.auth.Crypto.randomToken(24)
              hooks.create(wsId, b.name.trim, b.url.trim, b.format, b.events.distinct, secret, s.user.id).flatMap { h =>
                audit.log(s, "webhook.create", "workspace", wsId, Json.obj("name" -> h.name.asJson, "url" -> h.url.asJson, "events" -> h.events.asJson)) *>
                  Created(dto(h, withSecret = true))
              }
        }
      }

    case req @ PATCH -> Root / "api" / "webhooks" / UUIDVar(id) =>
      withHook(req, id) { (s, _, h) =>
        body[UpdateWebhookReq](req) { b =>
          validate(b.name, b.url, b.format, b.events) match
            case Some(m) => err(Status.BadRequest, m)
            case None =>
              hooks.update(id, b.name.map(_.trim), b.url.map(_.trim), b.format, b.events.map(_.distinct), b.enabled).flatMap {
                case None => err(Status.NotFound, "webhook not found")
                case Some(nh) =>
                  audit.log(s, "webhook.update", "workspace", h.workspaceId, Json.obj("name" -> nh.name.asJson, "url" -> nh.url.asJson,
                    "events" -> nh.events.asJson, "enabled" -> nh.enabled.asJson)) *> Ok(dto(nh))
              }
        }
      }

    case req @ DELETE -> Root / "api" / "webhooks" / UUIDVar(id) =>
      withHook(req, id) { (s, _, h) =>
        hooks.delete(id) *> audit.log(s, "webhook.delete", "workspace", h.workspaceId, Json.obj("name" -> h.name.asJson, "url" -> h.url.asJson)) *> NoContent()
      }

    case req @ POST -> Root / "api" / "webhooks" / UUIDVar(id) / "test" =>
      withHook(req, id) { (s, ws, h) =>
        svc.test(h, ws.name, s.user.name).flatMap((st, e) => Ok(WebhookTestDto(st.exists(_ / 100 == 2), st, e)))
      }
  }
