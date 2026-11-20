package skiwi.api

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.Authorization
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.*

import java.time.Instant
import java.util.UUID

/** エージェント専用アカウント: 管理者だけが作り・キーを発行・失効でき、そのキーで MCP と REST API を本人の権限で使える。TEST_DATABASE_URL が必要。 */
class AgentAccountsSpec extends DbSuite {

  private val apiCfg = ApiConfig(adminGroups = Set("agent-admins"), cookieSecure = false)

  final case class Env(store: Store, scim: ScimStore, app: HttpApp[IO])

  private val res: Resource[IO, Env] = cfg match {
    case None => Resource.eval(IO.raiseError[Env](new IllegalStateException("TEST_DATABASE_URL not set")))
    case Some(c) =>
      for {
        _ <- Resource.eval(Database.migrate(c))
        xa <- Database.transactor(c)
      } yield {
        val store = new Store(xa)
        val docs = new DocStore(xa)
        val ops = new OpsStore(xa)
        val sess = new SessionAuth(store, ops, apiCfg)
        val audit = new Audit(ops)
        val rest = new ApiRoutes(store, docs, ops, sess, audit, IO.unit).routes <+>
          new OpsRoutes(ops, sess, audit).routes
        val routes = rest <+> new McpRoutes(rest, sess, "https://skiwi.example.com").routes
        val app = cats.data.Kleisli { (req: Request[IO]) =>
          routes.orNotFound.run(req).recoverWith { case ReadOnlyTokenError => IO.pure(Response[IO](Status.Forbidden)) }
        }
        Env(store, new ScimStore(xa), app)
      }
  }

  private val fixture = ResourceSuiteLocalFixture("agents", res)
  override def munitFixtures = if cfg.isDefined then List(fixture) else Nil
  private def env = fixture()

  /** ログインした人（Cookie のセッション） */
  private def user(name: String, groups: List[String] = Nil): IO[(User, String)] = {
    val cookie = Crypto.randomToken()
    for {
      u <- env.store.upsertUser(
        s"$name-${UUID.randomUUID()}",
        Some(s"${UUID.randomUUID().toString.take(8)}@example.com"),
        name
      )
      _ <- if groups.nonEmpty then env.store.syncGroups(u.id, groups) else IO.unit
      _ <- env.store.createSession(Crypto.sha256(cookie), u.id, Instant.now().plusSeconds(600))
    } yield (u, cookie)
  }

