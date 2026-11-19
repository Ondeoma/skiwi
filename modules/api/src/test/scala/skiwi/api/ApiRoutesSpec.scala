package skiwi.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.implicits.*
import sbirds.auth.Crypto
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** TEST_DATABASE_URL が設定されている場合のみ実行。IdPを介さず、DBに直接セッションを作ってAPIを叩く。 */
class ApiRoutesSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("skiwi-admins"), cookieSecure = false)

  private val res: Resource[IO, (Store, HttpApp[IO])] = cfg match {
    case None =>
      Resource.eval(IO.raiseError[(Store, HttpApp[IO])](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for {
        _ <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      } yield {
        val store = new Store(xa)
        val ops = new OpsStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        (store, new ApiRoutes(store, new DocStore(xa), ops, sess, new Audit(ops)).routes.orNotFound)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("api", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil

  private def login(name: String, groups: List[String] = Nil): IO[String] = {
    val (store, _) = fixture()
    val token = Crypto.randomToken()
    for {
      u <- store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- store.syncGroups(u.id, groups)
      _ <- store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield token
  }

  private def call(token: Option[String],
                   method: Method,
                   path: String,
                   body: Option[Json] = None): IO[(Status, Json)] = {
    val (_, app) = fixture()
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    body.foreach(b => req = req.withEntity(b))
    app.run(req).flatMap(r =>
      r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null)))
    )
  }

  private def id(j: Json) = UUID.fromString(j.hcursor.get[String]("id").toOption.get)

  test("unauthenticated requests get 401") {
    run(call(None, Method.GET, "/api/me").map((st, _) => assertEquals(st, Status.Unauthorized)))
  }

  test("me returns Japanese name and groups") {
    run {
      for {
        t <- login("山田太郎", List("dev"))
        (st, j) <- call(Some(t), Method.GET, "/api/me")
      } yield {
        assertEquals(st, Status.Ok)
        assertEquals(j.hcursor.get[String]("name"), Right("山田太郎"))
        assertEquals(j.hcursor.get[List[String]]("groups"), Right(List("dev")))
        assertEquals(j.hcursor.get[Boolean]("isAdmin"), Right(false))
      }
    }
  }

  test("admin group makes isAdmin true") {
    run(login("boss", List("skiwi-admins")).flatMap(t => call(Some(t), Method.GET, "/api/me")).map((_, j) =>
      assertEquals(j.hcursor.get[Boolean]("isAdmin"), Right(true))
    ))
  }

  test("workspace favorites are per user and listed first") {
    run {
      def ids(j: Json) = j.asArray.get.toList.map(id)
      def favs(j: Json) = j.asArray.get.toList.filter(_.hcursor.get[Boolean]("favorite").contains(true)).map(id)
      for {
        owner <- login("fav-owner")
        other <- login("fav-other")
        (_, a) <- call(Some(owner), Method.POST, "/api/workspaces", Some(Json.obj("name" -> "あ".asJson)))
        (_, b) <- call(Some(owner), Method.POST, "/api/workspaces", Some(Json.obj("name" -> "ん".asJson)))
        (_, before) <- call(Some(owner), Method.GET, "/api/workspaces")
        _ = assert(ids(before).indexOf(id(a)) < ids(before).indexOf(id(b)), "名前順")
        (st, f) <-
          call(Some(owner), Method.PUT, s"/api/workspaces/${id(b)}/favorite", Some(Json.obj("favorite" -> true.asJson)))
        _ = assertEquals(st, Status.Ok)
        _ = assertEquals(f.hcursor.get[Boolean]("favorite"), Right(true))
        (_, after) <- call(Some(owner), Method.GET, "/api/workspaces")
        _ = assertEquals(ids(after).headOption, Some(id(b))) // お気に入りが先頭
        _ = assertEquals(favs(after), List(id(b)))
        // 他のユーザーには影響しない・見えないワークスペースは登録できない
        (sOther, _) <-
          call(Some(other), Method.PUT, s"/api/workspaces/${id(b)}/favorite", Some(Json.obj("favorite" -> true.asJson)))
        _ = assertEquals(sOther, Status.NotFound)
        (_, otherList) <- call(Some(other), Method.GET, "/api/workspaces")
        _ = assert(!ids(otherList).contains(id(b)))
        // 解除
        _ <- call(
          Some(owner),
          Method.PUT,
          s"/api/workspaces/${id(b)}/favorite",
          Some(Json.obj("favorite" -> false.asJson))
        )
        (_, last) <- call(Some(owner), Method.GET, "/api/workspaces")
      } yield assertEquals(favs(last), Nil)
    }
  }

  test("workspace/page lifecycle with permissions over HTTP") {
    run {
      for {
        owner <- login("owner")
        other <- login("other")
        (s1, ws) <- call(Some(owner), Method.POST, "/api/workspaces", Some(Json.obj("name" -> "社内Wiki".asJson)))
        _ = assertEquals(s1, Status.Created)
        wsId = id(ws)

        // 他人はワークスペース直下にページを作れない（存在を隠して404）
        (sOther, _) <-
          call(Some(other), Method.POST, s"/api/workspaces/$wsId/pages", Some(Json.obj("title" -> "x".asJson)))
        _ = assertEquals(sOther, Status.NotFound)

        (s2, root) <-
          call(Some(owner), Method.POST, s"/api/workspaces/$wsId/pages", Some(Json.obj("title" -> "トップ".asJson)))
        _ = assertEquals(s2, Status.Created)
        rootId = id(root)
        (_, child) <- call(
          Some(owner),
          Method.POST,
          s"/api/workspaces/$wsId/pages",
          Some(Json.obj("title" -> "子".asJson, "parentId" -> rootId.asJson))
        )
        childId = id(child)

        // 共有前: other は見えない
        (s3, _) <- call(Some(other), Method.GET, s"/api/pages/$rootId")
        _ = assertEquals(s3, Status.NotFound)

        // other に view を付与 → GET 可、PATCH は 403
        otherId <- call(Some(other), Method.GET, "/api/me").map((_, j) => id(j))
        acl = Json.obj(
          "inherit" -> true.asJson,
          "entries" -> List(
            Json.obj("principalType" -> "user".asJson, "principalId" -> otherId.asJson, "role" -> "view".asJson)
          ).asJson
        )
        (s4, _) <- call(Some(owner), Method.PUT, s"/api/pages/$rootId/acl", Some(acl))
        _ = assertEquals(s4, Status.NoContent)
        (s5, g) <- call(Some(other), Method.GET, s"/api/pages/$childId")
        _ = assertEquals(s5, Status.Ok)
        _ = assertEquals(g.hcursor.get[String]("role"), Right("view"))
        _ = assertEquals(g.hcursor.get[String]("title"), Right("子"))
        (s6, _) <- call(Some(other), Method.PATCH, s"/api/pages/$childId", Some(Json.obj("title" -> "改ざん".asJson)))
        _ = assertEquals(s6, Status.Forbidden)

        // owner は編集・移動できる。自分の子孫の下への移動は400
        (s7, up) <- call(
          Some(owner),
          Method.PATCH,
          s"/api/pages/$childId",
          Some(Json.obj("title" -> "子(改)".asJson, "icon" -> "📄".asJson))
        )
        _ = assertEquals(s7, Status.Ok)
        _ = assertEquals(up.hcursor.get[String]("icon"), Right("📄"))
        // 項目を省くとアイコンは変わらず、null で外れる
        (_, keep) <- call(Some(owner), Method.PATCH, s"/api/pages/$childId", Some(Json.obj("title" -> "子(改)".asJson)))
        _ = assertEquals(keep.hcursor.get[String]("icon"), Right("📄"))
        (_, cleared) <- call(Some(owner), Method.PATCH, s"/api/pages/$childId", Some(Json.obj("icon" -> Json.Null)))
        _ = assertEquals(cleared.hcursor.get[Option[String]]("icon"), Right(None))
        _ = assertEquals(cleared.hcursor.get[String]("title"), Right("子(改)"))
        (s8, _) <-
          call(Some(owner), Method.POST, s"/api/pages/$rootId/move", Some(Json.obj("parentId" -> childId.asJson)))
        _ = assertEquals(s8, Status.BadRequest)

        // 継承停止しても設定者本人は締め出されない
        (s9, _) <- call(
          Some(owner),
          Method.PUT,
          s"/api/pages/$childId/acl",
          Some(Json.obj("inherit" -> false.asJson, "entries" -> Json.arr()))
        )
        _ = assertEquals(s9, Status.NoContent)
        (s10, _) <- call(Some(owner), Method.GET, s"/api/pages/$childId")
        (s11, _) <- call(Some(other), Method.GET, s"/api/pages/$childId")
        _ = assertEquals(s10, Status.Ok)
        _ = assertEquals(s11, Status.NotFound)

        // ツリー一覧: other には root のみ
        (_, list) <- call(Some(other), Method.GET, s"/api/workspaces/$wsId/pages")
        _ = assertEquals(list.asArray.map(_.size), Some(1))

        // 削除は子孫ごと
        (s12, _) <- call(Some(owner), Method.DELETE, s"/api/pages/$rootId")
        _ = assertEquals(s12, Status.NoContent)
        (s13, _) <- call(Some(owner), Method.GET, s"/api/pages/$childId")
        _ = assertEquals(s13, Status.NotFound)
      } yield ()
    }
  }
}
