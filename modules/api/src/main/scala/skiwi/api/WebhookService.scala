package skiwi.api

import sbirds.net.NetGuard
import sbirds.db.*
import skiwi.shared.Titles

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import skiwi.core.*
import skiwi.db.{Store, Webhook, WebhookItem, WebhookStore}

import java.net.URI
import org.http4s.client.Client
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*

/**
 * Webhook の送信。送信時点のページのタイトル・URLで本文を組み立てる。個別に権限を絞ったページ・削除されたページ（削除の通知を除く）は送らない。
 * allowHttp: http:// の送り先を許す（開発・テスト用。既定は https のみ）
 * http: NetGuard.client（WEBHOOK_ALLOW_PRIVATE でなければ、社内のアドレス（10.x・192.168.x・127.0.0.1 など）には送らない。編集者にサーバーから社内へ接続させない）
 */
final class WebhookService(hooks: WebhookStore,
                           store: Store,
                           publicUrl: String,
                           http: Client[IO],
                           allowHttp: Boolean = false,
                           interval: FiniteDuration = 10.seconds) {
  private val base = publicUrl.stripSuffix("/")

  def validUrl(u: String): Boolean =
    Either.catchNonFatal(URI.create(u)).toOption.exists { uri =>
      val scheme = Option(uri.getScheme).map(_.toLowerCase)
      Option(uri.getHost).exists(_.nonEmpty) && (scheme.contains("https") || (allowHttp && scheme.contains("http"))) &&
      u.length <= 2000
    }

  /** 出来事を積む（失敗してもリクエストは止めない） */
  def emit(event: String, pageId: UUID, actor: String, extra: Json = Json.obj(), delay: Int = 0): IO[Unit] =
    hooks.enqueue(event, pageId, Json.obj("actor" -> actor.asJson).deepMerge(extra).noSpaces, delay).void
      .handleErrorWith(e => sbirds.observe.Log.report(ErrorCode.WebhookEnqueueFailed, s"$event: $e", e))

  private def sign(secret: String, body: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256"))
    mac.doFinal(body.getBytes("UTF-8")).map("%02x".format(_)).mkString
  }

  private final case class Msg(event: String,
                               text: String,
                               title: String,
                               url: Option[String],
                               workspace: (UUID, String),
                               page: Option[(UUID, String)],
                               actor: Option[String],
                               extra: Json)

  /** 送る内容（文面）。None: 送らない */
  private def message(it: WebhookItem): IO[Either[String, Msg]] = {
    val d = io.circe.parser.parse(it.detail).toOption.map(_.hcursor)
    def f(k: String) = d.flatMap(_.get[String](k).toOption).filter(_.nonEmpty)
    val title = Titles.display(it.pageTitle)
    val link = it.pageId.map(id => s"$base/w/${it.workspaceId}/p/$id")
    val ws = it.workspaceName
    val actor = f("actor")
    val who = actor.fold("")(a => s"${a}さんが")
    if it.event != "test" && it.restricted then IO.pure(Left("閲覧を制限したページです"))
    else if it.event != "test" && it.event != "page.deleted" && it.pageDeleted then IO.pure(Left("ページは削除されています"))
    else {
      val page = it.pageId.map(_ -> title)
      def msg(text: String, url: Option[String] = link, extra: Json = Json.obj()) =
        Right(Msg(it.event, text, title, url, (it.workspaceId, ws), page, actor, extra))
      it.event match {
        case "test" => IO.pure(Right(Msg(
            "test",
            s"✅ skiwi からのテスト送信です（ワークスペース「$ws」）",
            "テスト",
            Some(base),
            (it.workspaceId, ws),
            None,
            actor,
            Json.obj()
          )))
        case "page.created" => IO.pure(msg(s"📄 ${who}ページ「$title」を作成しました（$ws）"))
        case "page.deleted" => IO.pure(msg(s"🗑 ${who}ページ「$title」をゴミ箱に移しました（$ws）", None))
        case "comment.created" =>
          val ex = f("excerpt")
          IO.pure(msg(
            s"💬 ${who}「$title」にコメントしました（$ws）${ex.fold("")(e => s"\n> $e")}",
            link.map(_ + f("threadId").fold("")(t => s"?comment=$t")),
            Json.obj("excerpt" -> ex.asJson)
          ))
        case "page.published" =>
          val note = f("note")
          IO.pure(msg(
            s"🌐 ${who}「$title」をゲスト向けに公開しました（$ws）${note.fold("")(n => s"\n> $n")}",
            extra = Json.obj("note" -> note.asJson, "publicUrl" -> f("publicUrl").asJson)
          ))
        case "automation" =>
          IO.pure(msg(f("text").getOrElse(s"⚙ 自動化ルール: 「$title」"), extra = Json.obj("rule" -> f("rule").asJson)))
        case "page.updated" =>
          val ids = d.flatMap(_.downField("editors").as[List[String]].toOption).getOrElse(Nil).flatMap(uuidOpt)
          store.userNames(ids).map { names =>
            val eds = ids.flatMap(names.get).distinct
            msg(
              s"✏️ 「$title」が更新されました${if eds.isEmpty then "" else s"（${eds.map(_ + "さん").mkString("、")}）"}（$ws）",
              extra = Json.obj("editors" -> eds.asJson)
            )
          }
        case other => IO.pure(Left(s"unknown event: $other"))
      }
    }
  }

  private def payload(format: String, m: Msg, deliveryId: Long): Json = format match {
    case "slack" =>
      val t = m.url.fold(m.text)(u => s"${m.text}\n<$u|${m.title.replace("|", "｜").replace(">", "＞")}>")
      Json.obj("text" -> t.asJson)
    case "teams" =>
      // Teams のワークフロー（Webhook を受け取ってチャネルに投稿する）向けの Adaptive Card
      val body = Json.arr(Json.obj("type" -> "TextBlock".asJson, "text" -> m.text.asJson, "wrap" -> true.asJson))
      val actions = m.url.toList.map(u =>
        Json.obj("type" -> "Action.OpenUrl".asJson, "title" -> "skiwi で開く".asJson, "url" -> u.asJson)
      )
      Json.obj(
        "type" -> "message".asJson,
        "attachments" -> Json.arr(Json.obj(
          "contentType" -> "application/vnd.microsoft.card.adaptive".asJson,
          "content" -> Json.obj(
            "$schema" -> "http://adaptivecards.io/schemas/adaptive-card.json".asJson,
            "type" -> "AdaptiveCard".asJson,
            "version" -> "1.4".asJson,
            "body" -> body,
            "actions" -> Json.fromValues(actions)
          )
        ))
      )
    case _ =>
      Json.obj(
        "id" -> deliveryId.asJson,
        "event" -> m.event.asJson,
        "at" -> Instant.now().asJson,
        "text" -> m.text.asJson,
        "workspace" -> Json.obj("id" -> m.workspace._1.asJson, "name" -> m.workspace._2.asJson),
        "page" ->
          m.page.fold(Json.Null)((id, t) => Json.obj("id" -> id.asJson, "title" -> t.asJson, "url" -> m.url.asJson)),
        "actor" -> m.actor.asJson
      ).deepMerge(m.extra).dropNullValues
  }

  /** 送る。戻り値: (HTTPステータス, エラー) */
  private def post(url: String,
                   format: String,
                   secret: String,
                   event: String,
                   deliveryId: Long,
                   body: String): IO[(Option[Int], Option[String])] =
    Uri.fromString(url).toOption.filter(_ => validUrl(url)) match {
      case None => IO.pure((None, Some("URL が正しくありません")))
      case Some(uri) =>
        val base = Request[IO](Method.POST, uri).withEntity(body)(using EntityEncoder.stringEncoder)
          .putHeaders(
            headers.`Content-Type`(MediaType.application.json, Charset.`UTF-8`),
            "User-Agent" -> "skiwi-webhook/1"
          )
        val req = if format == "generic" then
          base.putHeaders(
            "X-Skiwi-Event" -> event,
            "X-Skiwi-Delivery" -> deliveryId.toString,
            "X-Skiwi-Signature" -> s"sha256=${sign(secret, body)}"
          )
        else base
        http.run(req).use(r => IO.pure(r.status.code))
          .map(c => (Some(c), Option.when(c / 100 != 2)(s"HTTP $c")))
          .timeout(15.seconds)
          .handleError {
            case _: NetGuard.Rejected.Internal => (None, Some("社内のアドレスには送れません（WEBHOOK_ALLOW_PRIVATE）"))
            case e => (None, Some(Option(e.getMessage).getOrElse(e.getClass.getSimpleName).take(300)))
          }
    }

  def deliver(it: WebhookItem): IO[OutboxResult] =
    sbirds.observe.Telemetry.span("webhook.deliver", "webhook.event" -> it.event)(deliverImpl(it)).flatTap { r =>
      sbirds.observe.Metrics.count(
        "skiwi.webhook.deliveries",
        "result" ->
          (r match {
            case OutboxResult.Sent => "sent"
            case _: OutboxResult.Retry => "retry"
            case _ => "skip"
          })
      )
    }

  private def deliverImpl(it: WebhookItem): IO[OutboxResult] =
    if !it.enabled then IO.pure(OutboxResult.Skip("disabled"))
    else
      message(it).flatMap {
        case Left(reason) => IO.pure(OutboxResult.Skip(reason))
        case Right(m) =>
          post(it.url, it.format, it.secret, it.event, it.id, payload(it.format, m, it.id).noSpaces).flatMap {
            (st, e) =>
              hooks.recordResult(it.webhookId, st, e).as {
                st match {
                  case Some(s) if s / 100 == 2 => OutboxResult.Sent
                  case Some(s) if s / 100 == 4 && s != 429 => OutboxResult.Skip(s"HTTP $s")
                  case _ => OutboxResult.Retry(e.getOrElse("error"))
                }
              }
          }
      }

  /** テスト送信（すぐに送って結果を返す） */
  def test(h: Webhook, wsName: String, actor: String): IO[(Option[Int], Option[String])] = {
    val m = Msg(
      "test",
      s"✅ skiwi からのテスト送信です（ワークスペース「$wsName」）",
      "テスト",
      Some(base),
      (h.workspaceId, wsName),
      None,
      Some(actor),
      Json.obj()
    )
    post(h.url, h.format, h.secret, "test", 0L, payload(h.format, m, 0L).noSpaces).flatTap((st, e) =>
      hooks.recordResult(h.id, st, e)
    )
  }

  val runOnce: IO[Int] = hooks.processDue(20)(deliver)

  val loop: IO[Nothing] =
    Periodic.run(interval, interval, ErrorCode.WebhookWorkerFailed)(runOnce)

  val purgeLoop: IO[Nothing] =
    Periodic.run(1.hour, 1.hour, ErrorCode.WebhookPurgeFailed)(hooks.purgeOld())
}
