package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import com.sun.net.httpserver.HttpServer
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*
import skiwi.storage.InMemoryBlobStore

import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** ウォッチの細分化・点検・一括操作・公開の履歴・Webhook・データベース。TEST_DATABASE_URL が必要。 */
class WorkflowSpec extends DbSuite:

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, ops: OpsStore, hooks: WebhookStore, svc: WebhookService, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _    <- Resource.eval(Database.migrate(c))
        xa   <- Database.transactor(c)
        http <- NetGuard.client(allowPrivate = true)
      yield
        val store = new Store(xa)
        val ops   = new OpsStore(xa)
        val docs  = new DocStore(xa)
        val sess  = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val attSt = new AttachmentStore(xa)
        val blobs = new InMemoryBlobStore
        val hooks = new WebhookStore(xa)
        val svc   = new WebhookService(hooks, store, "https://skiwi.example.com", http, allowHttp = true)
        val dbSt  = new DatabaseStore(xa)
        val shareR = new ShareRoutes(store, new ShareStore(xa), docs, attSt, new AttachmentRoutes(store, attSt, Some(blobs), sess), sess, audit, true, "https://skiwi.example.com", Some(svc))
        val mentions = new Mentions(store, ops, sess)
        val app = (shareR.routes <+> new BulkRoutes(store, sess, audit, IO.unit, Some(svc)).routes <+> new WebhookRoutes(store, hooks, svc, sess, audit).routes <+>
          new DatabaseRoutes(store, dbSt, ops, sess, audit).routes <+> new PageExtraRoutes(store, docs, ops, sess, audit).routes <+>
          new CommentRoutes(store, new CommentStore(xa), ops, sess, audit, mentions, Some(svc)).routes <+>
          new PageCopyRoutes(store, attSt, Some(blobs), new AccessRequestStore(xa), ops, sess, audit).routes <+>
          new ApiRoutes(store, docs, ops, sess, audit, IO.unit, None, Some(svc), Some(dbSt)).routes).orNotFound
        Env(store, docs, ops, hooks, svc, app)

  private val fixture = ResourceSuiteLocalFixture("workflow", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(AuthSession, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (AuthSession(u, Nil, false), token)

  private def call(token: Option[String], method: Method, path: String, body: Option[Json] = None): IO[(Status, Json)] =
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    body.foreach(b => req = req.withEntity(b))
    env.app.run(req).flatMap(r => r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null))))

  private def page(ws: UUID, title: String, parent: Option[UUID], by: UUID) =
    env.store.createPage(ws, parent, Placement.Last, title, None, by).map(_.toOption.get)

  private def kinds(u: UUID, pageId: UUID) = env.ops.notifications(u, 50).map(_.filter(_.pageId.contains(pageId)).map(_.kind))

  test("watch: subtree watch covers child pages, and edits/comments can be chosen separately") {
    run {
      for
        (o, ot)  <- user("watch-owner")
        (a, at)  <- user("watch-subtree")
        (b, bt)  <- user("watch-comments")
        ws       <- env.store.createWorkspace("watch", o.user.id)
        _        <- env.store.setWorkspaceAcl(ws.id, Some(Role.View), Nil)
        parent   <- page(ws.id, "親", None, o.user.id)
        child    <- page(ws.id, "子", Some(parent.id), o.user.id)
        // a: 親を「子ページも含める」でウォッチ
        (s1, w1) <- call(Some(at), Method.PUT, s"/api/pages/${parent.id}/watch", Some(Json.obj("edits" -> true.asJson, "comments" -> true.asJson, "subtree" -> true.asJson)))
        _         = assertEquals(s1, Status.Ok)
        _         = assertEquals(w1.hcursor.get[Boolean]("subtree").toOption, Some(true))
        (_, st)  <- call(Some(at), Method.GET, s"/api/pages/${child.id}/watch")
        _         = assertEquals(st.hcursor.get[Boolean]("watching").toOption, Some(false))
        _         = assertEquals(st.hcursor.downField("inherited").get[String]("title").toOption, Some("親"))
        // b: 子をコメントだけ
        _        <- call(Some(bt), Method.PUT, s"/api/pages/${child.id}/watch", Some(Json.obj("edits" -> false.asJson, "comments" -> true.asJson)))
        _        <- env.ops.enqueuePageEdited(child.id, Set(o.user.id), 300, 1800)
        ka       <- kinds(a.user.id, child.id)
        kb       <- kinds(b.user.id, child.id)
        _         = assert(ka.contains("page_edited"), ka)
        _         = assert(!kb.contains("page_edited"), kb)
        cw       <- env.ops.watchers(child.id)
        _         = assert(cw.contains(a.user.id) && cw.contains(b.user.id), cw)
        // a が子に直接「本文の更新なし」を設定すると、親の設定より優先
        _        <- call(Some(at), Method.PUT, s"/api/pages/${child.id}/watch", Some(Json.obj("edits" -> false.asJson, "comments" -> true.asJson)))
        n0       <- env.ops.notifications(a.user.id, 50).map(_.count(_.pageId.contains(child.id)))
        _        <- env.ops.markNotificationsRead(a.user.id, None)
        _        <- env.ops.enqueuePageEdited(child.id, Set(o.user.id), 300, 1800)
        un       <- env.ops.unreadNotifications(a.user.id)
        _         = assertEquals(un, 0)
        _         = assert(n0 >= 1)
        // 従来の { watching } も受け付ける
        (s2, w2) <- call(Some(bt), Method.PUT, s"/api/pages/${child.id}/watch", Some(Json.obj("watching" -> false.asJson)))
        _         = assertEquals((s2, w2.hcursor.get[Boolean]("watching").toOption), (Status.Ok, Some(false)))
      yield ()
    }
  }

  test("health: incoming link counts and last edit time for visible pages") {
    run {
      for
        (o, ot) <- user("health-owner")
        ws      <- env.store.createWorkspace("health", o.user.id)
        a       <- page(ws.id, "リンク元", None, o.user.id)
        b       <- page(ws.id, "リンク先", Some(a.id), o.user.id)
        c       <- page(ws.id, "孤立", Some(a.id), o.user.id)
        link     = Json.obj("type" -> "pageLink".asJson, "attrs" -> Json.obj("pageId" -> b.id.toString.asJson))
        doc      = Json.obj("type" -> "doc".asJson, "content" -> Json.arr(Json.obj("type" -> "paragraph".asJson, "content" -> Json.arr(link))))
        _       <- env.docs.saveCompaction(a.id, Array[Byte](1), doc, "x", Set.empty, Set.empty, o.user.id)
        (st, j) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/health")
        _        = assertEquals(st, Status.Ok)
        rows     = j.asArray.get.map(x => x.hcursor.downField("page").get[String]("title").toOption.get -> x.hcursor.get[Long]("incoming").toOption.get).toMap
        _        = assertEquals(rows, Map("リンク元" -> 0L, "リンク先" -> 1L, "孤立" -> 0L))
        (s2, _) <- call(None, Method.GET, s"/api/workspaces/${ws.id}/health")
        _        = assertEquals(s2, Status.Unauthorized)
      yield ()
    }
  }

  test("bulk: move, labels, status and delete with per-page permission checks") {
    run {
      for
        (o, ot) <- user("bulk-owner")
        (v, vt) <- user("bulk-viewer")
        ws      <- env.store.createWorkspace("bulk", o.user.id)
        _       <- env.store.setWorkspaceAcl(ws.id, Some(Role.View), List(AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit)))
        a       <- page(ws.id, "A", None, o.user.id)
        a1      <- page(ws.id, "A1", Some(a.id), o.user.id)
        b       <- page(ws.id, "B", None, o.user.id)
        dest    <- page(ws.id, "移動先", None, o.user.id)
        ids      = List(a.id, a1.id, b.id).map(_.toString.asJson)
        (s1, r1) <- call(Some(ot), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> ids.asJson, "action" -> "move".asJson, "parentId" -> dest.id.asJson)))
        _         = assertEquals(s1, Status.Ok)
        _         = assertEquals(r1.hcursor.get[List[UUID]]("done").toOption, Some(List(a.id, b.id)))
        _         = assertEquals(r1.hcursor.get[List[UUID]]("skipped").toOption, Some(List(a1.id)))
        moved    <- env.store.pageAccess(a.id, o).map(_.get._1)
        movedA1  <- env.store.pageAccess(a1.id, o).map(_.get._1)
        _         = assertEquals((moved.parentId, movedA1.parentId), (Some(dest.id), Some(a.id)))
        // 自分の下には移動できない
        (_, r2)  <- call(Some(ot), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> List(dest.id).asJson, "action" -> "move".asJson, "parentId" -> a.id.asJson)))
        _         = assertEquals(r2.hcursor.downField("failed").downArray.get[UUID]("id").toOption, Some(dest.id))
        // ラベル: 付ける → 外す
        _        <- call(Some(ot), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> ids.asJson, "action" -> "labels".asJson, "addLabels" -> List("手順", "API").asJson)))
        la       <- env.store.labels(a1.id)
        _         = assertEquals(la.sorted, List("API", "手順"))
        _        <- call(Some(ot), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> ids.asJson, "action" -> "labels".asJson, "removeLabels" -> List("api").asJson)))
        lb       <- env.store.labels(b.id)
        _         = assertEquals(lb, List("手順"))
        // 状態
        _        <- call(Some(ot), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> ids.asJson, "action" -> "status".asJson, "status" -> "review".asJson)))
        meta     <- env.store.pageMeta(List(a.id, a1.id, b.id))
        _         = assertEquals(meta.values.flatMap(_.status).toList, List("review", "review", "review"))
        // 閲覧のみの人は変更できない
        (_, r3)  <- call(Some(vt), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> ids.asJson, "action" -> "delete".asJson)))
        _         = assertEquals(r3.hcursor.get[List[UUID]]("done").toOption, Some(Nil))
        _         = assertEquals(r3.hcursor.downField("failed").downArray.get[String]("error").toOption, Some("権限がありません"))
        // 削除（子は親と一緒に）
        (_, r4)  <- call(Some(ot), Method.POST, "/api/pages/bulk", Some(Json.obj("ids" -> ids.asJson, "action" -> "delete".asJson)))
        _         = assertEquals(r4.hcursor.get[List[UUID]]("done").toOption, Some(List(a.id, b.id)))
        gone     <- List(a.id, a1.id, b.id).traverse(id => env.store.pageAccess(id, o))
        _         = assert(gone.forall(_.isEmpty))
      yield ()
    }
  }

  test("releases: publishing keeps notes and contents; guests see notes only when enabled") {
    run {
      for
        (o, ot) <- user("rel-owner")
        ws      <- env.store.createWorkspace("releases", o.user.id)
        root    <- page(ws.id, "マニュアル", None, o.user.id)
        _       <- env.docs.saveCompaction(root.id, Array[Byte](1), io.circe.parser.parse("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"初版"}]}]}""").toOption.get, "初版", Set.empty, Set.empty, o.user.id)
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("mode" -> "pinned".asJson)))
        token    = sh.hcursor.get[String]("token").toOption.get
        _       <- env.docs.saveCompaction(root.id, Array[Byte](2), io.circe.parser.parse("""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"第2版"}]}]}""").toOption.get, "第2版", Set.empty, Set.empty, o.user.id)
        (s1, _) <- call(Some(ot), Method.POST, s"/api/pages/${root.id}/share/publish", Some(Json.obj("note" -> "手順を更新しました".asJson)))
        _        = assertEquals(s1, Status.Ok)
        (_, rs) <- call(Some(ot), Method.GET, s"/api/pages/${root.id}/share/releases")
        list     = rs.asArray.get
        _        = assertEquals(list.size, 2)
        _        = assertEquals(list.head.hcursor.get[String]("note").toOption, Some("手順を更新しました"))
        rid      = list.head.hcursor.get[String]("id").toOption.get
        (_, rc) <- call(Some(ot), Method.GET, s"/api/pages/${root.id}/share/releases/$rid")
        _        = assertEquals(rc.hcursor.downField("pages").downArray.get[String]("text").toOption, Some("第2版"))
        oldId    = list(1).hcursor.get[String]("id").toOption.get
        (_, oc) <- call(Some(ot), Method.GET, s"/api/pages/${root.id}/share/releases/$oldId")
        _        = assertEquals(oc.hcursor.downField("pages").downArray.get[String]("text").toOption, Some("初版"))
        // 表示しない設定ではゲストに見えない
        (g0, _) <- call(None, Method.GET, s"/api/public/$token/releases")
        _        = assertEquals(g0, Status.NotFound)
        _       <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("showReleases" -> true.asJson)))
        (g1, gr) <- call(None, Method.GET, s"/api/public/$token/releases")
        _        = assertEquals(g1, Status.Ok)
        _        = assertEquals(gr.asArray.get.flatMap(_.hcursor.get[String]("note").toOption).toList, List("手順を更新しました"))
        (_, tr) <- call(None, Method.GET, s"/api/public/$token")
        _        = assertEquals(tr.hcursor.get[Boolean]("releases").toOption, Some(true))
        // メモの編集
        (s2, _) <- call(Some(ot), Method.PATCH, s"/api/pages/${root.id}/share/releases/$oldId", Some(Json.obj("note" -> "最初の公開".asJson)))
        _        = assertEquals(s2, Status.NoContent)
        (_, gr2) <- call(None, Method.GET, s"/api/public/$token/releases")
        _        = assertEquals(gr2.asArray.get.size, 2)
      yield ()
    }
  }

  test("webhooks: https only, signed JSON delivery, restricted pages are not sent") {
    run {
      val received = new ConcurrentLinkedQueue[(String, String, String)]()
      val server = Resource.make(IO {
        val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/hook", ex => {
          val body = new String(ex.getRequestBody.readAllBytes(), "UTF-8")
          received.add((Option(ex.getRequestHeaders.getFirst("X-Skiwi-Event")).getOrElse(""), Option(ex.getRequestHeaders.getFirst("X-Skiwi-Signature")).getOrElse(""), body))
          ex.sendResponseHeaders(204, -1)
          ex.close()
        })
        s.start()
        s
      })(s => IO(s.stop(0)))
      server.use { srv =>
        val url = s"http://127.0.0.1:${srv.getAddress.getPort}/hook"
        for
          (o, ot)  <- user("hook-owner")
          (v, vt)  <- user("hook-viewer")
          ws       <- env.store.createWorkspace("フック", o.user.id)
          _        <- env.store.setWorkspaceAcl(ws.id, Some(Role.View), List(AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit)))
          (s0, _)  <- call(Some(vt), Method.POST, s"/api/workspaces/${ws.id}/webhooks", Some(Json.obj("name" -> "x".asJson, "url" -> url.asJson, "format" -> "generic".asJson, "events" -> List("page.created").asJson)))
          _         = assertEquals(s0, Status.Forbidden)
          (s1, _)  <- call(Some(ot), Method.POST, s"/api/workspaces/${ws.id}/webhooks", Some(Json.obj("name" -> "x".asJson, "url" -> "ftp://example.com".asJson, "format" -> "generic".asJson, "events" -> List("page.created").asJson)))
          _         = assertEquals(s1, Status.BadRequest)
          (s2, h)  <- call(Some(ot), Method.POST, s"/api/workspaces/${ws.id}/webhooks", Some(Json.obj("name" -> "受け口".asJson, "url" -> url.asJson, "format" -> "generic".asJson,
                        "events" -> List("page.created", "page.updated", "comment.created", "page.deleted").asJson)))
          _         = assertEquals(s2, Status.Created)
          secret    = h.hcursor.get[String]("secret").toOption.get
          hid       = h.hcursor.get[String]("id").toOption.get
          (_, lst) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/webhooks")
          _         = assertEquals(lst.hcursor.downArray.get[Option[String]]("secret").toOption.flatten, None)
          // テスト送信
          (_, t)   <- call(Some(ot), Method.POST, s"/api/webhooks/$hid/test")
          _         = assertEquals(t.hcursor.get[Boolean]("ok").toOption, Some(true))
          // ページの作成（すぐ送るよう遅延なしで積む）・更新
          (_, pj)  <- call(Some(ot), Method.POST, s"/api/workspaces/${ws.id}/pages", Some(Json.obj("title" -> "新しい手順".asJson)))
          pid       = UUID.fromString(pj.hcursor.get[String]("id").toOption.get)
          _        <- env.ops.enqueuePageEdited(pid, Set(o.user.id), 0, 0)
          // 個別に権限を絞ったページは積まない
          secretP  <- page(ws.id, "秘密", None, o.user.id)
          _        <- env.store.setPageAcl(secretP.id, PageAcl(false, List(AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit))))
          _        <- env.ops.enqueuePageEdited(secretP.id, Set(o.user.id), 0, 0)
          _        <- IO.sleep(scala.concurrent.duration.DurationInt(100).millis)
          _        <- sql"update webhook_delivery set not_before = now() where webhook_id = ${UUID.fromString(hid)}".update.run.transact(xaOf).void
          n        <- env.svc.runOnce
          got       = received.asScala.toList
          events    = got.map(_._1)
          _         = assert(events.contains("test"), events)
          _         = assert(events.contains("page.created") && events.contains("page.updated"), events)
          created   = got.find(_._1 == "page.created").get
          mac       = { val m = javax.crypto.Mac.getInstance("HmacSHA256"); m.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256")); m.doFinal(created._3.getBytes("UTF-8")).map("%02x".format(_)).mkString }
          _         = assertEquals(created._2, s"sha256=$mac")
          body      = io.circe.parser.parse(created._3).toOption.get
          _         = assertEquals(body.hcursor.downField("page").get[String]("title").toOption, Some("新しい手順"))
          _         = assertEquals(body.hcursor.downField("page").get[String]("url").toOption, Some(s"https://skiwi.example.com/w/${ws.id}/p/$pid"))
          _         = assert(!got.exists(_._3.contains("秘密")), got)
          // 無効にすると積まない
          _        <- call(Some(ot), Method.PATCH, s"/api/webhooks/$hid", Some(Json.obj("enabled" -> false.asJson)))
          before    = received.size
          _        <- env.hooks.enqueue("page.deleted", pid, "{}")
          _        <- env.svc.runOnce
          _         = assertEquals(received.size, before)
        yield ()
      }
    }
  }

  private lazy val xaOf: doobie.Transactor[IO] =
    val c = cfg.get
    doobie.Transactor.fromDriverManager[IO]("org.postgresql.Driver", c.url, c.user, c.password, None)

  test("database: schema, rows with validated values, and duplication") {
    run {
      for
        (o, ot) <- user("db-owner")
        ws      <- env.store.createWorkspace("データベース", o.user.id)
        dbPage  <- page(ws.id, "案件", None, o.user.id)
        props    = Json.arr(
                     Json.obj("id" -> "st".asJson, "name" -> "状態".asJson, "type" -> "select".asJson,
                       "options" -> Json.arr(Json.obj("id" -> "a".asJson, "name" -> "未着手".asJson, "color" -> "gray".asJson), Json.obj("id" -> "b".asJson, "name" -> "完了".asJson, "color" -> "green".asJson))),
                     Json.obj("id" -> "due".asJson, "name" -> "期限".asJson, "type" -> "date".asJson),
                     Json.obj("id" -> "who".asJson, "name" -> "担当".asJson, "type" -> "person".asJson),
                     Json.obj("id" -> "n".asJson, "name" -> "金額".asJson, "type" -> "number".asJson))
        views    = Json.arr(Json.obj("id" -> "v1".asJson, "name" -> "表".asJson, "type" -> "table".asJson))
        (s0, _) <- call(Some(ot), Method.PUT, s"/api/pages/${dbPage.id}/database", Some(Json.obj("props" -> Json.arr(Json.obj("id" -> "x".asJson, "name" -> "x".asJson, "type" -> "nope".asJson)), "views" -> views)))
        _        = assertEquals(s0, Status.BadRequest)
        (s1, d1) <- call(Some(ot), Method.PUT, s"/api/pages/${dbPage.id}/database", Some(Json.obj("props" -> props, "views" -> views)))
        _        = assertEquals(s1, Status.Ok)
        (s2, r)  <- call(Some(ot), Method.POST, s"/api/pages/${dbPage.id}/database/rows", Some(Json.obj("title" -> "A社".asJson,
                      "values" -> Json.obj("st" -> "a".asJson, "due" -> "2026-12-01".asJson, "who" -> List(o.user.id.toString).asJson, "n" -> 1200.asJson))))
        _        = assertEquals(s2, Status.Created)
        rowId    = r.hcursor.downField("page").get[String]("id").toOption.get
        (s3, _) <- call(Some(ot), Method.PATCH, s"/api/pages/$rowId/props", Some(Json.obj("values" -> Json.obj("st" -> "zzz".asJson))))
        _        = assertEquals(s3, Status.BadRequest)
        (s4, _) <- call(Some(ot), Method.PATCH, s"/api/pages/$rowId/props", Some(Json.obj("values" -> Json.obj("due" -> "12/01".asJson))))
        _        = assertEquals(s4, Status.BadRequest)
        (s5, p5) <- call(Some(ot), Method.PATCH, s"/api/pages/$rowId/props", Some(Json.obj("values" -> Json.obj("st" -> "b".asJson, "n" -> Json.Null))))
        _        = assertEquals(s5, Status.Ok)
        _        = assertEquals(p5.hcursor.downField("values").get[String]("st").toOption, Some("b"))
        _        = assert(p5.hcursor.downField("values").downField("n").failed)
        (_, g)  <- call(Some(ot), Method.GET, s"/api/pages/${dbPage.id}/database")
        _        = assertEquals(g.hcursor.downField("rows").downArray.downField("page").get[String]("title").toOption, Some("A社"))
        _        = assertEquals(g.hcursor.downField("users").get[String](o.user.id.toString).toOption, Some("db-owner"))
        (_, pp) <- call(Some(ot), Method.GET, s"/api/pages/$rowId/props")
        _        = assertEquals(pp.hcursor.downField("database").get[String]("title").toOption, Some("案件"))
        (_, ls) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/pages")
        flags    = ls.asArray.get.map(x => x.hcursor.get[String]("title").toOption.get -> x.hcursor.get[Boolean]("database").toOption.get).toMap
        _        = assertEquals(flags, Map("案件" -> true, "A社" -> false))
        // 複製するとデータベースの定義と値も写る
        (_, dup) <- call(Some(ot), Method.POST, s"/api/pages/${dbPage.id}/duplicate", Some(Json.obj("includeChildren" -> true.asJson)))
        newRoot   = dup.hcursor.get[String]("rootId").toOption.get
        (_, g2)  <- call(Some(ot), Method.GET, s"/api/pages/$newRoot/database")
        _         = assertEquals(g2.hcursor.downField("rows").downArray.downField("values").get[String]("st").toOption, Some("b"))
        // データベースをやめる
        (s6, _)  <- call(Some(ot), Method.DELETE, s"/api/pages/${dbPage.id}/database")
        _         = assertEquals(s6, Status.NoContent)
        (s7, _)  <- call(Some(ot), Method.GET, s"/api/pages/${dbPage.id}/database")
        _         = assertEquals(s7, Status.NotFound)
      yield ()
    }
  }
