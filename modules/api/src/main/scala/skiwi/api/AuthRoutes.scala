package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Location
import skiwi.auth.{Crypto, OidcClient}
import skiwi.core.AgentAccounts
import skiwi.db.Store

import java.time.Instant

/** /auth/login → IdP → /auth/callback → セッションCookie発行。 */
/** scimManagesGroups=true のとき、ログイン時のgroupsクレームでグループ所属を上書きしない（SCIMを正とする）。
  * devLogin=true（DEV_LOGIN. OIDC を設定しないときだけ）のときは、IdP の代わりに開発用のログイン（/login の画面と /auth/dev-login）を使う。 */
final class AuthRoutes(oidc: Option[OidcClient], store: Store, protected val sessions: SessionAuth, audit: Audit, scimManagesGroups: Boolean = false,
    publicUrl: String = "", devLogin: Boolean = false) extends RouteSupport:

  private def redirect(to: String): Response[IO] =
    Response[IO](Status.Found).putHeaders(Location(Uri.unsafeFromString(to)))

  /** ログインを始めたブラウザだけが callback を完了できるよう、state を Cookie にも入れる（ログイン CSRF 対策）。
    * IdP からの戻りはトップレベルの GET なので SameSite=Lax でも送られる。 */
  private val StateCookie = "skiwi_login_state"
  private def stateCookie(v: String, maxAge: Long) =
    ResponseCookie(StateCookie, v, maxAge = Some(maxAge), path = Some("/auth"), httpOnly = true,
      secure = sessions.cookieSecure, sameSite = Some(SameSite.Lax))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // returnTo: ログイン後の戻り先（同じサイトの相対パスだけ。/oauth/authorize などサーバーの画面へ戻るため）
    case req @ GET -> Root / "auth" / "login" =>
      val state, verifier, nonce = Crypto.randomToken()
      val back = safeReturnTo(req.uri.query.params.get("returnTo")).filter(_ != "/")
      oidc match
        case Some(o) =>
          for
            _   <- store.saveLoginState(state, verifier, nonce, Instant.now(), back)
            uri <- o.authorizationUri(state, verifier, nonce)
          yield redirect(uri.renderString).addCookie(stateCookie(state, 600))
        case None if devLogin => IO.pure(redirect(s"/login?returnTo=${java.net.URLEncoder.encode(back.getOrElse("/"), "UTF-8")}"))
        case None             => err(Status.ServiceUnavailable, "OIDC が設定されていません")

    case req @ GET -> Root / "auth" / "callback" =>
      val params = req.uri.query.params
      (params.get("state"), params.get("code"), params.get("error")) match
        case _ if oidc.isEmpty => err(Status.ServiceUnavailable, "OIDC が設定されていません")
        case (_, _, Some(e)) => BadRequest(s"ログインできませんでした: $e")
        case (Some(state), _, _) if !req.cookies.find(_.name == StateCookie).exists(c => Crypto.constantTimeEquals(c.content, state)) =>
          BadRequest("ログインを始めたブラウザと違います。もう一度ログインしてください")
        case (Some(state), Some(code), _) =>
          store.takeLoginState(state, Instant.now()).flatMap {
            case None => BadRequest("ログインの有効期限が切れました。もう一度ログインしてください")
            case Some((verifier, nonce, back)) =>
              oidc.get.authenticate(code, verifier, nonce, Instant.now()).attempt.flatMap {
                case Left(e) =>
                  skiwi.observe.Log.println(s"[auth] login rejected: ${e.getMessage}") *> Forbidden("ログインできませんでした")
                // エージェント専用アカウントの sub ではログインさせない（IdP がその形の sub を返しても）
                case Right(id) if AgentAccounts.isAgent(id.sub) =>
                  skiwi.observe.Log.println("[auth] login rejected: agent account sub") *> Forbidden("ログインできませんでした")
                case Right(id) =>
                  store.upsertUser(id.sub, id.email, id.name, id.picture).flatMap { user =>
                    store.isActive(user.id).flatMap {
                      case false =>
                        audit.user(user, "session", "auth.login.denied", Json.obj("reason" -> "inactive".asJson)) *>
                          Forbidden("このアカウントは無効化されています")
                      case true =>
                        for
                          _      <- if scimManagesGroups then IO.unit else store.syncGroups(user.id, id.groups)
                          cookie <- sessions.issue(user, id.idToken)
                          _      <- audit.user(user, "session", "auth.login")
                          _      <- skiwi.observe.Metrics.count("skiwi.logins")
                        yield redirect(back.flatMap(b => safeReturnTo(Some(b))).getOrElse("/")).addCookie(cookie)
                    }
                  }
              }
          }.map(_.addCookie(stateCookie("", 0)))
        case _ => BadRequest("code と state が必要です")

    // ログアウト: セッションを消し、IdP にもログアウトを頼む（IdP のログインが残っていると、すぐにログインし直してしまうため）.
    // 返す redirect は次に開く URL（IdP のログアウト画面か「/」）
    case req @ POST -> Root / "auth" / "logout" =>
      sessions.fromRequest(req).attempt.flatMap { who =>
        who.toOption.flatten.traverse_(s => audit.log(s, "auth.logout", "user", s.user.id)) *>
          sessions.revoke(req).flatMap { (idToken, c) =>
            (if publicUrl.isEmpty then IO.pure(None) else oidc.flatTraverse(_.logoutUri(idToken, s"${publicUrl.stripSuffix("/")}/"))).map { idp =>
              Response[IO](Status.Ok).withEntity(Json.obj("redirect" -> idp.fold("/")(_.renderString).asJson)).addCookie(c)
            }
          }
      }
  
    // 開発用: 名前だけでログインする（DEV_LOGIN=true で OIDC を設定していないときだけ）。ユーザーは dev:<名前>.
    // 管理者は ADMIN_GROUPS のグループで決まるので、admin=true なら ADMIN_GROUPS のグループに入れ、false なら外す（ほかのグループはそのまま）
    case req @ POST -> Root / "auth" / "dev-login" if devLogin && oidc.isEmpty =>
      body[Json](req) { j =>
        val c     = j.hcursor
        val name  = c.get[String]("name").getOrElse("").trim
        val email = c.get[String]("email").toOption.map(_.trim).filter(_.contains("@"))
        val admin = c.get[Boolean]("admin").getOrElse(false)
        if name.isEmpty || name.length > 100 || name.exists(_.isControl) then err(Status.BadRequest, "名前を入力してください（100文字まで）")
        else if admin && sessions.adminGroups.isEmpty then err(Status.BadRequest, "ADMIN_GROUPS が設定されていないため、管理者としてログインできません")
        else
          store.upsertUser(s"dev:$name", email, name).flatMap { user =>
            store.isActive(user.id).flatMap {
              case false => err(Status.Forbidden, "このアカウントは無効化されています")
              case true =>
                for
                  cur    <- store.userWithGroups(user.id).map(_.fold(Nil)(_._2.map(_.externalId)))
                  others  = cur.filterNot(sessions.adminGroups.contains)
                  _      <- store.syncGroups(user.id, if admin then others ++ sessions.adminGroups.toList.sorted else others)
                  cookie <- sessions.issue(user)
                  _      <- audit.user(user, "session", "auth.login", Json.obj("via" -> "dev-login".asJson))
                  _      <- skiwi.observe.Metrics.count("skiwi.logins")
                  res    <- Ok(Json.obj("redirect" -> safeReturnTo(c.get[String]("returnTo").toOption).getOrElse("/").asJson))
                yield res.addCookie(cookie)
            }
          }
      }
  }
