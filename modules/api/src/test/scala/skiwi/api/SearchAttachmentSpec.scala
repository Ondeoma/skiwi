package skiwi.api

import cats.effect.*
import cats.syntax.semigroupk.*
import fs2.Stream
import io.circe.Json
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.multipart.{Multiparts, Part}
import org.typelevel.ci.CIString
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*
import skiwi.storage.InMemoryBlobStore

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/** 検索(PGroonga)と添付ファイルのAPIテスト。TEST_DATABASE_URL（PGroonga入りのPostgreSQL）が必要。 */
class SearchAttachmentSpec extends DbSuite:

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, blobs: InMemoryBlobStore, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield
        val store = new Store(xa)
        val sess  = new SessionAuth(store, new OpsStore(xa), apiCfg)
        val blobs = new InMemoryBlobStore
        val app = (new SearchRoutes(store, new SearchStore(xa), sess).routes <+>
          new AttachmentRoutes(store, new AttachmentStore(xa), Some(blobs), sess, maxBytes = 1024).routes).orNotFound
        Env(store, new DocStore(xa), blobs, app)

  private val fixture = ResourceSuiteLocalFixture("env", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(AuthSession, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (AuthSession(u, Nil, false), token)

  private def get(token: String, path: String): IO[Response[IO]] =
    env.app.run(Request[IO](Method.GET, Uri.unsafeFromString(path)).addCookie("skiwi_session", token))

  private def searchTitles(token: String, q: String, ws: UUID): IO[List[(String, Option[String])]] =
    get(token, s"/api/search?workspaceId=$ws&q=" + java.net.URLEncoder.encode(q, "UTF-8"))
      .flatMap(_.as[Json])
      .map(_.asArray.get.toList.map(j => (j.hcursor.downField("page").get[String]("title").toOption.get, j.hcursor.get[String]("snippet").toOption)))

  private def upload(token: String, pageId: UUID, name: String, ctype: String, body: Array[Byte]): IO[Response[IO]] =
    Multiparts.forSync[IO].flatMap { mps =>
      val part = Part.fileData[IO]("file", name, Stream.emits(body).covary[IO], `Content-Type`(MediaType.unsafeParse(ctype)))
      mps.multipart(Vector(part)).flatMap { mp =>
        val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/pages/$pageId/attachments"))
          .withEntity(mp).putHeaders(mp.headers).addCookie("skiwi_session", token)
        env.app.run(req)
      }
    }

  test("Japanese full-text search with permission filtering and snippets") {
    run {
      for
        (owner, ot) <- user("owner")
        (other, xt) <- user("other")
        ws <- env.store.createWorkspace("検索", owner.user.id)
        p1 <- env.store.createPage(ws.id, None, Placement.Last, "議事録 2026年11月", None, owner.user.id).map(_.toOption.get)
        p2 <- env.store.createPage(ws.id, None, Placement.Last, "設計メモ", None, owner.user.id).map(_.toOption.get)
        p3 <- env.store.createPage(ws.id, None, Placement.Last, "秘密", None, owner.user.id).map(_.toOption.get)
        _ <- env.docs.saveCompaction(p1.id, Array[Byte](0, 0), Json.obj(), "本日は同時編集サーバーの設計について議論した。", Set.empty, Set.empty, owner.user.id)
        _ <- env.docs.saveCompaction(p2.id, Array[Byte](0, 0), Json.obj(), "ＰＧｒｏｏｎｇａで日本語の全文検索を行う。Scala実装。", Set.empty, Set.empty, owner.user.id)
        _ <- env.docs.saveCompaction(p3.id, Array[Byte](0, 0), Json.obj(), "同時編集の秘密の計画", Set.empty, Set.empty, owner.user.id)
        // other には p1, p2 だけ共有（p3 は見えない）
        _ <- env.store.setPageAcl(p1.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, other.user.id), Role.View))))
        _ <- env.store.setPageAcl(p2.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, other.user.id), Role.View))))

        r1 <- searchTitles(ot, "同時編集", ws.id)
        _ = assertEquals(r1.map(_._1).toSet, Set("議事録 2026年11月", "秘密"))
        _ = assert(r1.find(_._1 == "議事録 2026年11月").flatMap(_._2).exists(_.contains("<span class=\"keyword\">同時編集</span>")), r1.toString)

        r2 <- searchTitles(xt, "同時編集", ws.id)
        _ = assertEquals(r2.map(_._1), List("議事録 2026年11月")) // 権限のない「秘密」は除外

        // 全角/半角・大文字小文字の正規化
        r3 <- searchTitles(xt, "pgroonga", ws.id)
        _ = assertEquals(r3.map(_._1), List("設計メモ"))

        // 複数語はAND
        r4 <- searchTitles(ot, "同時編集 計画", ws.id)
        _ = assertEquals(r4.map(_._1), List("秘密"))

        // タイトル一致
        r5 <- searchTitles(xt, "議事録", ws.id)
        _ = assertEquals(r5.map(_._1), List("議事録 2026年11月"))

        // 特殊文字を含んでもエラーにならない
        bad <- get(xt, s"/api/search?workspaceId=${ws.id}&q=" + java.net.URLEncoder.encode("\"(OR) -* \\", "UTF-8"))
        _ = assertEquals(bad.status, Status.Ok)
        empty <- searchTitles(xt, "   ", ws.id)
        _ = assertEquals(empty, Nil)
      yield ()
    }
  }

  private def searchAll(token: String, q: String): IO[List[(String, Boolean)]] =
    get(token, "/api/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
      .flatMap(_.as[Json])
      .map(_.asArray.get.toList.map(j => (j.hcursor.downField("page").get[String]("title").toOption.get, j.hcursor.get[Boolean]("fuzzy").toOption.get)))

  private def searchWorkspaces(token: String, q: String): IO[List[(String, Boolean)]] =
    get(token, "/api/search/workspaces?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
      .flatMap(_.as[Json])
      .map(_.asArray.get.toList.map(j => (j.hcursor.get[String]("name").toOption.get, j.hcursor.get[Boolean]("fuzzy").toOption.get)))

  test("search ignores kana/width differences, finds near spellings, spans workspaces, and finds workspaces") {
    run {
      val tag = UUID.randomUUID().toString.take(6) // 他のテストのデータと区別する
      for
        (owner, ot) <- user("fz-owner")
        (other, xt) <- user("fz-other")
        w1 <- env.store.createWorkspace(s"開発チーム$tag", owner.user.id)
        w2 <- env.store.createWorkspace(s"総務$tag", owner.user.id)
        a <- env.store.createPage(w1.id, None, Placement.Last, s"サーバー移行手順$tag", None, owner.user.id).map(_.toOption.get)
        b <- env.store.createPage(w2.id, None, Placement.Last, s"すけじゅーる調整$tag", None, owner.user.id).map(_.toOption.get)
        c <- env.store.createPage(w2.id, None, Placement.Last, s"基盤$tag", None, owner.user.id).map(_.toOption.get)
        _ <- env.docs.saveCompaction(c.id, Array[Byte](0, 0), Json.obj(), s"Kubernetes クラスタの構築メモ $tag", Set.empty, Set.empty, owner.user.id)

        // ひらがな/カタカナ・半角カナ・長音の違いを無視（ワークスペースをまたいで検索）
        r1 <- searchAll(ot, s"スケジュール $tag")
        _ = assertEquals(r1, List((s"すけじゅーる調整$tag", false)))
        r2 <- searchAll(ot, s"ｻｰﾊﾞｰ $tag")
        _ = assertEquals(r2, List((s"サーバー移行手順$tag", false)))
        r3 <- searchAll(ot, s"さーばー いこう $tag")
        _ = assertEquals(r3, Nil) // 漢字の読み（移行＝いこう）までは扱わない
        // 英単語の途中からも一致
        r4 <- searchAll(ot, s"bernet $tag")
        _ = assertEquals(r4.map(_._1), List(s"基盤$tag"))
        // 誤字・表記ゆれはあいまい一致（fuzzy=true）として見つかる
        r5 <- searchAll(ot, s"Kubernetis $tag")
        _ = assertEquals(r5, List((s"基盤$tag", true)))
        r6 <- searchAll(ot, s"サーバ移項 $tag")
        _ = assertEquals(r6, List((s"サーバー移行手順$tag", true)))
        // 権限のないユーザーには見えない
        r7 <- searchAll(xt, s"スケジュール $tag")
        _ = assertEquals(r7, Nil)

        // ワークスペース名の検索（全角/半角・あいまい一致）
        s1 <- searchWorkspaces(ot, s"かいはつ $tag")
        _ = assertEquals(s1, Nil) // 読みは扱わない
        s2 <- searchWorkspaces(ot, s"開発 $tag")
        _ = assertEquals(s2, List((s"開発チーム$tag", false)))
        s3 <- searchWorkspaces(ot, s"開発ﾁｰﾑ $tag")
        _ = assertEquals(s3, List((s"開発チーム$tag", false)))
        s4 <- searchWorkspaces(ot, s"開発チーマ $tag")
        _ = assertEquals(s4, List((s"開発チーム$tag", true)))
        s5 <- searchWorkspaces(xt, s"開発 $tag")
        _ = assertEquals(s5, Nil)
      yield ()
    }
  }

  test("attachments: upload, download with Japanese filename, permissions, unsafe types, size limit") {
    run {
      for
        (owner, ot) <- user("owner")
        (viewer, vt) <- user("viewer")
        (_, st) <- user("stranger")
        ws <- env.store.createWorkspace("添付", owner.user.id)
        p <- env.store.createPage(ws.id, None, Placement.Last, "ページ", None, owner.user.id).map(_.toOption.get)
        _ <- env.store.setPageAcl(p.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, viewer.user.id), Role.View))))

        png = Array[Byte](0x89.toByte, 'P', 'N', 'G', 1, 2, 3)
        up <- upload(ot, p.id, "../スクリーン ショット.png", "image/png", png)
        _ = assertEquals(up.status, Status.Created)
        dto <- up.as[Json]
        id = dto.hcursor.get[String]("id").toOption.get
        _ = assertEquals(dto.hcursor.get[String]("filename"), Right("スクリーン ショット.png")) // パス成分は除去
        _ = assertEquals(dto.hcursor.get[Long]("size"), Right(png.length.toLong))

        // 閲覧者はダウンロード可（画像はインライン）
        dl <- get(vt, s"/api/attachments/$id")
        bytes <- dl.body.compile.to(Array)
        _ = assertEquals(dl.status, Status.Ok)
        _ = assert(bytes.sameElements(png))
        cd = dl.headers.get(CIString("Content-Disposition")).get.head.value
        _ = assert(cd.startsWith("inline;") && cd.contains("filename*=UTF-8''%E3%82%B9"), cd)
        _ = assertEquals(dl.headers.get(CIString("X-Content-Type-Options")).map(_.head.value), Some("nosniff"))

        // 閲覧者はアップロード不可、無関係ユーザーはダウンロード不可（存在を隠す）
        vu <- upload(vt, p.id, "a.txt", "text/plain", "x".getBytes)
        _ = assertEquals(vu.status, Status.Forbidden)
        sd <- get(st, s"/api/attachments/$id")
        _ = assertEquals(sd.status, Status.NotFound)

        // HTMLは必ずダウンロード扱い（同一オリジンでのXSS防止）
        hu <- upload(ot, p.id, "evil.html", "text/html", "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
        hid <- hu.as[Json].map(_.hcursor.get[String]("id").toOption.get)
        hd <- get(ot, s"/api/attachments/$hid")
        _ = assertEquals(hd.headers.get(CIString("Content-Type")).map(_.head.value), Some("application/octet-stream"))
        _ = assert(hd.headers.get(CIString("Content-Disposition")).get.head.value.startsWith("attachment;"))
        _ = assert(hd.headers.get(CIString("Content-Security-Policy")).get.head.value.startsWith("sandbox"))

        // サイズ上限（テストでは1KB）
        big <- upload(ot, p.id, "big.bin", "application/octet-stream", Array.fill[Byte](4096)(1))
        _ = assertEquals(big.status, Status.PayloadTooLarge)
        _ = assertEquals(env.blobs.keys.count(_.startsWith(s"pages/${p.id}/")), 2) // 失敗分は保存されない
      yield ()
    }
  }
