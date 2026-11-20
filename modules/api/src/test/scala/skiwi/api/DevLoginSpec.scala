package skiwi.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Location
import org.http4s.implicits.*
import skiwi.db.*

import java.util.UUID

/**
 * 開発用のログイン（DEV_LOGIN）: /auth/login が /login の画面へ回し、/auth/dev-login で dev:<名前> のユーザーとしてログインする。
 * 「管理者として」は ADMIN_GROUPS のグループに入れる。TEST_DATABASE_URL が必要。
 */
class DevLoginSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("dev-admins"), cookieSecure = false)

  final case class Env(store: Store, app: Boolean => HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match {
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for {
        _ <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      } yield {
        val store = new Store(xa)
        val ops = new OpsStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val api = new ApiRoutes(store, new DocStore(xa), ops, sess, audit).routes
        Env(store, dev => (new AuthRoutes(None, store, sess, audit, devLogin = dev).routes <+> api).orNotFound)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("dev-login", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def devLogin(body: Json, dev: Boolean = true): IO[Response[IO]] =
    env.app(dev).run(Request[IO](Method.POST, uri"/auth/dev-login").withEntity(body))

  /** ログインして /api/me を返す */
  private def loginMe(name: String, admin: Boolean): IO[Json] =
    for {
      r <- devLogin(Json.obj("name" -> name.asJson, "admin" -> admin.asJson, "returnTo" -> "/w/abc".asJson))
      _ = assertEquals(r.status, Status.Ok)
      j <- r.as[Json]
      _ = assertEquals(j.hcursor.get[String]("redirect"), Right("/w/abc"))
      ck = r.cookies.find(_.name == "skiwi_session").get
      _ = assert(ck.httpOnly)
      me <- env.app(true).run(Request[IO](
        Method.GET,
        uri"/api/me"
      ).addCookie("skiwi_session", ck.content)).flatMap(_.as[Json])
    } yield me

  test("/auth/login は開発用のログインの画面（/login）へ戻り先を付けて回す") {
    run {
      env.app(true).run(Request[IO](Method.GET, uri"/auth/login?returnTo=/w/abc/p/def")).map { r =>
        assertEquals(r.status, Status.Found)
        assertEquals(r.headers.get[Location].map(_.uri.renderString), Some("/login?returnTo=%2Fw%2Fabc%2Fp%2Fdef"))
      }
    }
  }

  test("管理者としてログインすると ADMIN_GROUPS のグループに入り、外すと管理者でなくなる（ほかのグループは残す）") {
    run {
      val name = s"開発 太郎 ${UUID.randomUUID().toString.take(8)}"
      for {
        me <- loginMe(name, admin = true)
        _ = assertEquals(me.hcursor.get[String]("name"), Right(name))
        _ = assertEquals(me.hcursor.get[Boolean]("isAdmin"), Right(true))
        id = me.hcursor.get[UUID]("id").toOption.get
        // IdP やSCIM から入ったほかのグループ
        cur <- env.store.userWithGroups(id).map(_.get._2.map(_.externalId))
        _ <- env.store.syncGroups(id, cur :+ "営業部")
        me2 <- loginMe(name, admin = false)
        _ = assertEquals(me2.hcursor.get[UUID]("id"), Right(id))
        _ = assertEquals(me2.hcursor.get[Boolean]("isAdmin"), Right(false))
        _ = assertEquals(me2.hcursor.get[List[String]]("groups"), Right(List("営業部")))
        u <- env.store.userWithGroups(id)
        _ = assertEquals(u.map(_._1.sub), Some(s"dev:$name"))
      } yield ()
    }
  }

  test("名前がない・戻り先が外のサイト・DEV_LOGIN でない") {
    run {
      for {
        r1 <- devLogin(Json.obj("name" -> "  ".asJson))
        _ = assertEquals(r1.status, Status.BadRequest)
        r2 <- devLogin(Json.obj(
          "name" -> s"外 ${UUID.randomUUID().toString.take(8)}".asJson,
          "returnTo" -> "//evil.example.com/".asJson
        ))
        _ = assertEquals(r2.status, Status.Ok)
        j <- r2.as[Json]
        _ = assertEquals(j.hcursor.get[String]("redirect"), Right("/"))
        r3 <- devLogin(Json.obj("name" -> "だれか".asJson), dev = false)
        _ = assertEquals(r3.status, Status.NotFound)
        r4 <- env.app(false).run(Request[IO](Method.GET, uri"/auth/login"))
        _ = assertEquals(r4.status, Status.ServiceUnavailable)
      } yield ()
    }
  }
}