  /** auth: Left(Cookie) か Right(API キー) */
  private def call(auth: Either[String, String],
                   method: Method,
                   path: String,
                   body: Option[Json] = None): IO[(Status, Json)] = {
    val base = Request[IO](method, Uri.unsafeFromString(path))
    val req0 = auth.fold(
      c => base.addCookie("skiwi_session", c),
      t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))
    )
    val req = body.fold(req0)(req0.withEntity(_))
    env.app.run(req).flatMap(r => r.bodyText.compile.string.map(s => (r.status, parse(s).getOrElse(Json.Null))))
  }

  private def tool(key: String, name: String, args: (String, Json)*): IO[(Status, Boolean, String)] =
    call(
      Right(key),
      Method.POST,
      "/mcp",
      Some(Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id" -> 1.asJson,
        "method" -> "tools/call".asJson,
        "params" -> Json.obj("name" -> name.asJson, "arguments" -> Json.obj(args*))
      ))
    ).map {
      (st, j) =>
        val r = j.hcursor.downField("result")
        (
          st,
          r.get[Boolean]("isError").getOrElse(false),
          r.downField("content").downN(0).get[String]("text").getOrElse("")
        )
    }

  test("管理者だけがエージェント専用アカウントを作り、キーを発行・失効できる。キーは本人の権限で MCP・REST に使え、無効化・失効で使えなくなる。ログインはできない") {
    run {
      val agentName = s"エージェント-${UUID.randomUUID().toString.take(8)}"
      for {
        (_, adminC) <- user("管理 花子", List("agent-admins"))
        (_, plainC) <- user("一般 太郎")
        // 一般の人は 403
        (st, _) <- call(Left(plainC), Method.GET, "/api/admin/agents")
        _ = assertEquals(st, Status.Forbidden)
        (st, _) <- call(Left(plainC), Method.POST, "/api/admin/agents", Some(Json.obj("name" -> "x".asJson)))
        _ = assertEquals(st, Status.Forbidden)
        // 名前の確認
        (st, _) <- call(Left(adminC), Method.POST, "/api/admin/agents", Some(Json.obj("name" -> "   ".asJson)))
        _ = assertEquals(st, Status.BadRequest)
        (st, _) <- call(Left(adminC), Method.POST, "/api/admin/agents", Some(Json.obj("name" -> "a\nb".asJson)))
        _ = assertEquals(st, Status.BadRequest)
        // 作る
        (st, created) <-
          call(Left(adminC), Method.POST, "/api/admin/agents", Some(Json.obj("name" -> s"  $agentName  ".asJson)))
        _ = assertEquals(st, Status.Created, created)
        agent = created.as[AgentDto].toOption.get
        _ = assertEquals(agent.name, agentName)
        _ = assert(!agent.disabled)
        // 管理者のスコープは発行できない（read・write だけ）
        (st, _) <- call(
          Left(adminC),
          Method.POST,
          s"/api/admin/agents/${agent.id}/keys",
          Some(Json.obj("name" -> "k".asJson, "scope" -> "admin".asJson))
        )
        _ = assertEquals(st, Status.BadRequest)
        // ふつうのユーザーにはキーを発行できない（404）
        (plainU, _) <- user("一般 次郎")
        (st, _) <- call(
          Left(adminC),
          Method.POST,
          s"/api/admin/agents/${plainU.id}/keys",
          Some(Json.obj("name" -> "k".asJson, "scope" -> "write".asJson))
        )
        _ = assertEquals(st, Status.NotFound)
        (st, _) <-
          call(Left(adminC), Method.PATCH, s"/api/admin/agents/${plainU.id}", Some(Json.obj("disabled" -> true.asJson)))
        _ = assertEquals(st, Status.NotFound)
        // 書き込みのキーを発行する（平文はこの応答だけ）
        (st, k) <- call(
          Left(adminC),
          Method.POST,
          s"/api/admin/agents/${agent.id}/keys",
          Some(Json.obj("name" -> "skakapo".asJson, "scope" -> "write".asJson))
        )
        _ = assertEquals(st, Status.Created, k)
        key = k.hcursor.get[String]("token").toOption.get
        keyId = k.hcursor.downField("info").get[UUID]("id").toOption.get
        (_, list) <- call(Left(adminC), Method.GET, "/api/admin/agents")
        listed = list.as[List[AgentDto]].toOption.get.find(_.id == agent.id).get
        _ = assertEquals(listed.keys.map(_.id), List(keyId))
        _ = assert(!list.noSpaces.contains("hash") && !list.noSpaces.contains(key))
        // 管理者の API キーでもエージェントは操作できない（Cookie のセッションだけ）
        (st, ak) <- call(
          Left(adminC),
          Method.POST,
          "/api/tokens",
          Some(Json.obj("name" -> "admin-key".asJson, "scope" -> "write".asJson))
        )
        _ = assertEquals(st, Status.Created)
        (st, _) <- call(Right(ak.hcursor.get[String]("token").toOption.get), Method.GET, "/api/admin/agents")
        _ = assertEquals(st, Status.Forbidden)
        // 管理者の API キーには管理者の権限が付かない（監査ログも読めず、/api/me でも管理者でない）
        (st, _) <- call(Right(ak.hcursor.get[String]("token").toOption.get), Method.GET, "/api/admin/audit")
        _ = assertEquals(st, Status.Forbidden)
        (_, me) <- call(Right(ak.hcursor.get[String]("token").toOption.get), Method.GET, "/api/me")
        _ = assertEquals(me.hcursor.get[Boolean]("isAdmin"), Right(false))
        // エージェントのキー自身も管理者の API には使えない
        (st, _) <- call(Right(key), Method.GET, "/api/admin/agents")
        _ = assertEquals(st, Status.Forbidden)
        // キーで MCP（tools/list）と REST が使える
        (st, tl) <- call(
          Right(key),
          Method.POST,
          "/mcp",
          Some(Json.obj("jsonrpc" -> "2.0".asJson, "id" -> 1.asJson, "method" -> "tools/list".asJson))
        )
        _ = assertEquals(st, Status.Ok)
        _ = assert(tl.hcursor.downField("result").downField("tools").values.exists(_.nonEmpty), tl)
        (st, me) <- call(Right(key), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Ok)
        _ = assertEquals(me.hcursor.get[String]("name").toOption, Some(agentName))
        // 権限はふつうの共有で与える（ディレクトリの検索で見つかる）
        (owner, ownerC) <- user("持ち主 三郎")
        (_, ws) <- call(Left(ownerC), Method.POST, "/api/workspaces", Some(Json.obj("name" -> "エージェントの試験".asJson)))
        wsId = ws.hcursor.get[String]("id").toOption.get
        (_, before, _) <- tool(key, "create_page", "workspaceId" -> wsId.asJson, "title" -> "まだ書けない".asJson)
        _ = assert(before, "権限を与える前は書けない")
        (_, dir) <-
          call(Left(ownerC), Method.GET, s"/api/directory?q=${java.net.URLEncoder.encode(agentName, "UTF-8")}")
        _ = assert(dir.asArray.exists(_.exists(_.hcursor.get[UUID]("id").toOption.contains(agent.id))), dir)
        (st, _) <- call(
          Left(ownerC),
          Method.PUT,
          s"/api/workspaces/$wsId/acl",
          Some(Json.obj(
            "defaultRole" -> Json.Null,
            "entries" -> List(
              AclEntryDto(PrincipalType.User, agent.id, Role.Edit),
              AclEntryDto(PrincipalType.User, owner.id, Role.Edit)
            ).asJson
          ))
        )
        _ = assertEquals(st, Status.NoContent)
        (st, err, made) <- tool(key, "create_page", "workspaceId" -> wsId.asJson, "title" -> "エージェントのメモ".asJson)
        _ = assert(st == Status.Ok && !err, made)
        // 無効にするとキーは使えない。有効に戻すと使える
        (st, d) <-
          call(Left(adminC), Method.PATCH, s"/api/admin/agents/${agent.id}", Some(Json.obj("disabled" -> true.asJson)))
        _ = assertEquals(st, Status.Ok)
        _ = assert(d.as[AgentDto].toOption.get.disabled)
        (st, _) <- call(Right(key), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Unauthorized)
        (st, _) <- call(
          Left(adminC),
          Method.PATCH,
          s"/api/admin/agents/${agent.id}",
          Some(Json.obj("disabled" -> false.asJson, "name" -> s"$agentName-2".asJson))
        )
        _ = assertEquals(st, Status.Ok)
        (st, _) <- call(Right(key), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Ok)
        // 失効させると使えない
        (st, _) <- call(Left(adminC), Method.DELETE, s"/api/admin/agents/${plainU.id}/keys/$keyId")
        _ = assertEquals(st, Status.NotFound)
        (st, _) <- call(Left(adminC), Method.DELETE, s"/api/admin/agents/${agent.id}/keys/$keyId")
        _ = assertEquals(st, Status.NoContent)
        (st, _) <- call(Right(key), Method.GET, "/api/me")
        _ = assertEquals(st, Status.Unauthorized)
        (_, list2) <- call(Left(adminC), Method.GET, "/api/admin/agents")
        _ = assert(list2.as[List[AgentDto]].toOption.get.find(_.id == agent.id).get.keys.forall(_.revoked))
        // ログインはできない（OIDC の sub が agent: でも入れない）・SCIM からは見えず乗っ取れない
        sub <- env.scim.listUsers(None, 1, 10000).map(_._2.map(_.userName))
        _ = assert(!sub.exists(AgentAccounts.isAgent))
        _ <- env.scim.getUser(agent.id).map(u => assertEquals(u, None))
        _ <- env.scim.deleteUser(agent.id).map(r => assert(!r))
        login <- env.store.upsertUser(s"agent:${agent.id}", Some("evil@example.com"), "なりすまし").attempt
        _ = assert(login.isLeft)
      } yield ()
    }
  }
}
