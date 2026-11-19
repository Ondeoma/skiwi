package skiwi.api

import cats.effect.*
import cats.syntax.all.*
import io.circe.{Encoder, Json}
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.typelevel.ci.CIString
import skiwi.core.*
import skiwi.db.{BackupConfig, BackupRow, BackupStore}
import sbirds.storage.BlobStore

import java.net.URLEncoder
import java.util.UUID

final class BackupRoutes(backups: BackupStore,
                         svc: BackupService,
                         blobs: Option[BlobStore],
                         protected val sessions: SessionAuth,
                         audit: Audit) extends RouteSupport {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "admin" / "workspaces" / UUIDVar(ws) / "backups" =>
      getAdminWorkspaceBackups(req, ws)
    case req @ PUT -> Root / "api" / "admin" / "workspaces" / UUIDVar(ws) / "backups" / "config" =>
      putAdminWorkspaceBackupsConfig(req, ws)
    case req @ POST -> Root / "api" / "admin" / "workspaces" / UUIDVar(ws) / "backups" =>
      postAdminWorkspaceBackups(req, ws)
    case req @ GET -> Root / "api" / "admin" / "backups" / UUIDVar(id) / "download" => getAdminBackupDownload(req, id)
    case req @ DELETE -> Root / "api" / "admin" / "backups" / UUIDVar(id) => deleteAdminBackup(req, id)
  }

  private def getAdminWorkspaceBackups(req: Request[IO], ws: UUID): IO[Response[IO]] =
    admin(req)(_ =>
      (backups.config(ws), backups.list(ws)).flatMapN((c, bs) =>
        Ok(BackupsDto(c.map(cfgDto), bs.map(dto), blobs.isDefined))
      )
    )

  private def putAdminWorkspaceBackupsConfig(req: Request[IO], ws: UUID): IO[Response[IO]] =
    admin(req) { s =>
      decodeBody[BackupConfigDto](req).flatMap {
        case Right(b) if b.hour >= 0 && b.hour <= 23 && b.keep >= 1 && b.keep <= 90 =>
          backups.setConfig(ws, b.enabled, b.hour, b.keep, s.user.id).flatMap { c =>
            audit.log(
              s,
              "backup.config",
              "workspace",
              ws,
              Json.obj("enabled" -> c.enabled.asJson, "hour" -> c.hour.asJson, "keep" -> c.keep.asJson)
            ) *> Ok(cfgDto(c))
          }
        case _ => err(ErrorCode.BackupScheduleInvalid)
      }
    }

  private def postAdminWorkspaceBackups(req: Request[IO], ws: UUID): IO[Response[IO]] =
    admin(req) { s =>
      if blobs.isEmpty then err(ErrorCode.BackupStorageNotConfigured)
      else
        backups.claimManual(ws).flatMap {
          case None => err(ErrorCode.BackupAlreadyRunning)
          case Some(id) =>
            backups.config(ws).flatMap { c =>
              audit.log(s, "backup.run", "workspace", ws) *> svc.run(ws, id, c.map(_.keep)).start *>
                backups.get(id).flatMap(b => Accepted(dto(b.get)))
            }
        }
    }

  private def getAdminBackupDownload(req: Request[IO], id: UUID): IO[Response[IO]] =
    admin(req) { s =>
      backups.get(id).flatMap {
        case Some(b) if b.status == "done" && b.s3Key.isDefined =>
          blobs.flatTraverse(_.get(b.s3Key.get)).flatMap {
            case None => err(ErrorCode.BackupFileNotFound)
            case Some((meta, body)) =>
              audit.log(s, "backup.download", "workspace", b.workspaceId, Json.obj("backupId" -> id.asJson)) *>
                IO.pure(Response[IO](Status.Ok).withBodyStream(body).putHeaders(
                  Header.Raw(CIString("Content-Type"), "application/zip"),
                  Header.Raw(CIString("Content-Length"), meta.size.toString),
                  Header.Raw(
                    CIString("Content-Disposition"),
                    s"attachment; filename=\"backup.zip\"; filename*=UTF-8''${URLEncoder.encode(b.s3Key.get.split('/').last, "UTF-8")}"
                  ),
                  Header.Raw(CIString("Cache-Control"), "no-store")
                ))
          }
        case Some(_) => err(ErrorCode.BackupNotReady)
        case None => err(ErrorCode.BackupNotFound)
      }
    }

  private def deleteAdminBackup(req: Request[IO], id: UUID): IO[Response[IO]] =
    admin(req) { s =>
      backups.get(id).flatMap {
        case None => err(ErrorCode.BackupNotFound)
        case Some(b) if b.status == "running" => err(ErrorCode.BackupInProgress)
        case Some(b) =>
          backups.delete(id).flatMap(key => key.traverse_(k => blobs.traverse_(_.delete(k)))) *>
            audit.log(s, "backup.delete", "workspace", b.workspaceId, Json.obj("backupId" -> id.asJson)) *>
            NoContent()
      }
    }

  private def dto(b: BackupRow) =
    BackupDto(b.id, b.createdAt, b.finishedAt, b.status, b.size, b.pages, b.attachments, b.error, b.manual)
  private def cfgDto(c: BackupConfig) = BackupConfigDto(c.enabled, c.hour, c.keep)

}
