package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import sbirds.auth.Crypto
import skiwi.core.*
import skiwi.db.{AgentAccount, AgentKey, ApiToken, OpsStore}

import java.time.Instant
import java.util.UUID

/** APIトークンの管理（本人のCookieセッションのみ）、エージェント専用アカウントの管理と監査ログの閲覧（管理者のみ）。 */
final class OpsRoutes(ops: OpsStore, protected val sessions: SessionAuth, audit: Audit) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "api" / "tokens" => getTokens(req)
    case req @ POST -> Root / "api" / "tokens" => postTokens(req)
    case req @ DELETE -> Root / "api" / "tokens" / UUIDVar(id) => deleteToken(req, id)
    case req @ GET -> Root / "api" / "admin" / "audit" => getAdminAudit(req)
    case req @ GET -> Root / "api" / "admin" / "agents" => getAdminAgents(req)
    case req @ POST -> Root / "api" / "admin" / "agents" => postAdminAgents(req)
    case req @ PATCH -> Root / "api" / "admin" / "agents" / UUIDVar(id) => patchAdminAgent(req, id)
    case req @ POST -> Root / "api" / "admin" / "agents" / UUIDVar(id) / "keys" => postAdminAgentKeys(req, id)
    case req @ DELETE -> Root / "api" / "admin" / "agents" / UUIDVar(id) / "keys" / UUIDVar(keyId) =>
      deleteAdminAgentKey(req, id, keyId)
  }

  private def getTokens(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => ops.listTokens(s.user.id).flatMap(ts => Ok(ts.map(dto))))

  private def postTokens(req: Request[IO]): IO[Response[IO]] =
    sessionOnly(req)(s => issueToken(req, s, s.user.id, "token.create"))

  private def deleteToken(req: Request[IO], id: UUID): IO[Response[IO]] =
    sessionOnly(req) { s =>
      ops.revokeToken(s.user.id, id).flatMap {
        case Some(t) => audit.log(s, "token.revoke", "token", t.id, Json.obj("name" -> t.name.asJson)) *> NoContent()
        case None => err(ErrorCode.ApiTokenNotFound)
      }
    }

  private def getAdminAudit(req: Request[IO]): IO[Response[IO]] =
    admin(req) { _ =>
      val p = req.uri.query.params
      val limit = p.get("limit").flatMap(_.toIntOption).map(n => math.max(1, math.min(n, 500))).getOrElse(100)
      val before = p.get("before").flatMap(_.toLongOption)
      ops.listAudit(limit, before, p.get("action")).flatMap { es =>
        Ok(es.map(e =>
          AuditDto(
            e.id,
            e.at,
            e.actorId,
            e.actorName,
            e.via,
            e.action,
            e.targetType,
            e.targetId,
            io.circe.parser.parse(e.detail).getOrElse(Json.obj())
          )
        ))
      }
    }

  private def getAdminAgents(req: Request[IO]): IO[Response[IO]] =
    adminSession(req)(_ => ops.listAgents().flatMap(as => Ok(as.map(agentDto))))

  private def postAdminAgents(req: Request[IO]): IO[Response[IO]] =
    adminSession(req) { s =>
      body[CreateAgentReq](req) { b =>
        AgentAccounts.validName(b.name).fold(
          err(ErrorCode.RequestBodyInvalid, _),
          name =>
            ops.createAgent(name).flatMap { a =>
              audit.log(s, "agent.create", "user", a.id, Json.obj("name" -> a.name.asJson)) *>
                Created(agentDto(a, Nil))
            }
        )
      }
    }

  private def patchAdminAgent(req: Request[IO], id: UUID): IO[Response[IO]] =
    adminSession(req) { s =>
      body[UpdateAgentReq](req) { b =>
        b.name.traverse(AgentAccounts.validName) match {
          case Left(e) => err(ErrorCode.InvalidInput, e)
          case Right(name) =>
            ops.updateAgent(id, name, b.disabled.map(!_)).flatMap {
              case None => err(ErrorCode.AgentAccountNotFound)
              case Some(a) =>
                audit.log(
                  s,
                  "agent.update",
                  "user",
                  a.id,
                  Json.obj("name" -> name.asJson, "disabled" -> b.disabled.asJson).dropNullValues
                ) *>
                  ops.listAgents().flatMap(as => Ok(as.find(_._1.id == a.id).fold(agentDto(a, Nil))(agentDto)))
            }
        }
      }
    }

  private def postAdminAgentKeys(req: Request[IO], id: UUID): IO[Response[IO]] =
    adminSession(req)(s =>
      withAgent(id)(a => issueToken(req, s, a.id, "agent.key.create", Json.obj("agent" -> a.name.asJson)))
    )

  private def deleteAdminAgentKey(req: Request[IO], id: UUID, keyId: UUID): IO[Response[IO]] =
    adminSession(req) { s =>
      withAgent(id) { a =>
        ops.revokeToken(a.id, keyId).flatMap {
          case Some(t) => audit.log(
              s,
              "agent.key.revoke",
              "token",
              t.id,
              Json.obj("name" -> t.name.asJson, "agent" -> a.name.asJson)
            ) *> NoContent()
          case None => err(ErrorCode.ApiKeyNotFound)
        }
      }
    }

  /** トークンでトークンを作る・消すことはできない（漏洩時の被害拡大を防ぐ）。 */
  private def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req) { s =>
      if s.via == AuthVia.Session then f(s) else err(ErrorCode.ApiTokenCannotManageTokens)
    }

  private def dto(t: ApiToken) = TokenDto(t.id, t.name, t.scope, t.createdAt, t.expiresAt, t.lastUsedAt)

  /**
   * userId のユーザーの API トークンを発行する（本人の発行と、管理者によるエージェント専用アカウントのキーの発行で共通）.
   * スコープは read・write だけ（管理者の権限を持つトークンは作れない）。平文のトークンはこの応答でのみ返す
   */
  private def issueToken(req: Request[IO],
                         s: AuthSession,
                         userId: UUID,
                         action: String,
                         extra: Json = Json.obj()): IO[Response[IO]] =
    decodeBody[CreateTokenReq](req).flatMap {
      case Right(b)
          if b.name.trim.nonEmpty && Set("read", "write")(b.scope) && b.expiresInDays.forall(d => d > 0 && d <= 3650) =>
        val token = "skiwi_" + Crypto.randomToken(32)
        val expires = b.expiresInDays.map(d => Instant.now().plusSeconds(d.toLong * 86400))
        ops.createToken(userId, b.name.trim.take(100), Crypto.sha256(token), b.scope, expires).flatMap { t =>
          audit.log(
            s,
            action,
            "token",
            t.id,
            Json.obj(
              "name" -> t.name.asJson,
              "scope" -> t.scope.asJson,
              "expiresAt" -> t.expiresAt.map(_.toString).asJson
            ).deepMerge(extra)
          ) *>
            Created(CreatedTokenDto(token, dto(t)))
        }
      case _ => err(ErrorCode.ApiTokenParamsInvalid)
    }

  /** 管理者のみ・Cookie セッションのみ（API トークンでエージェント専用アカウントを操作させない） */
  private def adminSession(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    sessionOnly(req)(s => if s.isAdmin then f(s) else err(ErrorCode.AdminOnly))

  /** エージェント専用アカウントのとき（それ以外は 404） */
  private def withAgent(id: UUID)(f: AgentAccount => IO[Response[IO]]): IO[Response[IO]] =
    ops.getAgent(id).flatMap(_.fold(err(ErrorCode.AgentAccountNotFound))(f))

  private def agentDto(a: AgentAccount, keys: List[AgentKey]) =
    AgentDto(
      a.id,
      a.name,
      !a.active,
      a.createdAt,
      keys.map(k => AgentKeyDto(k.id, k.name, k.scope, k.createdAt, k.lastUsedAt, k.expiresAt, k.revoked))
    )

}
