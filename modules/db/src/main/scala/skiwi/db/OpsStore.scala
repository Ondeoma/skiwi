package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skiwi.core.*

import java.time.Instant
import java.util.UUID

final case class ApiToken(id: UUID, name: String, scope: String, createdAt: Instant, expiresAt: Option[Instant], lastUsedAt: Option[Instant])

final case class AuditEntry(
    id: Long,
    at: Instant,
    actorId: Option[UUID],
    actorName: Option[String],
    via: String,
    action: String,
    targetType: Option[String],
    targetId: Option[String],
    detail: String
)

/** 監査ログの記録内容。detail はJSON文字列。 */
final case class AuditEvent(
    actorId: Option[UUID],
    actorName: Option[String],
    via: String,
    action: String,
    targetType: Option[String] = None,
    targetId: Option[String] = None,
    detail: String = "{}"
)

/** ウォッチの受け取り方: 本文の更新・コメント・子ページも含める */
final case class WatchOpts(edits: Boolean, comments: Boolean, subtree: Boolean)

final case class DirectoryEntry(tpe: PrincipalType, id: UUID, name: String, detail: Option[String])

/** メール送信待ちの1件（送信ワーカーが取得する）。 */
final case class OutboxItem(
    id: Long,
    userId: UUID,
    email: Option[String],
    userName: String,
    active: Boolean,
    kind: String,
    pageId: Option[UUID],
    detail: String,
    attempts: Int
)

/** 画面内の通知（ベル）。page/workspace は表示時点の情報。 */
final case class NotificationRow(
    id: Long,
    kind: String,
    pageId: Option[UUID],
    workspaceId: Option[UUID],
    actors: List[String],
    detail: String,
    createdAt: Instant,
    readAt: Option[Instant],
    pageTitle: Option[String],
    pageIcon: Option[String],
    pageWorkspaceId: Option[UUID],
    workspaceName: Option[String]
)

/** 自分に割り当てられたToDo */
final case class TaskRow(pageId: UUID, seq: Int, text: String, checked: Boolean, due: Option[java.time.LocalDate],
                         pageTitle: String, pageIcon: Option[String], workspaceId: UUID)

