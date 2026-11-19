package skiwi.api

import cats.effect.*
import cats.syntax.semigroupk.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** カスタムCSS（ワークスペース・ページ・子孫への継承）のテスト。TEST_DATABASE_URL が必要。 */
class CssSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store, app: HttpApp[IO])

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
        val attSt = new AttachmentStore(xa)
        val app =
          (new CssRoutes(store, sess, audit).routes <+>
            new ShareRoutes(
              store,
              new ShareStore(xa),
              new DocStore(xa),
              attSt,
              new AttachmentRoutes(store, attSt, None, sess),
              sess,
              audit
            ).routes).orNotFound
        Env(store, app)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("css", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(AuthSession, String)] = {
    val token = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield (AuthSession(u, Nil, false), token)
  }

  private def call(token: Option[String],
                   method: Method,
                   path: String,
                   body: Option[Json] = None): IO[(Status, Json)] = {
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    body.foreach(b => req = req.withEntity(b))
    env.app.run(req).flatMap(r =>
      r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null)))
    )
  }

  private def page(ws: UUID, title: String, parent: Option[UUID], by: UUID) =
    env.store.createPage(ws, parent, Placement.Last, title, None, by).map(_.toOption.get)

  private def cssOf(j: Json) =
    j.hcursor.get[List[Json]]("layers").toOption.get.map(l => l.hcursor.get[String]("css").toOption.get)

  test("workspace and page css are layered, and page css can be limited to the page itself") {
    run {
      for {
        (o, ot) <- user("css-owner")
        (v, vt) <- user("css-viewer")
        ws <- env.store.createWorkspace("CSS", o.user.id)
        _ <- env.store.setWorkspaceAcl(
          ws.id,
          None,
          List(
            AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit),
            AclEntry(Principal(PrincipalType.User, v.user.id), Role.View)
          )
        )
        a <- page(ws.id, "親", None, o.user.id)
        b <- page(ws.id, "子", Some(a.id), o.user.id)
        c <- page(ws.id, "孫", Some(b.id), o.user.id)
        (s1, _) <-
          call(Some(ot), Method.PUT, s"/api/workspaces/${ws.id}/css", Some(Json.obj("css" -> "h1{color:red}".asJson)))
        _ = assertEquals(s1, Status.NoContent)
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${a.id}/css",
          Some(Json.obj("css" -> "p{color:blue}".asJson, "inherit" -> true.asJson))
        )
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${b.id}/css",
          Some(Json.obj("css" -> "p{color:green}".asJson, "inherit" -> false.asJson))
        )
        (_, forB) <- call(Some(vt), Method.GET, s"/api/pages/${b.id}/styles")
        (_, forC) <- call(Some(vt), Method.GET, s"/api/pages/${c.id}/styles")
        // 閲覧者は設定できない
        (sv, _) <- call(
          Some(vt),
          Method.PUT,
          s"/api/pages/${c.id}/css",
          Some(Json.obj("css" -> "x{}".asJson, "inherit" -> true.asJson))
        )
        (sw, _) <- call(Some(vt), Method.PUT, s"/api/workspaces/${ws.id}/css", Some(Json.obj("css" -> "x{}".asJson)))
        (sl, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${c.id}/css",
          Some(Json.obj("css" -> ("a" * 20001).asJson, "inherit" -> true.asJson))
        )
        // 空にすると外れる
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${a.id}/css",
          Some(Json.obj("css" -> "   ".asJson, "inherit" -> true.asJson))
        )
        (_, forC2) <- call(Some(vt), Method.GET, s"/api/pages/${c.id}/styles")
      } yield {
        // 子: ワークスペース → 親（子にも適用）→ 自分
        assertEquals(cssOf(forB), List("h1{color:red}", "p{color:blue}", "p{color:green}"))
        assertEquals(forB.hcursor.get[Boolean]("inherit"), Right(false))
        assertEquals(forB.hcursor.get[String]("css"), Right("p{color:green}"))
        // 孫: 子のCSSは「このページだけ」なので入らない
        assertEquals(cssOf(forC), List("h1{color:red}", "p{color:blue}"))
        assertEquals((sv, sw, sl), (Status.Forbidden, Status.Forbidden, Status.BadRequest))
        assertEquals(cssOf(forC2), List("h1{color:red}"))
      }
    }
  }

  test("guests receive the page styles with the published content") {
    run {
      for {
        (o, ot) <- user("css-guest-owner")
        ws <- env.store.createWorkspace("CSS公開", o.user.id)
        p <- page(ws.id, "公開", None, o.user.id)
        _ <-
          call(Some(ot), Method.PUT, s"/api/workspaces/${ws.id}/css", Some(Json.obj("css" -> "h2{color:navy}".asJson)))
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${p.id}/css",
          Some(Json.obj("css" -> "p{margin:0}".asJson, "inherit" -> true.asJson))
        )
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj()))
        token = sh.hcursor.get[String]("token").toOption.get
        (_, g) <- call(None, Method.GET, s"/api/public/$token/pages/${p.id}")
      } yield assertEquals(g.hcursor.get[List[String]]("styles"), Right(List("h2{color:navy}", "p{margin:0}")))
    }
  }

  test(
    "guest html: layered top (outer first) and bottom (inner first), inheritance can be turned off, only for editors"
  ) {
    run {
      for {
        (o, ot) <- user("gh-owner")
        (v, vt) <- user("gh-viewer")
        ws <- env.store.createWorkspace("ゲスト表示", o.user.id)
        _ <- env.store.setWorkspaceAcl(
          ws.id,
          None,
          List(
            AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit),
            AclEntry(Principal(PrincipalType.User, v.user.id), Role.View)
          )
        )
        a <- page(ws.id, "親", None, o.user.id)
        b <- page(ws.id, "子", Some(a.id), o.user.id)
        c <- page(ws.id, "孫", Some(b.id), o.user.id)
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/workspaces/${ws.id}/guest-html",
          Some(Json.obj("top" -> "<p>WS上</p>".asJson, "bottom" -> "<p>WS下</p>".asJson))
        )
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${a.id}/guest-html",
          Some(Json.obj("top" -> "<p>親上</p>".asJson, "bottom" -> "<p>親下</p>".asJson, "inherit" -> true.asJson))
        )
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${b.id}/guest-html",
          Some(Json.obj("top" -> "<p>子上</p>".asJson, "bottom" -> Json.Null, "inherit" -> false.asJson))
        )
        (sv, _) <- call(Some(vt), Method.GET, s"/api/pages/${b.id}/guest-html")
        (sw, _) <-
          call(Some(vt), Method.PUT, s"/api/workspaces/${ws.id}/guest-html", Some(Json.obj("top" -> "x".asJson)))
        (_, own) <- call(Some(ot), Method.GET, s"/api/pages/${b.id}/guest-html")
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${a.id}/share", Some(Json.obj("hideHeader" -> true.asJson)))
        token = sh.hcursor.get[String]("token").toOption.get
        (_, tree) <- call(None, Method.GET, s"/api/public/$token")
        (_, gb) <- call(None, Method.GET, s"/api/public/$token/pages/${b.id}")
        (_, gc) <- call(None, Method.GET, s"/api/public/$token/pages/${c.id}")
        // ヘッダーの設定は他の設定を変えても残る
        (_, sh2) <-
          call(Some(ot), Method.PUT, s"/api/pages/${a.id}/share", Some(Json.obj("includeChildren" -> true.asJson)))
      } yield {
        assertEquals((sv, sw), (Status.Forbidden, Status.Forbidden))
        assertEquals(own.hcursor.get[String]("top"), Right("<p>子上</p>"))
        assertEquals(own.hcursor.get[List[Json]]("layers").map(_.size), Right(2)) // ワークスペースと親
        assertEquals(tree.hcursor.get[Boolean]("hideHeader"), Right(true))
        assertEquals(gb.hcursor.get[List[String]]("top"), Right(List("<p>WS上</p>", "<p>親上</p>", "<p>子上</p>")))
        assertEquals(gb.hcursor.get[List[String]]("bottom"), Right(List("<p>親下</p>", "<p>WS下</p>")))
        // 孫: 子の「このページだけ」は入らない
        assertEquals(gc.hcursor.get[List[String]]("top"), Right(List("<p>WS上</p>", "<p>親上</p>")))
        assertEquals(sh2.hcursor.get[Boolean]("hideHeader"), Right(true))
        assertEquals(tree.hcursor.get[String]("sidebar"), Right("auto"))
      }
    }
  }

  test("sidebar setting of a public link: default auto, can be set to show / hide, invalid values are rejected") {
    run {
      for {
        (o, ot) <- user("sb-owner")
        ws <- env.store.createWorkspace("サイドメニュー", o.user.id)
        p <- page(ws.id, "公開", None, o.user.id)
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj()))
        token = sh.hcursor.get[String]("token").toOption.get
        (sb, _) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("sidebar" -> "maybe".asJson)))
        (_, s2) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("sidebar" -> "show".asJson)))
        (_, t1) <- call(None, Method.GET, s"/api/public/$token")
        (_, s3) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("hideHeader" -> true.asJson)))
      } yield {
        assertEquals(sh.hcursor.get[String]("sidebar"), Right("auto"))
        assertEquals(sb, Status.BadRequest)
        assertEquals(s2.hcursor.get[String]("sidebar"), Right("show"))
        assertEquals(t1.hcursor.get[String]("sidebar"), Right("show"))
        assertEquals(s3.hcursor.get[String]("sidebar"), Right("show")) // 他の設定を変えても残る
      }
    }
  }
}
