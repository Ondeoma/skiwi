package skiwi.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** 受信用の Webhook。mode: row（データベースの項目を追加）| child（子ページを作る）| comment（コメントを付ける） */
final case class InboundHook(id: UUID,
                             workspaceId: UUID,
                             targetPageId: UUID,
                             targetTitle: Option[String],
                             name: String,
                             mode: String,
                             enabled: Boolean,
                             createdById: Option[UUID],
                             createdBy: Option[String],
                             createdAt: Instant,
                             lastAt: Option[Instant],
                             useCount: Int)

final class InboundStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val cols =
    fr"""h.id, h.workspace_id, h.target_page_id, p.title, h.name, h.mode, h.enabled, h.created_by, u.name, h.created_at, h.last_at, h.use_count
         from inbound_hook h left join page p on p.id = h.target_page_id and p.deleted_at is null left join app_user u on u.id = h.created_by"""

  def list(wsId: UUID): IO[List[InboundHook]] =
    tx((fr"select" ++ cols ++ fr"where h.workspace_id = $wsId order by h.created_at").query[InboundHook].to[List])
  def get(id: UUID): IO[Option[InboundHook]] =
    tx((fr"select" ++ cols ++ fr"where h.id = $id").query[InboundHook].option)
  def byToken(hash: Array[Byte]): IO[Option[InboundHook]] =
    tx((fr"select" ++ cols ++ fr"where h.token_hash = $hash").query[InboundHook].option)

  def create(wsId: UUID, target: UUID, name: String, mode: String, hash: Array[Byte], by: UUID): IO[InboundHook] = tx(
    sql"""insert into inbound_hook (workspace_id, target_page_id, name, mode, token_hash, created_by)
          values ($wsId, $target, $name, $mode, $hash, $by) returning id""".query[UUID].unique
      .flatMap(id => (fr"select" ++ cols ++ fr"where h.id = $id").query[InboundHook].unique)
  )

  def update(id: UUID, name: Option[String], enabled: Option[Boolean]): IO[Option[InboundHook]] = tx {
    val sets = List(name.map(v => fr"name = $v"), enabled.map(v => fr"enabled = $v")).flatten
    (if sets.isEmpty then 0.pure[ConnectionIO]
     else (fr"update inbound_hook set" ++ sets.intercalate(fr",") ++ fr"where id = $id").update.run) *>
      (fr"select" ++ cols ++ fr"where h.id = $id").query[InboundHook].option
  }

  /** URL（トークン）を作り直す */
  def rotate(id: UUID, hash: Array[Byte]): IO[Boolean] =
    tx(sql"update inbound_hook set token_hash = $hash where id = $id".update.run.map(_ > 0))

  def delete(id: UUID): IO[Boolean] = tx(sql"delete from inbound_hook where id = $id".update.run.map(_ > 0))

  def used(id: UUID): IO[Unit] =
    tx(sql"update inbound_hook set last_at = now(), use_count = use_count + 1 where id = $id".update.run.void)
}
