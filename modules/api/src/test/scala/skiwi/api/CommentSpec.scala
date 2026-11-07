package skiwi.api

import cats.effect.*
import cats.syntax.semigroupk.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** コメント・メンション・閲覧できる人・検索の絞り込みのテスト。TEST_DATABASE_URL が必要。 */
class CommentSpec extends DbSuite:

  private val apiCfg = ApiConfig(adminGroups = Set("cm-admins"), cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, ops: OpsStore, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield
        val store = new Store(xa)
        val ops   = new OpsStore(xa)
        val sess  = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val app = (new CommentRoutes(store, new CommentStore(xa), ops, sess, audit, new Mentions(store, ops, sess)).routes <+>
          new SearchRoutes(store, new SearchStore(xa), sess).routes <+>
          new PageExtraRoutes(store, new DocStore(xa), ops, sess, audit).routes).orNotFound
        Env(store, new DocStore(xa), ops, app)

  private val fixture = ResourceSuiteLocalFixture("comments", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String, groups: List[String] = Nil): IO[(AuthSession, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.syncGroups(u.id, groups)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (AuthSession(u, Nil, false), token)

  private def call(token: String, method: Method, path: String, body: Option[Json] = None): IO[(Status, Json)] =
    var req = Request[IO](method, Uri.unsafeFromString(path)).addCookie("skiwi_session", token)
    body.foreach(b => req = req.withEntity(b))
    env.app.run(req).flatMap(r => r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null))))

  private def kinds(token: String): IO[List[String]] =
    call(token, Method.GET, "/api/notifications").map(_._2.hcursor.get[List[Json]]("items").toOption.get.map(_.hcursor.get[String]("kind").toOption.get))

  private def view(p: UUID, u: AuthSession) = env.store.setPageAcl(p, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, u.user.id), Role.View))))

  test("comments, replies, mentions only for people who can see the page, resolve and delete") {
    run {
      for
        (o, ot)  <- user("cm-owner")
        (v, vt)  <- user("cm-viewer")
        (x, xt)  <- user("cm-outsider")
        ws <- env.store.createWorkspace("コメント", o.user.id)
        p  <- env.store.createPage(ws.id, None, Placement.Last, "仕様", None, o.user.id).map(_.toOption.get)
        _  <- view(p.id, v)
        // 閲覧者もコメントできる。見えない人はメンションしても通知されない
        (s1, c1) <- call(vt, Method.POST, s"/api/pages/${p.id}/comments", Some(Json.obj(
                      "body" -> s"確認お願いします @[cm-owner](${o.user.id}) @[cm-outsider](${x.user.id})".asJson,
                      "anchor" -> "text:abc123".asJson, "quote" -> "対象の文章".asJson)))
        _ = assertEquals(s1, Status.Created)
        root = UUID.fromString(c1.hcursor.get[String]("id").toOption.get)
        (sx, _) <- call(xt, Method.GET, s"/api/pages/${p.id}/comments")
        _ = assertEquals(sx, Status.NotFound)
        ok <- kinds(ot)
        _ = assertEquals(ok, List("mention"))
        ox <- kinds(xt)
        _ = assertEquals(ox, Nil)
        // 返信はスレッドの参加者へ
        (s2, _) <- call(ot, Method.POST, s"/api/pages/${p.id}/comments", Some(Json.obj("body" -> "対応しました".asJson, "threadId" -> root.asJson)))
        _ = assertEquals(s2, Status.Created)
        vk <- kinds(vt)
        _ = assertEquals(vk, List("comment"))
        (_, list) <- call(ot, Method.GET, s"/api/pages/${p.id}/comments")
        _ = assertEquals(list.asArray.get.size, 2)
        _ = assertEquals(list.asArray.get.head.hcursor.get[String]("anchor"), Right("text:abc123"))
        // 編集は投稿者だけ。解決は投稿者・編集者
        (se, _) <- call(ot, Method.PATCH, s"/api/comments/$root", Some(Json.obj("body" -> "改ざん".asJson)))
        _ = assertEquals(se, Status.Forbidden)
        (sr, r) <- call(ot, Method.POST, s"/api/comments/$root/resolve", Some(Json.obj("resolved" -> true.asJson)))
        _ = assertEquals((sr, r.hcursor.get[String]("resolvedBy")), (Status.Ok, Right("cm-owner")))
        // 不正な返信先・アンカー
        (sb, _) <- call(ot, Method.POST, s"/api/pages/${p.id}/comments", Some(Json.obj("body" -> "x".asJson, "anchor" -> "<script>".asJson)))
        _ = assertEquals(sb, Status.BadRequest)
        // 削除（ページの編集者は他人のコメントも消せる）
        (sd, _) <- call(ot, Method.DELETE, s"/api/comments/$root")
        _ = assertEquals(sd, Status.NoContent)
        (_, after) <- call(vt, Method.GET, s"/api/pages/${p.id}/comments")
        _ = assertEquals(after.asArray.get.head.hcursor.get[Boolean]("deleted"), Right(true))
        _ = assertEquals(after.asArray.get.head.hcursor.get[String]("body"), Right(""))
      yield ()
    }
  }

  test("mentions written in the page body are detected once") {
    run {
      for
        (o, _) <- user("bm-owner")
        (m, _) <- user("bm-mentioned")
        ws <- env.store.createWorkspace("本文メンション", o.user.id)
        p  <- env.store.createPage(ws.id, None, Placement.Last, "議事録", None, o.user.id).map(_.toOption.get)
        doc = (ids: List[UUID]) => Json.obj("type" -> "doc".asJson, "content" -> Json.arr(Json.obj("type" -> "paragraph".asJson,
                "content" -> ids.map(i => Json.obj("type" -> "mention".asJson, "attrs" -> Json.obj("userId" -> i.toString.asJson, "label" -> "x".asJson))).asJson)))
        r1 <- env.docs.saveCompaction(p.id, Array[Byte](0, 0), doc(List(m.user.id)), "", Set.empty, Set.empty, o.user.id)
        r2 <- env.docs.saveCompaction(p.id, Array[Byte](0, 0), doc(List(m.user.id)), "", Set.empty, Set.empty, o.user.id)
        r3 <- env.docs.saveCompaction(p.id, Array[Byte](0, 0), doc(Nil), "", Set.empty, Set.empty, o.user.id)
        r4 <- env.docs.saveCompaction(p.id, Array[Byte](0, 0), doc(List(m.user.id, UUID.randomUUID())), "", Set.empty, Set.empty, o.user.id)
      yield
        assertEquals(r1.newMentions, Set(m.user.id))
        assertEquals(r2.newMentions, Set.empty[UUID]) // 既に書かれている人には再通知しない
        assertEquals(r3.newMentions, Set.empty[UUID])
        assertEquals(r4.newMentions, Set(m.user.id))  // 消して書き直したら通知する。存在しないユーザーは無視
    }
  }

  test("audience lists who can access a page and why") {
    run {
      val grp = s"cm-team-${UUID.randomUUID().toString.take(6)}"
      for
        (o, ot)  <- user("au-owner")
        (g, _)   <- user("au-member", List(grp))
        (v, vt)  <- user("au-viewer")
        (a, _)   <- user("au-admin", List("cm-admins"))
        ws <- env.store.createWorkspace("閲覧者", o.user.id)
        parent <- env.store.createPage(ws.id, None, Placement.Last, "親", None, o.user.id).map(_.toOption.get)
        child  <- env.store.createPage(ws.id, Some(parent.id), Placement.Last, "子", None, o.user.id).map(_.toOption.get)
        gid <- env.ops.searchDirectory(grp).map(_.find(_.tpe == PrincipalType.Group).get.id)
        _ <- env.store.setPageAcl(parent.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.Group, gid), Role.Edit))))
        _ <- view(child.id, v)
        (s0, _) <- call(vt, Method.GET, s"/api/pages/${child.id}/audience")
        _ = assertEquals(s0, Status.Forbidden) // 閲覧者には見せない
        (s1, list) <- call(ot, Method.GET, s"/api/pages/${child.id}/audience")
        _ = assertEquals(s1, Status.Ok)
        byId = list.asArray.get.map(j => j.hcursor.get[String]("userId").toOption.get -> (j.hcursor.get[String]("role").toOption.get, j.hcursor.get[String]("reason").toOption.get)).toMap
      yield
        assertEquals(byId(o.user.id.toString), ("edit", "ワークスペースの共有設定（個別の共有）"))
        assertEquals(byId(g.user.id.toString), ("edit", s"親ページ「親」の共有設定（グループ「$grp」）"))
        assertEquals(byId(v.user.id.toString), ("view", "このページの共有設定（個別の共有）"))
        assertEquals(byId(a.user.id.toString), ("edit", "管理者"))
    }
  }

  test("search filters by label, period and author, also without keywords") {
    run {
      val tag = UUID.randomUUID().toString.take(6)
      for
        (o, ot) <- user("sf-owner")
        (x, xt) <- user("sf-other")
        ws <- env.store.createWorkspace(s"絞り込み$tag", o.user.id)
        _  <- env.store.setWorkspaceAcl(ws.id, Some(Role.Edit), Nil)
        a  <- env.store.createPage(ws.id, None, Placement.Last, s"手順A$tag", None, o.user.id).map(_.toOption.get)
        b  <- env.store.createPage(ws.id, None, Placement.Last, s"手順B$tag", None, x.user.id).map(_.toOption.get)
        _  <- env.store.setLabels(a.id, List("運用"))
        titlesOf = (q: String) => call(ot, Method.GET, s"/api/search?workspaceId=${ws.id}&$q").map(_._2.asArray.get.toList.map(_.hcursor.downField("page").get[String]("title").toOption.get).toSet)
        t1 <- titlesOf(s"q=${java.net.URLEncoder.encode("手順", "UTF-8")}&label=${java.net.URLEncoder.encode("運用", "UTF-8")}")
        t2 <- titlesOf("q=&mine=true")
        t3 <- titlesOf("q=&days=7")
        t4 <- titlesOf("q=")
      yield
        assertEquals(t1, Set(s"手順A$tag"))
        assertEquals(t2, Set(s"手順A$tag"))
        assertEquals(t3, Set(s"手順A$tag", s"手順B$tag"))
        assertEquals(t4, Set.empty[String]) // 語も絞り込みもなければ何も返さない
    }
  }
