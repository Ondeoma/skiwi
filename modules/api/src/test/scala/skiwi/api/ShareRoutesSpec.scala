package skiwi.api

import cats.effect.*
import cats.syntax.semigroupk.*
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.multipart.{Multiparts, Part}
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.*
import skiwi.storage.InMemoryBlobStore

import java.time.Instant
import java.util.UUID

/** ゲスト向け公開リンクのテスト。TEST_DATABASE_URL が必要。 */
class ShareRoutesSpec extends DbSuite:

  private val apiCfg = ApiConfig(adminGroups = Set.empty, cookieSecure = false)

  final case class Env(store: Store, docs: DocStore, app: HttpApp[IO], disabled: HttpApp[IO], smallUpload: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for
        _  <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      yield
        val store  = new Store(xa)
        val ops    = new OpsStore(xa)
        val sess   = new SessionAuth(store, ops, apiCfg)
        val docs   = new DocStore(xa)
        val attSt  = new AttachmentStore(xa)
        val attach = new AttachmentRoutes(store, attSt, Some(new InMemoryBlobStore), sess)
        val shares = new ShareStore(xa)
        val audit  = new Audit(ops)
        def share(on: Boolean) = new ShareRoutes(store, shares, docs, attSt, attach, sess, audit, on)
        val api = new ApiRoutes(store, docs, ops, sess, audit, IO.unit, Some(shares))
        val small = new AttachmentRoutes(store, attSt, Some(new InMemoryBlobStore), sess, maxBytes = 1024)
        Env(store, docs, (share(true).routes <+> attach.routes <+> api.routes).orNotFound, share(false).routes.orNotFound,
          (small.routes <+> api.routes).orNotFound)

  private val fixture = ResourceSuiteLocalFixture("share", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private def user(name: String): IO[(AuthSession, String)] =
    val token = Crypto.randomToken()
    for
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", None, name)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    yield (AuthSession(u, Nil, false), token)

  private def call(token: Option[String], method: Method, path: String, body: Option[Json] = None, app: HttpApp[IO] = env.app): IO[(Status, Json, Headers)] =
    var req = Request[IO](method, Uri.unsafeFromString(path))
    token.foreach(t => req = req.addCookie("skiwi_session", t))
    body.foreach(b => req = req.withEntity(b))
    app.run(req).flatMap(r => r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null), r.headers)))

  private def upload(token: String, pageId: UUID): IO[UUID] =
    Multiparts.forSync[IO].flatMap { mps =>
      val part = Part.fileData[IO]("file", "a.png", Entity.stream(Stream.emits("PNG".getBytes).covary[IO]), `Content-Type`(MediaType.image.png))
      mps.multipart(Vector(part)).flatMap { mp =>
        val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/pages/$pageId/attachments"))
          .withEntity(mp).putHeaders(mp.headers).addCookie("skiwi_session", token)
        env.app.run(req).flatMap(_.as[Json]).map(j => UUID.fromString(j.hcursor.get[String]("id").toOption.get))
      }
    }

  private def ids(j: Json): List[String] = j.hcursor.get[List[Json]]("pages").toOption.get.map(_.hcursor.get[String]("id").toOption.get)

  test("guests can read a published page tree, but nothing else") {
    run {
      for
        (owner, ot) <- user("pub-owner")
        (viewer, vt) <- user("pub-viewer")
        ws   <- env.store.createWorkspace("公開テスト", owner.user.id)
        root <- env.store.createPage(ws.id, None, Placement.Last, "マニュアル", Some("📘"), owner.user.id).map(_.toOption.get)
        kid  <- env.store.createPage(ws.id, Some(root.id), Placement.Last, "はじめに", None, owner.user.id).map(_.toOption.get)
        priv <- env.store.createPage(ws.id, Some(root.id), Placement.Last, "社外秘", None, owner.user.id).map(_.toOption.get)
        privKid <- env.store.createPage(ws.id, Some(priv.id), Placement.Last, "社外秘の子", None, owner.user.id).map(_.toOption.get)
        other <- env.store.createPage(ws.id, None, Placement.Last, "別のページ", None, owner.user.id).map(_.toOption.get)
        // 社外秘は権限の継承を止めている → 公開範囲に含めない
        _ <- env.store.setPageAcl(priv.id, PageAcl(false, List(AclEntry(Principal(PrincipalType.User, owner.user.id), Role.Edit))))
        // 閲覧者は公開設定できない
        _ <- env.store.setPageAcl(root.id, PageAcl(true, List(AclEntry(Principal(PrincipalType.User, viewer.user.id), Role.View))))
        (sv, _, _) <- call(Some(vt), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj()))
        _ = assertEquals(sv, Status.Forbidden)

        att <- upload(ot, kid.id)
        content = Json.obj("type" -> "doc".asJson, "content" -> Json.arr(
          Json.obj("type" -> "image".asJson, "attrs" -> Json.obj("src" -> s"/api/attachments/$att".asJson)),
          Json.obj("type" -> "paragraph".asJson, "content" -> Json.arr(
            Json.obj("type" -> "pageLink".asJson, "attrs" -> Json.obj("pageId" -> root.id.toString.asJson, "title" -> "マニュアル".asJson)),
            Json.obj("type" -> "pageLink".asJson, "attrs" -> Json.obj("pageId" -> other.id.toString.asJson, "title" -> "別のページ".asJson))
          ))
        ))
        _ <- env.docs.saveCompaction(kid.id, Array[Byte](0, 0), content, "本文", Set.empty, Set.empty, owner.user.id)

        // 公開前
        (s0, info0, _) <- call(Some(ot), Method.GET, s"/api/pages/${root.id}/share")
        _ = assertEquals(s0, Status.Ok)
        _ = assertEquals(info0.hcursor.get[Option[Json]]("share").toOption.flatten, None)

        (s1, sh, _) <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("includeChildren" -> true.asJson)))
        _ = assertEquals(s1, Status.Ok)
        token = sh.hcursor.get[String]("token").toOption.get

        // 再設定してもリンクは変わらない
        (_, sh2, _) <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("includeChildren" -> true.asJson)))
        _ = assertEquals(sh2.hcursor.get[String]("token"), Right(token))

        // 子ページの設定画面には「親の公開に含まれる」と出る。社外秘の子には出ない
        (_, kidInfo, _) <- call(Some(ot), Method.GET, s"/api/pages/${kid.id}/share")
        _ = assertEquals(kidInfo.hcursor.downField("inherited").get[String]("pageId"), Right(root.id.toString))
        (_, privKidInfo, _) <- call(Some(ot), Method.GET, s"/api/pages/${privKid.id}/share")
        _ = assert(privKidInfo.hcursor.downField("inherited").focus.forall(_.isNull), privKidInfo.noSpaces)

        // ツリーの印
        (_, list, _) <- call(Some(ot), Method.GET, s"/api/workspaces/${ws.id}/pages")
        _ = assertEquals(list.asArray.get.filter(_.hcursor.get[Boolean]("published").contains(true)).map(_.hcursor.get[String]("id").toOption.get), Vector(root.id.toString))

        // ゲスト（Cookieなし）
        (g1, tree, h1) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals(g1, Status.Ok)
        _ = assertEquals(h1.get(org.typelevel.ci.CIString("X-Robots-Tag")).map(_.head.value), Some("noindex, nofollow"))
        _ = assertEquals(ids(tree), List(root.id.toString, kid.id.toString))
        (g2, page, _) <- call(None, Method.GET, s"/api/public/$token/pages/${kid.id}")
        _ = assertEquals(g2, Status.Ok)
        blocks = page.hcursor.downField("content").get[List[Json]]("content").toOption.get
        _ = assertEquals(blocks.head.hcursor.downField("attrs").get[String]("src"), Right(s"/api/public/$token/attachments/$att"))
        links = blocks(1).hcursor.get[List[Json]]("content").toOption.get.map(_.hcursor.downField("attrs"))
        _ = assertEquals(links(0).get[String]("title"), Right("マニュアル"))
        _ = assertEquals(links(1).get[Option[String]]("title"), Right(None)) // 公開範囲外のタイトルは伏せる
        (g3, _, _) <- call(None, Method.GET, s"/api/public/$token/attachments/$att")
        _ = assertEquals(g3, Status.Ok)

        // 公開範囲外・不正なリンク
        (n1, _, _) <- call(None, Method.GET, s"/api/public/$token/pages/${priv.id}")
        (n2, _, _) <- call(None, Method.GET, s"/api/public/$token/pages/${other.id}")
        (n3, _, _) <- call(None, Method.GET, s"/api/public/not-a-token")
        (n4, _, _) <- call(None, Method.GET, s"/api/pages/${kid.id}") // 通常のAPIはログインが必要
        (n5, _, _) <- call(None, Method.GET, s"/api/public/$token", app = env.disabled) // 機能を無効にした場合
        _ = assertEquals(List(n1, n2, n3, n4, n5), List(Status.NotFound, Status.NotFound, Status.NotFound, Status.Unauthorized, Status.NotFound))

        // 子ページを含めない
        _ <- call(Some(ot), Method.PUT, s"/api/pages/${root.id}/share", Some(Json.obj("includeChildren" -> false.asJson)))
        (_, only, _) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals(ids(only), List(root.id.toString))
        (n6, _, _) <- call(None, Method.GET, s"/api/public/$token/pages/${kid.id}")
        _ = assertEquals(n6, Status.NotFound)

        // 公開停止
        (sd, _, _) <- call(Some(ot), Method.DELETE, s"/api/pages/${root.id}/share")
        _ = assertEquals(sd, Status.NoContent)
        (n7, _, _) <- call(None, Method.GET, s"/api/public/$token")
        _ = assertEquals(n7, Status.NotFound)
      yield ()
    }
  }

  test("Content-Length のない（chunked の）添付のアップロードも上限で止める") {
    run {
      for
        (owner, ot) <- user("up-owner")
        ws <- env.store.createWorkspace("上限", owner.user.id)
        p  <- env.store.createPage(ws.id, None, Placement.Last, "p", None, owner.user.id).map(_.toOption.get)
        st <- Multiparts.forSync[IO].flatMap { mps =>
                val big  = Part.fileData[IO]("file", "big.bin", Entity.stream(Stream.emits(Array.fill[Byte](200 * 1024)(1)).covary[IO]),
                  `Content-Type`(MediaType.application.`octet-stream`))
                mps.multipart(Vector(big)).flatMap { mp =>
                  val req = Request[IO](Method.POST, Uri.unsafeFromString(s"/api/pages/${p.id}/attachments"))
                    .withEntity(mp).putHeaders(mp.headers).addCookie("skiwi_session", ot)
                  val chunked = req.withBodyStream(req.body).removeHeader[org.http4s.headers.`Content-Length`]
                  env.smallUpload.run(chunked).map(_.status)
                }
              }
      yield assertEquals(st, Status.PayloadTooLarge)
    }
  }
