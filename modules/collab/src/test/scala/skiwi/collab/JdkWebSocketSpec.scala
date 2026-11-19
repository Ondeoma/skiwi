package skiwi.collab

import cats.effect.*
import com.comcast.ip4s.*
import cats.effect.std.Queue
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.websocket.{WSFrame, WSRequest}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.typelevel.ci.CIString
import scodec.bits.ByteVector

import scala.concurrent.duration.*

/** 担当ノードへの転送に使う WebSocket のクライアント（JDK 標準の WebSocket）を、実際の Ember のサーバーにつないで確かめる。 */
class JdkWebSocketSpec extends CatsEffectSuite {

  private given org.typelevel.log4cats.LoggerFactory[IO] = org.typelevel.log4cats.noop.NoOpFactory[IO]

  /** /echo: 受けたフレームをそのまま返す（ヘッダの X-Test を最初にテキストで返す）。/deny: 403 */
  private def app(
    wsb: WebSocketBuilder[IO]
  ): HttpApp[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "echo" =>
      val hello = req.headers.get(CIString("X-Test")).map(_.head.value).getOrElse("")
      Queue.unbounded[IO, WebSocketFrame].flatMap { q =>
        wsb.build(
          Stream.emit(WebSocketFrame.Text(hello)) ++ Stream.fromQueueUnterminated(q),
          _.evalMap {
            case f @ (_: WebSocketFrame.Text | _: WebSocketFrame.Binary) => q.offer(f)
            case _ => IO.unit
          }
        )
      }
    case GET -> Root / "deny" => Forbidden()
  }.orNotFound

  private val server = ResourceSuiteLocalFixture(
    "server",
    EmberServerBuilder.default[IO].withHost(ipv4"127.0.0.1").withPort(port"0").withHttpWebSocketApp(app).build
  )

  override def munitFixtures = List(server)

  private def url(
    path: String
  ) = Uri.unsafeFromString(s"ws://127.0.0.1:${server().address.port.value}$path")

  test("ヘッダを付けてつなぎ、テキストとバイナリを送受信できる") {
    JdkWebSocket.client().flatMap(_.connectHighLevel(WSRequest(url("/echo")).withHeaders(Headers(Header.Raw(
      CIString("X-Test"),
      "forwarded"
    ))))).use { c =>
      for {
        hello <- c.receive
        _ <- c.send(WSFrame.Text("こんにちは"))
        t <- c.receive
        _ <- c.send(WSFrame.Binary(ByteVector(1, 2, 3)))
        b <- c.receive
      } yield {
        assertEquals(hello, Some(WSFrame.Text("forwarded")))
        assertEquals(t, Some(WSFrame.Text("こんにちは")))
        assertEquals(b, Some(WSFrame.Binary(ByteVector(1, 2, 3))))
      }
    }.timeout(10.seconds)
  }

  test("相手が接続を断ったら、その応答のステータスが分かる（担当ノードの認証の拒否をそのまま返すため）") {
    JdkWebSocket.client().flatMap(_.connectHighLevel(WSRequest(url("/deny")))).use_.attempt.map {
      case Left(e) => assertEquals(Collab.handshakeStatus(e), Some(403))
      case Right(_) => fail("つながってしまった")
    }.timeout(10.seconds)
  }
}
