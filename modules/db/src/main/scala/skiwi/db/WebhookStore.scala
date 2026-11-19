package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** format: generic（JSON＋署名）| slack | teams。events: 送る出来事 */
final case class Webhook(
  id: UUID,
  workspaceId: UUID,
  name: String,
  url: String,
  format: String,
  events: List[String],
  secret: String,
  enabled: Boolean,
  createdAt: Instant,
  createdBy: Option[String],
  lastStatus: Option[Int],
  lastError: Option[String],
  lastAt: Option[Instant])

/** 送信待ちの1件（送信時点のページ・ワークスペースの情報付き）。restricted: 個別に権限を絞ったページ（送らない） */
final case class WebhookItem(
  id: Long,
  webhookId: UUID,
  url: String,
  format: String,
  secret: String,
  enabled: Boolean,
  event: String,
  pageId: Option[UUID],
  detail: String,
  attempts: Int,
  pageTitle: Option[String],
  pageDeleted: Boolean,
  restricted: Boolean,
  workspaceId: UUID,
  workspaceName: String)

object WebhookItem {
  // 項目が多く自動導出できないため、2つの組に分けて読む
  given Read[WebhookItem] =
    Read[(
      (Long, UUID, String, String, String, Boolean, String, Option[UUID]),
      (String, Int, Option[String], Boolean, Boolean, UUID, String)
    )]
      .map { case ((a, b, c, d, e, f, g, h), (i, j, k, l, m, n, o)) =>
        WebhookItem(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)
      }
}

object Webhook {
  given Read[Webhook] =
    Read[(
      (UUID, UUID, String, String, String, List[String], String),
      (Boolean, Instant, Option[String], Option[Int], Option[String], Option[Instant])
    )]
      .map { case ((a, b, c, d, e, f, g), (h, i, j, k, l, m)) => Webhook(a, b, c, d, e, f, g, h, i, j, k, l, m) }
}

object WebhookStore {
  val Events = List("page.created", "page.updated", "page.deleted", "comment.created", "page.published")

  /** 個別に権限を絞った（権限の継承を止めた）ページ、またはその子孫か */
  def restrictedSql(
    pageCol: Fragment
  ): Fragment =
    fr"""coalesce((with recursive chain as (
           select id, parent_id, inherit_acl from page where id = """ ++ pageCol ++ fr"""
           union all select p.id, p.parent_id, p.inherit_acl from page p join chain c on p.id = c.parent_id
         ) select not bool_and(inherit_acl) from chain), false)"""

  /**
   * ページの出来事を、そのワークスペースの Webhook（その出来事を送る設定で有効なもの）の送信待ちに積む。
   * 個別に権限を絞ったページの出来事は積まない。quiet: 同じページの同じ出来事を、未送信のうちは1件にまとめて送信を遅らせる秒数
   */
  def enqueueC(
    event: String,
    pageId: UUID,
    detail: String,
    quiet: Option[(Int, Int)] = None,
    delay: Int = 0
  ): ConnectionIO[Int] =
    quiet match {
      case None =>
        (fr"""insert into webhook_delivery (webhook_id, event, page_id, detail, not_before)
              select h.id, $event, $pageId, $detail::jsonb, now() + make_interval(secs => $delay)
              from webhook h join page p on p.workspace_id = h.workspace_id
              where p.id = $pageId and h.enabled and $event = any(h.events) and not """ ++
          restrictedSql(fr"$pageId")).update.run
      case Some((q, maxDelay)) =>
        // 本文の更新: 編集者をまとめる（mail_outbox と同じ）
        (fr"""insert into webhook_delivery (webhook_id, event, page_id, detail, dedupe_key, not_before)
              select h.id, $event, $pageId, $detail::jsonb, 'wh:' || h.id || ':' || $event || ':' || $pageId, now() + make_interval(secs => $q)
              from webhook h join page p on p.workspace_id = h.workspace_id
              where p.id = $pageId and h.enabled and $event = any(h.events) and not """ ++ restrictedSql(fr"$pageId") ++
          fr"""on conflict (dedupe_key) where sent_at is null and not skipped do update set
                detail = jsonb_build_object('editors',
                  (select coalesce(jsonb_agg(distinct e), '[]'::jsonb)
                     from jsonb_array_elements(coalesce(webhook_delivery.detail->'editors', '[]'::jsonb) || coalesce(excluded.detail->'editors', '[]'::jsonb)) e)),
                not_before = least(now() + make_interval(secs => $q), webhook_delivery.created_at + make_interval(secs => $maxDelay))""").update.run
    }
}

