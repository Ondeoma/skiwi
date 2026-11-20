package skiwi.api

import sbirds.db.*
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

/** ゴミ箱・ワークスペース管理・被リンク・ラベル・最近・通知・公開リンクの制限のテスト。TEST_DATABASE_URL が必要。 */
class FeaturesSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("feat-admins"), cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, ops: OpsStore, app: HttpApp[IO])

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
        val docs = new DocStore(xa)
        val audit = new Audit(ops)
        val attSt = new AttachmentStore(xa)
        val blobs = new InMemoryBlobStore
        val attach = new AttachmentRoutes(store, attSt, Some(blobs), sess)
        val shares = new ShareStore(xa)
        val app =
          (new ApiRoutes(store, docs, ops, sess, audit, IO.unit, Some(shares)).routes <+>
            new TrashRoutes(store, sess, audit, Some(blobs)).routes <+>
            new PageExtraRoutes(store, docs, ops, sess, audit).routes <+>
            new ShareRoutes(store, shares, docs, attSt, attach, sess, audit).routes).orNotFound
        Env(store, docs, ops, app)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("features", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String, groups: List[String] = Nil): IO[(AuthSession, String)] = {
    val token = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.syncGroups(u.id, groups)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield (AuthSession(u, Nil, false), token)
  }

  private def call(token: Option[String],
                   method: Method,
                   path: String,
                   body: Option[Json] = None,
                   cookies: List[(String, String)] = Nil): IO[(Status, Json, Response[IO])] = {
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    cookies.foreach((k, v) => req = req.addCookie(k, v))
    body.foreach(b => req = req.withEntity(b))
    env.app.run(req).flatMap(r =>
      r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null), r))
    )
  }

  private def titles(j: Json, path: String*): List[String] =
    j.asArray.get.toList.map(x =>
      path.foldLeft(x.hcursor: io.circe.ACursor)(_.downField(_)).get[String]("title").toOption.get
    )

  private def page(ws: UUID, title: String, parent: Option[UUID], by: UUID) =
    env.store.createPage(ws, parent, Placement.Last, title, None, by).map(_.toOption.get)

  test("trash: delete, list, restore (to root if parent is gone) and purge") {
    run {
      for {
        (o, ot) <- user("trash-owner")
        ws <- env.store.createWorkspace("ゴミ箱", o.user.id)
        a <- page(ws.id, "親", None, o.user.id)
        b <- page(ws.id, "子", Some(a.id), o.user.id)
        c <- page(ws.id, "孫", Some(b.id), o.user.id)
        // 先に孫だけ消し、次に親ごと消す
        _ <- call(Some(ot), Method.DELETE, s"/api/pages/${c.id}")
        _ <- call(Some(ot), Method.DELETE, s"/api/pages/${a.id}")
        (_, trash, _) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/trash")
        _ = assertEquals(titles(trash), List("親", "孫"))
        _ = assertEquals(trash.asArray.get.head.hcursor.get[Long]("count"), Right(2L)) // 親と子
        // 孫だけ戻す → 親が削除済みなので直下へ
        (s1, r1, _) <- call(Some(ot), Method.POST, s"/api/trash/pages/${c.id}/restore")
        _ = assertEquals(s1, Status.Ok)
        _ = assertEquals(r1.hcursor.get[Option[String]]("parentId"), Right(None))
        // 親を戻すと子も戻る
        _ <- call(Some(ot), Method.POST, s"/api/trash/pages/${a.id}/restore")
        (_, list, _) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/pages")
        _ = assertEquals(titles(list).toSet, Set("親", "子", "孫"))
        // 完全に削除
        _ <- call(Some(ot), Method.DELETE, s"/api/pages/${a.id}")
        (s2, _, _) <- call(Some(ot), Method.DELETE, s"/api/trash/pages/${a.id}")
        _ = assertEquals(s2, Status.NoContent)
        (_, trash2, _) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/trash")
        _ = assertEquals(trash2.asArray.get.size, 0)
        // 他人は触れない
        (x, xt) <- user("trash-other")
        _ <- call(Some(ot), Method.DELETE, s"/api/pages/${c.id}")
        (s3, _, _) <- call(Some(xt), Method.POST, s"/api/trash/pages/${c.id}/restore")
        _ = assertEquals(s3, Status.NotFound)
      } yield ()
    }
  }

  test("trash: restoring to the root needs edit on the workspace; deleting needs edit on every descendant") {
    run {
      for {
        (o, ot) <- user("perm-owner")
        (m, mt) <- user("perm-member")
        ws <- env.store.createWorkspace("権限", o.user.id)
        edit = (u: AuthSession) => AclEntry(Principal(PrincipalType.User, u.user.id), Role.Edit)
        // 制限ページ R（m は編集できる）とその子 X
        r <- page(ws.id, "制限", None, o.user.id)
        _ <- env.store.setPageAcl(r.id, PageAcl(false, List(edit(o), edit(m))))
        x <- page(ws.id, "子", Some(r.id), o.user.id)
        (d1, _, _) <- call(Some(mt), Method.DELETE, s"/api/pages/${x.id}")
        _ = assertEquals(d1, Status.NoContent)
        _ <- call(Some(ot), Method.DELETE, s"/api/pages/${r.id}")
        // 親が消えているので直下に戻る → 直下に作れない m は戻せない（ワークスペース全体に見えてしまうため）
        (s1, _, _) <- call(Some(mt), Method.POST, s"/api/trash/pages/${x.id}/restore")
        _ = assertEquals(s1, Status.Forbidden)
        (s2, _, _) <- call(Some(ot), Method.POST, s"/api/trash/pages/${x.id}/restore")
        _ = assertEquals(s2, Status.Ok)
        // P（m は編集できる）の下に、m が見られない Q
        p <- page(ws.id, "親", None, o.user.id)
        _ <- env.store.setPageAcl(p.id, PageAcl(true, List(edit(m))))
        q <- page(ws.id, "非公開の子", Some(p.id), o.user.id)
        _ <- env.store.setPageAcl(q.id, PageAcl(false, List(edit(o))))
        (s3, _, _) <- call(Some(mt), Method.DELETE, s"/api/pages/${p.id}")
        _ = assertEquals(s3, Status.Forbidden)
        (s4, _, _) <- call(Some(ot), Method.DELETE, s"/api/pages/${p.id}")
        _ = assertEquals(s4, Status.NoContent)
        // 完全削除も同じ（m は親を戻せても、見られない子ごと完全には消せない）
        (s5, _, _) <- call(Some(mt), Method.DELETE, s"/api/trash/pages/${p.id}")
        _ = assertEquals(s5, Status.Forbidden)
        (s6, _, _) <- call(Some(ot), Method.DELETE, s"/api/trash/pages/${p.id}")
        _ = assertEquals(s6, Status.NoContent)
      } yield ()
    }
  }

  test("workspace rename, delete to trash, restore") {
    run {
      for {
        (o, ot) <- user("ws-owner")
        (v, vt) <- user("ws-viewer")
        ws <- env.store.createWorkspace("旧名", o.user.id)
        p <- page(ws.id, "中身", None, o.user.id)
        _ <- env.store.setWorkspaceAcl(
          ws.id,
          None,
          List(
            AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit),
            AclEntry(Principal(PrincipalType.User, v.user.id), Role.View)
          )
        )
        (sv, _, _) <- call(Some(vt), Method.PATCH, s"/api/workspaces/${ws.id}", Some(Json.obj("name" -> "x".asJson)))
        _ = assertEquals(sv, Status.Forbidden)
        (s1, r1, _) <-
          call(Some(ot), Method.PATCH, s"/api/workspaces/${ws.id}", Some(Json.obj("name" -> " 新名 ".asJson)))
        _ = assertEquals((s1, r1.hcursor.get[String]("name")), (Status.Ok, Right("新名")))
        (s2, _, _) <- call(Some(ot), Method.DELETE, s"/api/workspaces/${ws.id}")
        _ = assertEquals(s2, Status.NoContent)
        (_, wss, _) <- call(Some(ot), Method.GET, "/api/workspaces")
        _ = assert(!wss.asArray.get.exists(_.hcursor.get[String]("id").contains(ws.id.toString)))
        (sp, _, _) <- call(Some(ot), Method.GET, s"/api/pages/${p.id}")
        _ = assertEquals(sp, Status.NotFound)
        (_, tw, _) <- call(Some(ot), Method.GET, "/api/trash/workspaces")
        _ = assert(tw.asArray.get.exists(_.hcursor.get[String]("name").contains("新名")))
        (_, tv, _) <- call(Some(vt), Method.GET, "/api/trash/workspaces")
        _ = assert(!tv.asArray.get.exists(_.hcursor.get[String]("id").contains(ws.id.toString)), "閲覧者には出さない")
        (s3, _, _) <- call(Some(ot), Method.POST, s"/api/trash/workspaces/${ws.id}/restore")
        _ = assertEquals(s3, Status.Ok)
        (sp2, _, _) <- call(Some(ot), Method.GET, s"/api/pages/${p.id}")
        _ = assertEquals(sp2, Status.Ok)
      } yield ()
    }
  }

  test("backlinks, labels, recent pages and notifications") {
    run {
      for {
        (o, ot) <- user("bl-owner")
        (w, wt) <- user("bl-watcher")
        ws <- env.store.createWorkspace("被リンク", o.user.id)
        target <- page(ws.id, "対象", None, o.user.id)
        src1 <- page(ws.id, "参照元1", None, o.user.id)
        src2 <- page(ws.id, "参照元2（非公開）", None, o.user.id)
        content = (t: UUID) =>
          Json.obj(
            "type" -> "doc".asJson,
            "content" -> Json.arr(Json.obj(
              "type" -> "paragraph".asJson,
              "content" -> Json.arr(
                Json.obj("type" -> "pageLink".asJson, "attrs" -> Json.obj("pageId" -> t.toString.asJson))
              )
            ))
          )
        _ <-
          env.docs.saveCompaction(src1.id, Array[Byte](0, 0), content(target.id), "", Set.empty, Set.empty, o.user.id)
        // 通常のリンク(URL)も被リンクになる
        link = Json.obj(
          "type" -> "doc".asJson,
          "content" -> Json.arr(Json.obj(
            "type" -> "paragraph".asJson,
            "content" -> Json.arr(
              Json.obj(
                "type" -> "text".asJson,
                "text" -> "x".asJson,
                "marks" -> Json.arr(Json.obj(
                  "type" -> "link".asJson,
                  "attrs" -> Json.obj("href" -> s"https://skiwi.example.com/w/${ws.id}/p/${target.id}".asJson)
                ))
              )
            )
          ))
        )
        _ <- env.docs.saveCompaction(src2.id, Array[Byte](0, 0), link, "", Set.empty, Set.empty, o.user.id)
        (_, bl, _) <- call(Some(ot), Method.GET, s"/api/pages/${target.id}/backlinks")
        _ = assertEquals(titles(bl).toSet, Set("参照元1", "参照元2（非公開）"))
        // 見えない参照元は出さない
        _ <- env.store.setPageAcl(
          target.id,
          PageAcl(true, List(AclEntry(Principal(PrincipalType.User, w.user.id), Role.View)))
        )
        _ <- env.store.setPageAcl(
          src1.id,
          PageAcl(true, List(AclEntry(Principal(PrincipalType.User, w.user.id), Role.View)))
        )
        (_, bl2, _) <- call(Some(wt), Method.GET, s"/api/pages/${target.id}/backlinks")
        _ = assertEquals(titles(bl2), List("参照元1"))
        // リンクを消すと被リンクも消える
        _ <- env.docs.saveCompaction(src1.id, Array[Byte](0, 0), Json.obj(), "", Set.empty, Set.empty, o.user.id)
        (_, bl3, _) <- call(Some(ot), Method.GET, s"/api/pages/${target.id}/backlinks")
        _ = assertEquals(titles(bl3), List("参照元2（非公開）"))

        // ラベル
        (s1, l1, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${target.id}/labels",
          Some(Json.obj("labels" -> List(" 手順書 ", "#API", "api", "").asJson))
        )
        _ = assertEquals((s1, l1.hcursor.get[List[String]]("labels")), (Status.Ok, Right(List("API", "手順書"))))
        (sw, _, _) <-
          call(Some(wt), Method.PUT, s"/api/pages/${target.id}/labels", Some(Json.obj("labels" -> List("x").asJson)))
        _ = assertEquals(sw, Status.Forbidden)
        (_, pl, _) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/pages")
        _ = assertEquals(
          pl.asArray.get.find(
            _.hcursor.get[String]("id").contains(target.id.toString)
          ).flatMap(_.hcursor.get[List[String]]("labels").toOption),
          Some(List("API", "手順書"))
        )

        // 最近見た・編集した
        _ <- call(Some(wt), Method.POST, s"/api/pages/${src1.id}/visit")
        _ <- call(Some(wt), Method.POST, s"/api/pages/${target.id}/visit")
        (_, rv, _) <- call(Some(wt), Method.GET, "/api/recent?kind=visited")
        _ = assertEquals(titles(rv, "page").take(2), List("対象", "参照元1"))
        _ <- env.ops.setWatch(target.id, w.user.id, on = true)
        _ <- env.ops.enqueuePageEdited(target.id, Set(o.user.id), 60, 600)
        (_, re, _) <- call(Some(ot), Method.GET, "/api/recent?kind=edited")
        _ = assertEquals(titles(re, "page").headOption, Some("対象"))

        // 通知: 2回の編集は未読1件にまとまる。共有通知も届く
        _ <- env.ops.enqueuePageEdited(target.id, Set(o.user.id), 60, 600)
        _ <- env.ops.enqueueShared(src1.id, Set(w.user.id), "bl-owner", "view")
        (_, n1, _) <- call(Some(wt), Method.GET, "/api/notifications")
        _ = assertEquals(n1.hcursor.get[Int]("unread"), Right(2))
        kinds = n1.hcursor.get[List[Json]]("items").toOption.get.map(_.hcursor.get[String]("kind").toOption.get)
        _ = assertEquals(kinds.sorted, List("page_edited", "shared"))
        _ <- call(Some(wt), Method.POST, "/api/notifications/read", Some(Json.obj()))
        (_, n2, _) <- call(Some(wt), Method.GET, "/api/notifications")
        _ = assertEquals(n2.hcursor.get[Int]("unread"), Right(0))
      } yield ()
    }
  }

  test("public link expiry, password and admin list") {
    run {
      for {
        (o, ot) <- user("pw-owner")
        (_, at) <- user("pw-admin", List("feat-admins"))
        ws <- env.store.createWorkspace("公開制限", o.user.id)
        p <- page(ws.id, "パスワード付き", None, o.user.id)
        (s0, _, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("password" -> "abc".asJson)))
        _ = assertEquals(s0, Status.BadRequest) // 4文字未満
        (_, sh, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("password" -> "ひみつ123".asJson)))
        token = sh.hcursor.get[String]("token").toOption.get
        _ = assertEquals(sh.hcursor.get[Boolean]("hasPassword"), Right(true))
        (g1, e1, _) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals((g1, e1.hcursor.get[String]("error")), (Status.Unauthorized, Right("password_required")))
        (g2, _, _) <- call(None, Method.POST, s"/api/public/$token/unlock", Some(Json.obj("password" -> "違う".asJson)))
        _ = assertEquals(g2, Status.Forbidden)
        (g3, _, r3) <-
          call(None, Method.POST, s"/api/public/$token/unlock", Some(Json.obj("password" -> "ひみつ123".asJson)))
        _ = assertEquals(g3, Status.NoContent)
        cookie = r3.cookies.head
        (g4, _, _) <- call(None, Method.GET, s"/api/public/$token", cookies = List(cookie.name -> cookie.content))
        _ = assertEquals(g4, Status.Ok)
        // 他の設定を変えてもパスワードはそのまま。パスワードを外すと誰でも見られる
        (_, sh2, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("includeChildren" -> false.asJson)))
        _ = assertEquals(sh2.hcursor.get[Boolean]("hasPassword"), Right(true))
        _ <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("password" -> Json.Null)))
        (g5, _, _) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals(g5, Status.Ok)
        // 有効期限切れは 410
        _ <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${p.id}/share",
          Some(Json.obj("expiresAt" -> Instant.now().minusSeconds(5).asJson))
        )
        (g6, _, _) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals(g6, Status.Gone)
        _ <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("expiresAt" -> Json.Null)))
        (g7, _, _) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals(g7, Status.Ok)
        // 管理者一覧
        (sa, _, _) <- call(Some(ot), Method.GET, "/api/admin/shares")
        _ = assertEquals(sa, Status.Forbidden)
        (sb, list, _) <- call(Some(at), Method.GET, "/api/admin/shares")
        _ = assertEquals(sb, Status.Ok)
        _ = assert(list.asArray.get.exists(_.hcursor.get[String]("pageId").contains(p.id.toString)))
      } yield ()
    }
  }

  test("page favorites and importing content into an empty page") {
    run {
      for {
        (o, ot) <- user("fp-owner")
        (v, vt) <- user("fp-viewer")
        ws <- env.store.createWorkspace("お気に入りと取り込み", o.user.id)
        a <- page(ws.id, "よく見る", None, o.user.id)
        b <- page(ws.id, "取り込み先", None, o.user.id)
        (s1, _, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${a.id}/favorite", Some(Json.obj("favorite" -> true.asJson)))
        _ = assertEquals(s1, Status.Ok)
        (_, favs, _) <- call(Some(ot), Method.GET, "/api/favorites/pages")
        _ = assertEquals(titles(favs, "page"), List("よく見る"))
        // 見えないページは登録できない
        (s2, _, _) <-
          call(Some(vt), Method.PUT, s"/api/pages/${a.id}/favorite", Some(Json.obj("favorite" -> true.asJson)))
        _ = assertEquals(s2, Status.NotFound)
        // 削除したページは一覧から消える
        _ <- call(Some(ot), Method.DELETE, s"/api/pages/${a.id}")
        (_, favs2, _) <- call(Some(ot), Method.GET, "/api/favorites/pages")
        _ = assertEquals(titles(favs2, "page"), Nil)

        // 取り込み: 空のページにだけ入れられる
        body =
          Json.obj("ydoc" -> "AAA=".asJson, "content" -> Json.obj("type" -> "doc".asJson), "text" -> "取り込んだ本文".asJson)
        (sv, _, _) <- call(Some(vt), Method.POST, s"/api/pages/${b.id}/import", Some(body))
        _ = assertEquals(sv, Status.NotFound)
        (si, _, _) <- call(Some(ot), Method.POST, s"/api/pages/${b.id}/import", Some(body))
        _ = assertEquals(si, Status.NoContent)
        (_, c, _) <- call(Some(ot), Method.GET, s"/api/pages/${b.id}/content")
        _ = assertEquals(c.hcursor.get[String]("text"), Right("取り込んだ本文"))
        (sc, _, _) <- call(Some(ot), Method.POST, s"/api/pages/${b.id}/import", Some(body))
        _ = assertEquals(sc, Status.Conflict)
      } yield ()
    }
  }
}
