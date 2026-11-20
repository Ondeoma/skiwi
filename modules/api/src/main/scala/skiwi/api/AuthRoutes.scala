package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.{LoginResult, OidcClient}
import sbirds.http.{Errors, OidcLoginFlow}
import skiwi.core.{AgentAccounts, ErrorCode}
import skiwi.db.Store

import java.time.Instant

/** /auth/login → IdP → /auth/callback → セッションCookie発行。 */
/**
 * scimManagesGroups=true のとき、ログイン時のgroupsクレームでグループ所属を上書きしない（SCIMを正とする）。
 * devLogin=true（DEV_LOGIN. OIDC を設定しないときだけ）のときは、IdP の代わりに開発用のログイン（/login の画面と /auth/dev-login）を使う。
 */
final class AuthRoutes(oidc: Option[OidcClient],
                       store: Store,
                       protected val sessions: SessionAuth,
                       audit: Audit,
                       scimManagesGroups: Boolean = false,
                       publicUrl: String = "",
                       devLogin: Boolean = false) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // returnTo: ログイン後の戻り先（同じサイトの相対パスだけ。/oauth/authorize などサーバーの画面へ戻るため）
    case req @ GET -> Root / "auth" / "login" => getAuthLogin(req)
    case req @ GET -> Root / "auth" / "callback" => getAuthCallback(req)
    case req @ POST -> Root / "auth" / "logout" => postAuthLogout(req)
    case req @ POST -> Root / "auth" / "dev-login" if devLogin && oidc.isEmpty => postAuthDevLogin(req)
  }

  private def getAuthLogin(req: Request[IO]): IO[Response[IO]] = OidcLoginFlow.login(flow)(req)

  private def getAuthCallback(req: Request[IO]): IO[Response[IO]] = OidcLoginFlow.callback(flow)(req)

  /** OIDC のログインの流れ（sbirds.http.OidcLoginFlow）に差し込む、skiwi の部分。 */
  private val flow = new OidcLoginFlow.Host[LoginResult] {
    val idp = oidc.map(OidcLoginFlow.Idp(_))

    // ログインを始めたブラウザだけが callback を完了できるよう、state を Cookie にも入れる（ログイン CSRF 対策）
    val cookie = OidcLoginFlow.StateCookie(
      "skiwi_login_state",
      sessions.cookieSecure,
      clear = OidcLoginFlow.Clear.Always,
      checkWithoutCode = true
    )
    override val idpErrorParam = true

    def returnTo(req: Request[IO]): String = safeReturnTo(req.uri.query.params.get("returnTo")).getOrElse("/")
    def saveState(state: String, verifier: String, nonce: String, returnTo: String): IO[Unit] =
      store.saveLoginState(state, verifier, nonce, Instant.now(), Some(returnTo).filter(_ != "/"))
    def takeState(state: String): IO[Option[(String, String, String)]] =
      store.takeLoginState(state, Instant.now()).map(_.map((verifier, nonce, back) =>
        (verifier, nonce, back.flatMap(b => safeReturnTo(Some(b))).getOrElse("/"))
      ))
    def redirect(to: String): IO[Response[IO]] = AuthRoutes.this.redirect(to)

    def withoutIdp(req: Request[IO], back: String): IO[Response[IO]] =
      if devLogin then AuthRoutes.this.redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back, "UTF-8")}")
      else err(ErrorCode.OidcNotConfigured)

    def failed(req: Request[IO], f: OidcLoginFlow.Failure): IO[Response[IO]] = {
      import OidcLoginFlow.Failure.*
      f match {
        case NotConfigured => err(ErrorCode.OidcNotConfigured)
        case IdpError(_, _) if oidc.isEmpty => err(ErrorCode.OidcNotConfigured)
        // IdP の error・error_description は URL で差し替えられるので応答には出さず、ログにだけ残す
        case IdpError(e, d) => Errors.respondLogged(ErrorCode.LoginIdpError, OidcLoginFlow.idpErrorLog(e, d), Some(req))
        case ParamsMissing => err(ErrorCode.LoginCodeStateRequired)
        case BrowserMismatch => err(ErrorCode.LoginBrowserMismatch)
        case Expired => err(ErrorCode.LoginExpired)
        // 理由は利用者に見せず、ログにだけ出す
        case Denied(e) => rejected(req, e.getMessage)
        case Failed(e) => rejected(req, e.getMessage)
      }
    }

    def loggedIn(req: Request[IO], res: LoginResult, back: String): IO[Response[IO]] = res match {
      // エージェント専用アカウントの sub ではログインさせない（IdP がその形の sub を返しても）
      case LoginResult(id, _) if AgentAccounts.isAgent(id.sub) => rejected(req, "agent account sub")
      case LoginResult(id, idToken) =>
        store.upsertUser(id.sub, id.email, id.name, id.picture).flatMap { user =>
          store.isActive(user.id).flatMap {
            case false =>
              audit.user(user, "session", "auth.login.denied", Json.obj("reason" -> "inactive".asJson)) *>
                err(ErrorCode.UserDisabled)
            case true =>
              for {
                _ <- if scimManagesGroups then IO.unit else store.syncGroups(user.id, id.groups)
                cookie <- sessions.issue(user, Some(idToken))
                _ <- audit.user(user, "session", "auth.login")
                _ <- sbirds.observe.Metrics.count("skiwi.logins")
                res <- AuthRoutes.this.redirect(back, Some(cookie))
              } yield res
          }
        }
    }
  }

  private def rejected(req: Request[IO], why: String): IO[Response[IO]] =
    Errors.respondLogged(ErrorCode.LoginFailed, s"login rejected: $why", Some(req))

  private def postAuthLogout(req: Request[IO]): IO[Response[IO]] =
    sessions.fromRequest(req).attempt.flatMap { who =>
      who.toOption.flatten.traverse_(s => audit.log(s, "auth.logout", "user", s.user.id)) *>
        sessions.revoke(req).flatMap {
          (idToken, c) =>
            (if publicUrl.isEmpty then IO.pure("/")
             else OidcLoginFlow.logoutRedirect(oidc, idToken, s"${publicUrl.stripSuffix("/")}/")).map { to =>
              Response[IO](Status.Ok).withEntity(Json.obj("redirect" -> to.asJson)).addCookie(c)
            }
        }
    }

  private def postAuthDevLogin(req: Request[IO]): IO[Response[IO]] =
    body[Json](req) { j =>
      val c = j.hcursor
      val name = c.get[String]("name").getOrElse("").trim
      val email = c.get[String]("email").toOption.map(_.trim).filter(_.contains("@"))
      val admin = c.get[Boolean]("admin").getOrElse(false)
      if name.isEmpty || name.length > 100 || name.exists(_.isControl) then
        err(ErrorCode.DevLoginNameInvalid)
      else if admin && sessions.adminGroups.isEmpty then
        err(ErrorCode.DevLoginAdminGroupsMissing)
      else
        store.upsertUser(s"dev:$name", email, name).flatMap { user =>
          store.isActive(user.id).flatMap {
            case false => err(ErrorCode.UserDisabled)
            case true =>
              for {
                cur <- store.userWithGroups(user.id).map(_.fold(Nil)(_._2.map(_.externalId)))
                others = cur.filterNot(sessions.adminGroups.contains)
                _ <- store.syncGroups(user.id, if admin then others ++ sessions.adminGroups.toList.sorted else others)
                cookie <- sessions.issue(user)
                _ <- audit.user(user, "session", "auth.login", Json.obj("via" -> "dev-login".asJson))
                _ <- sbirds.observe.Metrics.count("skiwi.logins")
                res <-
                  Ok(Json.obj("redirect" -> safeReturnTo(c.get[String]("returnTo").toOption).getOrElse("/").asJson))
              } yield res.addCookie(cookie)
          }
        }
    }
}
