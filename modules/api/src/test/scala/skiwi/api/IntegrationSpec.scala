package skiwi.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, Location}
import sbirds.auth.Crypto
import skiwi.auth.SecretBox
import skiwi.core.*
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** skea・skawau・smohua 連携: 管理者の設定・許可画面へのリダイレクト・コードの交換・タスクの取得・連携の解除。skea・skawau はメモリ内の偽物。TEST_DATABASE_URL が必要。 */
class IntegrationSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("skea-admins"), cookieSecure = false)
  private val skeaUrl = "https://skea.test"
  private val box = SecretBox.fromSecret("integration-spec-secret-0123456789")

  /** 偽の skea が受け取ったもの */
  final case class Seen(token: Option[(Option[Authorization], UrlForm)] = None,
                        revoked: List[(Option[Authorization], UrlForm)] = Nil,
                        lookups: List[(Option[Authorization], List[String])] = Nil)
  final case class Env(store: Store,
                       app: HttpApp[IO],
                       seen: Ref[IO, Seen],
                       revokedTokens: Ref[IO, Set[String]],
                       ist: IntegrationStore)

  private def fakeSkea(seen: Ref[IO, Seen], revoked: Ref[IO, Set[String]]): HttpApp[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "oauth" / "token" =>
      req.as[UrlForm].flatMap { f =>
        seen.update(_.copy(token = Some((req.headers.get[Authorization], f)))) *>
          (if f.getFirst("code").contains("good") then
             Ok(Json.obj(
               "access_token" -> s"skea_${f.getFirst("code_verifier").get.take(8)}".asJson,
               "token_type" -> "Bearer".asJson,
               "scope" -> "read".asJson
             ))
           else BadRequest(Json.obj("error" -> "invalid_grant".asJson)))
      }
    case req @ POST -> Root / "oauth" / "revoke" =>
      req.as[UrlForm].flatMap(f =>
        seen.update(s => s.copy(revoked = (req.headers.get[Authorization], f) :: s.revoked)) *> Ok()
      )
    case req @ POST -> Root / "api" / "tasks" / "lookup" =>
      req.as[Json].flatMap { j =>
        val keys = j.hcursor.get[List[String]]("keys").getOrElse(Nil)
        val auth = req.headers.get[Authorization]
        val tok = auth.collect { case Authorization(Credentials.Token(_, t)) => t }.getOrElse("")
        seen.update(s => s.copy(lookups = (auth, keys) :: s.lookups)) *> revoked.get.flatMap { rv =>
          if rv.contains(tok) then IO.pure(Response[IO](Status.Unauthorized))
          else
            Ok(Json.obj("tasks" -> keys.filterNot(_.endsWith("-999")).map(k =>
              Json.obj(
                "key" -> k.asJson,
                "title" -> s"タスク $k".asJson,
                "status" -> Json.obj(
                  "name" -> "進行中".asJson,
                  "category" -> "in_progress".asJson,
                  "color" -> (if k == "SKEA-2" then "red;}" else "#2563eb").asJson
                )
              )
            ).asJson))
        }
      }
    case req @ POST -> Root / "api" / "files" / "lookup" =>
      req.as[Json].flatMap { j =>
        val ids = j.hcursor.get[List[String]]("ids").getOrElse(Nil)
        seen.update(s => s.copy(lookups = (req.headers.get[Authorization], ids) :: s.lookups)) *>
          Ok(Json.obj("files" -> ids.filterNot(_.startsWith("0")).map(id =>
            Json.obj(
              "id" -> id.toUpperCase.asJson,
              "name" -> s"file-${id.take(4)}.txt".asJson,
              "isDir" -> id.startsWith("d").asJson,
              "mimeType" -> Json.Null
            )
          ).asJson))
      }
    // smohua: フォームの題名・回答の数（0 で始まる ID は見られない）と、集計
    case req @ POST -> Root / "api" / "forms" / "lookup" =>
      req.as[Json].flatMap { j =>
        val ids = j.hcursor.get[List[String]]("ids").getOrElse(Nil)
        seen.update(s => s.copy(lookups = (req.headers.get[Authorization], ids) :: s.lookups)) *>
          Ok(Json.obj("forms" -> ids.filterNot(_.startsWith("0")).map(id =>
            Json.obj(
              "id" -> id.asJson,
              "title" -> "懇親会の出欠".asJson,
              "status" -> "open".asJson,
              "responses" -> 12.asJson,
              "lastResponseAt" -> "2026-11-17T01:02:03Z".asJson,
              "url" -> "https://evil.example/x".asJson
            )
          ).asJson))
      }
    case req @ GET -> Root / "api" / "forms" / id / "summary" =>
      val tok =
        req.headers.get[Authorization].collect { case Authorization(Credentials.Token(_, t)) => t }.getOrElse("")
      revoked.get.flatMap { rv =>
        if rv.contains(tok) then IO.pure(Response[IO](Status.Unauthorized))
        else if id.startsWith("0") then NotFound(Json.obj("error" -> "フォームが見つかりません".asJson))
        else
          Ok(Json.obj(
            "formId" -> id.asJson,
            "title" -> "懇親会の出欠".asJson,
            "total" -> 12.asJson,
            "questions" -> Json.arr()
          ))
      }
  }.orNotFound

  private val res: Resource[IO, Env] = cfg match {
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for {
        _ <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
        seen <- Resource.eval(Ref.of[IO, Seen](Seen()))
        revoked <- Resource.eval(Ref.of[IO, Set[String]](Set.empty))
        cache <- Resource.eval(Ref.of[IO, Map[(String, UUID, String), (Long, Option[Json])]](Map.empty))
      } yield {
        val store = new Store(xa)
        val ops = new OpsStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        val ist = new IntegrationStore(xa)
        val http = Client.fromHttpApp(fakeSkea(seen, revoked))
        val app = new IntegrationRoutes(
          LinkedService.all,
          ist,
          sess,
          new Audit(ops),
          http,
          "https://skiwi.example.com",
          box,
          cache
        ).routes.orNotFound
        Env(store, app, seen, revoked, ist)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("skea", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String, groups: List[String] = Nil): IO[(User, String)] = {
    val token = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- if groups.nonEmpty then env.store.syncGroups(u.id, groups) else IO.unit
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield (u, token)
  }

  private def call(token: String,
                   method: Method,
                   path: String,
                   body: Option[Json] = None,
                   app: HttpApp[IO] = env.app): IO[(Response[IO], Json)] = {
    val req = body.fold(Request[IO](method, Uri.unsafeFromString(path)))(b =>
      Request[IO](method, Uri.unsafeFromString(path)).withEntity(b)
    )
    app.run(req.addCookie("skiwi_session", token)).flatMap(r =>
      r.bodyText.compile.string.map(s => (r, io.circe.parser.parse(s).getOrElse(Json.Null)))
    )
  }

  private val settings = Json.obj(
    "url" -> s"$skeaUrl/".asJson,
    "clientId" -> "skiwi".asJson,
    "clientSecret" -> "s3cret".asJson,
    "projectKeys" -> List("SKEA", " demo ").asJson
  )

  /** 連携する（connect → callback）。戻り値は callback の Location */
  private def connect(token: String, code: String = "good"): IO[(Map[String, String], String)] =
    for {
      (r, _) <- call(token, Method.GET, "/api/integrations/skea/connect?returnTo=/w/1/p/2%3Fx%3D1%23h")
      q = r.headers.get[Location].get.uri.query.params
      (cb, _) <- call(token, Method.GET, s"/api/integrations/skea/callback?code=$code&state=${q("state")}")
    } yield (q, cb.headers.get[Location].get.uri.renderString)

  test("admin settings: save/read hides the secret, non-admin is forbidden") {
    run {
      for {
        (_, at) <- user("skea-admin", List("skea-admins"))
        (_, ut) <- user("skea-user")
        (r0, _) <- call(ut, Method.GET, "/api/admin/integrations/skea")
        _ = assertEquals(r0.status, Status.Forbidden)
        (r1, _) <- call(ut, Method.PUT, "/api/admin/integrations/skea", Some(settings))
        _ = assertEquals(r1.status, Status.Forbidden)
        (bad, bj) <- call(
          at,
          Method.PUT,
          "/api/admin/integrations/skea",
          Some(settings.deepMerge(Json.obj("projectKeys" -> List("UTF-8").asJson)))
        )
        _ = assertEquals(bad.status, Status.BadRequest)
        _ = assert(bj.hcursor.get[String]("error").exists(_.contains("UTF-8")))
        (r2, j2) <- call(at, Method.PUT, "/api/admin/integrations/skea", Some(settings))
        _ = assertEquals(r2.status, Status.Ok)
        (_, j) <- call(at, Method.GET, "/api/admin/integrations/skea")
        _ = assertEquals(j, j2)
        _ = assertEquals(j.hcursor.get[String]("url"), Right(skeaUrl))
        _ = assertEquals(j.hcursor.get[Boolean]("clientSecretSet"), Right(true))
        _ = assertEquals(j.hcursor.get[List[String]]("projectKeys"), Right(List("SKEA", "DEMO")))
        _ = assertEquals(
          j.hcursor.get[String]("redirectUri"),
          Right("https://skiwi.example.com/api/integrations/skea/callback")
        )
        _ = assert(!j.noSpaces.contains("s3cret"))
        // 秘密を省略して保存しても消えない
        (_, j3) <-
          call(at, Method.PUT, "/api/admin/integrations/skea", Some(settings.mapObject(_.remove("clientSecret"))))
        _ = assertEquals(j3.hcursor.get[Boolean]("clientSecretSet"), Right(true))
        (st, _) <- call(ut, Method.GET, "/api/integrations/skea")
        _ = assertEquals(st.status, Status.Ok)
      } yield ()
    }
  }

  test("connect redirects with PKCE S256; callback exchanges the code with Basic auth and code_verifier") {
    run {
      for {
        (_, at) <- user("skea-admin2", List("skea-admins"))
        _ <- call(at, Method.PUT, "/api/admin/integrations/skea", Some(settings))
        (u, ut) <- user("skea-user2")
        (_, s0) <- call(ut, Method.GET, "/api/integrations/skea")
        _ = assertEquals(s0.hcursor.get[Boolean]("enabled"), Right(true))
        _ = assertEquals(s0.hcursor.get[Boolean]("connected"), Right(false))
        // 外部への戻り先は拒否
        (ext, _) <- call(ut, Method.GET, "/api/integrations/skea/connect?returnTo=//evil.example.com/")
        _ = assertEquals(ext.status, Status.BadRequest)
        (ext2, _) <- call(ut, Method.GET, "/api/integrations/skea/connect?returnTo=https://evil.example.com/")
        _ = assertEquals(ext2.status, Status.BadRequest)
        (r, _) <- call(ut, Method.GET, "/api/integrations/skea/connect?returnTo=/w/1/p/2%3Fx%3D1%23h")
        _ = assertEquals(r.status, Status.Found)
        loc = r.headers.get[Location].get.uri
        q = loc.query.params
        _ = assertEquals(loc.renderString.takeWhile(_ != '?'), s"$skeaUrl/oauth/authorize")
        _ = assertEquals(q.get("response_type"), Some("code"))
        _ = assertEquals(q.get("client_id"), Some("skiwi"))
        _ = assertEquals(q.get("scope"), Some("read"))
        _ = assertEquals(q.get("redirect_uri"), Some("https://skiwi.example.com/api/integrations/skea/callback"))
        _ = assertEquals(q.get("code_challenge_method"), Some("S256"))
        // 他人は state を使えない
        (_, ot) <- user("skea-other")
        (other, _) <- call(ot, Method.GET, s"/api/integrations/skea/callback?code=good&state=${q("state")}")
        _ = assertEquals(other.headers.get[Location].map(_.uri.renderString), Some("/?skea=error"))
        (cb, _) <- call(ut, Method.GET, s"/api/integrations/skea/callback?code=good&state=${q("state")}")
        _ = assertEquals(cb.status, Status.Found)
        _ = assertEquals(cb.headers.get[Location].map(_.uri.renderString), Some("/w/1/p/2?x=1&skea=connected#h"))
        seen <- env.seen.get
        (auth, form) = seen.token.get
        _ = assertEquals(auth, Some(Authorization(BasicCredentials("skiwi", "s3cret"))))
        _ = assertEquals(form.getFirst("grant_type"), Some("authorization_code"))
        _ = assertEquals(form.getFirst("code"), Some("good"))
        _ =
          assertEquals(form.getFirst("redirect_uri"), Some("https://skiwi.example.com/api/integrations/skea/callback"))
        _ = assertEquals(form.getFirst("code_verifier").map(Crypto.pkceChallenge), Some(q("code_challenge")))
        // state は1回だけ
        (replay, _) <- call(ut, Method.GET, s"/api/integrations/skea/callback?code=good&state=${q("state")}")
        _ = assertEquals(replay.headers.get[Location].map(_.uri.renderString), Some("/?skea=error"))
        (_, s1) <- call(ut, Method.GET, "/api/integrations/skea")
        _ = assertEquals(s1.hcursor.get[Boolean]("connected"), Right(true))
        // 保存したトークンは暗号化されている
        enc <- env.ist.token("skea", u.id).map(_.get)
        _ = assert(!enc.contains("skea_"))
        _ = assert(box.decrypt(enc, s"skea.token:${u.id}").exists(_.startsWith("skea_")))
        // 拒否・交換の失敗は ?skea=denied / error で戻る
        (r2, _) <- call(ut, Method.GET, "/api/integrations/skea/connect")
        st2 = r2.headers.get[Location].get.uri.query.params("state")
        (den, _) <- call(ut, Method.GET, s"/api/integrations/skea/callback?error=access_denied&state=$st2")
        _ = assertEquals(den.headers.get[Location].map(_.uri.renderString), Some("/?skea=denied"))
        (_, failLoc) <- connect(ut, code = "bad")
        _ = assertEquals(failLoc, "/w/1/p/2?x=1&skea=error#h")
      } yield ()
    }
  }

  test("skea lookup: only configured project keys, cached; 401 from skea disconnects; disconnect calls revoke") {
    run {
      for {
        (_, at) <- user("skea-admin3", List("skea-admins"))
        _ <- call(at, Method.PUT, "/api/admin/integrations/skea", Some(settings))
        (_, ut) <- user("skea-user3")
        // 未連携
        (_, nc) <-
          call(ut, Method.POST, "/api/integrations/skea/lookup", Some(Json.obj("keys" -> List("SKEA-1").asJson)))
        _ = assertEquals(nc, Json.obj("connected" -> false.asJson, "items" -> Json.arr()))
        (q, _) <- connect(ut)
        _ <- env.seen.set(Seen())
        keys = List("SKEA-1", "DEMO-12", "UTF-8", "skea-3", "SKEA-999", "SKEA-1", "OTHER-1", "SKEA-2")
        (r, j) <- call(ut, Method.POST, "/api/integrations/skea/lookup", Some(Json.obj("keys" -> keys.asJson)))
        _ = assertEquals(r.status, Status.Ok)
        _ = assertEquals(j.hcursor.get[Boolean]("connected"), Right(true))
        ts = j.hcursor.downField("items").values.get.toList
        _ = assertEquals(ts.map(_.hcursor.get[String]("key").toOption.get), List("SKEA-1", "DEMO-12", "SKEA-2"))
        _ = assertEquals(ts.head.hcursor.get[String]("url"), Right(s"$skeaUrl/tasks/SKEA-1"))
        _ = assertEquals(ts.head.hcursor.downField("status").get[String]("color"), Right("#2563eb"))
        // 色は CSS に入れるので、形の違うものは既定の色にする
        _ = assertEquals(ts(2).hcursor.downField("status").get[String]("color"), Right("#6b7280"))
        seen <- env.seen.get
        _ = assertEquals(seen.lookups.size, 1)
        (auth, sent) = seen.lookups.head
        _ = assertEquals(sent, List("SKEA-1", "DEMO-12", "SKEA-999", "SKEA-2"))
        _ = assert(auth.exists {
          case Authorization(Credentials.Token(AuthScheme.Bearer, t)) => t.startsWith("skea_"); case _ => false
        })
        tok = auth.collect { case Authorization(Credentials.Token(_, t)) => t }.get
        // 2回目はキャッシュ（見つからなかった SKEA-999 も含めて skea に問い合わせない）
        (_, j2) <- call(
          ut,
          Method.POST,
          "/api/integrations/skea/lookup",
          Some(Json.obj("keys" -> List("SKEA-1", "SKEA-999").asJson))
        )
        _ = assertEquals(j2.hcursor.downField("items").values.map(_.size), Some(1))
        _ <- env.seen.get.map(s => assertEquals(s.lookups.size, 1))
        (big, _) <- call(
          ut,
          Method.POST,
          "/api/integrations/skea/lookup",
          Some(Json.obj("keys" -> (1 to 101).map(i => s"SKEA-$i").asJson))
        )
        _ = assertEquals(big.status, Status.BadRequest)
        // skea でトークンが失効 → 401 → 連携が外れる
        _ <- env.revokedTokens.update(_ + tok)
        (_, j3) <-
          call(ut, Method.POST, "/api/integrations/skea/lookup", Some(Json.obj("keys" -> List("SKEA-5").asJson)))
        _ = assertEquals(j3, Json.obj("connected" -> false.asJson, "items" -> Json.arr()))
        (_, s1) <- call(ut, Method.GET, "/api/integrations/skea")
        _ = assertEquals(s1.hcursor.get[Boolean]("connected"), Right(false))
        // 連携し直して、解除すると skea の revoke が呼ばれる
        _ <- connect(ut)
        (d, _) <- call(ut, Method.DELETE, "/api/integrations/skea/connection")
        _ = assertEquals(d.status, Status.NoContent)
        seen2 <- env.seen.get
        (rauth, rform) = seen2.revoked.head
        _ = assertEquals(rauth, Some(Authorization(BasicCredentials("skiwi", "s3cret"))))
        _ = assert(rform.getFirst("token").exists(_.startsWith("skea_")))
        (_, s2) <- call(ut, Method.GET, "/api/integrations/skea")
        _ = assertEquals(s2.hcursor.get[Boolean]("connected"), Right(false))
        _ = assert(q.contains("state"))
      } yield ()
    }
  }

  test("skawau: no project keys; lookup only lowercase UUIDs; separate from skea") {
    val f1 = "a1b2c3d4-0000-4000-8000-000000000001"
    val d1 = "d1b2c3d4-0000-4000-8000-000000000002"
    val gone = "01b2c3d4-0000-4000-8000-000000000003"
    run {
      for {
        (_, at) <- user("skawau-admin", List("skea-admins"))
        (r0, j0) <- call(
          at,
          Method.PUT,
          "/api/admin/integrations/skawau",
          Some(Json.obj(
            "url" -> "https://skawau.test".asJson,
            "clientId" -> "skiwi".asJson,
            "clientSecret" -> "x".asJson
          ))
        )
        _ = assertEquals(r0.status, Status.Ok)
        _ = assertEquals(
          j0.hcursor.get[String]("redirectUri"),
          Right("https://skiwi.example.com/api/integrations/skawau/callback")
        )
        _ = assertEquals(j0.hcursor.get[List[String]]("projectKeys"), Right(Nil))
        (u, ut) <- user("skawau-user")
        (_, s0) <- call(ut, Method.GET, "/api/integrations/skawau")
        _ = assertEquals(s0.hcursor.get[Boolean]("enabled"), Right(true))
        (r, _) <- call(ut, Method.GET, "/api/integrations/skawau/connect?returnTo=/")
        _ = assertEquals(
          r.headers.get[Location].get.uri.renderString.takeWhile(_ != '?'),
          "https://skawau.test/oauth/authorize"
        )
        st = r.headers.get[Location].get.uri.query.params("state")
        // skea の callback では使えない（state はサービスごと）
        (wrong, _) <- call(ut, Method.GET, s"/api/integrations/skea/callback?code=good&state=$st")
        _ = assertEquals(wrong.headers.get[Location].map(_.uri.renderString), Some("/?skea=error"))
        (r2, _) <- call(ut, Method.GET, "/api/integrations/skawau/connect?returnTo=/")
        (cb, _) <- call(
          ut,
          Method.GET,
          s"/api/integrations/skawau/callback?code=good&state=${r2.headers.get[Location].get.uri.query.params("state")}"
        )
        _ = assertEquals(cb.headers.get[Location].map(_.uri.renderString), Some("/?skawau=connected"))
        _ <- env.ist.token("skea", u.id).map(t => assertEquals(t, None))
        _ <- env.seen.set(Seen())
        (_, j) <- call(
          ut,
          Method.POST,
          "/api/integrations/skawau/lookup",
          Some(Json.obj("keys" -> List(f1, d1, gone, f1.toUpperCase, "SKEA-1", "x").asJson))
        )
        _ = assertEquals(j.hcursor.get[Boolean]("connected"), Right(true))
        items = j.hcursor.downField("items").values.get.toList
        _ = assertEquals(items.map(_.hcursor.get[String]("key").toOption.get), List(f1, d1))
        _ = assertEquals(items.map(_.hcursor.get[Boolean]("isDir").toOption.get), List(false, true))
        _ = assertEquals(items.head.hcursor.get[String]("name"), Right("file-a1b2.txt"))
        seen <- env.seen.get
        _ = assertEquals(seen.lookups.map(_._2), List(List(f1, d1, gone)))
      } yield ()
    }
  }

  test("smohua: lookup forms and relay the summary of a form with the user's token; revoked token disconnects") {
    val f1 = "a1b2c3d4-0000-4000-8000-00000000000a"
    val gone = "01b2c3d4-0000-4000-8000-00000000000b"
    run {
      for {
        (_, at) <- user("smohua-admin", List("skea-admins"))
        (r0, j0) <- call(
          at,
          Method.PUT,
          "/api/admin/integrations/smohua",
          Some(Json.obj(
            "url" -> "https://smohua.test".asJson,
            "clientId" -> "skiwi".asJson,
            "clientSecret" -> "x".asJson
          ))
        )
        _ = assertEquals(r0.status, Status.Ok)
        _ = assertEquals(
          j0.hcursor.get[String]("redirectUri"),
          Right("https://skiwi.example.com/api/integrations/smohua/callback")
        )
        (u, ut) <- user("smohua-user")
        // 連携する前は connected=false（相手には問い合わせない）
        (_, d0) <- call(ut, Method.GET, s"/api/integrations/smohua/items/$f1/summary")
        _ = assertEquals(
          (d0.hcursor.get[Boolean]("connected"), d0.hcursor.downField("data").focus),
          (Right(false), Some(Json.Null))
        )
        (r, _) <- call(ut, Method.GET, "/api/integrations/smohua/connect?returnTo=/")
        _ = assertEquals(
          r.headers.get[Location].get.uri.renderString.takeWhile(_ != '?'),
          "https://smohua.test/oauth/authorize"
        )
        (cb, _) <- call(
          ut,
          Method.GET,
          s"/api/integrations/smohua/callback?code=good&state=${r.headers.get[Location].get.uri.query.params("state")}"
        )
        _ = assertEquals(cb.headers.get[Location].map(_.uri.renderString), Some("/?smohua=connected"))
        (_, j) <-
          call(ut, Method.POST, "/api/integrations/smohua/lookup", Some(Json.obj("keys" -> List(f1, gone, "x").asJson)))
        items = j.hcursor.downField("items").values.get.toList
        _ = assertEquals(items.map(_.hcursor.get[String]("key").toOption.get), List(f1))
        _ = assertEquals(
          (items.head.hcursor.get[String]("title"), items.head.hcursor.get[Int]("responses")),
          (Right("懇親会の出欠"), Right(12))
        )
        // リンクは設定した URL から作る（相手が返した URL は使わない）
        _ = assertEquals(items.head.hcursor.get[String]("url"), Right(s"https://smohua.test/forms/$f1/responses"))
        (rs, d) <- call(ut, Method.GET, s"/api/integrations/smohua/items/$f1/summary")
        _ = assertEquals(
          (rs.status, d.hcursor.get[Boolean]("connected"), d.hcursor.downField("data").get[Int]("total")),
          (Status.Ok, Right(true), Right(12))
        )
        (nf, _) <- call(ut, Method.GET, s"/api/integrations/smohua/items/$gone/summary")
        _ = assertEquals(nf.status, Status.NotFound)
        (bad, _) <- call(ut, Method.GET, "/api/integrations/smohua/items/not-a-uuid/summary")
        _ = assertEquals(bad.status, Status.BadRequest)
        // 知らない種類・詳しい中身のないサービスにはルートがない
        (unknown, _) <- call(ut, Method.GET, s"/api/integrations/smohua/items/$f1/secrets")
        _ = assertEquals(unknown.status, Status.NotFound)
        (skea, _) <- call(ut, Method.GET, s"/api/integrations/skea/items/$f1/summary")
        _ = assertEquals(skea.status, Status.NotFound)
        // 相手でトークンが失効していたら、連携を外す
        tok <- env.ist.token("smohua", u.id).map(_.flatMap(box.decrypt(_, s"smohua.token:${u.id}")).get)
        _ <- env.revokedTokens.update(_ + tok)
        (_, d2) <- call(ut, Method.GET, s"/api/integrations/smohua/items/$f1/summary")
        _ = assertEquals(d2.hcursor.get[Boolean]("connected"), Right(false))
        _ <- env.ist.token("smohua", u.id).map(t => assertEquals(t, None))
      } yield ()
    }
  }

  test("SecretBox: round trip, bound to the context") {
    val enc = box.encrypt("skea_token", "skea.token:a")
    assertEquals(box.decrypt(enc, "skea.token:a"), Some("skea_token"))
    assertEquals(box.decrypt(enc, "skea.token:b"), None)
    // 別の APP_SECRET の鍵では読めない
    assertEquals(SecretBox.fromSecret("another-secret-0123456789").decrypt(enc, "skea.token:a"), None)
    assertEquals(safeReturnTo(Some("/\\evil")), None)
    assertEquals(safeReturnTo(None), Some("/"))
  }
}
