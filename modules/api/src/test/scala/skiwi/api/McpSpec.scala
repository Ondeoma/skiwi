package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import skiwi.auth.Crypto
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** MCP（/mcp）: 認証、初期化、ツールの一覧と呼び出し、読み取り専用のトークン。本文の Markdown 化は DB なしでも試す。 */
class McpSpec extends DbSuite:

  private val publicUrl = "https://skiwi.example.com"
  private val apiCfg    = ApiConfig(adminGroups = Set("skiwi-admins"), cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield
        val store = new Store(xa)
        val docs  = new DocStore(xa)
        val ops   = new OpsStore(xa)
        val sess  = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val rest = new ApiRoutes(store, docs, ops, sess, audit, IO.unit).routes <+> new OpsRoutes(ops, sess, audit).routes <+>
          new PageExtraRoutes(store, docs, ops, sess, audit).routes <+>
          new CommentRoutes(store, new CommentStore(xa), ops, sess, audit, new Mentions(store, ops, sess)).routes <+>
          new SearchRoutes(store, new SearchStore(xa), sess).routes <+>
          new DatabaseRoutes(store, new DatabaseStore(xa), ops, sess, audit).routes <+>
          new ContentEditRoutes(store, docs, ops, sess, audit, (id, u) => docs.appendUpdate(id, u)).routes
        val routes = rest <+> new McpRoutes(rest, sess, publicUrl).routes
        val app = cats.data.Kleisli { (req: Request[IO]) =>
          routes.orNotFound.run(req).recoverWith { case ReadOnlyTokenError => IO.pure(Response[IO](Status.Forbidden)) }
        }
        Env(store, docs, app)

  private val fixture = ResourceSuiteLocalFixture("mcp", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  /** ログインして Cookie のセッションを作り、そのセッションで API トークンを発行する。 */
  private def tokenFor(name: String, scope: String): IO[(String, String)] =
    val cookie = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(cookie), u.id, Instant.now().plusSeconds(600))
      r <- env.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/tokens")).addCookie("skiwi_session", cookie)
             .withEntity(Json.obj("name" -> "mcp".asJson, "scope" -> scope.asJson)))
      j <- r.as[Json]
    yield (j.hcursor.get[String]("token").toOption.get, cookie)

  private var nextId = 0
  private def rpc(token: Option[String], method: String, params: Json = Json.obj(), notify: Boolean = false): IO[(Status, Json)] =
    nextId += 1
    val msg = Json.obj("jsonrpc" -> "2.0".asJson, "method" -> method.asJson, "params" -> params)
      .deepMerge(if notify then Json.obj() else Json.obj("id" -> nextId.asJson))
    val req = Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).withEntity(msg)
    val withAuth = token.fold(req)(t => req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))
    env.app.run(withAuth).flatMap(r => r.bodyText.compile.string.map(s => (r.status, parse(s).getOrElse(Json.Null))))

  /** ツールを呼び、(エラーか, 文字) を返す。 */
  private def tool(token: String, name: String, args: (String, Json)*): IO[(Boolean, String)] =
    rpc(Some(token), "tools/call", Json.obj("name" -> name.asJson, "arguments" -> Json.obj(args*))).map { (st, j) =>
      assertEquals(st, Status.Ok)
      val r = j.hcursor.downField("result")
      (r.get[Boolean]("isError").getOrElse(false), r.downField("content").downN(0).get[String]("text").getOrElse(""))
    }

  test("認証: トークンがない・Cookie だけ・無効なトークンは 401") {
    run {
      for
        (st, _) <- rpc(None, "initialize")
        _ = assertEquals(st, Status.Unauthorized)
        (_, cookie) <- tokenFor("クッキー 花子", "write")
        r <- env.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).addCookie("skiwi_session", cookie)
               .withEntity(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 1.asJson, "method" -> "ping".asJson)))
        _ = assertEquals(r.status, Status.Unauthorized)
        (st, _) <- rpc(Some("skiwi_bogus"), "initialize")
        _ = assertEquals(st, Status.Unauthorized)
      yield ()
    }
  }

  test("初期化・通知・ツールの一覧") {
    run {
      for
        (tok, _) <- tokenFor("初期化 太郎", "read")
        (st, init) <- rpc(Some(tok), "initialize", Json.obj("protocolVersion" -> "2025-06-18".asJson, "capabilities" -> Json.obj(),
                        "clientInfo" -> Json.obj("name" -> "test".asJson, "version" -> "1".asJson)))
        _ = assertEquals(st, Status.Ok)
        _ = assertEquals(init.hcursor.downField("result").get[String]("protocolVersion").toOption, Some("2025-06-18"))
        _ = assertEquals(init.hcursor.downField("result").downField("serverInfo").get[String]("name").toOption, Some("skiwi"))
        (st, _) <- rpc(Some(tok), "notifications/initialized", notify = true)
        _ = assertEquals(st, Status.Accepted)
        (_, list) <- rpc(Some(tok), "tools/list")
        names = list.hcursor.downField("result").downField("tools").values.toList.flatten.flatMap(_.hcursor.get[String]("name").toOption)
        _ = assert(Set("search", "get_page", "create_page", "add_comment", "get_database").subsetOf(names.toSet), names)
        (_, unknown) <- rpc(Some(tok), "nope/nope")
        _ = assertEquals(unknown.hcursor.downField("error").get[Int]("code").toOption, Some(-32601))
      yield ()
    }
  }

  test("ツール: ページを作って読み、名前を変え、コメントし、一覧と検索で見つけ、ゴミ箱へ移す") {
    run {
      for
        (tok, cookie) <- tokenFor("エムシーピー 次郎", "write")
        // ワークスペースは画面（Cookie）で作る
        wsResp <- env.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/workspaces")).addCookie("skiwi_session", cookie)
                    .withEntity(Json.obj("name" -> "MCPの試験".asJson)))
        ws <- wsResp.as[Json].map(_.hcursor.get[String]("id").toOption.get)
        (err, wsList) <- tool(tok, "list_workspaces")
        _ = assert(!err && wsList.contains(ws), wsList)
        (err, created) <- tool(tok, "create_page", "workspaceId" -> ws.asJson, "title" -> "週報".asJson, "markdown" -> "今週の進み具合\n\n来週の予定".asJson)
        _ = assert(!err, created)
        page = parse(created).toOption.get.hcursor.get[String]("pageId").toOption.get
        _ = assert(created.contains(s"$publicUrl/w/$ws/p/$page"), created)
        (err, read) <- tool(tok, "get_page", "pageId" -> page.asJson)
        _ = assert(!err && read.startsWith("# 週報") && read.contains("今週の進み具合") && read.contains("来週の予定"), read)
        (err, _) <- tool(tok, "update_page", "pageId" -> page.asJson, "title" -> "週報（11月）".asJson, "status" -> "review".asJson)
        _ = assert(!err)
        (err, child) <- tool(tok, "create_page", "workspaceId" -> ws.asJson, "title" -> "メモ".asJson, "parentId" -> page.asJson)
        _ = assert(!err, child)
        (err, tree) <- tool(tok, "list_pages", "workspaceId" -> ws.asJson)
        _ = assert(!err && tree.contains(s"- 週報（11月） [$page]") && tree.contains("  - メモ ["), tree)
        (err, c) <- tool(tok, "add_comment", "pageId" -> page.asJson, "body" -> "確認しました".asJson)
        _ = assert(!err, c)
        (err, cs) <- tool(tok, "list_comments", "pageId" -> page.asJson)
        _ = assert(!err && cs.contains("確認しました"), cs)
        (err, hits) <- tool(tok, "search", "query" -> "進み具合".asJson, "workspaceId" -> ws.asJson)
        _ = assert(!err && hits.contains(page), hits)
        (err, msg) <- tool(tok, "get_page", "pageId" -> "not-a-uuid".asJson)
        _ = assert(err && msg.contains("UUID"), msg)
        (err, _) <- tool(tok, "delete_page", "pageId" -> page.asJson)
        _ = assert(!err)
        (err, gone) <- tool(tok, "get_page", "pageId" -> page.asJson)
        _ = assert(err && gone.startsWith("404"), gone)
      yield ()
    }
  }

  test("本文の編集: 置き換え・追記・節の置き換え。保存した Yjs の状態が読み出し用の本文と一致し、前の本文が版に残る") {
    run {
      for
        (tok, cookie) <- tokenFor("編集 四郎", "write")
        wsResp <- env.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/workspaces")).addCookie("skiwi_session", cookie)
                    .withEntity(Json.obj("name" -> "本文の編集".asJson)))
        ws <- wsResp.as[Json].map(_.hcursor.get[String]("id").toOption.get)
        (err, created) <- tool(tok, "create_page", "workspaceId" -> ws.asJson, "title" -> "手順書".asJson,
                            "markdown" -> "# 準備\n\n道具をそろえる\n\n## 道具\n\n- はさみ\n\n# 作業\n\n切る".asJson)
        _ = assert(!err, created)
        page = parse(created).toOption.get.hcursor.get[String]("pageId").toOption.get
        pid  = UUID.fromString(page)
        (err, msg) <- tool(tok, "replace_section", "pageId" -> page.asJson, "heading" -> "## 準備".asJson,
                        "markdown" -> "# 準備\n\n**道具**と材料をそろえる".asJson)
        _ = assert(!err, msg)
        (err, msg) <- tool(tok, "append_to_page", "pageId" -> page.asJson, "markdown" -> "# 片付け\n\n- [ ] ごみを捨てる".asJson)
        _ = assert(!err, msg)
        (err, read) <- tool(tok, "get_page", "pageId" -> page.asJson)
        body = read.substring(read.indexOf("---\n\n") + 5).trim
        _ = assertEquals(body, "# 準備\n\n**道具**と材料をそろえる\n\n# 作業\n\n切る\n\n# 片付け\n\n- [ ] ごみを捨てる")
        (err, msg) <- tool(tok, "replace_section", "pageId" -> page.asJson, "heading" -> "ない見出し".asJson, "markdown" -> "x".asJson)
        _ = assert(err && msg.contains("準備"), msg)
        // DB の Yjs の状態（スナップショット＋更新ログ）を本物の Yjs で読むと、読み出し用の本文と同じ
        parts <- env.docs.load(pid)
        cur   <- env.docs.currentContent(pid)
        _ = if skiwi.ydoc.YjsNode.available then
              val ys = skiwi.ydoc.YjsNode.blocks(parts.snapshots.map(_._2) ++ parts.updates.map(_._2))
              assertEquals(ys, parse(cur.get._1).toOption.get.hcursor.downField("content").focus.get.asArray.get)
        (err, msg) <- tool(tok, "replace_page_content", "pageId" -> page.asJson, "markdown" -> "全部書き直した".asJson)
        _ = assert(!err, msg)
        (_, read2) <- tool(tok, "get_page", "pageId" -> page.asJson)
        _ = assert(read2.trim.endsWith("---\n\n全部書き直した"), read2)
        vs <- env.docs.listVersions(pid)
        _ = assert(vs.count(_.label.contains("API で本文を変える前")) == 2, vs)
      yield ()
    }
  }

  test("読み取り専用のトークンでは変更できない") {
    run {
      for
        (tok, cookie) <- tokenFor("閲覧 三郎", "read")
        wsResp <- env.app.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/workspaces")).addCookie("skiwi_session", cookie)
                    .withEntity(Json.obj("name" -> "閲覧だけ".asJson)))
        ws <- wsResp.as[Json].map(_.hcursor.get[String]("id").toOption.get)
        (err, msg) <- tool(tok, "create_page", "workspaceId" -> ws.asJson, "title" -> "作れない".asJson)
        _ = assert(err && msg.contains("読み取り専用"), msg)
        (err, _) <- tool(tok, "list_pages", "workspaceId" -> ws.asJson)
        _ = assert(!err)
      yield ()
    }
  }

  test("本文の Markdown 化（見出し・リスト・ToDo・書式・リンク・ページへのリンク・表）") {
    val doc = parse("""{"type":"doc","content":[
      {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"予定"}]},
      {"type":"paragraph","content":[{"type":"text","text":"太字","marks":[{"type":"bold"}]},{"type":"text","text":" と "},
        {"type":"text","text":"リンク","marks":[{"type":"link","attrs":{"href":"/w/a/p/b"}}]}]},
      {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"一つ目"}]},
        {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"入れ子"}]}]}]}]}]},
      {"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"済み"}]}]}]},
      {"type":"paragraph","content":[{"type":"pageLink","attrs":{"workspaceId":"w1","pageId":"p1","title":"設計"}}]},
      {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"名前"}]}]}]},
        {"type":"tableRow","content":[{"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"a|b"}]}]}]}]}
    ]}""").toOption.get
    val md = MarkdownExport(u => if u.startsWith("/") then publicUrl + u else u, _ => "").body(doc)
    assertEquals(md, List(
      "## 予定",
      "**太字** と [リンク](https://skiwi.example.com/w/a/p/b)",
      "- 一つ目\n  - 入れ子",
      "- [x] 済み",
      "[設計](https://skiwi.example.com/w/w1/p/p1)",
      "| 名前 |\n| --- |\n| a\\|b |"
    ).mkString("\n\n"))
  }
