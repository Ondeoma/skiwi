package skiwi.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import sbirds.auth.Crypto
import skiwi.db.*

import java.time.Instant
import java.util.{Base64, UUID}

/** フェーズ4: APIトークン、監査ログ、版履歴、共有・ウォッチ通知、SCIM。TEST_DATABASE_URL がある場合のみ実行。 */
class Phase4Spec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("skiwi-admins"), cookieSecure = false)
  private val scimToken = "scim-test-token-0123456789"

  final case class Env(xa: doobie.Transactor[IO],
                       store: Store,
                       docs: DocStore,
                       ops: OpsStore,
                       app: HttpApp[IO],
                       changed: Ref[IO, Int])

  private val res: Resource[IO, Env] = cfg match {
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for {
        _ <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
        changed <- Resource.eval(Ref.of[IO, Int](0))
      } yield {
        val store = new Store(xa)
        val docs = new DocStore(xa)
        val ops = new OpsStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val bump = changed.update(_ + 1)
        val routes = new ApiRoutes(store, docs, ops, sess, audit, bump).routes <+>
          new OpsRoutes(ops, sess, audit).routes <+>
          new ScimRoutes(new ScimStore(xa), scimToken, audit, bump).routes
        val app = cats.data.Kleisli { (req: Request[IO]) =>
          routes.orNotFound.run(req).recoverWith { case ReadOnlyTokenError => IO.pure(Response[IO](Status.Forbidden)) }
        }
        Env(xa, store, docs, ops, app, changed)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("env", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  private final case class Who(token: String, id: UUID, name: String)

  private def login(name: String, groups: List[String] = Nil, email: Option[String] = None): IO[Who] = {
    val token = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(s"$name-${UUID.randomUUID()}", email, name)
      _ <- env.store.syncGroups(u.id, groups)
      _ <- env.store.createSession(Crypto.sha256(token), u.id, Instant.now().plusSeconds(600))
    } yield Who(token, u.id, name)
  }

  private enum Auth {
    case Cookie(
      t: String)
    case Bearer(
      t: String)
    case None
  }

  private def call(auth: Auth, method: Method, path: String, body: Option[Json] = None): IO[(Status, Json)] = {
    var req = Request[IO](method, Uri.unsafeFromString(path))
    auth match {
      case Auth.Cookie(t) => req = req.addCookie("skiwi_session", t)
      case Auth.Bearer(t) => req = req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))
      case Auth.None => ()
    }
    body.foreach(b => req = req.withEntity(b))
    env.app.run(req).flatMap(r =>
      r.bodyText.compile.string.map(s => (r.status, io.circe.parser.parse(s).getOrElse(Json.Null)))
    )
  }

  private def as(w: Who) = Auth.Cookie(w.token)

  private def scim(method: Method,
                   path: String,
                   body: Option[Json] = None,
                   token: String = scimToken): IO[(Status, Json)] =
    call(Auth.Bearer(token), method, path, body)

  private def str(j: Json, f: String*) = f.foldLeft(j.hcursor: io.circe.ACursor)(_.downField(_)).as[String].toOption
  private def uid(j: Json) = UUID.fromString(str(j, "id").get)

  private def newPage(w: Who): IO[(UUID, UUID)] =
    for {
      (_, ws) <-
        call(as(w), Method.POST, "/api/workspaces", Some(Json.obj("name" -> s"WS-${UUID.randomUUID()}".asJson)))
      (_, p) <- call(as(w), Method.POST, s"/api/workspaces/${uid(ws)}/pages", Some(Json.obj("title" -> "議事録".asJson)))
    } yield (uid(ws), uid(p))

  private def auditFor(target: UUID): IO[List[String]] =
    sql"select action from audit_log where target_id = ${target.toString} order by id".query[String].to[List].transact(
      env.xa
    )

  // ------------------------------------------------------------------ API tokens

  test("API tokens: create, use, read-only scope, revoke, cannot manage tokens") {
    run {
      for {
        alice <- login("トークン 太郎")
        (_, page) <- newPage(alice)
        (st, created) <- call(
          as(alice),
          Method.POST,
          "/api/tokens",
          Some(Json.obj("name" -> "CI用".asJson, "scope" -> "write".asJson, "expiresInDays" -> 30.asJson))
        )
        _ = assertEquals(st, Status.Created)
        tok = str(created, "token").get
        _ = assert(tok.startsWith("skiwi_"))
        (st, me) <- call(Auth.Bearer(tok), Method.GET, "/api/me")
        _ = assertEquals((st, str(me, "name")), (Status.Ok, Some("トークン 太郎")))
        // write トークンで編集できる
        (st, _) <- call(Auth.Bearer(tok), Method.PATCH, s"/api/pages/$page", Some(Json.obj("title" -> "API更新".asJson)))
        _ = assertEquals(st, Status.Ok)
        // トークンでトークンは作れない
        (st, _) <- call(
          Auth.Bearer(tok),
          Method.POST,
          "/api/tokens",
          Some(Json.obj("name" -> "x".asJson, "scope" -> "write".asJson))
        )
        _ = assertEquals(st, Status.Forbidden)
        // read トークンは GET のみ
        (_, ro) <-
          call(as(alice), Method.POST, "/api/tokens", Some(Json.obj("name" -> "閲覧".asJson, "scope" -> "read".asJson)))
        rtok = str(ro, "token").get
        (st, _) <- call(Auth.Bearer(rtok), Method.GET, s"/api/pages/$page")
        _ = assertEquals(st, Status.Ok)
        (st, _) <- call(Auth.Bearer(rtok), Method.PATCH, s"/api/pages/$page", Some(Json.obj("title" -> "x".asJson)))
        _ = assertEquals(st, Status.Forbidden)
        // 一覧に平文トークンは含まれない
        (_, list) <- call(as(alice), Method.GET, "/api/tokens")
        _ = assertEquals(list.asArray.map(_.size), Some(2))
        _ = assert(!list.noSpaces.contains(tok))
        // 失効後は使えない
        (st, _) <- call(as(alice), Method.DELETE, s"/api/tokens/${str(created, "info", "id").get}")
        _ = assertEquals(st, Status.NoContent)
        (st, _) <- call(Auth.Bearer(tok), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Unauthorized)
        (st, _) <- call(Auth.Bearer("skiwi_bogus"), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Unauthorized)
        // 監査: トークン経由の編集は tokenId 付きで記録される
        detail <-
          sql"select via, detail->>'tokenId' from audit_log where action = 'page.update' and target_id = ${page.toString}"
            .query[(String, Option[String])].unique.transact(env.xa)
        _ = assertEquals(detail, ("token", Some(str(created, "info", "id").get)))
        acts <-
          sql"select action from audit_log where actor_id = ${alice.id} order by id".query[String].to[List].transact(
            env.xa
          )
      } yield assert(
        List(
          "workspace.create",
          "page.create",
          "token.create",
          "page.update",
          "token.create",
          "token.revoke"
        ).forall(acts.contains),
        acts
      )
    }
  }

  // ------------------------------------------------------------------ audit

  test("audit log is append-only and admin-only") {
    run {
      for {
        user <- login("一般")
        admin <- login("管理者", List("skiwi-admins"))
        _ <- newPage(user)
        upd <- sql"update audit_log set action = 'x' where id = (select max(id) from audit_log)".update.run.transact(
          env.xa
        ).attempt
        _ = assert(upd.isLeft, "update must be rejected")
        del <- sql"delete from audit_log".update.run.transact(env.xa).attempt
        _ = assert(del.isLeft, "delete must be rejected")
        (st, _) <- call(as(user), Method.GET, "/api/admin/audit")
        _ = assertEquals(st, Status.Forbidden)
        (st, list) <- call(as(admin), Method.GET, "/api/admin/audit?action=page.&limit=5")
      } yield {
        assertEquals(st, Status.Ok)
        val acts = list.asArray.get.flatMap(j => str(j, "action"))
        assert(acts.nonEmpty && acts.forall(_.startsWith("page.")), acts)
        assert(acts.size <= 5)
      }
    }
  }

  // ------------------------------------------------------------------ versions

  test("versions: automatic on compaction (interval), manual, restore with backup") {
    run {
      for {
        alice <- login("版 花子")
        bob <- login("版 次郎")
        (_, page) <- newPage(alice)
        _ <- call(
          as(alice),
          Method.PUT,
          s"/api/pages/$page/acl",
          Some(Json.obj(
            "inherit" -> true.asJson,
            "entries" -> Json.arr(
              Json.obj(
                "principalType" -> "user".asJson,
                "principalId" -> bob.id.toString.asJson,
                "role" -> "view".asJson
              )
            )
          ))
        )
        doc =
          (t: String) => s"""{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$t"}]}]}"""
        // 1回目の圧縮で版ができ、直後の圧縮では（間隔内なので）できない
        v1 <- env.docs.saveCompaction(
          page,
          Array[Byte](1, 2),
          io.circe.parser.parse(doc("初版")).toOption.get,
          "初版",
          Set.empty,
          Set.empty,
          alice.id,
          Set(bob.id)
        )
        v2 <- env.docs.saveCompaction(
          page,
          Array[Byte](1, 3),
          io.circe.parser.parse(doc("第二版")).toOption.get,
          "第二版",
          Set.empty,
          Set.empty,
          alice.id
        )
        _ = assertEquals((v1.versioned, v2.versioned), (true, false))
        // 間隔0なら作られる。ただし内容が同じなら作らない
        v3 <- env.docs.saveCompaction(
          page,
          Array[Byte](1, 3),
          io.circe.parser.parse(doc("第二版")).toOption.get,
          "第二版",
          Set.empty,
          Set.empty,
          alice.id,
          versionEvery = scala.concurrent.duration.Duration.Zero
        )
        v4 <- env.docs.saveCompaction(
          page,
          Array[Byte](1, 3),
          io.circe.parser.parse(doc("第二版")).toOption.get,
          "第二版",
          Set.empty,
          Set.empty,
          alice.id,
          versionEvery = scala.concurrent.duration.Duration.Zero
        )
        _ = assertEquals((v3.versioned, v4.versioned), (true, false))
        (st, vs) <- call(as(bob), Method.GET, s"/api/pages/$page/versions")
        _ = assertEquals(st, Status.Ok)
        list = vs.asArray.get
        _ = assertEquals(list.size, 2)
        first = list.last
        _ = assertEquals(first.hcursor.get[List[String]]("authors"), Right(List("版 次郎", "版 花子").sorted))
        (st, vc) <- call(as(bob), Method.GET, s"/api/pages/$page/versions/${uid(first)}")
        _ = assertEquals((st, str(vc, "text")), (Status.Ok, Some("初版")))
        // 手動保存（ラベル付き）
        state = Json.obj(
          "ydoc" -> Base64.getEncoder.encodeToString(Array[Byte](9, 9)).asJson,
          "content" -> io.circe.parser.parse(doc("現在")).toOption.get,
          "text" -> "現在".asJson,
          "label" -> " リリース前 ".asJson
        )
        (st, mv) <- call(as(alice), Method.POST, s"/api/pages/$page/versions", Some(state))
        _ = assertEquals((st, str(mv, "label")), (Status.Created, Some("リリース前")))
        (st, _) <-
          call(as(alice), Method.POST, s"/api/pages/$page/versions", Some(state.mapObject(_.add("ydoc", "".asJson))))
        _ = assertEquals(st, Status.BadRequest)
        // 復元: 現在の状態が「復元前の状態」として退避され、復元する版の本文が返る
        (st, rv) <- call(as(alice), Method.POST, s"/api/pages/$page/versions/${uid(first)}/restore", Some(state))
        _ = assertEquals(st, Status.Ok)
        _ = assertEquals(rv.hcursor.downField("content").downField("type").as[String], Right("doc"))
        (_, vs2) <- call(as(alice), Method.GET, s"/api/pages/$page/versions")
        _ = assertEquals(vs2.asArray.get.headOption.flatMap(str(_, "label")), Some("復元前の状態"))
        // 閲覧者は保存・復元できない
        (st, _) <- call(as(bob), Method.POST, s"/api/pages/$page/versions/${uid(first)}/restore", Some(state))
        _ = assertEquals(st, Status.Forbidden)
        // 現在の本文API
        (st, cur) <- call(as(bob), Method.GET, s"/api/pages/$page/content")
        _ = assertEquals((st, str(cur, "text")), (Status.Ok, Some("第二版")))
        acts <- auditFor(page)
      } yield assert(acts.containsSlice(List("page.version.create", "page.restore")), acts)
    }
  }

  // ------------------------------------------------------------------ share / watch / outbox

  test("sharing enqueues notifications for new grantees (groups expanded) and triggers revalidation") {
    run {
      for {
        owner <- login("共有 元", email = Some("owner@example.com"))
        g = s"営業-${UUID.randomUUID()}"
        m1 <- login("メンバー1", List(g), Some("m1@example.com"))
        m2 <- login("メンバー2", List(g), Some("m2@example.com"))
        (_, page) <- newPage(owner)
        gid <- sql"select id from user_group where external_id = $g".query[UUID].unique.transact(env.xa)
        before <- env.changed.get
        body = Json.obj(
          "inherit" -> true.asJson,
          "entries" -> Json.arr(
            Json.obj("principalType" -> "group".asJson, "principalId" -> gid.toString.asJson, "role" -> "view".asJson)
          )
        )
        (st, _) <- call(as(owner), Method.PUT, s"/api/pages/$page/acl", Some(body))
        _ = assertEquals(st, Status.NoContent)
        after <- env.changed.get
        _ = assert(after > before, "access change hook must be called")
        rows <- sql"select user_id, detail->>'role' from mail_outbox where page_id = $page and kind = 'shared'"
          .query[(UUID, String)].to[List].transact(env.xa)
        _ = assertEquals(rows.map(_._1).toSet, Set(m1.id, m2.id))
        // 同じ設定を再送しても新規付与はないので積まれない。編集への格上げは積まれる
        _ <- call(as(owner), Method.PUT, s"/api/pages/$page/acl", Some(body))
        _ <- call(
          as(owner),
          Method.PUT,
          s"/api/pages/$page/acl",
          Some(Json.obj(
            "inherit" -> true.asJson,
            "entries" -> Json.arr(
              Json.obj("principalType" -> "group".asJson, "principalId" -> gid.toString.asJson, "role" -> "edit".asJson)
            )
          ))
        )
        n <- sql"select count(*) from mail_outbox where page_id = $page and kind = 'shared'".query[Int].unique.transact(
          env.xa
        )
        _ = assertEquals(n, 4)
        // ACL表示には名前が付く
        (_, acl) <- call(as(owner), Method.GET, s"/api/pages/$page/acl")
      } yield assertEquals(acl.hcursor.downField("entries").downArray.get[String]("name"), Right(g))
    }
  }

  test("watch: creator auto-watches; edits enqueue one merged mail per watcher, excluding editors") {
    run {
      for {
        owner <- login("ウォッチ 主")
        ed1 <- login("編集 一")
        ed2 <- login("編集 二")
        (_, page) <- newPage(owner)
        (_, w) <- call(as(owner), Method.GET, s"/api/pages/$page/watch")
        _ = assertEquals(w.hcursor.get[Boolean]("watching"), Right(true))
        _ <- env.ops.setWatch(page, ed1.id, on = true)
        _ <- env.ops.enqueuePageEdited(page, Set(ed1.id), 300, 1800)
        _ <- env.ops.enqueuePageEdited(page, Set(ed2.id), 300, 1800)
        rows <- sql"select user_id, detail::text from mail_outbox where page_id = $page and kind = 'page_edited'"
          .query[(UUID, String)].to[List].transact(env.xa)
        _ = assertEquals(rows.map(_._1).toSet, Set(owner.id, ed1.id)) // ed1 は2回目の編集者ではないので受け取る
        ownerRow = rows.find(_._1 == owner.id).get._2
        _ = assert(ownerRow.contains(ed1.id.toString) && ownerRow.contains(ed2.id.toString), ownerRow)
        (_, _) <- call(as(owner), Method.PUT, s"/api/pages/$page/watch", Some(Json.obj("watching" -> false.asJson)))
        (_, w2) <- call(as(owner), Method.GET, s"/api/pages/$page/watch")
      } yield assertEquals(w2.hcursor.get[Boolean]("watching"), Right(false))
    }
  }

  test("directory search finds users and groups by Japanese name") {
    run {
      for {
        u <- login(s"検索対象 ${UUID.randomUUID().toString.take(4)}", List("開発部"), Some("kensaku@example.com"))
        (st, j) <- call(as(u), Method.GET, s"/api/directory?q=${java.net.URLEncoder.encode("検索対象", "UTF-8")}")
        (_, g) <- call(as(u), Method.GET, s"/api/directory?q=${java.net.URLEncoder.encode("開発", "UTF-8")}")
      } yield {
        assertEquals(st, Status.Ok)
        assert(j.asArray.get.exists(e => str(e, "id").contains(u.id.toString)))
        assert(g.asArray.get.exists(e => str(e, "principalType").contains("group") && str(e, "name").contains("開発部")))
      }
    }
  }

  // ------------------------------------------------------------------ SCIM

  test("SCIM: auth, users (create/link/filter/patch/deactivate/delete)") {
    run {
      val un = s"taro-${UUID.randomUUID().toString.take(8)}"
      for {
        (st, _) <- scim(Method.GET, "/scim/v2/Users", token = "wrong")
        _ = assertEquals(st, Status.Unauthorized)
        (st, _) <- call(Auth.None, Method.GET, "/scim/v2/Users")
        _ = assertEquals(st, Status.Unauthorized)
        // ログインが先行したユーザー（sub=userName）に紐づく
        pre <- env.store.upsertUser(un, None, "仮の名前")
        tok = Crypto.randomToken()
        _ <- env.store.createSession(Crypto.sha256(tok), pre.id, Instant.now().plusSeconds(600))
        (st, u) <- scim(
          Method.POST,
          "/scim/v2/Users",
          Some(Json.obj(
            "schemas" -> List("urn:ietf:params:scim:schemas:core:2.0:User").asJson,
            "userName" -> un.asJson,
            "externalId" -> "ak-uuid-1".asJson,
            "name" -> Json.obj("givenName" -> "太郎".asJson, "familyName" -> "山田".asJson),
            "emails" -> Json.arr(Json.obj("value" -> "taro@example.com".asJson, "primary" -> true.asJson)),
            "active" -> true.asJson
          ))
        )
        _ = assertEquals(st, Status.Created)
        _ = assertEquals(uid(u), pre.id)
        _ = assertEquals((str(u, "displayName"), str(u, "emails")), (Some("山田 太郎"), None))
        _ = assertEquals(u.hcursor.downField("emails").downArray.get[String]("value"), Right("taro@example.com"))
        (st, l) <-
          scim(Method.GET, s"/scim/v2/Users?filter=${java.net.URLEncoder.encode(s"""userName eq "$un"""", "UTF-8")}")
        _ = assertEquals((st, l.hcursor.get[Int]("totalResults")), (Status.Ok, Right(1)))
        (st, _) <- scim(Method.GET, s"/scim/v2/Users?filter=${java.net.URLEncoder.encode("""name co "x"""", "UTF-8")}")
        _ = assertEquals(st, Status.BadRequest)
        // PATCH で表示名変更、その後 active=false（文字列 "False" も受け付ける）
        (st, p1) <- scim(
          Method.PATCH,
          s"/scim/v2/Users/${pre.id}",
          Some(Json.obj("Operations" -> Json.arr(
            Json.obj("op" -> "replace".asJson, "value" -> Json.obj("displayName" -> "山田 太郎（営業）".asJson))
          )))
        )
        _ = assertEquals(
          (st, str(p1, "displayName"), p1.hcursor.get[Boolean]("active")),
          (Status.Ok, Some("山田 太郎（営業）"), Right(true))
        )
        (_, me) <- call(Auth.Cookie(tok), Method.GET, "/api/me")
        _ = assertEquals(str(me, "name"), Some("山田 太郎（営業）"))
        before <- env.changed.get
        (st, p2) <- scim(
          Method.PATCH,
          s"/scim/v2/Users/${pre.id}",
          Some(Json.obj("Operations" -> Json.arr(
            Json.obj("op" -> "replace".asJson, "path" -> "active".asJson, "value" -> "False".asJson)
          )))
        )
        _ = assertEquals((st, p2.hcursor.get[Boolean]("active")), (Status.Ok, Right(false)))
        after <- env.changed.get
        _ = assert(after > before)
        // 無効化でセッションは消える
        (st, _) <- call(Auth.Cookie(tok), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Unauthorized)
        // PUT で再有効化
        (st, p3) <- scim(
          Method.PUT,
          s"/scim/v2/Users/${pre.id}",
          Some(Json.obj("userName" -> un.asJson, "displayName" -> "山田 太郎".asJson, "active" -> true.asJson))
        )
        _ = assertEquals((st, p3.hcursor.get[Boolean]("active")), (Status.Ok, Right(true)))
        (st, _) <- scim(Method.DELETE, s"/scim/v2/Users/${pre.id}")
        _ = assertEquals(st, Status.NoContent)
        (st, g) <- scim(Method.GET, s"/scim/v2/Users/${pre.id}")
        _ = assertEquals((st, g.hcursor.get[Boolean]("active")), (Status.Ok, Right(false)))
        (st, _) <- scim(Method.GET, s"/scim/v2/Users/${UUID.randomUUID()}")
        _ = assertEquals(st, Status.NotFound)
        (st, _) <- scim(Method.POST, "/scim/v2/Users", Some(Json.obj("displayName" -> "名無し".asJson)))
        _ = assertEquals(st, Status.BadRequest)
        acts <- auditFor(pre.id)
      } yield assertEquals(
        acts,
        List("scim.user.create", "scim.user.update", "scim.user.deactivate", "scim.user.update", "scim.user.delete")
      )
    }
  }

  test("SCIM: groups (create with members, patch add/remove, rename, delete cleans ACL) drive permissions") {
    run {
      val gname = s"SCIM営業-${UUID.randomUUID().toString.take(6)}"
      for {
        owner <- login("グループ 管理")
        (_, u1) <- scim(
          Method.POST,
          "/scim/v2/Users",
          Some(Json.obj("userName" -> s"u1-${UUID.randomUUID()}".asJson, "displayName" -> "一郎".asJson))
        )
        (_, u2) <- scim(
          Method.POST,
          "/scim/v2/Users",
          Some(Json.obj("userName" -> s"u2-${UUID.randomUUID()}".asJson, "displayName" -> "二郎".asJson))
        )
        (st, g) <- scim(
          Method.POST,
          "/scim/v2/Groups",
          Some(Json.obj(
            "displayName" -> gname.asJson,
            "members" -> Json.arr(Json.obj("value" -> uid(u1).toString.asJson))
          ))
        )
        _ = assertEquals(st, Status.Created)
        gid = uid(g)
        _ = assertEquals(g.hcursor.downField("members").as[List[Json]].map(_.size), Right(1))
        (_, _) <- scim(
          Method.PATCH,
          s"/scim/v2/Groups/$gid",
          Some(Json.obj("Operations" -> Json.arr(
            Json.obj(
              "op" -> "add".asJson,
              "path" -> "members".asJson,
              "value" -> Json.arr(Json.obj("value" -> uid(u2).toString.asJson))
            ),
            Json.obj("op" -> "remove".asJson, "path" -> s"""members[value eq "${uid(u1)}"]""".asJson)
          )))
        )
        (_, g2) <- scim(Method.GET, s"/scim/v2/Groups/$gid")
        _ = assertEquals(
          g2.hcursor.downField("members").as[List[Json]].map(_.flatMap(str(_, "value"))),
          Right(List(uid(u2).toString))
        )
        (st, l) <- scim(
          Method.GET,
          s"/scim/v2/Groups?filter=${java.net.URLEncoder.encode(s"""displayName eq "$gname"""", "UTF-8")}&excludedAttributes=members"
        )
        _ = assertEquals((st, l.hcursor.get[Int]("totalResults")), (Status.Ok, Right(1)))
        _ = assert(l.hcursor.downField("Resources").downArray.downField("members").failed)
        // グループに権限を付けると、SCIMで入ったメンバーがページを見られる
        (_, page) <- newPage(owner)
        _ <- call(
          as(owner),
          Method.PUT,
          s"/api/pages/$page/acl",
          Some(Json.obj(
            "inherit" -> false.asJson,
            "entries" -> Json.arr(
              Json.obj("principalType" -> "group".asJson, "principalId" -> gid.toString.asJson, "role" -> "view".asJson)
            )
          ))
        )
        u2tok = Crypto.randomToken()
        _ <- env.store.createSession(Crypto.sha256(u2tok), uid(u2), Instant.now().plusSeconds(600))
        (st, _) <- call(Auth.Cookie(u2tok), Method.GET, s"/api/pages/$page")
        _ = assertEquals(st, Status.Ok)
        // 名前変更
        (st, _) <- scim(
          Method.PATCH,
          s"/scim/v2/Groups/$gid",
          Some(Json.obj("Operations" -> Json.arr(
            Json.obj("op" -> "replace".asJson, "path" -> "displayName".asJson, "value" -> s"$gname-改".asJson)
          )))
        )
        _ = assertEquals(st, Status.Ok)
        // 削除するとACLからも消え、見られなくなる
        (st, _) <- scim(Method.DELETE, s"/scim/v2/Groups/$gid")
        _ = assertEquals(st, Status.NoContent)
        (st, _) <- call(Auth.Cookie(u2tok), Method.GET, s"/api/pages/$page")
        _ = assertEquals(st, Status.NotFound)
        n <- sql"select count(*) from page_acl where principal_id = $gid".query[Int].unique.transact(env.xa)
      } yield assertEquals(n, 0)
    }
  }
}