/** 運用系の永続化: APIトークン、監査ログ、ウォッチ、メールアウトボックス、ディレクトリ検索。 */
final class OpsStore(xa: Transactor[IO]) extends TxStore(xa):


  private val tokenCols = fr"id, name, scope, created_at, expires_at, last_used_at"

  // ---------------------------------------------------------------- API tokens

  def createToken(userId: UUID, name: String, hash: Array[Byte], scope: String, expiresAt: Option[Instant]): IO[ApiToken] = tx(
    (fr"""insert into api_token (user_id, name, token_hash, scope, expires_at)
          values ($userId, $name, $hash, $scope, $expiresAt) returning""" ++ tokenCols).query[ApiToken].unique
  )

  def listTokens(userId: UUID): IO[List[ApiToken]] = tx(
    (fr"select" ++ tokenCols ++ fr"from api_token where user_id = $userId and revoked_at is null order by created_at desc").query[ApiToken].to[List]
  )

  def revokeToken(userId: UUID, id: UUID): IO[Option[ApiToken]] = tx(
    (fr"update api_token set revoked_at = now() where id = $id and user_id = $userId and revoked_at is null returning" ++ tokenCols)
      .query[ApiToken].option
  )

  /** トークンから (ユーザー, グループ, トークンID, 読み取り専用か)。無効・期限切れ・失効・無効ユーザーは None。 */
  def findToken(hash: Array[Byte], now: Instant): IO[Option[(User, List[Group], UUID, Boolean)]] = tx {
    sql"""select t.id, t.scope, u.id, u.oidc_sub, u.email, u.name
          from api_token t join app_user u on u.id = t.user_id
          where t.token_hash = $hash and t.revoked_at is null and u.active
            and (t.expires_at is null or t.expires_at > $now)"""
      .query[(UUID, String, User)].option.flatMap {
        case None => none[(User, List[Group], UUID, Boolean)].pure[ConnectionIO]
        case Some((tid, scope, u)) =>
          for
            // 最終使用日時は1分に1回だけ更新する（書き込みを抑える）
            _  <- sql"""update api_token set last_used_at = $now
                        where id = $tid and (last_used_at is null or last_used_at < $now - interval '1 minute')""".update.run
            gs <- SqlParts.groupsOfC(u.id)
          yield Some((u, gs, tid, scope == "read"))
      }
  }

  // ---------------------------------------------------------------- audit log

  def audit(e: AuditEvent): IO[Unit] = tx(
    sql"""insert into audit_log (actor_id, actor_name, via, action, target_type, target_id, detail)
          values (${e.actorId}, ${e.actorName}, ${e.via}, ${e.action}, ${e.targetType}, ${e.targetId}, ${e.detail}::jsonb)""".update.run.void
  )

  /** 新しい順。beforeId でページング。action は前方一致で絞り込み。 */
  def listAudit(limit: Int, beforeId: Option[Long], actionPrefix: Option[String]): IO[List[AuditEntry]] = tx {
    val conds = List(
      beforeId.map(b => fr"id < $b"),
      actionPrefix.filter(_.nonEmpty).map(a => fr"action like ${a.replace("%", "\\%").replace("_", "\\_") + "%"}")
    ).flatten
    (fr"select id, at, actor_id, actor_name, via, action, target_type, target_id, detail::text from audit_log" ++
      (if conds.isEmpty then fr"" else fr"where" ++ conds.reduce(_ ++ fr"and" ++ _)) ++ fr"order by id desc limit $limit").query[AuditEntry].to[List]
  }

  // ---------------------------------------------------------------- watch

  def setWatch(pageId: UUID, userId: UUID, on: Boolean): IO[Unit] = tx(
    if on then sql"insert into page_watch (page_id, user_id) values ($pageId, $userId) on conflict do nothing".update.run.void
    else sql"delete from page_watch where page_id = $pageId and user_id = $userId".update.run.void
  )

  /** ウォッチの受け取り方を設定する。本文の更新もコメントも受け取らないならウォッチをやめる。 */
  def setWatchOpts(pageId: UUID, userId: UUID, o: WatchOpts): IO[Unit] = tx(
    if !o.edits && !o.comments then sql"delete from page_watch where page_id = $pageId and user_id = $userId".update.run.void
    else
      sql"""insert into page_watch (page_id, user_id, edits, comments, subtree) values ($pageId, $userId, ${o.edits}, ${o.comments}, ${o.subtree})
            on conflict (page_id, user_id) do update set edits = excluded.edits, comments = excluded.comments, subtree = excluded.subtree""".update.run.void
  )

  def isWatching(pageId: UUID, userId: UUID): IO[Boolean] = tx(
    sql"select exists(select 1 from page_watch where page_id = $pageId and user_id = $userId)".query[Boolean].unique
  )

  /** このページの直接のウォッチ設定と、「子ページも含める」でウォッチしている一番近い祖先（あれば）。 */
  def watchState(pageId: UUID, userId: UUID): IO[(Option[WatchOpts], Option[(UUID, String, WatchOpts)])] = tx {
    for
      own <- sql"select edits, comments, subtree from page_watch where page_id = $pageId and user_id = $userId".query[WatchOpts].option
      anc <- (ancestorsCte(pageId) ++
               fr"""select a.id, p.title, w.edits, w.comments, w.subtree from anc a join page p on p.id = a.id
                    join page_watch w on w.page_id = a.id and w.user_id = $userId
                    where a.d > 0 and w.subtree and p.deleted_at is null order by a.d limit 1""")
               .query[(UUID, String, WatchOpts)].option
    yield (own, anc)
  }

  private def ancestorsCte(pageId: UUID): Fragment =
    fr"""with recursive anc as (
           select id, parent_id, 0 as d from page where id = $pageId
           union all select p.id, p.parent_id, a.d + 1 from page p join anc a on p.id = a.parent_id
         )"""

  /** このページの通知を受け取る人（直接のウォッチと、子ページも含めてウォッチしている祖先。近い設定を優先）。
    * kind: "edits" | "comments" */
  private def effectiveWatchers(kind: String): Fragment =
    val col = if kind == "comments" then fr"comments" else fr"edits"
    fr"""select user_id from (
           select distinct on (w.user_id) w.user_id, w.edits, w.comments from page_watch w join anc a on a.id = w.page_id
           where a.d = 0 or w.subtree order by w.user_id, a.d
         ) ew where""" ++ col

  // ---------------------------------------------------------------- workspace favorites

  def favoriteWorkspaces(userId: UUID): IO[Set[UUID]] = tx(
    sql"select workspace_id from workspace_favorite where user_id = $userId".query[UUID].to[List].map(_.toSet)
  )

  def setFavorite(workspaceId: UUID, userId: UUID, on: Boolean): IO[Unit] = tx(
    if on then sql"insert into workspace_favorite (user_id, workspace_id) values ($userId, $workspaceId) on conflict do nothing".update.run.void
    else sql"delete from workspace_favorite where user_id = $userId and workspace_id = $workspaceId".update.run.void
  )

  // ---------------------------------------------------------------- 最近見た・編集したページ

  def recordVisit(userId: UUID, pageId: UUID): IO[Unit] = tx(
    sql"""insert into page_visit (user_id, page_id) values ($userId, $pageId)
          on conflict (user_id, page_id) do update set visited_at = now()""".update.run.void
  )

  /** kind: "visited" | "edited"。削除済みを除く新しい順（権限での絞り込みは呼び出し側）。 */
  def recentPages(userId: UUID, kind: String, limit: Int): IO[List[(UUID, Instant)]] = tx {
    val q =
      if kind == "edited" then
        sql"""select e.page_id, e.edited_at from page_edit e join page p on p.id = e.page_id
              where e.user_id = $userId and p.deleted_at is null order by e.edited_at desc limit $limit"""
      else
        sql"""select v.page_id, v.visited_at from page_visit v join page p on p.id = v.page_id
              where v.user_id = $userId and p.deleted_at is null order by v.visited_at desc limit $limit"""
    q.query[(UUID, Instant)].to[List]
  }

  // ---------------------------------------------------------------- ページのお気に入り

  def favoritePages(userId: UUID): IO[List[UUID]] = tx(
    sql"""select f.page_id from page_favorite f join page p on p.id = f.page_id
          where f.user_id = $userId and p.deleted_at is null order by f.created_at""".query[UUID].to[List]
  )

  def setPageFavorite(userId: UUID, pageId: UUID, on: Boolean): IO[Unit] = tx(
    if on then sql"insert into page_favorite (user_id, page_id) values ($userId, $pageId) on conflict do nothing".update.run.void
    else sql"delete from page_favorite where user_id = $userId and page_id = $pageId".update.run.void
  )

  // ---------------------------------------------------------------- ToDo

  /** 自分のToDo（削除済みページを除く。権限での絞り込みは呼び出し側）。未完了を期限順、期限なしは後ろ。 */
  def myTasks(userId: UUID, includeDone: Boolean, limit: Int = 300): IO[List[TaskRow]] = tx {
    val done = if includeDone then fr"" else fr"and not t.checked"
    (fr"""select t.page_id, t.seq, t.text, t.checked, t.due, p.title, p.icon, p.workspace_id
           from page_task t join page p on p.id = t.page_id
           where t.assignee_id = $userId and p.deleted_at is null""" ++ done ++
      fr"order by t.checked, t.due nulls last, p.updated_at desc, t.seq limit $limit").query[TaskRow].to[List]
  }

  /** 期限が今日・明日・過ぎている未完了のToDoを通知する（同じToDo・同じ期限には1回だけ）。tz は「今日」を決める時間帯。 */
  def enqueueTaskDue(tz: String): IO[Int] = tx {
    sql"""with due as (
            select t.assignee_id, t.page_id, t.text, t.due,
                   t.page_id || ':' || t.seq || ':' || t.due || ':' || md5(t.text) as key
            from page_task t join page p on p.id = t.page_id
            where not t.checked and p.deleted_at is null and t.due is not null
              and t.due <= (now() at time zone $tz)::date + 1
              and t.due >= (now() at time zone $tz)::date - 7
          ), fresh as (
            select d.* from due d
            where not exists (select 1 from notification n where n.user_id = d.assignee_id and n.kind = 'task_due' and n.detail->>'key' = d.key)
          ), ins as (
            insert into notification (user_id, kind, page_id, detail)
            select assignee_id, 'task_due', page_id, jsonb_build_object('key', key, 'excerpt', text, 'due', due::text) from fresh
            returning 1
          )
          insert into mail_outbox (user_id, kind, page_id, detail)
          select assignee_id, 'task_due', page_id, jsonb_build_object('excerpt', text, 'due', due::text) from fresh""".update.run
  }

  // ---------------------------------------------------------------- 画面内の通知

  def notifications(userId: UUID, limit: Int): IO[List[NotificationRow]] = tx(
    sql"""select n.id, n.kind, n.page_id, n.workspace_id, n.actors, n.detail::text, n.created_at, n.read_at,
                 p.title, p.icon, p.workspace_id, coalesce(w.name, pw.name)
          from notification n
          left join page p on p.id = n.page_id and p.deleted_at is null
          left join workspace pw on pw.id = p.workspace_id
          left join workspace w on w.id = n.workspace_id and w.deleted_at is null
          where n.user_id = $userId and (n.page_id is null or p.id is not null)
          order by n.created_at desc limit $limit""".query[NotificationRow].to[List]
  )

  def unreadNotifications(userId: UUID): IO[Int] = tx(
    sql"""select count(*) from notification n left join page p on p.id = n.page_id
          where n.user_id = $userId and n.read_at is null and (n.page_id is null or p.deleted_at is null)""".query[Int].unique
  )

  /** ids を省くとすべて既読にする。 */
  def markNotificationsRead(userId: UUID, ids: Option[List[Long]]): IO[Int] = tx {
    ids match
      case None => sql"update notification set read_at = now() where user_id = $userId and read_at is null".update.run
      case Some(xs) =>
        val arr = xs.toArray
        sql"update notification set read_at = now() where user_id = $userId and read_at is null and id = any($arr)".update.run
  }

  // ---------------------------------------------------------------- mail outbox

  /** ページ更新通知を積む。同じページ×受信者の未送信分は1通にまとめ、送信を quiet だけ後ろへずらす
    * （ただし最初の更新から maxDelay を超えては延ばさない）。編集者本人には送らない。 */
  def enqueuePageEdited(pageId: UUID, editors: Set[UUID], quietSeconds: Int, maxDelaySeconds: Int): IO[Int] = tx {
    val eds = editors.toArray
    // Webhook（ページの更新。同じページはまとめて送る）
    WebhookStore.enqueueC("page.updated", pageId, s"""{"editors":[${editors.map(e => s"\"$e\"").mkString(",")}]}""", Some((quietSeconds, maxDelaySeconds))) *>
    // 「自分が編集したページ」の記録
    sql"""insert into page_edit (user_id, page_id) select e, $pageId from unnest($eds::uuid[]) e
          join app_user u on u.id = e
          on conflict (user_id, page_id) do update set edited_at = now()""".update.run *>
    // 画面内の通知（未読があれば1件にまとめ、編集者を追加して新しくする）。受け取る人: 本文の更新を受け取る設定のウォッチ（祖先の「子ページも含める」を含む）
    (ancestorsCte(pageId) ++ fr", ew as (" ++ effectiveWatchers("edits") ++ fr")" ++
     fr"""insert into notification (user_id, kind, page_id, actors)
          select w.user_id, 'page_edited', $pageId, array(select name from app_user where id = any($eds::uuid[]) order by name)
          from ew w where not (w.user_id = any($eds::uuid[]))
          on conflict (user_id, page_id) where kind = 'page_edited' and read_at is null do update set
            actors = array(select distinct x from unnest(notification.actors || excluded.actors) x order by x),
            created_at = now()""").update.run *>
    (ancestorsCte(pageId) ++ fr", ew as (" ++ effectiveWatchers("edits") ++ fr")" ++
     fr"""insert into mail_outbox (user_id, kind, page_id, dedupe_key, detail, not_before)
          select w.user_id, 'page_edited', $pageId, 'page_edited:' || $pageId || ':' || w.user_id,
                 jsonb_build_object('editors', to_jsonb($eds::uuid[])),
                 now() + make_interval(secs => $quietSeconds)
          from ew w
          where not (w.user_id = any($eds::uuid[]))
          on conflict (dedupe_key) where sent_at is null and not skipped do update set
            detail = jsonb_build_object('editors',
              (select coalesce(jsonb_agg(distinct e), '[]'::jsonb)
                 from jsonb_array_elements((mail_outbox.detail->'editors') || (excluded.detail->'editors')) e)),
            not_before = least(now() + make_interval(secs => $quietSeconds),
                               mail_outbox.created_at + make_interval(secs => $maxDelaySeconds))""").update.run
  }

  /** 共有通知を積む（ユーザーごとに即時送信）。 */
  def enqueueShared(pageId: UUID, userIds: Set[UUID], byName: String, role: String): IO[Int] =
    if userIds.isEmpty then IO.pure(0)
    else tx {
      val ids = userIds.toArray
      sql"""insert into notification (user_id, kind, page_id, actors, detail)
            select u, 'shared', $pageId, array[$byName::text], jsonb_build_object('role', $role::text)
            from unnest($ids::uuid[]) u""".update.run *>
      sql"""insert into mail_outbox (user_id, kind, page_id, detail)
            select u, 'shared', $pageId, jsonb_build_object('by', $byName::text, 'role', $role::text)
            from unnest($ids::uuid[]) u""".update.run
    }

  /** メンション（kind=mention）・コメント（kind=comment）の通知。画面内とメールの両方に積む。
    * 権限の確認は呼び出し側で行う（メールは送信時にも確認する）。 */
  def enqueuePageNotice(kind: String, pageId: UUID, userIds: Set[UUID], byName: String, excerpt: String, threadId: Option[UUID]): IO[Int] =
    if userIds.isEmpty then IO.pure(0)
    else tx {
      val ids = userIds.toArray
      val t = threadId.map(_.toString)
      sql"""insert into notification (user_id, kind, page_id, actors, detail)
            select u, $kind, $pageId, array[$byName::text], jsonb_strip_nulls(jsonb_build_object('excerpt', $excerpt::text, 'threadId', $t::text))
            from unnest($ids::uuid[]) u""".update.run *>
      sql"""insert into mail_outbox (user_id, kind, page_id, detail)
            select u, $kind, $pageId, jsonb_strip_nulls(jsonb_build_object('by', $byName::text, 'excerpt', $excerpt::text, 'threadId', $t::text))
            from unnest($ids::uuid[]) u""".update.run
    }

  /** 汎用の通知（画面内。mail=true ならメールも）。detail はJSONオブジェクトの文字列。 */
  def enqueueNotice(kind: String, userIds: Set[UUID], pageId: Option[UUID], actor: String, detail: String, mail: Boolean): IO[Int] =
    if userIds.isEmpty then IO.pure(0)
    else tx {
      val ids = userIds.toArray
      sql"""insert into notification (user_id, kind, page_id, actors, detail)
            select u, $kind, $pageId, array[$actor::text], $detail::jsonb from unnest($ids::uuid[]) u""".update.run *>
      (if !mail then 0.pure[ConnectionIO]
       else sql"""insert into mail_outbox (user_id, kind, page_id, detail)
                  select u, $kind, $pageId, $detail::jsonb || jsonb_build_object('by', $actor::text) from unnest($ids::uuid[]) u""".update.run)
    }

  /** コメントの通知を受け取る人（直接のウォッチと、子ページも含めてウォッチしている祖先）。 */
  def watchers(pageId: UUID): IO[Set[UUID]] = tx(
    (ancestorsCte(pageId) ++ effectiveWatchers("comments")).query[UUID].to[List].map(_.toSet)
  )

  /** ワークスペース共有の通知。 */
  def enqueueSharedWorkspace(userIds: Set[UUID], byName: String, role: String, workspaceName: String, workspaceId: Option[UUID] = None): IO[Int] =
    if userIds.isEmpty then IO.pure(0)
    else tx {
      val ids = userIds.toArray
      sql"""insert into notification (user_id, kind, workspace_id, actors, detail)
            select u, 'shared_workspace', $workspaceId, array[$byName::text], jsonb_build_object('role', $role::text, 'workspace', $workspaceName::text)
            from unnest($ids::uuid[]) u""".update.run *>
      sql"""insert into mail_outbox (user_id, kind, detail)
            select u, 'shared', jsonb_build_object('by', $byName::text, 'role', $role::text, 'workspace', $workspaceName::text)
            from unnest($ids::uuid[]) u""".update.run
    }

  /** 送信期限が来た未送信メールを取得してロックする（複数ノードで重複送信しないよう SKIP LOCKED）。
    * f の結果に応じて送信済み/スキップ/再試行を記録する。 */
  def processDue(limit: Int)(f: OutboxItem => IO[OutboxResult]): IO[Int] =
    val claim =
      sql"""select o.id, o.user_id, u.email, u.name, u.active, o.kind, o.page_id, o.detail::text, o.attempts
            from mail_outbox o join app_user u on u.id = o.user_id
            where o.sent_at is null and not o.skipped and o.not_before <= now()
            order by o.not_before limit $limit
            for update of o skip locked""".query[OutboxItem].to[List]
    Outbox.process(xa, "mail_outbox", claim, _.id, _.attempts, maxAttempts = 10)(f)

  // ---------------------------------------------------------------- directory

  /** 共有ダイアログ用のユーザー・グループ検索（有効なユーザーのみ）。 */
  /** ユーザー・グループの検索（共有設定用）。ひらがな/カタカナ・全角/半角などの違いは無視して部分一致。 */
  def searchDirectory(q: String, limit: Int = 20): IO[List[DirectoryEntry]] = tx {
    val n = TextNorm.Normalizer
    def has(col: Fragment) = fr"strpos(pgroonga_normalize(coalesce(" ++ col ++ fr", ''), $n), pgroonga_normalize($q, $n)) > 0"
    for
      us <- (fr"select id, name, email from app_user where active and (" ++ has(fr"name") ++ fr"or" ++ has(fr"email") ++ fr"or" ++
              has(fr"username") ++ fr") order by name limit $limit").query[(UUID, String, Option[String])].to[List]
      gs <- (fr"select id, name from app_group where" ++ has(fr"name") ++ fr"order by name limit $limit")
        .query[(UUID, String)].to[List]
    yield gs.map((id, n) => DirectoryEntry(PrincipalType.Group, id, n, None)) ++
      us.map((id, n, e) => DirectoryEntry(PrincipalType.User, id, n, e))
  }

  /** プリンシパルIDから表示名を引く（ACL表示用）。 */
  def principalNames(ids: List[UUID]): IO[Map[UUID, DirectoryEntry]] =
    if ids.isEmpty then IO.pure(Map.empty)
    else tx {
      val arr = ids.distinct.toArray
      for
        us <- sql"select id, name, email from app_user where id = any($arr)".query[(UUID, String, Option[String])].to[List]
        gs <- sql"select id, name from app_group where id = any($arr)".query[(UUID, String)].to[List]
      yield (us.map((i, n, e) => i -> DirectoryEntry(PrincipalType.User, i, n, e)) ++
        gs.map((i, n) => i -> DirectoryEntry(PrincipalType.Group, i, n, None))).toMap
    }

  /** プリンシパル（ユーザー／グループ）を、有効なユーザーIDの集合に展開する。 */
  def expandToUsers(ps: List[Principal]): IO[Set[UUID]] =
    if ps.isEmpty then IO.pure(Set.empty)
    else tx {
      val users  = ps.collect { case Principal(PrincipalType.User, id) => id }.toArray
      val groups = ps.collect { case Principal(PrincipalType.Group, id) => id }.toArray
      sql"""select id from app_user where active and (id = any($users)
              or id in (select user_id from group_member where group_id = any($groups)))""".query[UUID].to[List].map(_.toSet)
    }

enum OutboxResult:
  case Sent
  case Skip(reason: String)
  case Retry(error: String)
