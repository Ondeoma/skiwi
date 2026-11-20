package skiwi.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

final case class BackupConfig(workspaceId: UUID, enabled: Boolean, hour: Int, keep: Int, updatedAt: Instant)
final case class BackupRow(id: UUID,
                           workspaceId: UUID,
                           createdAt: Instant,
                           finishedAt: Option[Instant],
                           status: String,
                           s3Key: Option[String],
                           size: Option[Long],
                           pages: Option[Int],
                           attachments: Option[Int],
                           error: Option[String],
                           manual: Boolean)

/** 定期バックアップの設定と記録 */
final class BackupStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val bcols =
    fr"id, workspace_id, created_at, finished_at, status, s3_key, size, pages, attachments, error, manual from backup"

  def config(wsId: UUID): IO[Option[BackupConfig]] =
    tx(sql"select workspace_id, enabled, hour, keep, updated_at from backup_config where workspace_id = $wsId".query[
      BackupConfig
    ].option)

  def setConfig(wsId: UUID, enabled: Boolean, hour: Int, keep: Int, by: UUID): IO[BackupConfig] = tx(
    sql"""insert into backup_config (workspace_id, enabled, hour, keep, updated_by) values ($wsId, $enabled, $hour, $keep, $by)
          on conflict (workspace_id) do update set enabled = excluded.enabled, hour = excluded.hour, keep = excluded.keep,
            updated_by = excluded.updated_by, updated_at = now()
          returning workspace_id, enabled, hour, keep, updated_at""".query[BackupConfig].unique
  )

  def enabledConfigs: IO[List[BackupConfig]] = tx(
    sql"""select c.workspace_id, c.enabled, c.hour, c.keep, c.updated_at from backup_config c join workspace w on w.id = c.workspace_id
          where c.enabled and w.deleted_at is null""".query[BackupConfig].to[List]
  )

  def list(wsId: UUID, limit: Int = 100): IO[List[BackupRow]] =
    tx((fr"select" ++ bcols ++ fr"where workspace_id = $wsId order by created_at desc limit $limit").query[
      BackupRow
    ].to[List])

  def get(id: UUID): IO[Option[BackupRow]] = tx((fr"select" ++ bcols ++ fr"where id = $id").query[BackupRow].option)

  /** 定期バックアップを始める（since 以降に定期バックアップがなければ）。複数ノードで同時に始めないよう設定の行をロックする。 */
  def claimScheduled(wsId: UUID, since: Instant): IO[Option[UUID]] = tx(
    sql"select workspace_id from backup_config where workspace_id = $wsId for update".query[UUID].option *>
      sql"""insert into backup (workspace_id, status, manual)
            select $wsId, 'running', false
            where not exists (select 1 from backup where workspace_id = $wsId and not manual and status <> 'failed' and created_at >= $since)
            returning id""".query[UUID].option
  )

  /** 手動のバックアップを始める（同じワークスペースで実行中のものがあれば None） */
  def claimManual(wsId: UUID): IO[Option[UUID]] = tx(
    sql"""insert into backup (workspace_id, status, manual)
          select $wsId, 'running', true
          where not exists (select 1 from backup where workspace_id = $wsId and status = 'running' and created_at > now() - interval '2 hours')
          returning id""".query[UUID].option
  )

  def finish(id: UUID, key: String, size: Long, pages: Int, attachments: Int): IO[Unit] = tx(
    sql"""update backup set status = 'done', finished_at = now(), s3_key = $key, size = $size, pages = $pages, attachments = $attachments
          where id = $id""".update.run.void
  )

  def fail(id: UUID, error: String): IO[Unit] =
    tx(sql"update backup set status = 'failed', finished_at = now(), error = $error where id = $id".update.run.void)

  /** 新しいものから keep 件を残し、それより古い完了済みのバックアップ（保存先のキー）を返して記録を消す */
  def prune(wsId: UUID, keep: Int): IO[List[String]] = tx(
    sql"""delete from backup where workspace_id = $wsId and status <> 'running' and id not in (
            select id from backup where workspace_id = $wsId and status = 'done' order by created_at desc limit $keep)
          and (status = 'done' or created_at < now() - interval '30 days')
          returning s3_key""".query[Option[String]].to[List].map(_.flatten)
  )

  def delete(id: UUID): IO[Option[String]] =
    tx(sql"delete from backup where id = $id returning s3_key".query[Option[String]].option.map(_.flatten))
}
