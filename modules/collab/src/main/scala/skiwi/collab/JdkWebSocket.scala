package skiwi.collab

import cats.Foldable
import cats.effect.*
import cats.effect.std.{Dispatcher, Mutex, Queue}
import cats.syntax.all.*
import org.http4s.client.websocket.*
import scodec.bits.ByteVector

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.nio.ByteBuffer
import java.util.concurrent.{CompletableFuture, CompletionStage}
import scala.concurrent.duration.*

/**
 * JDK 標準の WebSocket（java.net.http）で作る http4s の WSClient. 担当ノードへの転送に使う.
 * http4s 1.0 の ember には WebSocket のクライアントがなく、http4s-jdk-http-client は版が合わないため自前で持つ.
 * ハンドシェイクの失敗は JDK の WebSocketHandshakeException のまま返す（Collab.handshakeStatus が応答のステータスを読む）.
 */
object JdkWebSocket {

  def client(
    connectTimeout: FiniteDuration = 2.seconds
  ): Resource[IO, WSClient[IO]] =
    for {
      http <- Resource.eval(
        IO(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(connectTimeout.toMillis)).build())
      )
      disp <- Dispatcher.parallel[IO](await = false)
    } yield WSClient[IO](respondToPings = false)(req => connect(http, disp, req)) // ping には JDK が自動で応える

  private def connect(
    http: HttpClient,
    disp: Dispatcher[IO],
    req: WSRequest
  ): Resource[IO, WSConnection[IO]] =
    Resource.make(
      for {
        q <- Queue.unbounded[IO, Option[WSFrame]]
        lock <- Mutex[IO]
        listener = new WebSocket.Listener {
          private def push(
            f: Option[WSFrame],
            ws: WebSocket
          ): CompletionStage[?] = {
            disp.unsafeRunAndForget(q.offer(f))
            if f.isDefined then ws.request(1)
            null
          }
          override def onText(
            ws: WebSocket,
            data: CharSequence,
            last: Boolean
          ): CompletionStage[?] =
            push(Some(WSFrame.Text(data.toString, last)), ws)
          override def onBinary(
            ws: WebSocket,
            data: ByteBuffer,
            last: Boolean
          ): CompletionStage[?] =
            push(Some(WSFrame.Binary(ByteVector(data), last)), ws) // ByteVector(ByteBuffer) は中身を写す（JDK はバッファを使い回す）
          override def onPong(
            ws: WebSocket,
            data: ByteBuffer
          ): CompletionStage[?] =
            push(Some(WSFrame.Pong(ByteVector(data))), ws)
          override def onClose(
            ws: WebSocket,
            code: Int,
            reason: String
          ): CompletionStage[?] = {
            disp.unsafeRunAndForget(q.offer(Some(WSFrame.Close(code, reason))) *> q.offer(None))
            null
          }
          override def onError(
            ws: WebSocket,
            error: Throwable
          ): Unit =
            disp.unsafeRunAndForget(q.offer(None))
        }
        builder = req.headers.headers.foldLeft(http.newWebSocketBuilder())(
          (
            b,
            h
          ) => b.header(h.name.toString, h.value)
        )
        ws <- IO.fromCompletableFuture(IO(builder.buildAsync(URI.create(req.uri.renderString), listener)))
      } yield (ws, q, lock)
    ) {
      (
        ws,
        _,
        _
      ) =>
        // 閉じる合図を送ってから切る（送れなくても切る）
        IO.fromCompletableFuture(IO(ws.sendClose(WebSocket.NORMAL_CLOSURE, ""))).void.timeout(1.second).attempt *>
          IO(ws.abort())
    }.map {
      (
        ws,
        q,
        lock
      ) =>
        new WSConnection[IO] {
          // JDK の WebSocket は、前の送信が終わる前に次を送れない
          private def run(
            f: => CompletableFuture[WebSocket]
          ): IO[Unit] = lock.lock.surround(IO.fromCompletableFuture(IO(f)).void)
          def send(
            wsf: WSFrame
          ): IO[Unit] = wsf match {
            case WSFrame.Text(t, last) => run(ws.sendText(t, last))
            case WSFrame.Binary(d, last) => run(ws.sendBinary(d.toByteBuffer, last))
            case WSFrame.Ping(d) => run(ws.sendPing(d.toByteBuffer))
            case WSFrame.Pong(d) => run(ws.sendPong(d.toByteBuffer))
            case WSFrame.Close(c, r) => run(ws.sendClose(c, r))
          }
          def sendMany[G[_]: Foldable, A <: WSFrame](
            wsfs: G[A]
          ): IO[Unit] = wsfs.traverse_(send)
          def receive: IO[Option[WSFrame]] = q.take
          def subprotocol: Option[String] = Option(ws.getSubprotocol).filter(_.nonEmpty)
        }
    }
}
