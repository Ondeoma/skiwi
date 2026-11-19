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

/** 公開版の固定・公開の予約・URL名・ゲスト向け検索・OGP、ページの複製、閲覧権限の申請。TEST_DATABASE_URL が必要。 */
class PublishingSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store,
                       docs: DocStore,
                       ops: OpsStore,
                       attSt: AttachmentStore,
                       blobs: InMemoryBlobStore,
                       shares: ShareRoutes,
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
        val docs = new DocStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val attSt = new AttachmentStore(xa)
        val blobs = new InMemoryBlobStore
        val shareR = new ShareRoutes(
          store,
          new ShareStore(xa),
          docs,
          attSt,
          new AttachmentRoutes(store, attSt, Some(blobs), sess),
          sess,
          audit,
          true,
          "https://skiwi.example.com"
        )
        val app =
          (shareR.routes <+>
            new PageCopyRoutes(store, attSt, Some(blobs), new AccessRequestStore(xa), ops, sess, audit).routes <+>
            new ApiRoutes(store, docs, ops, sess, audit).routes).orNotFound
        Env(store, docs, ops, attSt, blobs, shareR, app)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("publishing", res)
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

  /** 本文を保存する（サーバーは Yjs を解釈しないので、Yjs 状態はダミー） */
  private def setText(pageId: UUID, text: String, by: UUID, extra: List[Json] = Nil) = {
    val doc = Json.obj(
      "type" -> "doc".asJson,
      "content" ->
        (Json.obj(
          "type" -> "paragraph".asJson,
          "content" -> Json.arr(Json.obj("type" -> "text".asJson, "text" -> text.asJson))
        ) :: extra).asJson
    )
    env.docs.saveCompaction(pageId, Array[Byte](1, 2, 3), doc, text, Set.empty, Set.empty, by)
  }

  private def firstText(j: Json) =
    j.hcursor.downField(
      "content"
    ).downField("content").downArray.downField("content").downArray.get[String]("text").toOption

  test("pinned mode: guests see the published version until it is published again") {
    run {
      for {
        (o, ot) <- user("pin-owner")
        ws <- env.store.createWorkspace("公開版", o.user.id)
        p <- page(ws.id, "手順書", None, o.user.id)
        _ <- setText(p.id, "第1版の本文", o.user.id)
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("mode" -> "pinned".asJson)))
        token = sh.hcursor.get[String]("token").toOption.get
        (_, g1) <- call(None, Method.GET, s"/api/public/$token/pages/${p.id}")
        _ <- IO.sleep(scala.concurrent.duration.DurationInt(20).millis)
        _ <- setText(p.id, "第2版の本文", o.user.id)
        _ <- env.store.updatePage(p.id, Some("手順書（改訂）"), None)
        (_, g2) <- call(None, Method.GET, s"/api/public/$token/pages/${p.id}")
        (_, info) <- call(Some(ot), Method.GET, s"/api/pages/${p.id}/share")
        (_, pub) <- call(Some(ot), Method.POST, s"/api/pages/${p.id}/share/publish")
        (_, g3) <- call(None, Method.GET, s"/api/public/$token/pages/${p.id}")
        // 編集中の内容をそのまま公開に戻す
        (_, _) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("mode" -> "live".asJson)))
        _ <- setText(p.id, "第3版の本文", o.user.id)
        (_, g4) <- call(None, Method.GET, s"/api/public/$token/pages/${p.id}")
      } yield {
        assertEquals(sh.hcursor.get[String]("mode"), Right("pinned"))
        assert(sh.hcursor.get[String]("publishedAt").isRight)
        assertEquals(firstText(g1), Some("第1版の本文"))
        assertEquals(firstText(g2), Some("第1版の本文"))
        assertEquals(g2.hcursor.downField("page").get[String]("title"), Right("手順書"))
        assertEquals(info.hcursor.downField("share").get[Boolean]("pendingChanges"), Right(true))
        assertEquals(pub.hcursor.get[Boolean]("pendingChanges"), Right(false))
        assertEquals(firstText(g3), Some("第2版の本文"))
        assertEquals(g3.hcursor.downField("page").get[String]("title"), Right("手順書（改訂）"))
        assertEquals(firstText(g4), Some("第3版の本文"))
      }
    }
  }

  test("scheduled start: not visible before startsAt, and startsAt must be before expiresAt") {
    run {
      val later = Instant.now().plusSeconds(3600)
      for {
        (o, ot) <- user("sched-owner")
        ws <- env.store.createWorkspace("予約", o.user.id)
        p <- page(ws.id, "お知らせ", None, o.user.id)
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("startsAt" -> later.asJson)))
        token = sh.hcursor.get[String]("token").toOption.get
        (s1, b1) <- call(None, Method.GET, s"/api/public/$token")
        (s2, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${p.id}/share",
          Some(Json.obj("expiresAt" -> later.minusSeconds(60).asJson))
        )
        (_, _) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("startsAt" -> Json.Null)))
        (s3, _) <- call(None, Method.GET, s"/api/public/$token")
      } yield {
        assertEquals(sh.hcursor.get[Boolean]("notStarted"), Right(true))
        assertEquals(s1, Status.Forbidden)
        assertEquals(b1.hcursor.get[String]("error"), Right("not_started"))
        assert(b1.hcursor.get[String]("startsAt").isRight)
        assertEquals(s2, Status.BadRequest)
        assertEquals(s3, Status.Ok)
      }
    }
  }

  test("slugs: friendly URL for the link and for pages, validated and unique") {
    run {
      val slug = s"manual-${UUID.randomUUID().toString.take(8)}"
      for {
        (o, ot) <- user("slug-owner")
        ws <- env.store.createWorkspace("URL名", o.user.id)
        root <- page(ws.id, "マニュアル", None, o.user.id)
        child <- page(ws.id, "インストール", Some(root.id), o.user.id)
        other <- page(ws.id, "別の公開", None, o.user.id)
        _ <- setText(child.id, "手順の本文", o.user.id)
        (_, _) <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj()))
        (bad, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("slug" -> "Bad Slug!".asJson)))
        (_, sh) <-
          call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("slug" -> slug.toUpperCase.asJson)))
        (_, _) <- call(Some(ot), Method.PUT, s"/api/pages/${other.id}/share", Some(Json.obj()))
        (dup, _) <- call(Some(ot), Method.PUT, s"/api/pages/${other.id}/share", Some(Json.obj("slug" -> slug.asJson)))
        (ps, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${child.id}/guest-slug", Some(Json.obj("slug" -> "install".asJson)))
        (uuidSlug, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${child.id}/guest-slug",
          Some(Json.obj("slug" -> UUID.randomUUID().toString.asJson))
        )
        (_, tree) <- call(None, Method.GET, s"/api/public/$slug")
        (s1, c1) <- call(None, Method.GET, s"/api/public/$slug/pages/install")
        (_, info) <- call(Some(ot), Method.GET, s"/api/pages/${child.id}/share")
      } yield {
        assertEquals(bad, Status.BadRequest)
        assertEquals(sh.hcursor.get[String]("slug"), Right(slug))
        assertEquals(dup, Status.Conflict)
        assertEquals(ps, Status.Ok)
        assertEquals(uuidSlug, Status.BadRequest)
        assertEquals(tree.hcursor.get[String]("key"), Right(slug))
        assert(
          tree.hcursor.get[List[Json]]("pages").toOption.get.exists(_.hcursor.get[String]("slug").contains("install"))
        )
        assertEquals(s1, Status.Ok)
        assertEquals(firstText(c1), Some("手順の本文"))
        assertEquals(info.hcursor.get[String]("pageSlug"), Right("install"))
        assertEquals(info.hcursor.downField("inherited").get[String]("slug"), Right(slug))
      }
    }
  }

  test("guest search: kana/width insensitive, only within the published scope, with highlighted snippets") {
    run {
      for {
        (o, ot) <- user("gs-owner")
        ws <- env.store.createWorkspace("検索", o.user.id)
        root <- page(ws.id, "ガイド", None, o.user.id)
        a <- page(ws.id, "準備", Some(root.id), o.user.id)
        b <- page(ws.id, "秘密", Some(root.id), o.user.id)
        _ <- setText(a.id, "まずはインストール手順を確認します。<script>", o.user.id)
        _ <- setText(b.id, "インストールの内部メモ", o.user.id)
        _ <- env.store.setPageAcl(b.id, PageAcl(false, Nil)) // 公開範囲から外れる
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj()))
        token = sh.hcursor.get[String]("token").toOption.get
        (_, r1) <-
          call(None, Method.GET, s"/api/public/$token/search?q=${java.net.URLEncoder.encode("いんすとーる 確認", "UTF-8")}")
        (_, r2) <-
          call(None, Method.GET, s"/api/public/$token/search?q=${java.net.URLEncoder.encode("ｶﾞｲﾄﾞ", "UTF-8")}")
      } yield {
        val hits = r1.asArray.get
        assertEquals(hits.map(_.hcursor.downField("page").get[String]("title").toOption.get).toList, List("準備"))
        val snip = hits.head.hcursor.get[String]("snippet").toOption.get
        assert(snip.contains("<mark>インストール</mark>"), snip)
        assert(snip.contains("&lt;script&gt;"), snip)
        assertEquals(
          r2.asArray.get.headOption.flatMap(_.hcursor.downField("page").get[String]("title").toOption),
          Some("ガイド")
        )
      }
    }
  }

  test("OGP / search engines: head tags for public pages, noindex by default, sitemap lists indexable links only") {
    run {
      val html = "<html><head><title>Wiki</title></head><body></body></html>"
      for {
        (o, ot) <- user("og-owner")
        ws <- env.store.createWorkspace("OGP", o.user.id)
        p <- page(ws.id, "製品紹介 <ベータ>", None, o.user.id)
        img = Json.obj(
          "type" -> "image".asJson,
          "attrs" -> Json.obj("src" -> "/api/attachments/11111111-2222-3333-4444-555555555555".asJson)
        )
        _ <- setText(p.id, "この製品は 社内の手続きを 楽にします。", o.user.id, List(img))
        (_, sh) <- call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj()))
        token = sh.hcursor.get[String]("token").toOption.get
        h1 <- env.shares.headFor(token, None)
        (_, _) <- call(
          Some(ot),
          Method.PUT,
          s"/api/pages/${p.id}/share",
          Some(Json.obj("indexable" -> true.asJson, "description" -> "説明文です".asJson))
        )
        h2 <- env.shares.headFor(token, None)
        sitemap <-
          env.app.run(Request[IO](Method.GET, Uri.unsafeFromString("/sitemap.xml"))).flatMap(_.bodyText.compile.string)
        (_, _) <-
          call(Some(ot), Method.PUT, s"/api/pages/${p.id}/share", Some(Json.obj("password" -> "secret-pw".asJson)))
        h3 <- env.shares.headFor(token, None)
        sitemap2 <-
          env.app.run(Request[IO](Method.GET, Uri.unsafeFromString("/sitemap.xml"))).flatMap(_.bodyText.compile.string)
        hNone <- env.shares.headFor("no-such-link", None)
      } yield {
        val out1 = env.shares.renderHead(html, h1)
        assert(out1.contains("<title>製品紹介 &lt;ベータ&gt; - skiwi</title>"), out1)
        assert(out1.contains("""<meta name="robots" content="noindex, nofollow">"""), out1)
        assert(out1.contains("""<meta property="og:description" content="この製品は 社内の手続きを 楽にします。">"""), out1)
        assert(
          out1.contains(
            s"""<meta property="og:image" content="https://skiwi.example.com/api/public/$token/attachments/11111111-2222-3333-4444-555555555555">"""
          ),
          out1
        )
        val out2 = env.shares.renderHead(html, h2)
        assert(!out2.contains("noindex"), out2)
        assert(out2.contains("""content="説明文です""""), out2)
        assert(sitemap.startsWith("<?xml"), sitemap)
        assert(sitemap.contains(s"<loc>https://skiwi.example.com/s/$token</loc>"), sitemap)
        assertEquals(h3, None) // パスワード付きは内容を出さない
        assert(!sitemap2.contains(token))
        assert(env.shares.renderHead(html, hNone).contains("noindex"))
      }
    }
  }

  test("duplicate: copies the visible subtree with labels, styles, ACL and attachments; placed next to the original") {
    run {
      for {
        (o, ot) <- user("dup-owner")
        (v, vt) <- user("dup-viewer")
        ws <- env.store.createWorkspace("複製元", o.user.id)
        ws2 <- env.store.createWorkspace("複製先", v.user.id)
        _ <- env.store.setWorkspaceAcl(
          ws.id,
          None,
          List(
            AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit),
            AclEntry(Principal(PrincipalType.User, v.user.id), Role.Edit)
          )
        )
        root <- page(ws.id, "テンプレート", None, o.user.id)
        next <- page(ws.id, "次のページ", None, o.user.id)
        kid <- page(ws.id, "子", Some(root.id), o.user.id)
        hidden <- page(ws.id, "非公開の子", Some(root.id), o.user.id)
        _ <- env.store.setPageAcl(
          hidden.id,
          PageAcl(false, List(AclEntry(Principal(PrincipalType.User, o.user.id), Role.Edit)))
        )
        _ <- env.store.setLabels(root.id, List("手順"))
        _ <- env.store.setPageCss(kid.id, Some("p{color:red}"), false)
        tmp <- IO(java.nio.file.Files.createTempFile("dup", ".txt")).flatTap(f =>
          IO(java.nio.file.Files.writeString(f, "hello"))
        )
        _ <- env.blobs.put(s"pages/${kid.id}/a1", fs2.io.file.Path.fromNioPath(tmp), "text/plain")
        att <-
          env.attSt.insert(UUID.randomUUID(), kid.id, s"pages/${kid.id}/a1", "a.txt", "text/plain", 5, "x", o.user.id)
        (s1, d1) <- call(Some(vt), Method.POST, s"/api/pages/${root.id}/duplicate", Some(Json.obj()))
        (s2, d2) <- call(
          Some(vt),
          Method.POST,
          s"/api/pages/${root.id}/duplicate",
          Some(Json.obj("workspaceId" -> ws2.id.asJson, "includeChildren" -> false.asJson, "title" -> "別の場所".asJson))
        )
        newRoot = d1.hcursor.get[UUID]("rootId").toOption.get
        pairs = d1.hcursor.get[List[Json]]("pages").toOption.get.map(j =>
          j.hcursor.get[UUID]("from").toOption.get -> j.hcursor.get[UUID]("to").toOption.get
        ).toMap
        ps <- env.store.listPages(ws.id, o)
        lbl <- env.store.labels(newRoot)
        css <- env.store.pageCss(pairs(kid.id))
        atts <- env.attSt.byPages(List(pairs(kid.id)))
        (s3, _) <- call(
          Some(ot),
          Method.POST,
          s"/api/pages/${root.id}/duplicate",
          Some(Json.obj("workspaceId" -> ws2.id.asJson))
        ) // 複製先を編集できない
      } yield {
        assertEquals(s1, Status.Created)
        assertEquals(pairs.keySet, Set(root.id, kid.id)) // 閲覧できない子は複製しない
        val sorted = ps.map(_._1).filter(_.parentId.isEmpty).sortBy(_.position)
        assertEquals(sorted.map(_.title), List("テンプレート", "テンプレート のコピー", "次のページ"))
        assertEquals(lbl, List("手順"))
        assertEquals(css, Some((Some("p{color:red}"), false)))
        assertEquals(atts.map(_.filename), List("a.txt"))
        assert(env.blobs.keys.contains(atts.head.s3Key))
        assertEquals(
          d1.hcursor.get[List[Json]]("attachments").toOption.get.map(_.hcursor.get[UUID]("from").toOption.get),
          List(att.id)
        )
        assertEquals(s2, Status.Created)
        assertEquals(d2.hcursor.get[List[Json]]("pages").toOption.get.size, 1)
        assertEquals(d2.hcursor.get[UUID]("workspaceId"), Right(ws2.id))
        assertEquals(s3, Status.NotFound)
      }
    }
  }

  test("access requests: request, notify editors, approve to grant; existence of a page is not revealed") {
    run {
      for {
        (o, ot) <- user("ar-owner")
        (r, rt) <- user("ar-requester")
        ws <- env.store.createWorkspace("申請", o.user.id)
        p <- page(ws.id, "機密の手順", None, o.user.id)
        (s0, m0) <- call(Some(rt), Method.GET, s"/api/pages/${p.id}/access-requests/mine")
        (s1, b1) <- call(
          Some(rt),
          Method.POST,
          s"/api/pages/${p.id}/access-requests",
          Some(Json.obj("role" -> "view".asJson, "message" -> "確認したいです".asJson))
        )
        (sx, bx) <- call(Some(rt), Method.POST, s"/api/pages/${UUID.randomUUID()}/access-requests", Some(Json.obj()))
        (_, m1) <- call(Some(rt), Method.GET, s"/api/pages/${p.id}/access-requests/mine")
        notes <- env.ops.notifications(o.user.id, 10)
        (sl, _) <- call(Some(rt), Method.GET, s"/api/pages/${p.id}/access-requests")
        (_, list) <- call(Some(ot), Method.GET, s"/api/pages/${p.id}/access-requests")
        rid = list.asArray.get.head.hcursor.get[String]("id").toOption.get
        (sa, a) <-
          call(Some(ot), Method.POST, s"/api/access-requests/$rid/approve", Some(Json.obj("role" -> "view".asJson)))
        acc <- env.store.pageAccess(p.id, r)
        notesR <- env.ops.notifications(r.user.id, 10)
        (_, again) <-
          call(Some(rt), Method.POST, s"/api/pages/${p.id}/access-requests", Some(Json.obj("role" -> "view".asJson)))
      } yield {
        assertEquals(s0, Status.Ok)
        assertEquals(m0.hcursor.get[String]("status"), Right("none"))
        assertEquals(s1, Status.Accepted)
        assertEquals(b1.hcursor.get[String]("status"), Right("pending"))
        assertEquals((sx, bx.hcursor.get[String]("status")), (Status.Accepted, Right("pending"))) // 存在しないページでも同じ応答
        assertEquals(m1.hcursor.get[String]("status"), Right("pending"))
        assert(
          notes.exists(n => n.kind == "access_request" && n.pageId.contains(p.id) && n.detail.contains("確認したいです")),
          notes.toString
        )
        assertEquals(sl, Status.NotFound)
        assertEquals(sa, Status.Ok)
        assertEquals(a.hcursor.get[String]("status"), Right("approved"))
        assertEquals(acc.map(_._2), Some(Role.View))
        assert(notesR.exists(_.kind == "access_granted"))
        assertEquals(again.hcursor.get[String]("status"), Right("has_access"))
      }
    }
  }
}
