package skiwi.api

import skiwi.shared.Titles

import cats.effect.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import skiwi.core.*
import skiwi.db.{AccessRequest, AccessRequestStore, AttachmentStore, OpsStore, PageAcl, Store}
import sbirds.storage.BlobStore

import java.time.Instant
import java.util.UUID

/** parentId: 項目なし=既定（同じワークスペースなら元と同じ階層、別のワークスペースなら直下）、null=ワークスペース直下 */
final case class DuplicateReq(workspaceId: Option[UUID],
                              parentId: Option[Option[UUID]],
                              includeChildren: Option[Boolean],
                              title: Option[String])
object DuplicateReq {
  given Decoder[DuplicateReq] = Decoder.instance { c =>
    for {
      ws <- c.get[Option[UUID]]("workspaceId")
      par <- c.downField("parentId").success.fold(Right(None))(f => f.as[Option[UUID]].map(Some(_)))
      inc <- c.get[Option[Boolean]]("includeChildren")
      t <- c.get[Option[String]]("title")
    } yield DuplicateReq(ws, par, inc, t)
  }
}
final case class IdPairDto(from: UUID, to: UUID) derives Encoder.AsObject
final case class DuplicateDto(rootId: UUID, workspaceId: UUID, pages: List[IdPairDto], attachments: List[IdPairDto])
  derives Encoder.AsObject

final case class AccessRequestReq(role: Option[String], message: Option[String]) derives Decoder

/** status: none（申請していない）| pending（承認待ち）| has_access（すでに閲覧・編集できる） */
final case class AccessStatusDto(status: String, role: Option[String] = None) derives Encoder.AsObject
final case class AccessRequestDto(id: UUID,
                                  userId: UUID,
                                  name: String,
                                  email: Option[String],
                                  role: String,
                                  message: Option[String],
                                  createdAt: Instant) derives Encoder.AsObject

/** expiresAt: 許可する権限の期限（任意） */
final case class DecideReq(role: Option[String], expiresAt: Option[Instant] = None) derives Decoder

