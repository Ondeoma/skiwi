package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{AutomationRule, AutomationStore, Store}

import java.util.UUID

/** ワークスペースの自動化ルールの設定（ワークスペースの編集権限が必要） */
final class AutomationRoutes(
  protected val store: Store,
  rules: AutomationStore,
  protected val sessions: SessionAuth,
  audit: Audit) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "workspaces" / UUIDVar(wsId) / "automations" => getWorkspaceAutomations(req, wsId)
    case req @ POST -> Root / "api" / "workspaces" / UUIDVar(wsId) / "automations" =>
      postWorkspaceAutomations(req, wsId)
    case req @ PATCH -> Root / "api" / "automations" / UUIDVar(id) => patchAutomation(req, id)
    case req @ DELETE -> Root / "api" / "automations" / UUIDVar(id) => deleteAutomation(req, id)
  }

  private def getWorkspaceAutomations(
    req: Request[IO],
    wsId: UUID
  ): IO[Response[IO]] =
    withWs(req, wsId, Role.Edit)(
      (
        _,
        _,
        _
      ) => rules.list(wsId).flatMap(rs => Ok(rs.map(dto)))
    )

  private def postWorkspaceAutomations(
    req: Request[IO],
    wsId: UUID
  ): IO[Response[IO]] =
    withWs(req, wsId, Role.Edit) {
      (
        s,
        _,
        _
      ) =>
        decodeBody[SaveAutomationReq](req).flatMap {
          case Right(SaveAutomationReq(Some(name), _, Some(t), Some(a))) if validName(name).isDefined =>
            (AutomationSchema.trigger(t) *> AutomationSchema.action(a)) match {
              case Left(m) => err(ErrorCode.InvalidInput, m)
              case Right(_) =>
                targetsOk(s, wsId, t).ifM(
                  rules.create(wsId, name.trim, t.noSpaces, a.noSpaces, s.user.id).flatMap { r =>
                    audit.log(
                      s,
                      "automation.create",
                      "workspace",
                      wsId,
                      Json.obj("name" -> r.name.asJson, "trigger" -> t, "action" -> a)
                    ) *> Created(dto(r))
                  },
                  err(ErrorCode.AutomationTriggerPageInvalid)
                )
            }
          case _ => err(ErrorCode.AutomationFieldsRequired)
        }
    }

  private def patchAutomation(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withRule(req, id) {
      (
        s,
        r
      ) =>
        body[SaveAutomationReq](req) {
          case b if b.name.exists(validName(_).isEmpty) => err(ErrorCode.NameInvalid)
          case b =>
            (b.trigger.traverse_(AutomationSchema.trigger) *> b.action.traverse_(AutomationSchema.action)) match {
              case Left(m) => err(ErrorCode.InvalidInput, m)
              case Right(_) =>
                b.trigger.forallM(targetsOk(s, r.workspaceId, _)).ifM(
                  rules.update(
                    id,
                    b.name.map(_.trim),
                    b.enabled,
                    b.trigger.map(_.noSpaces),
                    b.action.map(_.noSpaces)
                  ).flatMap {
                    case None => err(ErrorCode.AutomationNotFound)
                    case Some(nr) =>
                      audit.log(
                        s,
                        "automation.update",
                        "workspace",
                        r.workspaceId,
                        Json.obj("name" -> nr.name.asJson, "enabled" -> nr.enabled.asJson)
                      ) *> Ok(dto(nr))
                  },
                  err(ErrorCode.AutomationTriggerPageInvalid)
                )
            }
        }
    }

  private def deleteAutomation(
    req: Request[IO],
    id: UUID
  ): IO[Response[IO]] =
    withRule(req, id) {
      (
        s,
        r
      ) =>
        rules.delete(id) *>
          audit.log(s, "automation.delete", "workspace", r.workspaceId, Json.obj("name" -> r.name.asJson)) *>
          NoContent()
    }

  private def dto(
    r: AutomationRule
  ) = AutomationDto(
    r.id,
    r.name,
    r.enabled,
    parseOrNull(r.trigger),
    parseOrNull(r.action),
    r.createdBy,
    r.createdAt,
    r.lastRunAt,
    r.lastError,
    r.runCount
  )

  /** きっかけで指定したページ（データベース・配下の起点）が、このワークスペースにあり、作る人が見られること */
  private def targetsOk(
    s: AuthSession,
    wsId: UUID,
    trigger: Json
  ): IO[Boolean] =
    List("database", "under").flatMap(k => trigger.hcursor.get[String](k).toOption.flatMap(uuidOpt)).forallM { id =>
      store.pageAccess(id, s).map(_.exists(
        (
          p,
          _
        ) => p.workspaceId == wsId
      ))
    }

  private def withRule(
    req: Request[IO],
    id: UUID
  )(
    f: (AuthSession, AutomationRule) => IO[Response[IO]]
  ) =
    withWsItem(req, rules.get(id), "自動化ルール")(_.workspaceId)(
      (
        s,
        _,
        r
      ) => f(s, r)
    )

}
