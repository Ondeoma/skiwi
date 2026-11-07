package skiwi.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import cats.syntax.semigroupk.*
import skiwi.auth.*
import skiwi.db.*

import java.nio.charset.StandardCharsets
import java.util.Base64

/** ログイン〜コールバック〜セッション〜/api/me を、偽のIdP(メモリ内)で通しで検証する。 */
class OidcFlowSpec extends DbSuite:

  private val issuer = "https://idp.test/application/o/skiwi/"
  private val oidcCfg = OidcConfig(issuer, "skiwi-client", "s3cret", "https://skiwi.test/auth/callback")

  test("authorization code + PKCE flow creates a session with groups") {
    if cfg.isEmpty then IO(assume(false, "TEST_DATABASE_URL not set"))
    else
      val c = cfg.get
      (for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield xa).use { xa =>
        val store = new Store(xa)
        for
          challenge <- Ref.of[IO, String]("")
          nonce     <- Ref.of[IO, String]("")
          tokenForm <- Ref.of[IO, Option[UrlForm]](None)
          idp = HttpRoutes.of[IO] {
            case GET -> Root / "application" / "o" / "skiwi" / ".well-known" / "openid-configuration" =>
              Ok(Json.obj(
                "issuer" -> issuer.asJson,
                "authorization_endpoint" -> "https://idp.test/authorize".asJson,
                "token_endpoint" -> "https://idp.test/token".asJson))
            case req @ POST -> Root / "token" =>
              req.as[UrlForm].flatMap { f =>
                tokenForm.set(Some(f)) *> nonce.get.flatMap { n =>
                  val claims = Json.obj(
                    "iss" -> issuer.asJson, "aud" -> "skiwi-client".asJson,
                    "exp" -> (System.currentTimeMillis / 1000 + 300).asJson,
                    "nonce" -> n.asJson, "sub" -> s"sub-${java.util.UUID.randomUUID()}".asJson,
                    "name" -> "鈴木 花子".asJson, "email" -> "hanako@example.com".asJson,
                    "groups" -> List("skiwi-admins", "営業部").asJson)
                  val e = Base64.getUrlEncoder.withoutPadding()
                  val jwt = e.encodeToString("""{"alg":"none"}""".getBytes) + "." +
                    e.encodeToString(claims.noSpaces.getBytes(StandardCharsets.UTF_8)) + ".x"
                  Ok(Json.obj("id_token" -> jwt.asJson, "access_token" -> "at".asJson, "token_type" -> "Bearer".asJson))
                }
              }
          }.orNotFound
          oidc <- OidcClient.create(oidcCfg, Client.fromHttpApp(idp))
          apiCfg = ApiConfig(Set("skiwi-admins"), cookieSecure = true)
          ops = new OpsStore(xa)
          sess = new SessionAuth(store, ops, apiCfg)
          audit = new Audit(ops)
          app = (new AuthRoutes(oidc, store, sess, audit).routes <+> new ApiRoutes(store, new DocStore(xa), ops, sess, audit).routes).orNotFound

          // 1) /auth/login → IdPへのリダイレクト (PKCE S256, state, nonce)
          login <- app.run(Request[IO](Method.GET, uri"/auth/login"))
          loc = login.headers.get[Location].get.uri
          q = loc.query.params
          _ = assertEquals(login.status, Status.Found)
          _ = assertEquals(q.get("code_challenge_method"), Some("S256"))
          _ = assertEquals(q.get("client_id"), Some("skiwi-client"))
          _ = assertEquals(q.get("response_type"), Some("code"))
          _ <- nonce.set(q("nonce"))
          _ <- challenge.set(q("code_challenge"))

          // 2) /auth/callback → セッションCookie
          cb <- app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/auth/callback?code=abc&state=${q("state")}")))
          _ = assertEquals(cb.status, Status.Found)
          setCookie = cb.cookies.find(_.name == "skiwi_session").get
          _ = assert(setCookie.httpOnly && setCookie.secure)
          _ = assertEquals(setCookie.sameSite, Some(SameSite.Lax))

          // トークン要求に正しいPKCE verifier が含まれている
          form <- tokenForm.get.map(_.get)
          verifier = form.getFirst("code_verifier").get
          _ = assertEquals(Crypto.pkceChallenge(verifier), q("code_challenge"))
          _ = assertEquals(form.getFirst("code"), Some("abc"))
          _ = assertEquals(form.getFirst("grant_type"), Some("authorization_code"))

          // 3) セッションで /api/me
          me <- app.run(Request[IO](Method.GET, uri"/api/me").addCookie("skiwi_session", setCookie.content))
          j  <- me.as[Json]
          _ = assertEquals(j.hcursor.get[String]("name"), Right("鈴木 花子"))
          _ = assertEquals(j.hcursor.get[Boolean]("isAdmin"), Right(true))
          _ = assertEquals(j.hcursor.get[List[String]]("groups").map(_.toSet), Right(Set("skiwi-admins", "営業部")))

          // 4) stateは1回限り。再利用は拒否
          replay <- app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/auth/callback?code=abc&state=${q("state")}")))
          _ = assertEquals(replay.status, Status.BadRequest)

          // 5) ログアウト後は401
          lo <- app.run(Request[IO](Method.POST, uri"/auth/logout").addCookie("skiwi_session", setCookie.content))
          _ = assertEquals(lo.status, Status.NoContent)
          after <- app.run(Request[IO](Method.GET, uri"/api/me").addCookie("skiwi_session", setCookie.content))
          _ = assertEquals(after.status, Status.Unauthorized)
        yield ()
      }
  }
