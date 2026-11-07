package skiwi.api

import cats.effect.*
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.auth.Crypto
import skiwi.core.*
import skiwi.db.{ApiToken, OpsStore}

import java.time.Instant

/** APIトークンの管理（本人のCookieセッションのみ）と、監査ログの閲覧（管理者のみ）。 */
final class OpsRoutes(ops: OpsStore, protected val sessions: SessionAuth, audit: Audit) extends RouteSupport:


  /** トークンでトークンを作る・消すことはできない（漏洩時の被害拡大を防ぐ）。 */
  private def sessionOnly(req: Request[IO])(f: AuthSession => IO[Response[IO]]): IO[Response[IO]] =
    authed(req) { s =>
      if s.via == AuthVia.Session then f(s) else err(Status.Forbidden, "API tokens cannot manage tokens")
    }

  private def dto(t: ApiToken) = TokenDto(t.id, t.name, t.scope, t.createdAt, t.expiresAt, t.lastUsedAt)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ GET -> Root / "api" / "tokens" =>
      sessionOnly(req)(s => ops.listTokens(s.user.id).flatMap(ts => Ok(ts.map(dto))))

    case req @ POST -> Root / "api" / "tokens" =>
      sessionOnly(req) { s =>
        req.as[CreateTokenReq].attempt.flatMap {
          case Right(b) if b.name.trim.nonEmpty && Set("read", "write")(b.scope) && b.expiresInDays.forall(d => d > 0 && d <= 3650) =>
            val token   = "skiwi_" + Crypto.randomToken(32)
            val expires = b.expiresInDays.map(d => Instant.now().plusSeconds(d.toLong * 86400))
            ops.createToken(s.user.id, b.name.trim.take(100), Crypto.sha256(token), b.scope, expires).flatMap { t =>
              audit.log(s, "token.create", "token", t.id, Json.obj("name" -> t.name.asJson, "scope" -> t.scope.asJson,
                "expiresAt" -> t.expiresAt.map(_.toString).asJson)) *>
                Created(CreatedTokenDto(token, dto(t))) // 平文のトークンはこの応答でのみ返す
            }
          case _ => err(Status.BadRequest, "name, scope(read|write) and expiresInDays(1-3650, optional) are required")
        }
      }

    case req @ DELETE -> Root / "api" / "tokens" / UUIDVar(id) =>
      sessionOnly(req) { s =>
        ops.revokeToken(s.user.id, id).flatMap {
          case Some(t) => audit.log(s, "token.revoke", "token", t.id, Json.obj("name" -> t.name.asJson)) *> NoContent()
          case None    => err(Status.NotFound, "token not found")
        }
      }

    case req @ GET -> Root / "api" / "admin" / "audit" =>
      admin(req) { _ =>
        val p      = req.uri.query.params
        val limit  = p.get("limit").flatMap(_.toIntOption).map(n => math.max(1, math.min(n, 500))).getOrElse(100)
        val before = p.get("before").flatMap(_.toLongOption)
        ops.listAudit(limit, before, p.get("action")).flatMap { es =>
          Ok(es.map(e => AuditDto(e.id, e.at, e.actorId, e.actorName, e.via, e.action, e.targetType, e.targetId,
            io.circe.parser.parse(e.detail).getOrElse(Json.obj()))))
        }
      }
  }
