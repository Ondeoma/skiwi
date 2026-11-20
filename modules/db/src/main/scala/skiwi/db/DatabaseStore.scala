package skiwi.db

import sbirds.db.*
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.Instant
import java.util.UUID

/** データベース（ページの子ページを項目として表・ボード・カレンダーで見る）。props / views はJSON文字列。 */
final case class DatabaseDef(pageId: UUID, props: String, views: String, updatedAt: Instant)

/** 項目（子ページ）と値（JSONオブジェクトの文字列。キーは列のID）。サブ項目は parentId が項目のID */
final case class DatabaseRow(id: UUID, values: String)

/** 表示用の項目の情報: 作成日時・作成者・最終更新・最後に編集した人・本文の最初の画像と冒頭（ギャラリー用） */
final case class DatabaseRowMeta(id: UUID,
                                 parentId: Option[UUID],
                                 values: String,
                                 createdAt: Instant,
                                 createdBy: Option[String],
                                 editedAt: Instant,
                                 editedBy: Option[String],
                                 content: Option[String],
                                 excerpt: Option[String])

/** フォーム */
final case class DbForm(id: UUID,
                        databasePageId: UUID,
                        token: String,
                        title: String,
                        description: Option[String],
                        fields: String,
                        requireLogin: Boolean,
                        respondentProp: Option[String],
                        enabled: Boolean,
                        createdById: Option[UUID],
                        createdBy: Option[String],
                        createdAt: Instant,
                        lastAt: Option[Instant],
                        useCount: Int)
object DbForm {
  given Read[DbForm] =
    Read[(
      (UUID, UUID, String, String, Option[String], String, Boolean),
      (Option[String], Boolean, Option[UUID], Option[String], Instant, Option[Instant], Int)
    )]
      .map { case ((a, b, c, d, e, f, g), (h, i, j, k, l, m, n)) => DbForm(a, b, c, d, e, f, g, h, i, j, k, l, m, n) }
}

final class DatabaseStore(xa: Transactor[IO]) extends TxStore(xa) {

  def get(pageId: UUID): IO[Option[DatabaseDef]] = tx(
    sql"""select d.page_id, d.props::text, d.views::text, d.updated_at from page_database d join page p on p.id = d.page_id
          where d.page_id = $pageId and p.deleted_at is null""".query[DatabaseDef].option
  )

  def upsert(pageId: UUID, props: String, views: String): IO[DatabaseDef] = tx(
    sql"""insert into page_database (page_id, props, views) values ($pageId, $props::jsonb, $views::jsonb)
          on conflict (page_id) do update set props = excluded.props, views = excluded.views, updated_at = now()
          returning page_id, props::text, views::text, updated_at""".query[DatabaseDef].unique
  )

  def delete(pageId: UUID): IO[Boolean] =
    tx(sql"delete from page_database where page_id = $pageId".update.run.map(_ > 0))

  /** ワークスペース内のデータベースのページ（ツリー・ページ一覧の印用） */
  def idsIn(wsId: UUID): IO[Set[UUID]] = tx(
    sql"""select d.page_id from page_database d join page p on p.id = d.page_id where p.workspace_id = $wsId and p.deleted_at is null"""
      .query[UUID].to[List].map(_.toSet)
  )

  def isDatabase(pageId: UUID): IO[Boolean] =
    tx(sql"select exists(select 1 from page_database where page_id = $pageId)".query[Boolean].unique)

  /** 項目（直下の子ページ）の値。権限での絞り込みは呼び出し側 */
  def rows(pageId: UUID): IO[List[DatabaseRow]] = tx(
    sql"""select id, props::text from page where parent_id = $pageId and deleted_at is null order by position collate "C", id"""
      .query[DatabaseRow].to[List]
  )

  /** id がこのデータベースの項目（直下の子ページ）か */
  def isRow(pageId: UUID, id: UUID): IO[Boolean] =
    tx(sql"select exists(select 1 from page where id = $id and parent_id = $pageId and deleted_at is null)".query[
      Boolean
    ].unique)

  /** 項目とサブ項目（項目の子ページ）の、表示用の情報。並びは親子の順に関係なく位置順 */
  def rowsWithMeta(pageId: UUID): IO[List[DatabaseRowMeta]] = tx(
    sql"""with r as (
            select id from page where parent_id = $pageId and deleted_at is null
            union all
            select c.id from page c join page p on p.id = c.parent_id
            where p.parent_id = $pageId and p.deleted_at is null and c.deleted_at is null
          )
          select p.id, nullif(p.parent_id, $pageId), p.props::text, p.created_at, cu.name,
                 greatest(p.updated_at, coalesce(s.created_at, p.updated_at)),
                 (select u.name from page_edit e join app_user u on u.id = e.user_id where e.page_id = p.id order by e.edited_at desc limit 1),
                 s.content::text, left(s.plain_text, 160)
          from page p join r on r.id = p.id
          left join app_user cu on cu.id = p.created_by
          left join page_snapshot s on s.page_id = p.id and s.kind = 'compact'
          order by p.position collate "C", p.id""".query[DatabaseRowMeta].to[List]
  )

