package skiwi.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

final case class Comment(id: UUID,
                         pageId: UUID,
                         threadId: Option[UUID],
                         anchor: Option[String],
                         quote: Option[String],
                         body: String,
                         authorId: Option[UUID],
                         authorName: Option[String],
                         createdAt: Instant,
                         updatedAt: Instant,
                         resolvedAt: Option[Instant],
                         resolvedBy: Option[String],
                         deletedAt: Option[Instant]) {
  def isRoot: Boolean = threadId.isEmpty
  def root: UUID = threadId.getOrElse(id)
}

/** ページのコメント（スレッド＝先頭のコメント＋返信）。 */
final class CommentStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val cols =
    fr"""c.id, c.page_id, c.thread_id, c.anchor, c.quote, c.body, c.author_id, a.name,
                          c.created_at, c.updated_at, c.resolved_at, r.name, c.deleted_at
                          from comment c left join app_user a on a.id = c.author_id left join app_user r on r.id = c.resolved_by"""

  def list(pageId: UUID): IO[List[Comment]] =
    tx((fr"select" ++ cols ++ fr"where c.page_id = $pageId order by c.created_at").query[Comment].to[List])

  def get(id: UUID): IO[Option[Comment]] =
    tx((fr"select" ++ cols ++ fr"where c.id = $id").query[Comment].option)

  def create(pageId: UUID,
             threadId: Option[UUID],
             anchor: Option[String],
             quote: Option[String],
             body: String,
             author: UUID): IO[Comment] = tx {
    sql"""insert into comment (page_id, thread_id, anchor, quote, body, author_id)
          values ($pageId, $threadId, $anchor, $quote, $body, $author) returning id""".query[UUID].unique
      .flatMap(id => (fr"select" ++ cols ++ fr"where c.id = $id").query[Comment].unique)
  }

  def edit(id: UUID, body: String): IO[Unit] =
    tx(sql"update comment set body = $body, updated_at = now() where id = $id and deleted_at is null".update.run.void)

  def delete(id: UUID): IO[Unit] =
    tx(sql"update comment set deleted_at = now(), body = '' where id = $id".update.run.void)

  def resolve(id: UUID, by: Option[UUID]): IO[Unit] = tx(
    by match {
      case Some(u) =>
        sql"update comment set resolved_at = now(), resolved_by = $u where id = $id and thread_id is null".update.run.void
      case None =>
        sql"update comment set resolved_at = null, resolved_by = null where id = $id and thread_id is null".update.run.void
    }
  )

  /** スレッドの参加者（先頭と返信の投稿者。削除済みを除く） */
  def participants(rootId: UUID): IO[Set[UUID]] = tx(
    sql"""select distinct author_id from comment where (id = $rootId or thread_id = $rootId) and deleted_at is null and author_id is not null"""
      .query[UUID].to[List].map(_.toSet)
  )
}
