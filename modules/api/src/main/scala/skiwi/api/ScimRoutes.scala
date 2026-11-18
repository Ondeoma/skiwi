package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Authorization, `Content-Type`}
import skiwi.core.{AgentAccounts, uuidOpt}
import skiwi.db.{ScimGroup, ScimStore, ScimUser, ScimUserInput}

import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.UUID

/** SCIM 2.0 サーバー（IdP からのプッシュ型プロビジョニングを受ける）。
  * 認証は固定の Bearer トークン（SCIM_TOKEN）。対応: Users / Groups の作成・取得・一覧(eqフィルタ)・置換・PATCH・削除。
  * ユーザーの userName を OIDC の sub として扱う（IdP側でOIDCのsubjectをユーザー名にする）。 */
final class ScimRoutes(scim: ScimStore, token: String, audit: Audit, accessChanged: AccessChanged = IO.unit, baseUrl: String = "")
    extends Http4sDsl[IO]:

  private val UserSchema  = "urn:ietf:params:scim:schemas:core:2.0:User"
  private val GroupSchema = "urn:ietf:params:scim:schemas:core:2.0:Group"
  private val ListSchema  = "urn:ietf:params:scim:api:messages:2.0:ListResponse"
  private val ErrSchema   = "urn:ietf:params:scim:api:messages:2.0:Error"

  private val scimJson = `Content-Type`(MediaType.unsafeParse("application/scim+json"), Charset.`UTF-8`)

  private def json(status: Status, body: Json): IO[Response[IO]] =
    Response[IO](status).withEntity(body.noSpaces).withContentType(scimJson).pure[IO]

  private def error(status: Status, detail: String, scimType: Option[String] = None): IO[Response[IO]] =
    json(status, Json.obj("schemas" -> List(ErrSchema).asJson, "status" -> status.code.toString.asJson,
      "detail" -> detail.asJson, "scimType" -> scimType.asJson).dropNullValues)

  private def authorized(req: Request[IO]): Boolean =
    req.headers.get[Authorization].exists {
      case Authorization(Credentials.Token(AuthScheme.Bearer, t)) =>
        MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))
      case _ => false
    }

  private def body(req: Request[IO]): IO[Option[Json]] =
    req.bodyText.compile.string.map(s => _root_.io.circe.parser.parse(s).toOption)

  // ---------------------------------------------------------------- 表現

  private def userJson(u: ScimUser): Json = Json.obj(
    "schemas"    -> List(UserSchema).asJson,
    "id"         -> u.id.toString.asJson,
    "externalId" -> u.externalId.asJson,
    "userName"   -> u.userName.asJson,
    "displayName" -> u.name.asJson,
    "name"       -> Json.obj("formatted" -> u.name.asJson),
    "emails"     -> u.email.toList.map(e => Json.obj("value" -> e.asJson, "primary" -> true.asJson)).asJson,
    "active"     -> u.active.asJson,
    "meta" -> Json.obj("resourceType" -> "User".asJson, "created" -> u.createdAt.toString.asJson,
      "lastModified" -> u.updatedAt.toString.asJson, "location" -> s"$baseUrl/scim/v2/Users/${u.id}".asJson)
  ).dropNullValues

  private def groupJson(g: ScimGroup): Json = Json.obj(
    "schemas"     -> List(GroupSchema).asJson,
    "id"          -> g.id.toString.asJson,
    "externalId"  -> g.externalId.asJson,
    "displayName" -> g.displayName.asJson,
    "members"     -> g.members.map((id, n) => Json.obj("value" -> id.toString.asJson, "display" -> n.asJson)).asJson,
    "meta" -> Json.obj("resourceType" -> "Group".asJson, "created" -> g.createdAt.toString.asJson,
      "lastModified" -> g.updatedAt.toString.asJson, "location" -> s"$baseUrl/scim/v2/Groups/${g.id}".asJson)
  ).dropNullValues

  private def listJson(total: Int, start: Int, items: List[Json]): Json = Json.obj(
    "schemas" -> List(ListSchema).asJson, "totalResults" -> total.asJson,
    "startIndex" -> start.asJson, "itemsPerPage" -> items.size.asJson, "Resources" -> items.asJson
  )

  // ---------------------------------------------------------------- 入力の解釈

  private def str(c: ACursor): Option[String] = c.as[String].toOption.map(_.trim).filter(_.nonEmpty)

  private def userInput(j: Json, base: Option[ScimUser] = None): Either[String, ScimUserInput] =
    val c = j.hcursor
    val userName = str(c.downField("userName")).orElse(base.map(_.userName))
    val name = str(c.downField("displayName"))
      .orElse(str(c.downField("name").downField("formatted")))
      .orElse {
        val parts = List(str(c.downField("name").downField("familyName")), str(c.downField("name").downField("givenName"))).flatten
        Option.when(parts.nonEmpty)(parts.mkString(" "))
      }
      .orElse(base.map(_.name))
      .orElse(userName)
    val emails = c.downField("emails").as[List[Json]].getOrElse(Nil)
    val email = emails.find(_.hcursor.downField("primary").as[Boolean].contains(true)).orElse(emails.headOption)
      .flatMap(e => str(e.hcursor.downField("value")))
      .orElse(if c.downField("emails").succeeded then None else base.flatMap(_.email))
    val active = c.downField("active").as[Boolean].toOption.orElse(base.map(_.active)).getOrElse(true)
    val ext = str(c.downField("externalId")).orElse(base.flatMap(_.externalId))
    userName.toRight("userName を指定してください")
      // agent: で始まる名前はエージェント専用アカウント（管理者が作る）の sub なので使わせない（乗っ取り防止）
      .filterOrElse(un => !AgentAccounts.isAgent(un), "userName は agent: で始められません")
      .map(un => ScimUserInput(un, ext, name.getOrElse(un), email, active))

  private def memberIds(j: Json): List[UUID] =
    j.as[List[Json]].getOrElse(Nil).flatMap(m => str(m.hcursor.downField("value"))).flatMap(uuidOpt)

  /** `attr eq "value"` 形式のフィルタ（属性名は小文字化）。 */
  private val FilterRe = """(?i)^\s*([A-Za-z.]+)\s+eq\s+"((?:[^"\\]|\\.)*)"\s*$""".r
  private def filterOf(req: Request[IO]): Either[String, Option[(String, String)]] =
    req.uri.query.params.get("filter") match
      case None => Right(None)
      case Some(FilterRe(attr, v)) => Right(Some(attr.toLowerCase -> v.replace("\\\"", "\"").replace("\\\\", "\\")))
      case Some(other) => Left(s"使えない絞り込みです: $other")

  private def paging(req: Request[IO]): (Int, Int) =
    val p = req.uri.query.params
    (p.get("startIndex").flatMap(_.toIntOption).map(math.max(1, _)).getOrElse(1),
      p.get("count").flatMap(_.toIntOption).map(n => math.max(0, math.min(n, 200))).getOrElse(100))

  private def ops(j: Json): List[(String, Option[String], Json)] =
    j.hcursor.downField("Operations").as[List[Json]].getOrElse(Nil).map { o =>
      val c = o.hcursor
      (c.downField("op").as[String].getOrElse("").toLowerCase, str(c.downField("path")), c.downField("value").focus.getOrElse(Json.Null))
    }

  /** PATCH(ユーザー): 対象属性を JSON に適用してから置換と同じ処理を行う。 */
  private def patchUser(u: ScimUser, j: Json): Either[String, ScimUserInput] =
    val start = userJson(u)
    val patched = ops(j).foldLeft(start) {
      case (acc, ("replace" | "add", None, v)) if v.isObject => acc.deepMerge(v)
      case (acc, ("replace" | "add", Some(path), v)) =>
        path.toLowerCase match
          case "active" | "username" | "displayname" | "externalid" =>
            val key = Map("active" -> "active", "username" -> "userName", "displayname" -> "displayName", "externalid" -> "externalId")(path.toLowerCase)
            // 一部の実装は active を文字列 "False" で送る
            val value = if key == "active" then v.asString.map(_.equalsIgnoreCase("true")).map(Json.fromBoolean).getOrElse(v) else v
            acc.mapObject(_.add(key, value))
          case "name.formatted" => acc.mapObject(_.add("displayName", v))
          case p if p.startsWith("emails") =>
            val e = if v.isArray then v else Json.arr(Json.obj("value" -> v, "primary" -> true.asJson))
            acc.mapObject(_.add("emails", e))
          case _ => acc // 未対応の属性は無視
      case (acc, ("remove", Some(p), _)) if p.toLowerCase.startsWith("emails") => acc.mapObject(_.add("emails", Json.arr()))
      case (acc, _) => acc
    }
    userInput(patched, Some(u))

  private val MemberPathRe = """(?i)^members\[\s*value\s+eq\s+"([^"]+)"\s*\]$""".r

  // ---------------------------------------------------------------- ルート

  private def auditUser(action: String, u: ScimUser): IO[Unit] =
    audit.system("scim", action, "user", u.id,
      Json.obj("userName" -> u.userName.asJson, "name" -> u.name.asJson, "active" -> u.active.asJson))

  private def auditGroup(action: String, id: UUID, detail: Json): IO[Unit] = audit.system("scim", action, "group", id, detail)


  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req if req.pathInfo.renderString.startsWith("/scim/") && !authorized(req) =>
      error(Status.Unauthorized, "invalid SCIM token")

    case GET -> Root / "scim" / "v2" / "ServiceProviderConfig" =>
      json(Status.Ok, Json.obj(
        "schemas" -> List("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig").asJson,
        "patch" -> Json.obj("supported" -> true.asJson),
        "bulk" -> Json.obj("supported" -> false.asJson, "maxOperations" -> 0.asJson, "maxPayloadSize" -> 0.asJson),
        "filter" -> Json.obj("supported" -> true.asJson, "maxResults" -> 200.asJson),
        "changePassword" -> Json.obj("supported" -> false.asJson),
        "sort" -> Json.obj("supported" -> false.asJson),
        "etag" -> Json.obj("supported" -> false.asJson),
        "authenticationSchemes" -> List(Json.obj("type" -> "oauthbearertoken".asJson, "name" -> "Bearer token".asJson,
          "description" -> "SCIM_TOKEN".asJson)).asJson
      ))

    // ------------------------------------------------------------ Users
    case req @ GET -> Root / "scim" / "v2" / "Users" =>
      filterOf(req) match
        case Left(m) => error(Status.BadRequest, m, Some("invalidFilter"))
        case Right(f) =>
          val (start, count) = paging(req)
          scim.listUsers(f, start, count).flatMap((t, us) => json(Status.Ok, listJson(t, start, us.map(userJson))))

    case req @ POST -> Root / "scim" / "v2" / "Users" =>
      body(req).flatMap {
        case None => error(Status.BadRequest, "invalid JSON", Some("invalidSyntax"))
        case Some(j) =>
          userInput(j) match
            case Left(m) => error(Status.BadRequest, m, Some("invalidValue"))
            case Right(in) =>
              scim.upsertUser(in).flatMap { u =>
                auditUser("scim.user.create", u) *> (if u.active then IO.unit else accessChanged) *> json(Status.Created, userJson(u))
              }
      }

    case GET -> Root / "scim" / "v2" / "Users" / id =>
      uuidOpt(id).flatTraverse(scim.getUser).flatMap {
        case Some(u) => json(Status.Ok, userJson(u))
        case None    => error(Status.NotFound, "ユーザーが見つかりません")
      }

    case req @ PUT -> Root / "scim" / "v2" / "Users" / id =>
      (uuidOpt(id).flatTraverse(scim.getUser), body(req)).tupled.flatMap {
        case (None, _)       => error(Status.NotFound, "ユーザーが見つかりません")
        case (_, None)       => error(Status.BadRequest, "invalid JSON", Some("invalidSyntax"))
        case (Some(cur), Some(j)) =>
          userInput(j, Some(cur)) match
            case Left(m) => error(Status.BadRequest, m, Some("invalidValue"))
            case Right(in) => replaceUser(cur, in)
      }

    case req @ PATCH -> Root / "scim" / "v2" / "Users" / id =>
      (uuidOpt(id).flatTraverse(scim.getUser), body(req)).tupled.flatMap {
        case (None, _)       => error(Status.NotFound, "ユーザーが見つかりません")
        case (_, None)       => error(Status.BadRequest, "invalid JSON", Some("invalidSyntax"))
        case (Some(cur), Some(j)) =>
          patchUser(cur, j) match
            case Left(m)   => error(Status.BadRequest, m, Some("invalidValue"))
            case Right(in) => replaceUser(cur, in)
      }

    case DELETE -> Root / "scim" / "v2" / "Users" / id =>
      uuidOpt(id).flatTraverse(scim.getUser).flatMap {
        case None => error(Status.NotFound, "ユーザーが見つかりません")
        case Some(u) =>
          scim.deleteUser(u.id) *> auditUser("scim.user.delete", u.copy(active = false)) *> accessChanged *> NoContent()
      }

    // ------------------------------------------------------------ Groups
    case req @ GET -> Root / "scim" / "v2" / "Groups" =>
      filterOf(req) match
        case Left(m) => error(Status.BadRequest, m, Some("invalidFilter"))
        case Right(f) =>
          val (start, count) = paging(req)
          val excludeMembers = req.uri.query.params.get("excludedAttributes").exists(_.toLowerCase.contains("members"))
          scim.listGroups(f, start, count).flatMap { (t, gs) =>
            json(Status.Ok, listJson(t, start, gs.map(g => if excludeMembers then groupJson(g.copy(members = Nil)).mapObject(_.remove("members")) else groupJson(g))))
          }

    case req @ POST -> Root / "scim" / "v2" / "Groups" =>
      body(req).flatMap {
        case None => error(Status.BadRequest, "invalid JSON", Some("invalidSyntax"))
        case Some(j) =>
          str(j.hcursor.downField("displayName")) match
            case None => error(Status.BadRequest, "displayName を指定してください", Some("invalidValue"))
            case Some(name) =>
              scim.createGroup(name, str(j.hcursor.downField("externalId")), memberIds(j.hcursor.downField("members").focus.getOrElse(Json.arr()))).flatMap { g =>
                auditGroup("scim.group.create", g.id, Json.obj("displayName" -> g.displayName.asJson, "members" -> g.members.size.asJson)) *>
                  accessChanged *> json(Status.Created, groupJson(g))
              }
      }

    case GET -> Root / "scim" / "v2" / "Groups" / id =>
      uuidOpt(id).flatTraverse(scim.getGroup).flatMap {
        case Some(g) => json(Status.Ok, groupJson(g))
        case None    => error(Status.NotFound, "グループが見つかりません")
      }

    case req @ PUT -> Root / "scim" / "v2" / "Groups" / id =>
      (uuidOpt(id).flatTraverse(scim.getGroup), body(req)).tupled.flatMap {
        case (None, _) => error(Status.NotFound, "グループが見つかりません")
        case (_, None) => error(Status.BadRequest, "invalid JSON", Some("invalidSyntax"))
        case (Some(cur), Some(j)) =>
          val c = j.hcursor
          val name = str(c.downField("displayName")).getOrElse(cur.displayName)
          val members = c.downField("members").focus.map(memberIds)
          scim.replaceGroup(cur.id, name, str(c.downField("externalId")).orElse(cur.externalId), members).flatMap {
            case None => error(Status.NotFound, "グループが見つかりません")
            case Some(g) =>
              auditGroup("scim.group.replace", g.id, Json.obj("displayName" -> g.displayName.asJson, "members" -> g.members.size.asJson)) *>
                accessChanged *> json(Status.Ok, groupJson(g))
          }
      }

    case req @ PATCH -> Root / "scim" / "v2" / "Groups" / id =>
      (uuidOpt(id).flatTraverse(scim.getGroup), body(req)).tupled.flatMap {
        case (None, _) => error(Status.NotFound, "グループが見つかりません")
        case (_, None) => error(Status.BadRequest, "invalid JSON", Some("invalidSyntax"))
        case (Some(cur), Some(j)) =>
          val steps: List[IO[Json]] = ops(j).map {
            case ("add", Some(p), v) if p.equalsIgnoreCase("members") =>
              val ids = memberIds(v); scim.addMembers(cur.id, ids).as(Json.obj("add" -> ids.map(_.toString).asJson))
            case ("remove", Some(MemberPathRe(v)), _) =>
              val ids = uuidOpt(v).toList; scim.removeMembers(cur.id, ids).as(Json.obj("remove" -> ids.map(_.toString).asJson))
            case ("remove", Some(p), v) if p.equalsIgnoreCase("members") =>
              if v.isNull then scim.replaceMembers(cur.id, Nil).as(Json.obj("removeAll" -> true.asJson))
              else { val ids = memberIds(v); scim.removeMembers(cur.id, ids).as(Json.obj("remove" -> ids.map(_.toString).asJson)) }
            case ("replace", Some(p), v) if p.equalsIgnoreCase("members") =>
              val ids = memberIds(v); scim.replaceMembers(cur.id, ids).as(Json.obj("replace" -> ids.size.asJson))
            case ("replace", Some(p), v) if p.equalsIgnoreCase("displayName") && v.isString =>
              scim.renameGroup(cur.id, v.asString.get).as(Json.obj("displayName" -> v))
            case ("replace" | "add", None, v) if v.isObject =>
              val c = v.hcursor
              str(c.downField("displayName")).traverse_(n => scim.renameGroup(cur.id, n)) *>
                c.downField("members").focus.traverse_(m => scim.replaceMembers(cur.id, memberIds(m))).as(v)
            case (op, p, _) => IO.pure(Json.obj("ignored" -> s"$op ${p.getOrElse("")}".asJson))
          }
          steps.sequence.flatMap { applied =>
            auditGroup("scim.group.patch", cur.id, Json.obj("displayName" -> cur.displayName.asJson, "operations" -> applied.asJson)) *>
              accessChanged *>
              scim.getGroup(cur.id).flatMap {
                case Some(g) => json(Status.Ok, groupJson(g))
                case None    => error(Status.NotFound, "グループが見つかりません")
              }
          }
      }

    case DELETE -> Root / "scim" / "v2" / "Groups" / id =>
      uuidOpt(id).flatTraverse(scim.getGroup).flatMap {
        case None => error(Status.NotFound, "グループが見つかりません")
        case Some(g) =>
          scim.deleteGroup(g.id) *> auditGroup("scim.group.delete", g.id, Json.obj("displayName" -> g.displayName.asJson)) *>
            accessChanged *> NoContent()
      }
  }

  private def replaceUser(cur: ScimUser, in: ScimUserInput): IO[Response[IO]] =
    scim.replaceUser(cur.id, in).attempt.flatMap {
      case Left(e) if e.getMessage != null && e.getMessage.contains("duplicate key") =>
        error(Status.Conflict, "userName はすでに使われています", Some("uniqueness"))
      case Left(e) => IO.raiseError(e)
      case Right(None) => error(Status.NotFound, "ユーザーが見つかりません")
      case Right(Some(u)) =>
        val action = if cur.active && !u.active then "scim.user.deactivate" else "scim.user.update"
        auditUser(action, u) *> (if cur.active != u.active then accessChanged else IO.unit) *> json(Status.Ok, userJson(u))
    }
