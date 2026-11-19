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

import java.time.{Instant, LocalDate, ZoneId}
import java.util.UUID

/** ToDo・所有者と状態・リンク切れ・ワークスペースをまたぐ移動のテスト。TEST_DATABASE_URL が必要。 */
class TaskSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(
    store: Store,
    docs: DocStore,
    ops: OpsStore,
    app: HttpApp[IO])

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
        val docs = new DocStore(xa)
        val app =
          (new ApiRoutes(store, docs, ops, sess, audit).routes <+>
            new PageExtraRoutes(store, docs, ops, sess, audit).routes <+>
            new SearchRoutes(store, new SearchStore(xa), sess).routes).orNotFound
        Env(store, docs, ops, app)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("tasks", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(
    name: String
  ): IO[(AuthSession, String)] = {
    val token = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield (AuthSession(u, Nil, false), token)
  }

  private def call(
    token: String,
    method: Method,
    path: String,
    body: Option[Json] = None
  ): IO[(Status, Json)] = {
    var req = Request[IO](method, Uri.unsafeFromString(path)).addCookie("skiwi_session", token)
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

  private def mention(
    u: UUID
  ) = Json.obj("type" -> "mention".asJson, "attrs" -> Json.obj("userId" -> u.toString.asJson, "label" -> "x".asJson))
  private def text(
    s: String
  ) = Json.obj("type" -> "text".asJson, "text" -> s.asJson)
  private def item(
    checked: Boolean,
    inline: Json*
  ) =
    Json.obj(
      "type" -> "taskItem".asJson,
      "attrs" -> Json.obj("checked" -> checked.asJson),
      "content" -> Json.arr(Json.obj("type" -> "paragraph".asJson, "content" -> inline.toList.asJson))
    )
  private def due(
    d: LocalDate
  ) = Json.obj("type" -> "dueDate".asJson, "attrs" -> Json.obj("date" -> d.toString.asJson))
  private def doc(
    items: Json*
  ) = Json.obj(
    "type" -> "doc".asJson,
    "content" -> Json.arr(Json.obj("type" -> "taskList".asJson, "content" -> items.toList.asJson))
  )

  test("tasks assigned by mention are listed for the assignee and notified once when due") {
    run {
      val today = LocalDate.now(ZoneId.of("Asia/Tokyo"))
      for {
        (o, ot) <- user("tk-owner")
        (a, at) <- user("tk-assignee")
        ws <- env.store.createWorkspace("ToDo", o.user.id)
        p <- page(ws.id, "議事録", None, o.user.id)
        _ <-
          env.store.setPageAcl(p.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, a.user.id), Role.View))))
        _ <- env.docs.saveCompaction(
          p.id,
          Array[Byte](0, 0),
          doc(
            item(false, text("資料を作る "), mention(a.user.id), due(today)),
            item(true, text("済んだ作業 "), mention(a.user.id)),
            item(false, text("担当なしの作業")),
            item(false, text("あとで "), mention(a.user.id), due(today.plusDays(10)))
          ),
          "",
          Set.empty,
          Set.empty,
          o.user.id
        )
        (_, open) <- call(at, Method.GET, "/api/tasks")
        texts = open.asArray.get.toList.map(_.hcursor.get[String]("text").toOption.get)
        _ = assertEquals(texts, List("資料を作る @x", "あとで @x")) // 未完了・期限順。担当なし・完了は出ない
        (_, all) <- call(at, Method.GET, "/api/tasks?done=true")
        _ = assertEquals(all.asArray.get.size, 3)
        (_, none) <- call(ot, Method.GET, "/api/tasks")
        _ = assertEquals(none.asArray.get.size, 0)
        n1 <- env.ops.enqueueTaskDue("Asia/Tokyo")
        n2 <- env.ops.enqueueTaskDue("Asia/Tokyo") // 2回目は通知しない
        (_, notes) <- call(at, Method.GET, "/api/notifications")
        dueNotes =
          notes.hcursor.get[List[Json]]("items").toOption.get.filter(_.hcursor.get[String]("kind").contains("task_due"))
        // 閲覧できなくなったページのToDoは一覧に出さない
        _ <- env.store.setPageAcl(p.id, PageAcl(true, Nil))
        (_, hidden) <- call(at, Method.GET, "/api/tasks")
      } yield {
        assert(n1 >= 1)
        assertEquals(n2, 0)
        assertEquals(dueNotes.map(_.hcursor.get[String]("due").toOption.get), List(today.toString))
        assertEquals(hidden.asArray.get.size, 0)
      }
    }
  }

  test("page owner and status: set, clear, list and search") {
    run {
      for {
        (o, ot) <- user("os-owner")
        (x, _) <- user("os-other")
        ws <- env.store.createWorkspace("所有者と状態", o.user.id)
        p <- page(ws.id, "手順書", None, o.user.id)
        q <- page(ws.id, "古い手順", None, o.user.id)
        (s1, d1) <- call(
          ot,
          Method.PATCH,
          s"/api/pages/${p.id}",
          Some(Json.obj("ownerId" -> x.user.id.asJson, "status" -> "review".asJson))
        )
        _ = assertEquals(s1, Status.Ok)
        _ = assertEquals(
          (d1.hcursor.get[String]("ownerName"), d1.hcursor.get[String]("status")),
          (Right("os-other"), Right("review"))
        )
        (sb, _) <- call(ot, Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("status" -> "unknown".asJson)))
        _ = assertEquals(sb, Status.BadRequest)
        (sn, _) <- call(ot, Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("ownerId" -> UUID.randomUUID().asJson)))
        _ = assertEquals(sn, Status.BadRequest)
        _ <- call(
          ot,
          Method.PATCH,
          s"/api/pages/${q.id}",
          Some(Json.obj("status" -> "deprecated".asJson, "ownerId" -> o.user.id.asJson))
        )
        (_, list) <- call(ot, Method.GET, s"/api/workspaces/${ws.id}/pages")
        st = list.asArray.get.map(j =>
          j.hcursor.get[String]("title").toOption.get -> j.hcursor.get[Option[String]]("status").toOption.flatten
        ).toMap
        _ = assertEquals(st, Map("手順書" -> Some("review"), "古い手順" -> Some("deprecated")))
        titles = (qs: String) =>
          call(
            ot,
            Method.GET,
            s"/api/search?workspaceId=${ws.id}&q=&$qs"
          ).map(_._2.asArray.get.toList.map(_.hcursor.downField("page").get[String]("title").toOption.get))
        t1 <- titles("status=deprecated")
        t2 <- titles("owner=me")
        // 外す（タイトルなど他の項目は変わらない）
        (_, cleared) <-
          call(ot, Method.PATCH, s"/api/pages/${p.id}", Some(Json.obj("ownerId" -> Json.Null, "status" -> Json.Null)))
      } yield {
        assertEquals(t1, List("古い手順"))
        assertEquals(t2, List("古い手順"))
        assertEquals(cleared.hcursor.get[Option[String]]("status"), Right(None))
        assertEquals(cleared.hcursor.get[Option[String]]("ownerName"), Right(None))
        assertEquals(cleared.hcursor.get[String]("title"), Right("手順書"))
      }
    }
  }

  test("broken links report missing, trashed and inaccessible targets without leaking titles") {
    run {
      for {
        (o, ot) <- user("bl2-owner")
        (v, vt) <- user("bl2-viewer")
        ws <- env.store.createWorkspace("リンク切れ", o.user.id)
        _ <- env.store.setWorkspaceAcl(
          ws.id,
          None,
          List(
            AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit),
            AclEntry(Principal(PrincipalType.User, v.user.id), Role.View)
          )
        )
        src <- page(ws.id, "参照元", None, o.user.id)
        trashed <- page(ws.id, "消したページ", None, o.user.id)
        secret <- page(ws.id, "秘密", None, o.user.id)
        ok <- page(ws.id, "正常", None, o.user.id)
        _ <- env.store.setPageAcl(
          secret.id,
          PageAcl(false, List(AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit)))
        )
        _ <- env.store.deletePage(trashed.id)
        link = (t: UUID) => Json.obj("type" -> "pageLink".asJson, "attrs" -> Json.obj("pageId" -> t.toString.asJson))
        missing = UUID.randomUUID()
        _ <- env.docs.saveCompaction(
          src.id,
          Array[Byte](0, 0),
          Json.obj(
            "type" -> "doc".asJson,
            "content" -> Json.arr(Json.obj(
              "type" -> "paragraph".asJson,
              "content" -> Json.arr(link(trashed.id), link(secret.id), link(ok.id), link(missing))
            ))
          ),
          "",
          Set.empty,
          Set.empty,
          o.user.id
        )
        (_, forOwner) <- call(ot, Method.GET, s"/api/workspaces/${ws.id}/broken-links")
        (_, forViewer) <- call(vt, Method.GET, s"/api/workspaces/${ws.id}/broken-links")
        reasons = (j: Json) =>
          j.asArray.get.toList.map(x =>
            x.hcursor.get[String]("reason").toOption.get ->
              x.hcursor.get[Option[String]]("targetTitle").toOption.flatten
          ).toSet
      } yield {
        assertEquals(reasons(forOwner), Set("deleted" -> Some("消したページ"), "missing" -> None))
        // 閲覧者: 秘密のページは「閲覧できない」（タイトルは出さない）。ゴミ箱のページは閲覧できたものなのでタイトルを出す
        assertEquals(reasons(forViewer), Set("deleted" -> Some("消したページ"), "no_access" -> None, "missing" -> None))
      }
    }
  }

  test("moving a page with its children to another workspace") {
    run {
      for {
        (o, ot) <- user("mv-owner")
        (x, xt) <- user("mv-other")
        ws1 <- env.store.createWorkspace("移動元", o.user.id)
        ws2 <- env.store.createWorkspace("移動先", o.user.id)
        ws3 <- env.store.createWorkspace("他人の", x.user.id)
        a <- page(ws1.id, "親", None, o.user.id)
        b <- page(ws1.id, "子", Some(a.id), o.user.id)
        (s0, _) <- call(ot, Method.POST, s"/api/pages/${a.id}/move", Some(Json.obj("workspaceId" -> ws3.id.asJson)))
        _ = assertEquals(s0, Status.NotFound) // 編集できないワークスペースへは移せない
        (s1, moved) <- call(ot, Method.POST, s"/api/pages/${a.id}/move", Some(Json.obj("workspaceId" -> ws2.id.asJson)))
        _ = assertEquals(s1, Status.Ok)
        _ = assertEquals(moved.hcursor.get[String]("workspaceId"), Right(ws2.id.toString))
        (_, l1) <- call(ot, Method.GET, s"/api/workspaces/${ws1.id}/pages")
        (_, l2) <- call(ot, Method.GET, s"/api/workspaces/${ws2.id}/pages")
      } yield {
        assertEquals(l1.asArray.get.size, 0)
        assertEquals(l2.asArray.get.map(_.hcursor.get[String]("title").toOption.get).toSet, Set("親", "子"))
      }
    }
  }
}
