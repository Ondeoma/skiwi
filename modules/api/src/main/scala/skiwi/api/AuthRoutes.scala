package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import skiwi.auth.{Crypto, OidcClient}
import skiwi.db.Store

import java.time.Instant

/** /auth/login → IdP → /auth/callback → セッションCookie発行。 */
/** scimManagesGroups=true のとき、ログイン時のgroupsクレームでグループ所属を上書きしない（SCIMを正とする）。 */
final class AuthRoutes(oidc: OidcClient, store: Store, sessions: SessionAuth, audit: Audit, scimManagesGroups: Boolean = false)
    extends Http4sDsl[IO]:

  private def redirect(to: String): Response[IO] =
    Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "auth" / "login" =>
      val state, verifier, nonce = Crypto.randomToken()
      for
        _   <- store.saveLoginState(state, verifier, nonce, Instant.now())
        uri <- oidc.authorizationUri(state, verifier, nonce)
      yield redirect(uri.renderString)

    case req @ GET -> Root / "auth" / "callback" =>
      val params = req.uri.query.params
      (params.get("state"), params.get("code"), params.get("error")) match
        case (_, _, Some(err)) => BadRequest(s"login failed: $err")
        case (Some(state), Some(code), _) =>
          store.takeLoginState(state, Instant.now()).flatMap {
            case None => BadRequest("invalid or expired login state")
            case Some((verifier, nonce)) =>
              oidc.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
                case Left(e) =>
                  IO.println(s"[auth] login rejected: ${e.getMessage}") *> Forbidden("login rejected")
                case Right(id) =>
                  store.upsertUser(id.sub, id.email, id.name).flatMap { user =>
                    store.isActive(user.id).flatMap {
                      case false =>
                        audit.user(user, "session", "auth.login.denied", Json.obj("reason" -> "inactive".asJson)) *>
                          Forbidden("このアカウントは無効化されています")
                      case true =>
                        for
                          _      <- if scimManagesGroups then IO.unit else store.syncGroups(user.id, id.groups)
                          cookie <- sessions.issue(user)
                          _      <- audit.user(user, "session", "auth.login")
                        yield redirect("/").addCookie(cookie)
                    }
                  }
              }
          }
        case _ => BadRequest("missing state or code")

    case req @ POST -> Root / "auth" / "logout" =>
      sessions.fromRequest(req).attempt.flatMap { who =>
        who.toOption.flatten.traverse_(s => audit.log(s, "auth.logout", "user", s.user.id)) *>
          sessions.revoke(req).map(c => Response[IO](Status.NoContent).addCookie(c))
      }
  }
