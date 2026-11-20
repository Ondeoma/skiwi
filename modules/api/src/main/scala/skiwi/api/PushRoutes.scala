package skiwi.api

import cats.effect.*
import io.circe.Decoder
import org.http4s.*
import skiwi.core.{AuthVia, ErrorCode}

import java.util.UUID

/** プッシュ通知（PWA）の購読（sbirds.push.PushRoutes）. 端末の登録は画面のログインだけ（API トークンでは登録しない）. */
final class PushRoutes(store: sbirds.push.PushStore, push: sbirds.push.PushService, protected val sessions: SessionAuth)
  extends RouteSupport { self =>

  private val host = new sbirds.push.PushRoutes.Host {
    def authed(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]] = self.authed(req)(s => f(s.user.id))
    def interactive(req: Request[IO])(f: UUID => IO[Response[IO]]): IO[Response[IO]] =
      self.authed(req)(s => if s.via != AuthVia.Session then err(ErrorCode.PushSessionOnly) else f(s.user.id))
    def body[A: Decoder](req: Request[IO])(f: A => IO[Response[IO]]): IO[Response[IO]] = self.body[A](req)(f)
  }

  val routes: HttpRoutes[IO] = new sbirds.push.PushRoutes(
    store,
    push,
    host,
    notAllowed = ErrorCode.PushServiceNotAllowed,
    invalid = ErrorCode.PushSubscriptionInvalid
  ).routes
}