/** ページの複製と、閲覧権限の申請。 */
final class PageCopyRoutes(protected val store: Store,
                           attachments: AttachmentStore,
                           blobs: Option[BlobStore],
                           requests: AccessRequestStore,
                           ops: OpsStore,
                           protected val sessions: SessionAuth,
                           audit: Audit,
                           accessChanged: IO[Unit] = IO.unit) extends PageRouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ------------------------------------------------------------ 複製
    // 本文はクライアントが作り直して /import で入れる（添付ファイルのIDやページへのリンクを付け替えるため）
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "duplicate" => postPageDuplicate(req, id)
    case req @ POST -> Root / "api" / "pages" / UUIDVar(id) / "access-requests" => postPageAccessRequests(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "access-requests" / "mine" =>
      getPageAccessRequestsMine(req, id)
    case req @ GET -> Root / "api" / "pages" / UUIDVar(id) / "access-requests" => getPageAccessRequests(req, id)
    case req @ POST -> Root / "api" / "access-requests" / UUIDVar(rid) / action
        if action == "approve" || action == "deny" => postAccessRequest(req, rid, action)
  }

  private def postPageDuplicate(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.View) {
      (s, p, _) =>
        body[DuplicateReq](req) { b =>
          val ws = b.workspaceId.getOrElse(p.workspaceId)
          val sameWs = ws == p.workspaceId
          val parent = b.parentId.getOrElse(if sameWs then p.parentId else None)
          val placement = if sameWs && parent == p.parentId then Placement.After(p.id) else Placement.Last
          val title =
            b.title.map(_.trim).filter(_.nonEmpty).getOrElse(s"${Titles.display(p.title)} のコピー").take(MaxPageTitle)
          editUnder(s, ws, parent) {
            store.listPages(p.workspaceId, s).flatMap { visible =>
              store.duplicatePages(
                id,
                b.includeChildren.getOrElse(true),
                visible.map(_._1.id).toSet,
                ws,
                parent,
                placement,
                title,
                s.user.id
              )
            }.flatMap {
              case Left(e) => fail(e)
              case Right(pairs) =>
                val map = pairs.toMap
                val copyFiles: IO[List[IdPairDto]] = blobs match {
                  case None => IO.pure(Nil)
                  case Some(bs) =>
                    attachments.byPages(pairs.map(_._1)).flatMap(_.parTraverseN(4) { a =>
                      val nid = UUID.randomUUID()
                      val np = map(a.pageId)
                      val key = s"pages/$np/$nid"
                      bs.copy(a.s3Key, key).flatMap {
                        case false => IO.pure(None)
                        case true => attachments.insert(
                            nid,
                            np,
                            key,
                            a.filename,
                            a.contentType,
                            a.size,
                            a.sha256,
                            s.user.id
                          ).as(Some(IdPairDto(a.id, nid)))
                      }
                    }.map(_.flatten))
                }
                val root = pairs.head._2
                for {
                  files <- copyFiles
                  _ <- ops.setWatch(root, s.user.id, on = true)
                  _ <- audit.log(
                    s,
                    "page.duplicate",
                    "page",
                    root,
                    Json.obj(
                      "from" -> id.toString.asJson,
                      "title" -> title.asJson,
                      "pages" -> pairs.size.asJson,
                      "workspaceId" -> ws.toString.asJson
                    )
                  )
                  _ <- accessChanged
                  r <- Created(DuplicateDto(
                    root,
                    ws,
                    pairs.map((a, b) => IdPairDto(a, b)),
                    files
                  ))
                } yield r
            }
          }
        }
    }

  private def postPageAccessRequests(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req) { s =>
      body[AccessRequestReq](req) { b =>
        val role = b.role.filter(Set("view", "edit")).getOrElse("view")
        val msg = b.message.map(_.trim).filter(_.nonEmpty).map(_.take(500))
        store.pageAccess(id, s).flatMap {
          case Some((_, r)) if r >= requested(role) => Ok(AccessStatusDto("has_access", Some(r.asString)))
          case _ =>
            requests.create(id, s.user.id, role, msg).flatMap {
              (ar, created) =>
                val notify =
                  if !created then IO.unit
                  else
                    approvers(id).flatMap { us =>
                      val detail = Json.obj(
                        "requestId" -> ar.id.toString.asJson,
                        "role" -> role.asJson,
                        "excerpt" -> msg.asJson
                      ).dropNullValues.noSpaces
                      ops.enqueueNotice(
                        "access_request",
                        us - s.user.id,
                        Some(id),
                        s.user.name,
                        detail,
                        mail = true
                      ).void
                    }.handleErrorWith(e =>
                      sbirds.observe.Log.report(ErrorCode.NotifyEnqueueFailed, s"access request: $e", e)
                    )
                notify *> Accepted(AccessStatusDto("pending", Some(role)))
            }
        }
      }
    }

  private def getPageAccessRequestsMine(req: Request[IO], id: UUID): IO[Response[IO]] =
    authed(req) { s =>
      store.pageAccess(id, s).flatMap {
        case Some((_, r)) => Ok(AccessStatusDto("has_access", Some(r.asString)))
        case None =>
          requests.pendingOf(id, s.user.id).flatMap(r =>
            Ok(r.fold(AccessStatusDto("none"))(x => AccessStatusDto("pending", Some(x.role))))
          )
      }
    }

  private def getPageAccessRequests(req: Request[IO], id: UUID): IO[Response[IO]] =
    withPage(req, id, Role.Edit) {
      (_, _, _) =>
        requests.pending(id).flatMap { rs =>
          store.userRoles(id, rs.map(_.userId).toSet, sessions.adminGroups).flatMap { roles =>
            // 申請者がすでに申請した権限を持っていれば片付ける
            val (done, open) = rs.partition(r => roles.get(r.userId).exists(_ >= requested(r.role)))
            done.traverse_(r => requests.decide(r.id, "approved", None)) *> Ok(open.map(reqDto))
          }
        }
    }

  private def postAccessRequest(req: Request[IO], rid: UUID, action: String): IO[Response[IO]] =
    authed(req) { s =>
      requests.get(rid).flatMap {
        case None => err(ErrorCode.AccessRequestNotFound)
        case Some(r) =>
          store.pageAccess(r.pageId, s).flatMap {
            case None => err(ErrorCode.AccessRequestNotFound)
            case Some((_, Role.View)) => err(ErrorCode.PermissionDenied)
            case Some(_) if r.status != "pending" => Ok(AccessStatusDto(r.status))
            case Some((p, _)) =>
              decodeBody[DecideReq](req).map(_.toOption).flatMap { body =>
                val role = body.flatMap(_.role).filter(Set("view", "edit")).getOrElse(r.role)
                val until = body.flatMap(_.expiresAt).filter(_.isAfter(Instant.now()))
                if action == "deny" then
                  for {
                    ok <- requests.decide(rid, "denied", Some(s.user.id))
                    _ <- IO.whenA(ok)(ops.enqueueNotice(
                      "access_denied",
                      Set(r.userId),
                      None,
                      s.user.name,
                      Json.obj("pageId" -> r.pageId.toString.asJson).noSpaces,
                      mail = false
                    ).void)
                    _ <- audit.log(
                      s,
                      "page.access_request.deny",
                      "page",
                      r.pageId,
                      Json.obj("user" -> r.userName.asJson, "role" -> r.role.asJson)
                    )
                    resp <- Ok(AccessStatusDto("denied"))
                  } yield resp
                else
                  store.getPageAcl(r.pageId).flatMap {
                    case None => err(ErrorCode.PageNotFound)
                    case Some(acl) =>
                      val me = Principal(PrincipalType.User, r.userId)
                      val target = requested(role)
                      val current = acl.entries.find(_.principal == me).map(_.role)
                      val newRole = if current.contains(Role.Edit) then Role.Edit else target
                      val entries = acl.entries.filterNot(_.principal == me) :+ AclEntry(me, newRole)
                      // 期限: 指定があればそれ。もともと期限なしの権限があれば期限なしのまま
                      val keepForever = current.isDefined && !acl.expires.contains(me)
                      val expires = if keepForever then acl.expires - me
                      else until.fold(acl.expires - me)(u => acl.expires + (me -> u))
                      for {
                        _ <- store.setPageAcl(r.pageId, PageAcl(acl.inherit, entries, expires))
                        ok <- requests.decide(rid, "approved", Some(s.user.id))
                        _ <- IO.whenA(ok)(ops.enqueueNotice(
                          "access_granted",
                          Set(r.userId),
                          Some(r.pageId),
                          s.user.name,
                          Json.obj(
                            "role" -> newRole.asString.asJson,
                            "expiresAt" -> expires.get(me).asJson
                          ).dropNullValues.noSpaces,
                          mail = true
                        ).void)
                        _ <- audit.log(
                          s,
                          "page.access_request.approve",
                          "page",
                          r.pageId,
                          Json.obj(
                            "title" -> p.title.asJson,
                            "user" -> r.userName.asJson,
                            "role" -> newRole.asString.asJson,
                            "expiresAt" -> expires.get(me).asJson
                          )
                        )
                        _ <- accessChanged
                        resp <- Ok(AccessStatusDto("approved", Some(newRole.asString)))
                      } yield resp
                  }
              }
          }
      }
    }

  /** 申請の権限（"edit" 以外は閲覧） */
  private def requested(role: String): Role = if role == "edit" then Role.Edit else Role.View

  /** 申請を受け取る人: 所有者・作成者のうち編集できる人。いなければ編集できる人（最大10人）。 */
  private def approvers(pageId: UUID): IO[Set[UUID]] =
    requests.ownerAndCreator(pageId).flatMap(us =>
      store.userRoles(pageId, us.toSet, sessions.adminGroups).map(rs => us.filter(rs.get(_).contains(Role.Edit)))
    ).flatMap {
      case Nil =>
        store.audience(
          pageId,
          sessions.adminGroups
        ).map(_.toList.flatMap(_.entries).filter(_.role == Role.Edit).map(_.userId).take(10).toSet)
      case xs => IO.pure(xs.toSet)
    }

  private def reqDto(r: AccessRequest) =
    AccessRequestDto(r.id, r.userId, r.userName, r.userEmail, r.role, r.message, r.createdAt)

}
