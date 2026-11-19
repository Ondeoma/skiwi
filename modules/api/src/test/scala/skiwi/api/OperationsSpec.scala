package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID
import java.util.zip.ZipInputStream

/** ページの保護・期限付きの権限・データベースの関連と集計・自動化ルール・受信用の Webhook・定期バックアップ。TEST_DATABASE_URL が必要。 */
class OperationsSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("ops-admins"), cookieSecure = false)

  final case class Env(
    store: Store,
    docs: DocStore,
    ops: OpsStore,
    autos: Automations,
    backups: BackupService,
    backupSt: BackupStore,
    blobs: InMemoryBlobStore,
    app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match {
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for {
        _ <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
        rate <- Resource.eval(Ref.of[IO, Map[UUID, List[Long]]](Map.empty))
      } yield {
        val store = new Store(xa)
        val ops = new OpsStore(xa)
        val docs = new DocStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val attSt = new AttachmentStore(xa)
        val blobs = new InMemoryBlobStore
        val hookSt = new WebhookStore(xa)
        val dbSt = new DatabaseStore(xa)
        val autoSt = new AutomationStore(xa)
        val autos = new Automations(autoSt, store, ops, dbSt, hookSt)
        val comments = new CommentStore(xa)
        val backupSt = new BackupStore(xa)
        val backups = new BackupService(store, docs, attSt, dbSt, backupSt, Some(blobs), "https://skiwi.example.com")
        val app =
          (new LockRoutes(store, sess, audit).routes <+> new AutomationRoutes(store, autoSt, sess, audit).routes <+>
            new InboundRoutes(
              store,
              new InboundStore(xa),
              dbSt,
              docs,
              comments,
              ops,
              sess,
              audit,
              "https://skiwi.example.com",
              Some(new Mentions(store, ops, sess)),
              None,
              Some(autos),
              rate
            ).routes <+>
            new BackupRoutes(backupSt, backups, Some(blobs), sess, audit).routes <+>
            new DatabaseRoutes(store, dbSt, ops, sess, audit, Some(autos)).routes <+>
            new PageExtraRoutes(store, docs, ops, sess, audit, Some(autos)).routes <+>
            new PageCopyRoutes(store, attSt, Some(blobs), new AccessRequestStore(xa), ops, sess, audit).routes <+>
            new ApiRoutes(
              store,
              docs,
              ops,
              sess,
              audit,
              IO.unit,
              None,
              None,
              Some(dbSt),
              Some(autos)
            ).routes).orNotFound
        Env(store, docs, ops, autos, backups, backupSt, blobs, app)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("operations", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(
    name: String,
    groups: List[String] = Nil
  ): IO[(AuthSession, String)] = {
    val token = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(
        s"$name-${UUID.randomUUID()}",
        Some(s"${UUID.randomUUID().toString.take(8)}@example.com"),
        name
      )
      _ <- if groups.nonEmpty then env.store.syncGroups(u.id, groups) else IO.unit
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield (AuthSession(u, Nil, groups.contains("ops-admins")), token)
  }

  private def call(
    token: Option[String],
    method: Method,
    path: String,
    body: Option[Json] = None
  ): IO[(Status, Json)] = {
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    body.foreach(b => req = req.withEntity(b))
    env.app.run(req).flatMap(r =>
      r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null)))
    )
  }

  private def page(
    ws: UUID,
    title: String,
    parent: Option[UUID],
    by: UUID
  ) =
    env.store.createPage(ws, parent, Placement.Last, title, None, by).map(_.toOption.get)

  test("lock: only the locker, the owner and listed users can edit; others become viewers") {
    run {
      for {
        (o, ot) <- user("lock-owner")
        (e, et) <- user("lock-editor")
        (x, xt) <- user("lock-allowed")
        ws <- env.store.createWorkspace("保護", o.user.id)
        _ <- env.store.setWorkspaceAcl(ws.id, Some(Role.Edit), Nil)
        p <- page(ws.id, "規程", None, o.user.id)
        (s1, l) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${p.id}/lock",
          Some(Json.obj("reason" -> "確定版".asJson, "editors" -> List(x.user.id).asJson))
        )
        _ = assertEquals(s1, Status.Ok)
        _ = assertEquals(l.hcursor.get[Boolean]("locked").toOption, Some(true))
        re <- env.store.pageAccess(p.id, e).map(_.map(_._2))
        rx <- env.store.pageAccess(p.id, x).map(_.map(_._2))
        ro <- env.store.pageAccess(p.id, o).map(_.map(_._2))
        _ = assertEquals((re, rx, ro), (Some(Role.View), Some(Role.Edit), Some(Role.Edit)))
        list <- env.store.listPages(ws.id, e)
        _ = assertEquals(list.find(_._1.id == p.id).map(_._2), Some(Role.View))
        (_, pg) <- call(Some(et), Method.GET, s"/api/pages/${p.id}")
        _ = assertEquals(pg.hcursor.get[Boolean]("locked").toOption, Some(true))
        (s2, _) <- call(Some(et), Method.DELETE, s"/api/pages/${p.id}/lock")
        _ = assertEquals(s2, Status.Forbidden)
        (s3, _) <- call(Some(et), Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("title" -> "改ざん".asJson)))
        _ = assertEquals(s3, Status.Forbidden)
        (s4, _) <- call(Some(xt), Method.DELETE, s"/api/pages/${p.id}/lock")
        _ = assertEquals(s4, Status.NoContent)
        re2 <- env.store.pageAccess(p.id, e).map(_.map(_._2))
        _ = assertEquals(re2, Some(Role.Edit))
      } yield ()
    }
  }

  test("acl expiry: expired entries are ignored and purged; approving a request can set a deadline") {
    run {
      for {
        (o, ot) <- user("exp-owner")
        (g, gt) <- user("exp-guest")
        ws <- env.store.createWorkspace("期限", o.user.id)
        p <- page(ws.id, "協力会社向け", None, o.user.id)
        future = Instant.now().plusSeconds(3600)
        (s1, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${p.id}/acl",
          Some(Json.obj(
            "inherit" -> true.asJson,
            "entries" -> Json.arr(
              Json.obj(
                "principalType" -> "user".asJson,
                "principalId" -> g.user.id.asJson,
                "role" -> "view".asJson,
                "expiresAt" -> future.asJson
              )
            )
          ))
        )
        _ = assertEquals(s1, Status.NoContent)
        (_, acl) <- call(Some(ot), Method.GET, s"/api/pages/${p.id}/acl")
        _ = assert(acl.hcursor.downField("entries").downArray.get[Instant]("expiresAt").isRight, acl)
        a1 <- env.store.pageAccess(p.id, g)
        _ = assertEquals(a1.map(_._2), Some(Role.View))
        // 期限を過ぎたことにする
        _ <- env.store.setPageAcl(
          p.id,
          PageAcl(
            true,
            List(AclEntry(Principal(PrincipalType.User, g.user.id), Role.View)),
            Map(Principal(PrincipalType.User, g.user.id) -> Instant.now().minusSeconds(5))
          )
        )
        a2 <- env.store.pageAccess(p.id, g)
        _ = assertEquals(a2, None)
        n <- env.store.purgeExpiredAcl
        _ = assert(n >= 1)
        // 申請を期限付きで許可する
        (_, rq) <- call(
          Some(gt),
          Method.POST,
          s"/api/pages/${p.id}/access-requests",
          Some(Json.obj("role" -> "view".asJson, "message" -> "".asJson))
        )
        (_, rl) <- call(Some(ot), Method.GET, s"/api/pages/${p.id}/access-requests")
        rid = rl.hcursor.downArray.get[String]("id").toOption.get
        (s2, _) <- call(
          Some(ot),
          Method.POST,
          s"/api/access-requests/$rid/approve",
          Some(Json.obj("expiresAt" -> future.asJson))
        )
        _ = assertEquals(s2, Status.Ok)
        acl2 <- env.store.getPageAcl(p.id)
        _ = assert(acl2.get.expires.get(Principal(PrincipalType.User, g.user.id)).isDefined, acl2)
      } yield ()
    }
  }

  test("database: relation and rollup columns, bulk rows") {
    run {
      for {
        (o, ot) <- user("rel-owner")
        ws <- env.store.createWorkspace("関連", o.user.id)
        cust <- page(ws.id, "顧客", None, o.user.id)
        deals <- page(ws.id, "案件", None, o.user.id)
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${cust.id}/database",
          Some(Json.obj("props" -> Json.arr(), "views" -> Json.arr()))
        )
        (s0, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${deals.id}/database",
          Some(Json.obj(
            "props" -> Json.arr(
              Json.obj("id" -> "c".asJson, "name" -> "顧客".asJson, "type" -> "relation".asJson)
            ),
            "views" -> Json.arr()
          ))
        )
        _ = assertEquals(s0, Status.BadRequest) // 関連先がない
        props = Json.arr(
          Json.obj("id" -> "amt".asJson, "name" -> "金額".asJson, "type" -> "number".asJson),
          Json.obj("id" -> "c".asJson, "name" -> "顧客".asJson, "type" -> "relation".asJson, "target" -> cust.id.asJson)
        )
        (s1, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${deals.id}/database",
          Some(Json.obj("props" -> props, "views" -> Json.arr()))
        )
        _ = assertEquals(s1, Status.Ok)
        (_, a) <-
          call(Some(ot), Method.POST, s"/api/pages/${cust.id}/database/rows", Some(Json.obj("title" -> "A社".asJson)))
        aId = a.hcursor.downField("page").get[String]("id").toOption.get
        (s2, b) <- call(
          Some(ot),
          Method.POST,
          s"/api/pages/${deals.id}/database/rows/bulk",
          Some(Json.obj("rows" -> Json.arr(
            Json.obj("title" -> "導入".asJson, "values" -> Json.obj("amt" -> 100.asJson, "c" -> List(aId).asJson)),
            Json.obj("title" -> "保守".asJson, "values" -> Json.obj("amt" -> 50.asJson))
          )))
        )
        _ = assertEquals((s2, b.hcursor.get[Int]("created").toOption), (Status.Ok, Some(2)))
        // 顧客側に集計の列（関連先の案件の金額の合計）を足す
        cprops = Json.arr(
          Json.obj("id" -> "d".asJson, "name" -> "案件".asJson, "type" -> "relation".asJson, "target" -> deals.id.asJson),
          Json.obj(
            "id" -> "sum".asJson,
            "name" -> "合計".asJson,
            "type" -> "rollup".asJson,
            "relation" -> "d".asJson,
            "fn" -> "sum".asJson,
            "prop" -> "amt".asJson
          )
        )
        (s3, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${cust.id}/database",
          Some(Json.obj("props" -> cprops, "views" -> Json.arr()))
        )
        _ = assertEquals(s3, Status.Ok)
        (s4, _) <- call(
          Some(ot),
          Method.PATCH,
          s"/api/pages/$aId/props",
          Some(Json.obj("values" -> Json.obj("sum" -> 1.asJson)))
        )
        _ = assertEquals(s4, Status.BadRequest) // 集計は計算するもの
        (_, cd) <- call(Some(ot), Method.GET, s"/api/pages/${cust.id}/database")
        _ = assertEquals(
          cd.hcursor.downField("targets").downField(deals.id.toString).downField("rows").as[List[Json]].map(_.size),
          Right(2)
        )
      } yield ()
    }
  }

  test("automation: status change notifies the owner; a date rule fires once") {
    run {
      for {
        (o, ot) <- user("auto-owner")
        (w, wt) <- user("auto-assignee")
        ws <- env.store.createWorkspace("自動化", o.user.id)
        _ <- env.store.setWorkspaceAcl(ws.id, Some(Role.Edit), Nil)
        p <- page(ws.id, "手順書", None, o.user.id)
        _ <- env.store.setPageMeta(p.id, Some(Some(o.user.id)), None)
        (s0, _) <- call(
          Some(ot),
          Method.POST,
          s"/api/workspaces/${ws.id}/automations",
          Some(Json.obj(
            "name" -> "x".asJson,
            "trigger" -> Json.obj("type" -> "status_changed".asJson),
            "action" -> Json.obj("type" -> "notify".asJson)
          ))
        )
        _ = assertEquals(s0, Status.BadRequest)
        (s1, _) <- call(
          Some(ot),
          Method.POST,
          s"/api/workspaces/${ws.id}/automations",
          Some(Json.obj(
            "name" -> "レビュー依頼".asJson,
            "trigger" -> Json.obj("type" -> "status_changed".asJson, "to" -> "review".asJson),
            "action" ->
              Json.obj("type" -> "notify".asJson, "to" -> "owner".asJson, "message" -> "「{title}」のレビューをお願いします".asJson)
          ))
        )
        _ = assertEquals(s1, Status.Created)
        _ <- call(Some(wt), Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("status" -> "review".asJson)))
        ns <- env.ops.notifications(o.user.id, 20)
        _ = assert(ns.exists(n => n.kind == "automation" && n.detail.contains("「手順書」のレビューをお願いします")), ns)
        // 同じ状態のままなら動かない
        _ <- call(Some(wt), Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("status" -> "review".asJson)))
        ns2 <- env.ops.notifications(o.user.id, 20)
        _ = assertEquals(ns2.count(_.kind == "automation"), 1)
        // 日付: 期限の当日に担当者へ
        db <- page(ws.id, "タスク", None, o.user.id)
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${db.id}/database",
          Some(Json.obj(
            "props" -> Json.arr(
              Json.obj("id" -> "due".asJson, "name" -> "期限".asJson, "type" -> "date".asJson),
              Json.obj("id" -> "who".asJson, "name" -> "担当".asJson, "type" -> "person".asJson)
            ),
            "views" -> Json.arr()
          ))
        )
        today = LocalDate.now(ZoneId.of("Asia/Tokyo")).toString
        (_, row) <- call(
          Some(ot),
          Method.POST,
          s"/api/pages/${db.id}/database/rows",
          Some(Json.obj(
            "title" -> "提出".asJson,
            "values" -> Json.obj("due" -> today.asJson, "who" -> List(w.user.id).asJson)
          ))
        )
        _ <- call(
          Some(ot),
          Method.POST,
          s"/api/workspaces/${ws.id}/automations",
          Some(Json.obj(
            "name" -> "期限".asJson,
            "trigger" -> Json.obj(
              "type" -> "date_reached".asJson,
              "database" -> db.id.asJson,
              "prop" -> "due".asJson,
              "offset" -> 0.asJson
            ),
            "action" -> Json.obj("type" -> "notify".asJson, "to" -> "prop".asJson, "prop" -> "who".asJson)
          ))
        )
        _ <- env.autos.checkDates
        _ <- env.autos.checkDates
        wn <- env.ops.notifications(w.user.id, 20)
        _ = assertEquals(wn.count(_.kind == "automation"), 1)
      } yield ()
    }
  }

  test("inbound hook: creates database rows with body text, child pages and comments") {
    run {
      for {
        (o, ot) <- user("in-owner")
        ws <- env.store.createWorkspace("受信", o.user.id)
        db <- page(ws.id, "問い合わせ", None, o.user.id)
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${db.id}/database",
          Some(Json.obj(
            "props" -> Json.arr(
              Json.obj(
                "id" -> "st".asJson,
                "name" -> "状態".asJson,
                "type" -> "select".asJson,
                "options" -> Json.arr(Json.obj("id" -> "new".asJson, "name" -> "未対応".asJson, "color" -> "red".asJson))
              ),
              Json.obj("id" -> "d".asJson, "name" -> "期限".asJson, "type" -> "date".asJson)
            ),
            "views" -> Json.arr()
          ))
        )
        (s0, h) <- call(
          Some(ot),
          Method.POST,
          s"/api/workspaces/${ws.id}/inbound-hooks",
          Some(Json.obj("name" -> "フォーム".asJson, "targetPageId" -> db.id.asJson, "mode" -> "row".asJson))
        )
        _ = assertEquals(s0, Status.Created)
        url = h.hcursor.get[String]("url").toOption.get
        path = url.stripPrefix("https://skiwi.example.com")
        (s1, r) <- call(
          None,
          Method.POST,
          path,
          Some(Json.obj(
            "title" -> "A社から".asJson,
            "text" -> "1行目\n\n2行目".asJson,
            "values" -> Json.obj("状態" -> "未対応".asJson, "期限" -> "2026/12/1".asJson)
          ))
        )
        _ = assertEquals(s1, Status.Created)
        pid = UUID.fromString(r.hcursor.get[String]("pageId").toOption.get)
        content <- env.docs.currentContent(pid)
        _ = assertEquals(content.map(_._2), Some("1行目\n2行目"))
        (_, pv) <- call(Some(ot), Method.GET, s"/api/pages/$pid/props")
        _ = assertEquals(pv.hcursor.downField("values").get[String]("st").toOption, Some("new"))
        _ = assertEquals(pv.hcursor.downField("values").get[String]("d").toOption, Some("2026-12-01"))
        (s2, _) <- call(None, Method.POST, path, Some(Json.obj("values" -> Json.obj("ない列" -> "x".asJson))))
        _ = assertEquals(s2, Status.BadRequest)
        (s3, _) <- call(None, Method.POST, "/api/hooks/wrong-token", Some(Json.obj("text" -> "x".asJson)))
        _ = assertEquals(s3, Status.NotFound)
        // コメント（Slack 形式）
        (_, hc) <- call(
          Some(ot),
          Method.POST,
          s"/api/workspaces/${ws.id}/inbound-hooks",
          Some(Json.obj("name" -> "監視".asJson, "targetPageId" -> db.id.asJson, "mode" -> "comment".asJson))
        )
        cpath = hc.hcursor.get[String]("url").toOption.get.stripPrefix("https://skiwi.example.com")
        (s4, _) <- call(None, Method.POST, cpath, Some(Json.obj("text" -> "サーバーが応答しません".asJson)))
        _ = assertEquals(s4, Status.Created)
        // 無効にすると受け付けない
        hid = hc.hcursor.get[String]("id").toOption.get
        _ <- call(Some(ot), Method.PATCH, s"/api/inbound-hooks/$hid", Some(Json.obj("enabled" -> false.asJson)))
        (s5, _) <- call(None, Method.POST, cpath, Some(Json.obj("text" -> "x".asJson)))
        _ = assertEquals(s5, Status.Gone)
      } yield ()
    }
  }

  test("backup: ZIP with Markdown, attachments-compatible layout and skiwi-backup.json; admin only") {
    run {
      for {
        (o, ot) <- user("bk-owner")
        (a, at) <- user("bk-admin", List("ops-admins"))
        ws <- env.store.createWorkspace("バックアップ", o.user.id)
        root <- page(ws.id, "手順", None, o.user.id)
        child <- page(ws.id, "準備", Some(root.id), o.user.id)
        doc =
          """{"type":"doc","content":[{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"概要"}]},{"type":"paragraph","content":[{"type":"text","text":"太字","marks":[{"type":"bold"}]},{"type":"pageLink","attrs":{"pageId":"""" +
            child.id + """","workspaceId":"""" + ws.id + """","title":"準備"}}]}]}"""
        _ <- env.docs.saveCompaction(
          root.id,
          Array[Byte](1),
          io.circe.parser.parse(doc).toOption.get,
          "概要 太字",
          Set.empty,
          Set.empty,
          o.user.id
        )
        (s0, _) <- call(Some(ot), Method.POST, s"/api/admin/workspaces/${ws.id}/backups")
        _ = assertEquals(s0, Status.Forbidden)
        _ <- call(
          Some(at),
          Method.PUT,
          s"/api/admin/workspaces/${ws.id}/backups/config",
          Some(Json.obj("enabled" -> true.asJson, "hour" -> 0.asJson, "keep" -> 2.asJson))
        )
        n <- env.backups.runDue
        _ = assertEquals(n, 1)
        n2 <- env.backups.runDue
        _ = assertEquals(n2, 0) // 今日の分は済んでいる
        (_, l) <- call(Some(at), Method.GET, s"/api/admin/workspaces/${ws.id}/backups")
        b = l.hcursor.downField("backups").downArray
        _ = assertEquals(b.get[String]("status").toOption, Some("done"))
        key <- env.backupSt.list(ws.id).map(_.head.s3Key.get)
        bytes <- env.blobs.get(key).flatMap(_.get._2.compile.to(Array))
        files = {
          val z = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))
          Iterator.continually(z.getNextEntry).takeWhile(_ != null).map(e =>
            e.getName -> new String(z.readAllBytes(), "UTF-8")
          ).toMap
        }
        _ = assert(
          files.contains("手順.md") && files.contains("手順/準備.md") && files.contains("skiwi-backup.json"),
          files.keys
        )
        _ = assert(
          files("手順.md").contains("## 概要") && files("手順.md").contains("**太字**") &&
            files("手順.md").contains("[準備](%E6%89%8B%E9%A0%86/%E6%BA%96%E5%82%99.md)"),
          files("手順.md")
        )
      } yield ()
    }
  }

  test("markdown export matches the editor's rules for lists, tasks and tables") {
    val md = new MarkdownExport(identity, _ => "（ホワイトボード）")
    val doc = io.circe.parser.parse(
      """{"type":"doc","content":[
      {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"a*b"}]}]}]},
      {"type":"taskList","content":[{"type":"taskItem","attrs":{"checked":true},"content":[{"type":"paragraph","content":[{"type":"text","text":"済み"}]}]}]},
      {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"列"}]}]}]},
        {"type":"tableRow","content":[{"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"値"}]}]}]}]}]}"""
    ).toOption.get
    assertEquals(md.page(doc, "題"), "# 題\n\n- a\\*b\n\n- [x] 済み\n\n| 列 |\n| --- |\n| 値 |\n")
  }
}
