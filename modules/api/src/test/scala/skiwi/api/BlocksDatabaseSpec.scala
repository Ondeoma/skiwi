package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import com.sun.net.httpserver.HttpServer
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID

/** 全幅表示・データベースの連番／サブ項目／計算の列・フォーム・リンクの展開。TEST_DATABASE_URL が必要。 */
class BlocksDatabaseSpec extends DbSuite:

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store, app: HttpApp[IO], privateApp: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _     <- Resource.eval(Database.migrate(c))
        xa    <- Database.transactor(c)
        rate  <- Resource.eval(Ref.of[IO, Map[UUID, List[Long]]](Map.empty))
        cache <- Resource.eval(Ref.of[IO, Map[String, (Long, UnfurlDto)]](Map.empty))
        cache2 <- Resource.eval(Ref.of[IO, Map[String, (Long, UnfurlDto)]](Map.empty))
      yield
        val store = new Store(xa)
        val ops   = new OpsStore(xa)
        val docs  = new DocStore(xa)
        val sess  = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val dbSt  = new DatabaseStore(xa)
        val common = new FormRoutes(store, dbSt, sess, audit, "https://skiwi.example.com", rate).routes <+>
          new DatabaseRoutes(store, dbSt, ops, sess, audit).routes <+>
          new ApiRoutes(store, docs, ops, sess, audit, IO.unit, None, None, Some(dbSt)).routes
        Env(store,
          (new UnfurlRoutes(sess, allowPrivate = false, cache).routes <+> common).orNotFound,
          (new UnfurlRoutes(sess, allowPrivate = true, cache2).routes <+> common).orNotFound)

  private val fixture = ResourceSuiteLocalFixture("blocksdatabase", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(AuthSession, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (AuthSession(u, Nil, false), token)

  private def call(token: Option[String], method: Method, path: String, body: Option[Json] = None, app: HttpApp[IO] = null): IO[(Status, Json)] =
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    body.foreach(b => req = req.withEntity(b))
    Option(app).getOrElse(env.app).run(req).flatMap(r => r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null))))

  private def page(ws: UUID, title: String, parent: Option[UUID], by: UUID) =
    env.store.createPage(ws, parent, Placement.Last, title, None, by).map(_.toOption.get)

  test("full width is a page setting shown in page lists") {
    run {
      for
        (o, ot) <- user("fw-owner")
        ws      <- env.store.createWorkspace("全幅", o.user.id)
        p       <- page(ws.id, "広いページ", None, o.user.id)
        (s1, j) <- call(Some(ot), Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("fullWidth" -> true.asJson)))
        _        = assertEquals((s1, j.hcursor.get[Boolean]("fullWidth").toOption), (Status.Ok, Some(true)))
        (_, ls) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/pages")
        _        = assertEquals(ls.hcursor.downArray.get[Boolean]("fullWidth").toOption, Some(true))
      yield ()
    }
  }

  test("database: sequential ids, sub-items, computed columns and new view types") {
    run {
      for
        (o, ot) <- user("seq-owner")
        ws      <- env.store.createWorkspace("連番", o.user.id)
        db      <- page(ws.id, "タスク", None, o.user.id)
        props    = Json.arr(
                     Json.obj("id" -> "pt".asJson, "name" -> "見積".asJson, "type" -> "number".asJson),
                     Json.obj("id" -> "f".asJson, "name" -> "税込".asJson, "type" -> "formula".asJson, "expr" -> "prop(\"見積\") * 1.1".asJson),
                     Json.obj("id" -> "id".asJson, "name" -> "ID".asJson, "type" -> "unique_id".asJson, "prefix" -> "T".asJson),
                     Json.obj("id" -> "cb".asJson, "name" -> "作成者".asJson, "type" -> "created_by".asJson))
        views    = Json.arr(Json.obj("id" -> "v".asJson, "name" -> "表".asJson, "type" -> "table".asJson),
                     Json.obj("id" -> "g".asJson, "name" -> "ギャラリー".asJson, "type" -> "gallery".asJson),
                     Json.obj("id" -> "t".asJson, "name" -> "タイムライン".asJson, "type" -> "timeline".asJson),
                     Json.obj("id" -> "l".asJson, "name" -> "リスト".asJson, "type" -> "list".asJson))
        (s0, _) <- call(Some(ot), Method.PUT, s"/api/pages/${db.id}/database", Some(Json.obj("props" -> props, "views" -> views)))
        _        = assertEquals(s0, Status.Ok)
        // 項目をデータベースの API を使わずに作っても、表示するときに番号が付く
        _       <- page(ws.id, "ツリーから作った項目", Some(db.id), o.user.id)
        (_, r1) <- call(Some(ot), Method.POST, s"/api/pages/${db.id}/database/rows", Some(Json.obj("title" -> "設計".asJson, "values" -> Json.obj("pt" -> 100.asJson))))
        r1Id     = r1.hcursor.downField("page").get[UUID]("id").toOption.get
        _        = assertEquals(r1.hcursor.downField("values").get[Int]("_seq").toOption, Some(2))
        // サブ項目
        (s2, r2) <- call(Some(ot), Method.POST, s"/api/pages/${db.id}/database/rows", Some(Json.obj("title" -> "画面".asJson, "parentId" -> r1Id.asJson)))
        _         = assertEquals(s2, Status.Created)
        subId     = r2.hcursor.downField("page").get[UUID]("id").toOption.get
        (s3, _)  <- call(Some(ot), Method.PATCH, s"/api/pages/$subId/props", Some(Json.obj("values" -> Json.obj("pt" -> 20.asJson))))
        _         = assertEquals(s3, Status.Ok)
        (_, pp)  <- call(Some(ot), Method.GET, s"/api/pages/$subId/props")
        _         = assertEquals(pp.hcursor.downField("database").get[UUID]("pageId").toOption, Some(db.id))
        // 計算の列には値を入れられない
        (s4, _)  <- call(Some(ot), Method.PATCH, s"/api/pages/$r1Id/props", Some(Json.obj("values" -> Json.obj("f" -> 1.asJson))))
        _         = assertEquals(s4, Status.BadRequest)
        // サブ項目の親はこのデータベースの項目だけ
        other    <- page(ws.id, "関係ないページ", None, o.user.id)
        (s5, _)  <- call(Some(ot), Method.POST, s"/api/pages/${db.id}/database/rows", Some(Json.obj("title" -> "x".asJson, "parentId" -> other.id.asJson)))
        _         = assertEquals(s5, Status.BadRequest)
        (_, d)   <- call(Some(ot), Method.GET, s"/api/pages/${db.id}/database")
        rows      = d.hcursor.downField("rows").as[List[Json]].toOption.get
        _         = assertEquals(rows.size, 3)
        seqs      = rows.flatMap(_.hcursor.downField("values").get[Int]("_seq").toOption).sorted
        _         = assertEquals(seqs, List(1, 2, 3))
        sub       = rows.find(_.hcursor.downField("page").get[UUID]("id").toOption.contains(subId)).get
        _         = assertEquals(sub.hcursor.downField("page").get[UUID]("parentId").toOption, Some(r1Id))
        _         = assertEquals(sub.hcursor.downField("meta").get[String]("createdBy").toOption, Some("seq-owner"))
      yield ()
    }
  }

  test("forms: public answers become rows; required fields, login and disabling are enforced") {
    run {
      for
        (o, ot) <- user("form-owner")
        (r, rt) <- user("form-respondent")
        ws      <- env.store.createWorkspace("フォーム", o.user.id)
        db      <- page(ws.id, "問い合わせ", None, o.user.id)
        (sp, jp) <- call(Some(ot), Method.PUT, s"/api/pages/${db.id}/database", Some(Json.obj("props" -> Json.arr(
                     Json.obj("id" -> "kind".asJson, "name" -> "種類".asJson, "type" -> "select".asJson, "options" -> Json.arr(Json.obj("id" -> "q".asJson, "name" -> "質問".asJson, "color" -> "blue".asJson))),
                     Json.obj("id" -> "who".asJson, "name" -> "回答者".asJson, "type" -> "person".asJson),
                     Json.obj("id" -> "cb".asJson, "name" -> "作成者".asJson, "type" -> "created_by".asJson)), "views" -> Json.arr())))
        _        = assertEquals((sp, jp), (Status.Ok, jp))
        fields   = Json.arr(Json.obj("prop" -> "title".asJson, "required" -> true.asJson), Json.obj("prop" -> "kind".asJson, "required" -> false.asJson))
        (s0, _) <- call(Some(ot), Method.POST, s"/api/pages/${db.id}/forms", Some(Json.obj("title" -> "x".asJson, "fields" -> Json.arr(Json.obj("prop" -> "cb".asJson)))))
        _        = assertEquals(s0, Status.BadRequest) // 計算の列は質問にできない
        (s1, f) <- call(Some(ot), Method.POST, s"/api/pages/${db.id}/forms", Some(Json.obj("title" -> "お問い合わせ".asJson, "fields" -> fields)))
        _        = assertEquals((s1, f), (Status.Created, f))
        token    = f.hcursor.get[String]("url").toOption.get.split("/f/").last
        fid      = f.hcursor.get[String]("id").toOption.get
        (g0, pf) <- call(None, Method.GET, s"/api/forms/public/$token")
        _        = assertEquals(g0, Status.Ok)
        _        = assertEquals(pf.hcursor.downField("fields").as[List[Json]].map(_.map(_.hcursor.get[String]("type").toOption.get)), Right(List("title", "select")))
        (p0, _) <- call(None, Method.POST, s"/api/forms/public/$token", Some(Json.obj("values" -> Json.obj("kind" -> "q".asJson))))
        _        = assertEquals(p0, Status.BadRequest) // 件名がない
        (p1, _) <- call(None, Method.POST, s"/api/forms/public/$token", Some(Json.obj("values" -> Json.obj("title" -> "使い方".asJson, "kind" -> "q".asJson, "who" -> List(o.user.id).asJson))))
        _        = assertEquals(p1, Status.Created)
        (_, d)  <- call(Some(ot), Method.GET, s"/api/pages/${db.id}/database")
        row      = d.hcursor.downField("rows").downArray
        _        = assertEquals(row.downField("page").get[String]("title").toOption, Some("使い方"))
        _        = assertEquals(row.downField("values").get[String]("kind").toOption, Some("q"))
        _        = assert(row.downField("values").downField("who").failed) // フォームにない列は受け付けない
        // ログインが必要にして、回答者を記録する
        (s2, _) <- call(Some(ot), Method.PUT, s"/api/forms/$fid", Some(Json.obj("title" -> "お問い合わせ".asJson, "fields" -> fields, "requireLogin" -> true.asJson, "respondentProp" -> "who".asJson)))
        _        = assertEquals(s2, Status.Ok)
        (p2, _) <- call(None, Method.POST, s"/api/forms/public/$token", Some(Json.obj("values" -> Json.obj("title" -> "匿名".asJson))))
        _        = assertEquals(p2, Status.Unauthorized)
        (p3, _) <- call(Some(rt), Method.POST, s"/api/forms/public/$token", Some(Json.obj("values" -> Json.obj("title" -> "ログインして回答".asJson))))
        _        = assertEquals(p3, Status.Created)
        (_, d2) <- call(Some(ot), Method.GET, s"/api/pages/${db.id}/database")
        named    = d2.hcursor.downField("rows").as[List[Json]].toOption.get.find(_.hcursor.downField("page").get[String]("title").toOption.contains("ログインして回答")).get
        _        = assertEquals(named.hcursor.downField("values").get[List[UUID]]("who").toOption, Some(List(r.user.id)))
        // 止めると受け付けない
        _       <- call(Some(ot), Method.PUT, s"/api/forms/$fid", Some(Json.obj("title" -> "お問い合わせ".asJson, "fields" -> fields, "enabled" -> false.asJson)))
        (g1, _) <- call(None, Method.GET, s"/api/forms/public/$token")
        _        = assertEquals(g1, Status.NotFound)
      yield ()
    }
  }

  test("unfurl: reads OGP; private addresses are not fetched unless allowed") {
    run {
      val server = Resource.make(IO {
        val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/doc", ex => {
          val body = """<html><head><title>代わりのタイトル</title><meta property="og:title" content="設計書 &amp; 仕様"><meta property="og:description" content="説明です"><meta property="og:image" content="/img.png"><link rel="icon" href="/fav.ico"></head><body></body></html>""".getBytes("UTF-8")
          ex.getResponseHeaders.add("Content-Type", "text/html; charset=utf-8")
          ex.sendResponseHeaders(200, body.length.toLong)
          ex.getResponseBody.write(body)
          ex.close()
        })
        s.start()
        s
      })(s => IO(s.stop(0)))
      server.use { srv =>
        val url = s"http://127.0.0.1:${srv.getAddress.getPort}/doc"
        for
          (_, t)  <- user("unfurl-user")
          (s0, _) <- call(None, Method.GET, s"/api/unfurl?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
          _        = assertEquals(s0, Status.Unauthorized)
          (_, a)  <- call(Some(t), Method.GET, s"/api/unfurl?url=${java.net.URLEncoder.encode(url, "UTF-8")}")
          _        = assertEquals(a.hcursor.get[Option[String]]("title").toOption.flatten, None) // 社内のアドレスには接続しない
          (_, b)  <- call(Some(t), Method.GET, s"/api/unfurl?url=${java.net.URLEncoder.encode(url, "UTF-8")}", app = env.privateApp)
          _        = assertEquals(b.hcursor.get[String]("title").toOption, Some("設計書 & 仕様"))
          _        = assertEquals(b.hcursor.get[String]("description").toOption, Some("説明です"))
          _        = assertEquals(b.hcursor.get[String]("image").toOption, Some(s"http://127.0.0.1:${srv.getAddress.getPort}/img.png"))
          (_, c)  <- call(Some(t), Method.GET, s"/api/unfurl?url=${java.net.URLEncoder.encode("file:///etc/passwd", "UTF-8")}", app = env.privateApp)
          _        = assertEquals(c.hcursor.get[Option[String]]("title").toOption.flatten, None)
        yield ()
      }
    }
  }

  test("markdown export writes link cards as links") {
    val md = new MarkdownExport(identity, _ => "")
    val doc = io.circe.parser.parse("""{"type":"doc","content":[{"type":"linkCard","attrs":{"url":"https://example.com","title":"例"}},{"type":"databaseBlock","attrs":{"databaseId":"x"}}]}""").toOption.get
    assertEquals(md.page(doc, "題"), "# 題\n\n[例](https://example.com)\n\n（📊 データベース）\n")
  }
