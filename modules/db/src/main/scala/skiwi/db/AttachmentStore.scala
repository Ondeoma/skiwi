package skiwi.db

import cats.effect.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

final case class Attachment(id: UUID,
                            pageId: UUID,
                            s3Key: String,
                            filename: String,
                            contentType: String,
                            size: Long,
                            sha256: String,
                            createdAt: Instant)

final class AttachmentStore(xa: Transactor[IO]) {

  private val cols = fr"id, page_id, s3_key, filename, content_type, size, sha256, created_at"

  def insert(id: UUID,
             pageId: UUID,
             s3Key: String,
             filename: String,
             contentType: String,
             size: Long,
             sha256: String,
             by: UUID): IO[Attachment] =
    (fr"""insert into attachment (id, page_id, s3_key, filename, content_type, size, sha256, created_by)
          values ($id, $pageId, $s3Key, $filename, $contentType, $size, $sha256, $by) returning""" ++ cols)
      .query[Attachment].unique.transact(xa)

  def get(id: UUID): IO[
    Option[Attachment]
  ] = (fr"select" ++ cols ++ fr"from attachment where id = $id").query[Attachment].option.transact(xa)

  /** ページ（複数）の添付ファイル。 */
  def byPages(pageIds: List[UUID]): IO[List[Attachment]] = {
    val arr = pageIds.toArray
    (fr"select" ++ cols ++ fr"from attachment where page_id = any($arr) order by created_at").query[
      Attachment
    ].to[List].transact(xa)
  }

  /** 文字をまだ取り出していない添付ファイル（新しいものから）。 */
  def withoutText(limit: Int): IO[List[Attachment]] =
    (fr"select" ++ fr"a.id, a.page_id, a.s3_key, a.filename, a.content_type, a.size, a.sha256, a.created_at" ++
      fr"from attachment a where not exists (select 1 from attachment_text t where t.attachment_id = a.id)" ++
      fr"order by a.created_at desc limit $limit").query[Attachment].to[List].transact(xa)

  /** 取り出した文字を保存する（同じ添付ファイルは上書き）。 */
  def saveText(id: UUID, status: String, body: String): IO[Unit] =
    sql"""insert into attachment_text (attachment_id, status, body) values ($id, $status, $body)
          on conflict (attachment_id) do update set status = excluded.status, body = excluded.body, extracted_at = now()"""
      .update.run.transact(xa).void
      // 取り出している間に添付ファイルが消えた（ページの完全削除など）なら何もしない
      .recover { case e: java.sql.SQLException if e.getSQLState == "23503" => () }

  /** 添付ファイルの文字の状態（テスト・確認用）。 */
  def textOf(id: UUID): IO[Option[(String, String)]] =
    sql"select status, body from attachment_text where attachment_id = $id".query[(String, String)].option.transact(xa)
}
