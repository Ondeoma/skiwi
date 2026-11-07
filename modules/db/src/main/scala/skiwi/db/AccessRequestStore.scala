package skiwi.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** 閲覧権限の申請。 */
final case class AccessRequest(
    id: UUID, pageId: UUID, userId: UUID, userName: String, userEmail: Option[String],
    role: String, message: Option[String], status: String, createdAt: Instant
)

final class AccessRequestStore(xa: Transactor[IO]) extends TxStore(xa):


  private val cols = fr"r.id, r.page_id, r.user_id, u.name, u.email, r.role, r.message, r.status, r.created_at from access_request r join app_user u on u.id = r.user_id"

  /** 申請する。未処理の申請があれば内容を更新する。戻り値: (申請, 新しく作ったか) */
  def create(pageId: UUID, userId: UUID, role: String, message: Option[String]): IO[(AccessRequest, Boolean)] = tx {
    sql"""insert into access_request (page_id, user_id, role, message) values ($pageId, $userId, $role, $message)
          on conflict (page_id, user_id) where status = 'pending' do update set role = excluded.role, message = excluded.message
          returning id, (xmax = 0)""".query[(UUID, Boolean)].unique.flatMap { (id, inserted) =>
      (fr"select" ++ cols ++ fr"where r.id = $id").query[AccessRequest].unique.map(_ -> inserted)
    }
  }

  def get(id: UUID): IO[Option[AccessRequest]] = tx((fr"select" ++ cols ++ fr"where r.id = $id").query[AccessRequest].option)

  def pending(pageId: UUID): IO[List[AccessRequest]] =
    tx((fr"select" ++ cols ++ fr"where r.page_id = $pageId and r.status = 'pending' order by r.created_at").query[AccessRequest].to[List])

  def pendingOf(pageId: UUID, userId: UUID): IO[Option[AccessRequest]] =
    tx((fr"select" ++ cols ++ fr"where r.page_id = $pageId and r.user_id = $userId and r.status = 'pending'").query[AccessRequest].option)

  /** 未処理の申請を承認・却下する。既に処理済みなら false。 */
  def decide(id: UUID, status: String, by: Option[UUID]): IO[Boolean] = tx(
    sql"""update access_request set status = $status, decided_by = $by, decided_at = now()
          where id = $id and status = 'pending'""".update.run.map(_ > 0)
  )

  /** 申請を受け取る人の候補: 所有者・作成者（ページが存在しなければ空）。 */
  def ownerAndCreator(pageId: UUID): IO[List[UUID]] = tx(
    sql"select owner_id, created_by from page where id = $pageId and deleted_at is null".query[(Option[UUID], Option[UUID])].option
      .map(_.toList.flatMap((a, b) => List(a, b).flatten).distinct)
  )
