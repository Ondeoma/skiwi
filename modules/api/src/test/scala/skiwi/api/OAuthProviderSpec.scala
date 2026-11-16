package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.{Authorization, Location}
import org.http4s.implicits.*
import org.typelevel.ci.*
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** skea → skiwi の連携（skiwi が認可サーバー）: クライアントの登録・許可画面・トークンの発行と失効・ページの一括参照。TEST_DATABASE_URL が必要。 */
class OAuthProviderSpec extends DbSuite:

  private val apiCfg   = ApiConfig(adminGroups = Set("oauth-admins"), cookieSecure = false)
  private val clientId = "skea-test"
  private val secret   = "skea-test-secret-0123456789"
  private val redirect = "https://skea.test/api/integrations/skiwi/callback"

  final case class Env(store: Store, ops: OpsStore, ist: IntegrationStore, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield
        val store = new Store(xa)
        val ops   = new OpsStore(xa)
        val sess  = new SessionAuth(store, ops, apiCfg)
        val ist   = new IntegrationStore(xa)
        val oauth = new OAuthProviderRoutes(ist, ops, store, sess, new Audit(ops), cookieSecure = false)
        // 読み取り専用のトークンでの書き込みの確認のため、ページの作成（ApiRoutes）もつなぐ
        val api   = new ApiRoutes(store, new DocStore(xa), ops, sess, new Audit(ops))
        val app   = (oauth.routes <+> api.routes).orNotFound
        Env(store, ops, ist, HttpApp[IO](r => app.run(r).recoverWith { case ReadOnlyTokenError => IO.pure(Response[IO](Status.Forbidden)) }))

  private val fixture = ResourceSuiteLocalFixture("oauth-provider", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String, groups: List[String] = Nil): IO[(User, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- if groups.nonEmpty then env.store.syncGroups(u.id, groups) else IO.unit
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (u, token)

  private def json(r: Response[IO]): IO[(Response[IO], Json)] =
    r.bodyText.compile.string.map(s => (r, io.circe.parser.parse(s).getOrElse(Json.fromString(s))))

  private def call(session: Option[String], method: Method, path: String, body: Option[Json] = None, bearer: Option[String] = None): IO[(Response[IO], Json)] =
    var req = Request[IO](method, Uri.unsafeFromString(path))
    body.foreach(b => req = req.withEntity(b))
    session.foreach(t => req = req.addCookie("skiwi_session", t))
    bearer.foreach(t => req = req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
    env.app.run(req).flatMap(json)

  private val basic = Authorization(BasicCredentials(clientId, secret))

  private def oauthPost(path: String, form: UrlForm, auth: Option[Authorization] = Some(basic)): IO[(Response[IO], Json)] =
    val req = Request[IO](Method.POST, Uri.unsafeFromString(path)).withEntity(form)
    env.app.run(auth.fold(req)(a => req.putHeaders(a))).flatMap(json)

  /** クライアントを登録する（テストごとに同じ値で上書き） */
  private def register: IO[Unit] =
    for
      (_, at) <- user("oauth-admin", List("oauth-admins"))
      (r, j)  <- call(Some(at), Method.PUT, "/api/admin/integrations/skea/client",
                   Some(Json.obj("clientId" -> clientId.asJson, "clientSecret" -> secret.asJson, "redirectUri" -> redirect.asJson, "name" -> "skea".asJson)))
    yield assertEquals(r.status, Status.Ok, j.noSpaces)

  private val verifier  = "v" * 50
  private val challenge = Crypto.pkceChallenge(verifier)

  private def authorizeQuery(extra: (String, String)*): String =
    val ps = Map("response_type" -> "code", "client_id" -> clientId, "redirect_uri" -> redirect, "scope" -> "read", "state" -> "st-1",
                 "code_challenge" -> challenge, "code_challenge_method" -> "S256") ++ extra
    Uri.unsafeFromString("/oauth/authorize").withQueryParams(ps).renderString

  private def location(r: Response[IO]): Uri = r.headers.get[Location].get.uri

  /** 許可画面を開いて（CSRF の Cookie を受け取り）、決定をフォームで送る。戻り値は POST の応答 */
  private def decide(session: String, decision: String, csrfOverride: Option[String] = None): IO[Response[IO]] =
    for
      page <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery())).addCookie("skiwi_session", session))
      html <- page.bodyText.compile.string
      _     = assertEquals(page.status, Status.Ok)
      csrf  = page.cookies.find(_.name == OAuthProviderRoutes.CsrfCookie).get.content
      _     = assert(html.contains(s"""name="csrf" value="$csrf""""))
      form  = UrlForm("client_id" -> clientId, "redirect_uri" -> redirect, "state" -> "st-1", "code_challenge" -> challenge,
                      "code_challenge_method" -> "S256", "response_type" -> "code", "scope" -> "read", "csrf" -> csrf, "decision" -> decision)
      r    <- env.app.run(Request[IO](Method.POST, uri"/oauth/authorize").withEntity(form)
                .addCookie("skiwi_session", session).addCookie(OAuthProviderRoutes.CsrfCookie, csrfOverride.getOrElse(csrf)))
    yield r

  /** 許可してコードを受け取る */
  private def code(session: String): IO[String] =
    decide(session, "allow").map { r =>
      assertEquals(r.status, Status.Found)
      val q = location(r).query.params
      assertEquals(q.get("state"), Some("st-1"))
      q("code")
    }

  private def exchange(code: String, v: String = verifier, auth: Option[Authorization] = Some(basic), uri: String = redirect) =
    oauthPost("/oauth/token", UrlForm("grant_type" -> "authorization_code", "code" -> code, "redirect_uri" -> uri, "code_verifier" -> v), auth)

  /** 許可 → トークン */
  private def token(session: String): IO[String] =
    code(session).flatMap(exchange(_)).map { (r, j) =>
      assertEquals(r.status, Status.Ok, j.noSpaces)
      j.hcursor.get[String]("access_token").toOption.get
    }

  test("admin: client registration hides the secret, validates input, non-admin is forbidden") {
    run {
      for
        _       <- register
        (_, at) <- user("oauth-admin2", List("oauth-admins"))
        (_, ut) <- user("oauth-user")
        (r0, _) <- call(Some(ut), Method.GET, "/api/admin/integrations/skea/client")
        _        = assertEquals(r0.status, Status.Forbidden)
        (r1, j1) <- call(Some(at), Method.GET, "/api/admin/integrations/skea/client")
        _        = assertEquals(r1.status, Status.Ok)
        _        = assertEquals(j1.hcursor.get[String]("clientId"), Right(clientId))
        _        = assertEquals(j1.hcursor.get[Boolean]("clientSecretSet"), Right(true))
        _        = assert(!j1.noSpaces.contains(secret))
        // client_secret を省くと今の値のまま。短い秘密・不正な redirect_uri は拒否
        (r2, _) <- call(Some(at), Method.PUT, "/api/admin/integrations/skea/client",
                     Some(Json.obj("clientId" -> clientId.asJson, "redirectUri" -> redirect.asJson, "name" -> "skea".asJson)))
        _        = assertEquals(r2.status, Status.Ok)
        (r3, _) <- call(Some(at), Method.PUT, "/api/admin/integrations/skea/client",
                     Some(Json.obj("clientId" -> clientId.asJson, "clientSecret" -> "short".asJson, "redirectUri" -> redirect.asJson, "name" -> "skea".asJson)))
        _        = assertEquals(r3.status, Status.BadRequest)
        (r4, _) <- call(Some(at), Method.PUT, "/api/admin/integrations/skea/client",
                     Some(Json.obj("clientId" -> clientId.asJson, "redirectUri" -> "javascript:alert(1)".asJson, "name" -> "skea".asJson)))
        _        = assertEquals(r4.status, Status.BadRequest)
        (_, ok) <- exchange("nope")
        _        = assertEquals(ok.hcursor.get[String]("error"), Right("invalid_grant")) // 秘密は変わっていない
      yield ()
    }
  }

  test("authorize: consent page, invalid client/redirect is 400 without redirect, non-S256 and bad scope go back with an error, login redirect") {
    run {
      for
        _       <- register
        (u, ut) <- user("oauth-consent")
        page    <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery())).addCookie("skiwi_session", ut))
        html    <- page.bodyText.compile.string
        _        = assertEquals(page.status, Status.Ok)
        _        = assert(html.contains("skea</b> が、あなた（oauth-consent）の権限で skiwi のページのタイトルを読み取ります"))
        _        = assert(html.contains("許可する") && html.contains("拒否する"))
        _        = assert(page.headers.get(ci"Content-Security-Policy").exists(v => v.head.value.contains("frame-ancestors 'none'") && !v.head.value.contains("form-action")))
        _        = assert(page.headers.get(ci"Cache-Control").exists(_.head.value == "no-store"))
        csrf     = page.cookies.find(_.name == OAuthProviderRoutes.CsrfCookie).get
        _        = assertEquals(csrf.sameSite, Some(SameSite.Strict))
        _        = assert(csrf.httpOnly)
        badC    <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery("client_id" -> "evil"))).addCookie("skiwi_session", ut))
        _        = assertEquals(badC.status, Status.BadRequest)
        _        = assert(badC.headers.get[Location].isEmpty)
        badR    <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery("redirect_uri" -> "https://evil.test/cb"))).addCookie("skiwi_session", ut))
        _        = assertEquals(badR.status, Status.BadRequest)
        _        = assert(badR.headers.get[Location].isEmpty)
        plain   <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery("code_challenge_method" -> "plain"))).addCookie("skiwi_session", ut))
        _        = assertEquals(plain.status, Status.Found)
        _        = assert(location(plain).renderString.startsWith(redirect + "?"))
        _        = assertEquals(location(plain).query.params.get("error"), Some("invalid_request"))
        _        = assertEquals(location(plain).query.params.get("state"), Some("st-1"))
        scope   <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery("scope" -> "write"))).addCookie("skiwi_session", ut))
        _        = assertEquals(location(scope).query.params.get("error"), Some("invalid_scope"))
        // 未ログイン: ログインしてから同じ URL に戻る
        anon    <- env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(authorizeQuery())))
        _        = assertEquals(anon.status, Status.Found)
        _        = assertEquals(location(anon).path.renderString, "/auth/login")
        _        = assertEquals(location(anon).query.params.get("returnTo"), Some(authorizeQuery()))
        _        = assertEquals(safeReturnTo(Some(authorizeQuery())), Some(authorizeQuery()))
      yield ()
    }
  }

  test("consent POST: CSRF mismatch is rejected, deny returns access_denied, allow returns a code") {
    run {
      for
        _       <- register
        (_, ut) <- user("oauth-csrf")
        bad     <- decide(ut, "allow", csrfOverride = Some("forged"))
        _        = assertEquals(bad.status, Status.BadRequest)
        _        = assert(bad.headers.get[Location].isEmpty)
        // Cookie なし（別サイトからの POST）も拒否
        noCookie <- env.app.run(Request[IO](Method.POST, uri"/oauth/authorize").withEntity(UrlForm("client_id" -> clientId, "redirect_uri" -> redirect,
                      "code_challenge" -> challenge, "code_challenge_method" -> "S256", "response_type" -> "code", "csrf" -> "x", "decision" -> "allow"))
                      .addCookie("skiwi_session", ut))
        _        = assertEquals(noCookie.status, Status.BadRequest)
        deny    <- decide(ut, "deny")
        _        = assertEquals(deny.status, Status.Found)
        _        = assertEquals(location(deny).query.params.get("error"), Some("access_denied"))
        _        = assertEquals(location(deny).query.params.get("state"), Some("st-1"))
        c       <- code(ut)
        _        = assert(c.nonEmpty)
      yield ()
    }
  }

  test("token: Basic client auth, verifier/used/expired/redirect checks, read-only token named after the client, re-authorization revokes the old one") {
    run {
      for
        _       <- register
        (u, ut) <- user("oauth-token")
        c1      <- code(ut)
        // クライアント認証の失敗は 401 invalid_client
        (r0, j0) <- exchange(c1, auth = None)
        _        = assertEquals(r0.status, Status.Unauthorized)
        _        = assertEquals(j0.hcursor.get[String]("error"), Right("invalid_client"))
        (r1, _) <- exchange(c1, auth = Some(Authorization(BasicCredentials(clientId, "wrong-secret-0123456789"))))
        _        = assertEquals(r1.status, Status.Unauthorized)
        // 検証子が違う（コードは使用済みになる）
        (r2, j2) <- exchange(c1, v = "w" * 50)
        _        = assertEquals(r2.status, Status.BadRequest)
        _        = assertEquals(j2.hcursor.get[String]("error"), Right("invalid_grant"))
        (r3, j3) <- exchange(c1)
        _        = assertEquals(j3.hcursor.get[String]("error"), Right("invalid_grant"))
        // redirect_uri の不一致
        c2      <- code(ut)
        (_, j4) <- exchange(c2, uri = "https://skea.test/other")
        _        = assertEquals(j4.hcursor.get[String]("error"), Right("invalid_grant"))
        // grant_type
        (r5, j5) <- oauthPost("/oauth/token", UrlForm("grant_type" -> "password"))
        _        = assertEquals(j5.hcursor.get[String]("error"), Right("unsupported_grant_type"))
        // 期限切れのコード
        expired  = "expired-" + UUID.randomUUID()
        _       <- env.ist.createOAuthCode(Crypto.sha256(expired), OAuthCode(clientId, redirect, u.id, challenge), Instant.now().minusSeconds(1))
        (_, j6) <- exchange(expired)
        _        = assertEquals(j6.hcursor.get[String]("error"), Right("invalid_grant"))
        // 成功
        c3      <- code(ut)
        (r7, j7) <- exchange(c3)
        _        = assertEquals(r7.status, Status.Ok)
        _        = assert(r7.headers.get(ci"Cache-Control").exists(_.head.value == "no-store"))
        _        = assertEquals(j7.hcursor.get[String]("token_type"), Right("Bearer"))
        _        = assertEquals(j7.hcursor.get[String]("scope"), Right("read"))
        t1       = j7.hcursor.get[String]("access_token").toOption.get
        _        = assert(t1.startsWith("skiwi_"))
        // 同じコードは2回使えない
        (_, j8) <- exchange(c3)
        _        = assertEquals(j8.hcursor.get[String]("error"), Right("invalid_grant"))
        ts      <- env.ops.listTokens(u.id)
        _        = assertEquals(ts.map(t => (t.name, t.scope, t.expiresAt)), List(("skea 連携", "read", None)))
        (m1, _) <- call(None, Method.GET, "/api/workspaces", bearer = Some(t1)) // ページの一括参照にしか使えない
        _        = assertEquals(m1.status, Status.Unauthorized)
        // 許可し直すと前のトークンは失効する
        t2      <- token(ut)
        (o1, _) <- call(None, Method.POST, "/api/pages/lookup", Some(Json.obj("ids" -> Json.arr())), bearer = Some(t1))
        _        = assertEquals(o1.status, Status.Unauthorized)
        (o2, _) <- call(None, Method.POST, "/api/pages/lookup", Some(Json.obj("ids" -> Json.arr())), bearer = Some(t2))
        _        = assertEquals(o2.status, Status.Ok)
        ts2     <- env.ops.listTokens(u.id)
        _        = assertEquals(ts2.size, 1)
      yield ()
    }
  }

  test("revoke: 200 even for unknown tokens, only tokens issued to the client") {
    run {
      for
        _       <- register
        (u, ut) <- user("oauth-revoke")
        t       <- token(ut)
        // ユーザーが自分で作ったトークンはクライアントからは失効できない
        own      = "skiwi_" + Crypto.randomToken(32)
        _       <- env.ops.createToken(u.id, "自分の", Crypto.sha256(own), "read", None)
        (r0, _) <- oauthPost("/oauth/revoke", UrlForm("token" -> own))
        _        = assertEquals(r0.status, Status.Ok)
        (s0, _) <- call(None, Method.POST, "/api/pages/lookup", Some(Json.obj("ids" -> Json.arr())), bearer = Some(own))
        _        = assertEquals(s0.status, Status.Ok)
        (r1, _) <- oauthPost("/oauth/revoke", UrlForm("token" -> "unknown"))
        _        = assertEquals(r1.status, Status.Ok)
        (r2, _) <- oauthPost("/oauth/revoke", UrlForm("token" -> t), auth = None)
        _        = assertEquals(r2.status, Status.Unauthorized)
        (r3, _) <- oauthPost("/oauth/revoke", UrlForm("token" -> t))
        _        = assertEquals(r3.status, Status.Ok)
        (s1, _) <- call(None, Method.POST, "/api/pages/lookup", Some(Json.obj("ids" -> Json.arr())), bearer = Some(t))
        _        = assertEquals(s1.status, Status.Unauthorized)
      yield ()
    }
  }

  test("lookup: only visible pages (trashed and others' pages excluded), max 100 ids, the token works only for lookup") {
    run {
      for
        _        <- register
        (a, at)  <- user("oauth-lookup-a")
        (b, bt)  <- user("oauth-lookup-b")
        ws       <- env.store.createWorkspace("lookup", a.id)
        p1       <- env.store.createPage(ws.id, None, Placement.Last, "設計メモ", Some("📘"), a.id).map(_.toOption.get)
        p2       <- env.store.createPage(ws.id, None, Placement.Last, "アイコンなし", None, a.id).map(_.toOption.get)
        p3       <- env.store.createPage(ws.id, None, Placement.Last, "削除済み", None, a.id).map(_.toOption.get)
        _        <- env.store.deletePage(p3.id, Some(a.id))
        ta       <- token(at)
        tb       <- token(bt)
        ids       = Json.obj("ids" -> List(p1.id.toString, p2.id.toString, p3.id.toString, UUID.randomUUID().toString, "not-a-uuid").asJson)
        (r1, j1) <- call(None, Method.POST, "/api/pages/lookup", Some(ids), bearer = Some(ta))
        _         = assertEquals(r1.status, Status.Ok)
        _         = assertEquals(j1, Json.obj("pages" -> Json.arr(
                      Json.obj("id" -> p1.id.asJson, "workspaceId" -> ws.id.asJson, "title" -> "設計メモ".asJson, "icon" -> "📘".asJson),
                      Json.obj("id" -> p2.id.asJson, "workspaceId" -> ws.id.asJson, "title" -> "アイコンなし".asJson, "icon" -> Json.Null))))
        // 権限のない人には見えない
        (r2, j2) <- call(None, Method.POST, "/api/pages/lookup", Some(ids), bearer = Some(tb))
        _         = assertEquals(r2.status, Status.Ok)
        _         = assertEquals(j2, Json.obj("pages" -> Json.arr()))
        // 101件は 400
        (r3, _)  <- call(None, Method.POST, "/api/pages/lookup", Some(Json.obj("ids" -> List.fill(101)(p1.id.toString).asJson)), bearer = Some(ta))
        _         = assertEquals(r3.status, Status.BadRequest)
        (r4, _)  <- call(None, Method.POST, "/api/pages/lookup", Some(Json.obj("x" -> 1.asJson)), bearer = Some(ta))
        _         = assertEquals(r4.status, Status.BadRequest)
        // 発行したトークンはページの一括参照専用（ほかの API は読むだけでも 401）
        (w1, _)  <- call(None, Method.POST, s"/api/workspaces/${ws.id}/pages", Some(Json.obj("title" -> "x".asJson)), bearer = Some(ta))
        _         = assertEquals(w1.status, Status.Unauthorized)
        (g1, _)  <- call(None, Method.GET, s"/api/pages/${p1.id}", None, bearer = Some(ta))
        _         = assertEquals(g1.status, Status.Unauthorized)
        // 無効なトークンは 401
        (r5, _)  <- call(None, Method.POST, "/api/pages/lookup", Some(ids), bearer = Some("skiwi_bogus"))
        _         = assertEquals(r5.status, Status.Unauthorized)
      yield ()
    }
  }
