package skiwi.web

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.shared.*

import java.util.UUID
import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

/** API の呼び出しでのエラー（frontend/src/api.ts の ApiError と同じ） */
final case class ApiError(status: Int, message: String) extends Exception(message)

/** バックエンドの API（frontend/src/api.ts の request と同じ約束: 401 はログインへ、エラーは {"error": ...}） */
object Api {

  private def request(method: dom.HttpMethod, url: String, body: Option[Json] = None): Future[Option[Json]] = {
    val init = new dom.RequestInit {}
    init.method = method
    init.credentials = dom.RequestCredentials.`same-origin`
    body.foreach { b =>
      init.headers = js.Dictionary("Content-Type" -> "application/json")
      init.body = b.noSpaces
    }
    dom.fetch(url, init).toFuture.flatMap { res =>
      if (res.status == 401) {
        // 未ログイン: IdPへ
        dom.window.location.href = "/auth/login"
        Future.failed(ApiError(401, "unauthorized"))
      } else if (!res.ok) {
        res.text().toFuture.recover(_ => "").flatMap { t =>
          val msg = parse(t).toOption.flatMap(_.as[ErrorDto].toOption).map(_.error).getOrElse(res.statusText)
          Future.failed(ApiError(res.status, msg))
        }
      } else if (res.status == 204) Future.successful(None)
      else res.text().toFuture.map(t => Some(parse(t).fold(throw _, identity)))
    }
  }

  private def get[A: Decoder](url: String): Future[A] = request(dom.HttpMethod.GET, url).map(as[A])

  private def send[A: Decoder](method: dom.HttpMethod, url: String, body: Json): Future[A] =
    request(method, url, Some(body)).map(as[A])

  private def as[A: Decoder](j: Option[Json]): A = j.getOrElse(Json.Null).as[A].fold(throw _, identity)

  private def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  // ---------------------------------------------------------------- APIトークン・監査ログ

  def tokens(): Future[List[TokenDto]] = get[List[TokenDto]]("/api/tokens")

  def createToken(req: CreateTokenReq): Future[CreatedTokenDto] =
    send[CreatedTokenDto](dom.HttpMethod.POST, "/api/tokens", req.asJson)

  def revokeToken(id: UUID): Future[Unit] = request(dom.HttpMethod.DELETE, s"/api/tokens/$id").map(_ => ())

  def audit(before: Option[Long], action: Option[String], limit: Int): Future[List[AuditDto]] = {
    val q = (before.map(b => s"before=$b") ++ action.map(a => s"action=${enc(a)}") ++ Some(s"limit=$limit")).mkString("&")
    get[List[AuditDto]](s"/api/admin/audit?$q")
  }

}
