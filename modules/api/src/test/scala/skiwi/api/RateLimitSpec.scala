package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import com.comcast.ip4s.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString
import sbirds.http.RateLimitConfig

import scala.concurrent.duration.*

/** IP ごとの回数の制限: どの入口を数えるか。DB は使わない。 */
class RateLimitSpec extends CatsEffectSuite {

  private val ok = HttpRoutes.of[IO] { case _ => Ok("ok") }
  private val clock = IO.pure(10.minutes)

  private def from(ip: String, method: Method, path: Uri): Request[IO] =
    Request[IO](method, path).withAttribute(
      Request.Keys.ConnectionInfo,
      Request.Connection(
        SocketAddress(ipv4"10.0.0.2", port"8080"),
        SocketAddress(IpAddress.fromString(ip).get, port"50000"),
        false
      )
    )

  private def statuses(app: HttpApp[IO], n: Int, req: Request[IO]): IO[List[Status]] =
    List.fill(n)(app.run(req)).sequence.map(_.map(_.status))

  test("ログイン・OAuth の入口・公開リンクの API は IP ごとに数え、添付・フォーム・Webhook・ログイン後の API は数えない") {
    val app = RateLimit(ok, RateLimitConfig(authPerMinute = 2, publicPerMinute = 3), clock).orNotFound
    for {
      login <- List.fill(3)(app.run(from("203.0.113.5", Method.GET, uri"/auth/login"))).sequence
      other <- app.run(from("203.0.113.6", Method.GET, uri"/auth/login"))
      // 公開リンクの API は別の（緩い）カウンタ
      shared <- statuses(app, 4, from("203.0.113.7", Method.GET, uri"/api/public/abc/pages/p1"))
      attachments <- statuses(app, 10, from("203.0.113.7", Method.GET, uri"/api/public/abc/attachments/x"))
      // 認可の画面はログインと同じカウンタ、トークンの発行は公開リンクの API と同じ（緩い）カウンタ
      authorize <- statuses(app, 3, from("203.0.113.8", Method.GET, uri"/oauth/authorize"))
      token <- statuses(app, 4, from("203.0.113.8", Method.POST, uri"/oauth/token"))
      // フォーム・Webhook ごとの上限が別にある
      forms <- statuses(app, 10, from("203.0.113.5", Method.POST, uri"/api/forms/public/tok"))
      hooks <- statuses(app, 10, from("203.0.113.5", Method.POST, uri"/api/hooks/tok"))
      api <- statuses(app, 10, from("203.0.113.5", Method.GET, uri"/api/me"))
      mcp <- statuses(app, 10, from("203.0.113.5", Method.POST, uri"/mcp"))
    } yield {
      assertEquals(login.map(_.status), List(Status.Ok, Status.Ok, Status.TooManyRequests))
      assert(login.last.headers.get(CIString("Retry-After")).isDefined)
      assertEquals(other.status, Status.Ok) // 別の IP は別に数える
      assertEquals(shared, List(Status.Ok, Status.Ok, Status.Ok, Status.TooManyRequests))
      assert(attachments.forall(_ == Status.Ok))
      assertEquals(authorize, List(Status.Ok, Status.Ok, Status.TooManyRequests))
      assertEquals(token, List(Status.Ok, Status.Ok, Status.Ok, Status.TooManyRequests))
      assert(forms.forall(_ == Status.Ok) && hooks.forall(_ == Status.Ok))
      assert(api.forall(_ == Status.Ok) && mcp.forall(_ == Status.Ok))
    }
  }

  test("0 なら制限しない") {
    val app = RateLimit(ok, RateLimitConfig(authPerMinute = 0, publicPerMinute = 0), clock).orNotFound
    statuses(app, 30, from("203.0.113.5", Method.GET, uri"/auth/login")).map(rs => assert(rs.forall(_ == Status.Ok)))
  }
}
