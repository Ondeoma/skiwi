package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import org.http4s.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.*
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** ユーザーのアイコン: 設定・配信・削除、種類と大きさの検査、/api/me の avatarUrl。TEST_DATABASE_URL が必要。 */
class AvatarSpec extends DbSuite:

  final case class Env(store: Store, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield
        val store = new Store(xa)
        val ops   = new OpsStore(xa)
        val sess  = new SessionAuth(store, ops, ApiConfig(adminGroups = Set.empty, cookieSecure = false))
        val api   = new ApiRoutes(store, new DocStore(xa), ops, sess, new Audit(ops))
        Env(store, (new AvatarRoutes(ops, sess, maxBytes = 64).routes <+> api.routes).orNotFound)

  private val fixture = ResourceSuiteLocalFixture("avatar", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(User, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (u, token)

  private def call(token: Option[String], method: Method, path: String, ct: Option[String] = None, body: Array[Byte] = Array.empty): IO[Response[IO]] =
    var req = Request[IO](method, Uri.unsafeFromString(path)).withEntity(body)
    ct.foreach(t => req = req.withContentType(`Content-Type`.parse(t).toOption.get))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    env.app.run(req)

  private def jsonOf(r: Response[IO]): IO[Json] = r.bodyText.compile.string.map(io.circe.parser.parse(_).toOption.get)

  private val png = Array[Byte](0x89.toByte, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4)

  test("set, serve, replace and delete an avatar; /api/me carries a versioned url") {
    for
      (u, t)  <- user("アイコン 太郎")
      (_, t2) <- user("見る人")
      me0     <- call(Some(t), Method.GET, "/api/me").flatMap(jsonOf)
      _        = assertEquals(me0.hcursor.get[Option[String]]("avatarUrl").toOption.flatten, None)
      none    <- call(Some(t2), Method.GET, s"/api/users/${u.id}/avatar")
      _        = assertEquals(none.status, Status.NotFound)
      put     <- call(Some(t), Method.PUT, "/api/me/avatar", Some("image/png"), png)
      _        = assertEquals(put.status, Status.Ok)
      url     <- jsonOf(put).map(_.hcursor.get[String]("avatarUrl").toOption.get)
      _        = assert(url.startsWith(s"/api/users/${u.id}/avatar?v="), url)
      me1     <- call(Some(t), Method.GET, "/api/me").flatMap(jsonOf)
      _        = assertEquals(me1.hcursor.get[String]("avatarUrl").toOption, Some(url))
      // ほかのユーザーも見られる。版付きは長くキャッシュし、スクリプトは動かさない
      got     <- call(Some(t2), Method.GET, url)
      bytes   <- got.body.compile.to(Array)
      _        = assertEquals(got.status, Status.Ok)
      _        = assertEquals(bytes.toSeq, png.toSeq)
      _        = assertEquals(got.headers.get(ci"Content-Type").map(_.head.value), Some("image/png"))
      _        = assert(got.headers.get(ci"Cache-Control").exists(_.head.value.contains("immutable")))
      _        = assert(got.headers.get(ci"Content-Security-Policy").exists(_.head.value.contains("sandbox")))
      // ログインしていなければ見られない
      anon    <- call(None, Method.GET, url)
      _        = assertEquals(anon.status, Status.Unauthorized)
      del     <- call(Some(t), Method.DELETE, "/api/me/avatar")
      _        = assertEquals(del.status, Status.Ok)
      gone    <- call(Some(t2), Method.GET, s"/api/users/${u.id}/avatar")
      _        = assertEquals(gone.status, Status.NotFound)
    yield ()
  }

  test("rejects other types, mismatched content and oversized images") {
    for
      (_, t) <- user("検査")
      svg    <- call(Some(t), Method.PUT, "/api/me/avatar", Some("image/svg+xml"), "<svg/>".getBytes)
      _       = assertEquals(svg.status, Status.UnsupportedMediaType)
      fake   <- call(Some(t), Method.PUT, "/api/me/avatar", Some("image/png"), "<html>not png</html>".getBytes)
      _       = assertEquals(fake.status, Status.BadRequest)
      big    <- call(Some(t), Method.PUT, "/api/me/avatar", Some("image/png"), png ++ Array.fill[Byte](100)(0))
      _       = assertEquals(big.status, Status.PayloadTooLarge)
      jpeg   <- call(Some(t), Method.PUT, "/api/me/avatar", Some("image/jpeg"), Array[Byte](0xff.toByte, 0xd8.toByte, 0xff.toByte, 0))
      _       = assertEquals(jpeg.status, Status.Ok)
    yield ()
  }
