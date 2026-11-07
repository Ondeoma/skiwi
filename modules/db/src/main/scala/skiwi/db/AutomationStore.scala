package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** 自動化ルール。trigger / action はJSON文字列（検証・解釈は api の Automations） */
final case class AutomationRule(
    id: UUID, workspaceId: UUID, name: String, enabled: Boolean, trigger: String, action: String,
    createdBy: Option[String], createdAt: Instant, lastRunAt: Option[Instant], lastError: Option[String], runCount: Int
)

final class AutomationStore(xa: Transactor[IO]) extends TxStore(xa):


  private val cols =
    fr"""r.id, r.workspace_id, r.name, r.enabled, r.trigger::text, r.action::text, u.name, r.created_at, r.last_run_at, r.last_error, r.run_count
         from automation_rule r left join app_user u on u.id = r.created_by"""

  def list(wsId: UUID): IO[List[AutomationRule]] = tx((fr"select" ++ cols ++ fr"where r.workspace_id = $wsId order by r.created_at").query[AutomationRule].to[List])

  def get(id: UUID): IO[Option[AutomationRule]] = tx((fr"select" ++ cols ++ fr"where r.id = $id").query[AutomationRule].option)

  /** 有効なルールのうち、きっかけの種類が kind のもの */
  def enabledFor(wsId: UUID, kind: String): IO[List[AutomationRule]] = tx(
    (fr"select" ++ cols ++ fr"where r.workspace_id = $wsId and r.enabled and r.trigger->>'type' = $kind").query[AutomationRule].to[List]
  )

  /** すべてのワークスペースの、有効な日付のルール */
  def enabledDateRules: IO[List[AutomationRule]] = tx(
    (fr"select" ++ cols ++ fr"""join workspace w on w.id = r.workspace_id
                                where r.enabled and r.trigger->>'type' = 'date_reached' and w.deleted_at is null""").query[AutomationRule].to[List]
  )

  def create(wsId: UUID, name: String, trigger: String, action: String, by: UUID): IO[AutomationRule] = tx(
    sql"""insert into automation_rule (workspace_id, name, trigger, action, created_by) values ($wsId, $name, $trigger::jsonb, $action::jsonb, $by)
          returning id""".query[UUID].unique.flatMap(id => (fr"select" ++ cols ++ fr"where r.id = $id").query[AutomationRule].unique)
  )

  def update(id: UUID, name: Option[String], enabled: Option[Boolean], trigger: Option[String], action: Option[String]): IO[Option[AutomationRule]] = tx {
    val sets = List(name.map(v => fr"name = $v"), enabled.map(v => fr"enabled = $v"),
      trigger.map(v => fr"trigger = $v::jsonb"), action.map(v => fr"action = $v::jsonb")).flatten
    (if sets.isEmpty then 0.pure[ConnectionIO] else (fr"update automation_rule set" ++ sets.intercalate(fr",") ++ fr"where id = $id").update.run) *>
      (fr"select" ++ cols ++ fr"where r.id = $id").query[AutomationRule].option
  }

  def delete(id: UUID): IO[Boolean] = tx(sql"delete from automation_rule where id = $id".update.run.map(_ > 0))

  def recordRun(id: UUID, error: Option[String]): IO[Unit] = tx(
    sql"update automation_rule set last_run_at = now(), last_error = $error, run_count = run_count + 1 where id = $id".update.run.void
  )

  /** 日付のルールの重複防止。初めてなら true */
  def markFired(ruleId: UUID, key: String): IO[Boolean] = tx(
    sql"insert into automation_fired (rule_id, key) values ($ruleId, $key) on conflict do nothing".update.run.map(_ > 0)
  )

  /** page が ancestor 自身か、その子孫か */
  def isUnder(pageId: UUID, ancestor: UUID): IO[Boolean] = tx(
    sql"""with recursive anc as (select id, parent_id from page where id = $pageId
            union all select p.id, p.parent_id from page p join anc a on p.id = a.parent_id)
          select exists(select 1 from anc where id = $ancestor)""".query[Boolean].unique
  )

  /** データベースの項目のうち、日付の列に値があるもの: (項目, タイトル, 日付, 値のJSON) */
  def datedRows(dbPageId: UUID, prop: String): IO[List[(UUID, String, String, String)]] = tx(
    sql"""select id, title, props->>$prop, props::text from page
          where parent_id = $dbPageId and deleted_at is null and jsonb_exists(props, $prop) and jsonb_typeof(props->$prop) = 'string'"""
      .query[(UUID, String, String, String)].to[List]
  )