/** Webhook の設定と送信待ち。 */
final class WebhookStore(
  xa: Transactor[IO]) extends TxStore(xa) {

  private val cols =
    fr"""h.id, h.workspace_id, h.name, h.url, h.format, h.events, h.secret, h.enabled, h.created_at, u.name,
         h.last_status, h.last_error, h.last_at from webhook h left join app_user u on u.id = h.created_by"""

  def list(
    wsId: UUID
  ): IO[List[Webhook]] =
    tx((fr"select" ++ cols ++ fr"where h.workspace_id = $wsId order by h.created_at").query[Webhook].to[List])

  def get(
    id: UUID
  ): IO[Option[Webhook]] =
    tx((fr"select" ++ cols ++ fr"where h.id = $id").query[Webhook].option)

  def create(
    wsId: UUID,
    name: String,
    url: String,
    format: String,
    events: List[String],
    secret: String,
    by: UUID
  ): IO[Webhook] = tx {
    sql"""insert into webhook (workspace_id, name, url, format, events, secret, created_by)
          values ($wsId, $name, $url, $format, ${events.toArray}, $secret, $by) returning id""".query[
      UUID
    ].unique.flatMap { id =>
      (fr"select" ++ cols ++ fr"where h.id = $id").query[Webhook].unique
    }
  }

  def update(
    id: UUID,
    name: Option[String],
    url: Option[String],
    format: Option[String],
    events: Option[List[String]],
    enabled: Option[Boolean]
  ): IO[Option[Webhook]] = tx {
    val sets = List(
      name.map(v => fr"name = $v"),
      url.map(v => fr"url = $v"),
      format.map(v => fr"format = $v"),
      events.map(v => fr"events = ${v.toArray}"),
      enabled.map(v => fr"enabled = $v")
    ).flatten
    (if sets.isEmpty then 0.pure[ConnectionIO]
     else (fr"update webhook set" ++ sets.intercalate(fr",") ++ fr"where id = $id").update.run) *>
      (fr"select" ++ cols ++ fr"where h.id = $id").query[Webhook].option
  }

  def delete(
    id: UUID
  ): IO[Boolean] = tx(sql"delete from webhook where id = $id".update.run.map(_ > 0))

  def enqueue(
    event: String,
    pageId: UUID,
    detail: String,
    delay: Int = 0
  ): IO[Int] = tx(WebhookStore.enqueueC(event, pageId, detail, None, delay))

  /** 指定した Webhook に送る（自動化ルールから。出来事の設定は見ない。同じワークスペースで、権限を絞っていないページのみ） */
  def enqueueTo(
    webhookId: UUID,
    event: String,
    pageId: UUID,
    detail: String
  ): IO[Int] = tx(
    (fr"""insert into webhook_delivery (webhook_id, event, page_id, detail)
          select h.id, $event, $pageId, $detail::jsonb from webhook h join page p on p.workspace_id = h.workspace_id
          where h.id = $webhookId and p.id = $pageId and h.enabled and not """ ++
      WebhookStore.restrictedSql(fr"$pageId")).update.run
  )

  def recordResult(
    id: UUID,
    status: Option[Int],
    error: Option[String]
  ): IO[Unit] = tx(
    sql"update webhook set last_status = $status, last_error = $error, last_at = now() where id = $id".update.run.void
  )

  /** 送信期限の来たものを取得してロックし、f の結果を記録する（複数ノードで重複送信しないよう SKIP LOCKED）。 */
  def processDue(
    limit: Int
  )(
    f: WebhookItem => IO[OutboxResult]
  ): IO[Int] = {
    val claim =
      (fr"""select d.id, h.id, h.url, h.format, h.secret, h.enabled, d.event, d.page_id, d.detail::text, d.attempts,
                   p.title, coalesce(p.deleted_at is not null, true),""" ++ WebhookStore.restrictedSql(fr"d.page_id") ++
        fr""", w.id, w.name
            from webhook_delivery d join webhook h on h.id = d.webhook_id join workspace w on w.id = h.workspace_id
            left join page p on p.id = d.page_id
            where d.sent_at is null and not d.skipped and d.not_before <= now()
            order by d.not_before limit $limit
            for update of d skip locked""").query[WebhookItem].to[List]
    Outbox.process(xa, "webhook_delivery", claim, _.id, _.attempts, maxAttempts = 8)(f)
  }

  /** 送信済み・諦めた古い記録を消す */
  def purgeOld(
    days: Int = 7
  ): IO[Int] = tx(
    sql"""delete from webhook_delivery where (sent_at is not null or skipped) and created_at < now() - make_interval(days => $days)""".update.run
  )
}