  /** 連番（_seq）のない項目・サブ項目に、作成順で番号を振る */
  def ensureSeq(pageId: UUID): IO[Int] = tx(
    sql"""with missing as (
            select p.id, row_number() over (order by p.created_at, p.id) as rn from page p
            where p.deleted_at is null and not jsonb_exists(p.props, '_seq')
              and (p.parent_id = $pageId or p.parent_id in (select id from page where parent_id = $pageId and deleted_at is null))
          ), n as (select count(*)::int as c from missing),
          d as (
            update page_database set next_seq = next_seq + (select c from n) where page_id = $pageId and (select c from n) > 0
            returning next_seq - (select c from n) as base
          )
          update page p set props = p.props || jsonb_build_object('_seq', d.base + m.rn - 1)
          from missing m, d where p.id = m.id""".update.run
  )

  /** 項目のデータベース: 親がデータベースならその親、親が項目（サブ項目）ならその親 */
  def databaseOf(pageId: UUID): IO[Option[DatabaseDef]] = tx(
    sql"""select d.page_id, d.props::text, d.views::text, d.updated_at from page p
          join page_database d on d.page_id = p.parent_id
          where p.id = $pageId and p.deleted_at is null
          union all
          select d.page_id, d.props::text, d.views::text, d.updated_at from page p
          join page pp on pp.id = p.parent_id and pp.deleted_at is null
          join page_database d on d.page_id = pp.parent_id
          where p.id = $pageId and p.deleted_at is null and not exists (select 1 from page_database x where x.page_id = p.parent_id)
          limit 1""".query[DatabaseDef].option
  )

  // ---------------------------------------------------------------- フォーム

  private val fcols =
    fr"""f.id, f.database_page_id, f.token, f.title, f.description, f.fields::text, f.require_login, f.respondent_prop, f.enabled,
                           f.created_by, u.name, f.created_at, f.last_at, f.use_count from db_form f left join app_user u on u.id = f.created_by"""
  def forms(dbPageId: UUID): IO[List[DbForm]] =
    tx((fr"select" ++ fcols ++ fr"where f.database_page_id = $dbPageId order by f.created_at").query[DbForm].to[List])
  def form(id: UUID): IO[Option[DbForm]] = tx((fr"select" ++ fcols ++ fr"where f.id = $id").query[DbForm].option)
  def formByToken(token: String): IO[Option[DbForm]] =
    tx((fr"select" ++ fcols ++ fr"where f.token = $token").query[DbForm].option)
  def createForm(dbPageId: UUID,
                 token: String,
                 title: String,
                 description: Option[String],
                 fields: String,
                 requireLogin: Boolean,
                 respondentProp: Option[String],
                 by: UUID): IO[DbForm] = tx(
    sql"""insert into db_form (database_page_id, token, title, description, fields, require_login, respondent_prop, created_by)
          values ($dbPageId, $token, $title, $description, $fields::jsonb, $requireLogin, $respondentProp, $by) returning id""".query[
      UUID
    ].unique
      .flatMap(id => (fr"select" ++ fcols ++ fr"where f.id = $id").query[DbForm].unique)
  )
  def updateForm(id: UUID,
                 title: String,
                 description: Option[String],
                 fields: String,
                 requireLogin: Boolean,
                 respondentProp: Option[String],
                 enabled: Boolean): IO[Option[DbForm]] = tx(
    sql"""update db_form set title = $title, description = $description, fields = $fields::jsonb, require_login = $requireLogin,
            respondent_prop = $respondentProp, enabled = $enabled where id = $id""".update.run *>
      (fr"select" ++ fcols ++ fr"where f.id = $id").query[DbForm].option
  )
  def deleteForm(id: UUID): IO[Boolean] = tx(sql"delete from db_form where id = $id".update.run.map(_ > 0))
  def formUsed(id: UUID): IO[Unit] =
    tx(sql"update db_form set last_at = now(), use_count = use_count + 1 where id = $id".update.run.void)

  /** 複数のデータベースの定義（削除済みは含めない） */
  def getMany(ids: List[UUID]): IO[Map[UUID, DatabaseDef]] = {
    val arr = ids.toArray
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"""select d.page_id, d.props::text, d.views::text, d.updated_at from page_database d join page p on p.id = d.page_id
                  where d.page_id = any($arr) and p.deleted_at is null""".query[DatabaseDef].to[List].map(_.map(d =>
        d.pageId -> d
      ).toMap))
  }

  /** 複数のページの項目の値 */
  def valuesMany(ids: List[UUID]): IO[Map[UUID, String]] = {
    val arr = ids.toArray
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"select id, props::text from page where id = any($arr) and deleted_at is null".query[
        (UUID, String)
      ].to[List].map(_.toMap))
  }

  def values(pageId: UUID): IO[Option[String]] =
    tx(sql"select props::text from page where id = $pageId and deleted_at is null".query[String].option)

  /** 値を書き換える（patch のキーごと。null の値は消す） */
  def patchValues(pageId: UUID, patch: String): IO[Option[String]] = tx(
    sql"""update page set props = jsonb_strip_nulls(props || $patch::jsonb) where id = $pageId and deleted_at is null
          returning props::text""".query[String].option
  )
}
