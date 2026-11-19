package skiwi.db

import cats.data.NonEmptyList
import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skiwi.core.*
import skiwi.db.Codecs.given
import skiwi.db.SqlParts.*
import sbirds.shared.AppError

import java.time.Instant
import java.util.UUID

/** expires: 期限付きのエントリの期限 */
final case class PageAcl(
  inherit: Boolean,
  entries: List[AclEntry],
  expires: Map[Principal, Instant] = Map.empty)

/** このページを閲覧・編集できるユーザー。source が None なら管理者。 */
final case class AudienceEntry(
  userId: UUID,
  name: String,
  email: Option[String],
  role: Role,
  source: Option[Acl.Source])
final case class Audience(
  entries: List[AudienceEntry],
  chainTitles: List[String])

/** ページの所有者・状態（ページ一覧・詳細に付ける） */
final case class PageMeta(
  ownerId: Option[UUID],
  ownerName: Option[String],
  status: Option[String])

/** ページの詳細に付ける情報 */
final case class PageDetail(
  meta: PageMeta,
  database: Boolean,
  locked: Boolean,
  fullWidth: Boolean)

/** リンク切れの候補（権限の判定は呼び出し側） */
/** ページの保護。editors: 保護中も編集できる人（保護した人・所有者は常に編集できる） */
final case class PageLock(
  lockedAt: Instant,
  lockedById: Option[UUID],
  lockedBy: Option[String],
  reason: Option[String],
  editors: List[UUID])
final case class PageHealthRow(
  pageId: UUID,
  incoming: Long,
  lastEditedAt: Instant,
  lastEditor: Option[String],
  children: Long)
final case class LinkTargetRow(
  sourceId: UUID,
  sourceTitle: String,
  targetId: UUID,
  targetExists: Boolean,
  targetDeleted: Boolean)

/** カスタムCSSの1層。depth: 0=そのページ、1=親…（ワークスペースは None） */
final case class CssLayer(
  pageId: Option[UUID],
  depth: Option[Int],
  title: Option[String],
  css: String,
  inherit: Boolean)

/** ゲスト向けHTMLの1層（ワークスペースは pageId なし） */
final case class GuestHtmlLayer(
  pageId: Option[UUID],
  depth: Option[Int],
  title: Option[String],
  top: Option[String],
  bottom: Option[String],
  inherit: Boolean)

final case class TrashedPage(
  page: Page,
  deletedAt: Instant,
  deletedBy: Option[String],
  count: Long)
final case class TrashedWorkspace(
  id: UUID,
  name: String,
  deletedAt: Instant,
  deletedBy: Option[String])

/** すべての永続化操作。各メソッドは1トランザクションで実行される。 */
final class Store(
  xa: Transactor[IO]) extends TxStore(xa) {

  // ---------------------------------------------------------------- users / groups

  /** ログインのたびに作成・更新する（メール・名前・アイコンの URL は IdP の値に合わせる）. */
  def upsertUser(
    sub: String,
    email: Option[String],
    name: String,
    picture: Option[String] = None
  ): IO[User] =
    // エージェント専用アカウント（AgentAccounts）にはログインさせない
    if AgentAccounts.isAgent(sub) then IO.raiseError(new IllegalArgumentException("agent accounts cannot log in"))
    else
      tx(
        sql"""insert into app_user (sub, email, name, avatar_url) values ($sub, $email, $name, $picture)
          on conflict (sub) do update set email = excluded.email, name = excluded.name, avatar_url = excluded.avatar_url
          returning id, sub, email, name""".query[User].unique
      )

  /** ログイン時にIdPのgroupsクレームでメンバーシップを置き換える（SCIM導入後はSCIMを正とする）。 */
  def syncGroups(
    userId: UUID,
    groupNames: List[String]
  ): IO[Unit] = tx {
    val names = groupNames.distinct.toArray
    for {
      ids <- sql"""insert into user_group (external_id, name) select g, g from unnest($names::text[]) g
                   on conflict (external_id) do update set name = excluded.name
                   returning id""".query[UUID].to[List]
      _ <- sql"delete from group_member where user_id = $userId".update.run
      gids = ids.toArray
      _ <- sql"""insert into group_member (group_id, user_id) select g, $userId from unnest($gids::uuid[]) g
                 on conflict do nothing""".update.run
    } yield ()
  }

  /** 有効なユーザーとそのグループ（メール送信時の権限確認用）。 */
  def userWithGroups(
    userId: UUID
  ): IO[Option[(User, List[Group])]] = tx {
    sql"select id, sub, email, name from app_user where id = $userId and not disabled".query[User].option.flatMap {
      case None => none[(User, List[Group])].pure[ConnectionIO]
      case Some(u) => groupsOfC(userId).map(gs => Some((u, gs)))
    }
  }

  def userNames(
    ids: List[UUID]
  ): IO[Map[UUID, String]] =
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(sql"select id, name from app_user where id = any(${ids.distinct.toArray})".query[(UUID, String)].to[List].map(
        _.toMap
      ))

  def isActive(
    userId: UUID
  ): IO[Boolean] = tx(
    sql"select not disabled from app_user where id = $userId".query[Boolean].option.map(_.getOrElse(false))
  )

  // ---------------------------------------------------------------- sessions / oidc state

  def createSession(
    tokenHash: Array[Byte],
    userId: UUID,
    expiresAt: Instant,
    idToken: Option[String] = None
  ): IO[Unit] = tx(
    sql"insert into session (token_hash, user_id, expires_at, id_token) values ($tokenHash, $userId, $expiresAt, $idToken)".update.run.void
  )

  /** セッションの id_token（OIDC でログインしたとき）. */
  def sessionIdToken(
    tokenHash: Array[Byte]
  ): IO[Option[String]] =
    tx(sql"select id_token from session where token_hash = $tokenHash".query[Option[String]].option.map(_.flatten))

  /** セッションを消し、IdP からログアウトするための id_token を返す. */
  def deleteSession(
    tokenHash: Array[Byte]
  ): IO[Option[String]] = tx(
    sql"delete from session where token_hash = $tokenHash returning id_token".query[Option[String]].option.map(
      _.flatten
    )
  )

  def findSession(
    tokenHash: Array[Byte],
    now: Instant
  ): IO[Option[(User, List[Group])]] = tx {
    sql"""select u.id, u.sub, u.email, u.name
          from session s join app_user u on u.id = s.user_id
          where s.token_hash = $tokenHash and s.expires_at > $now and not u.disabled"""
      .query[User].option.flatMap {
        case None => none[(User, List[Group])].pure[ConnectionIO]
        case Some(u) => groupsOfC(u.id).map(gs => Some((u, gs)))
      }
  }

  def purgeExpiredSessions(
    now: Instant
  ): IO[Int] = tx(
    sql"delete from session where expires_at <= $now".update.run
  )

  /** returnTo: ログイン後の戻り先（同じサイトの相対パス。確認済みのものだけ渡す） */
  def saveLoginState(
    state: String,
    verifier: String,
    nonce: String,
    now: Instant,
    returnTo: Option[String] = None
  ): IO[Unit] = tx {
    val cutoff = now.minusSeconds(600)
    sql"delete from login_state where created_at < $cutoff".update.run *>
      sql"insert into login_state (state, verifier, nonce, return_to) values ($state, $verifier, $nonce, $returnTo)".update.run.void
  }

  /** state を1回だけ消費して (verifier, nonce, 戻り先) を返す。 */
  def takeLoginState(
    state: String,
    now: Instant
  ): IO[Option[(String, String, Option[String])]] = tx {
    val cutoff = now.minusSeconds(600)
    sql"""delete from login_state where state = $state and created_at >= $cutoff
          returning verifier, nonce, return_to""".query[(String, String, Option[String])].option
  }

  // ---------------------------------------------------------------- workspaces

  private def aclEntries(
    rows: List[(PrincipalType, UUID, Role)]
  ): List[AclEntry] =
    rows.map(
      (
        t,
        id,
        r
      ) => AclEntry(Principal(t, id), r)
    )

  def createWorkspace(
    name: String,
    creator: UUID
  ): IO[Workspace] = tx {
    for {
      ws <- sql"insert into workspace (name, created_by) values ($name, $creator) returning id, name, default_role"
        .query[Workspace].unique
      _ <- sql"""insert into workspace_acl (workspace_id, principal_type, principal_id, role)
                 values (${ws.id}, ${PrincipalType.User}, $creator, ${Role.Edit})""".update.run
    } yield ws
  }

  private def getWorkspaceC(
    id: UUID
  ): ConnectionIO[Option[Workspace]] =
    sql"select id, name, default_role from workspace where id = $id and deleted_at is null".query[Workspace].option

  /** ゴミ箱用: 削除済みも含む。 */
  private def getWorkspaceAnyC(
    id: UUID
  ): ConnectionIO[Option[Workspace]] =
    sql"select id, name, default_role from workspace where id = $id".query[Workspace].option

  private def workspaceAclC(
    id: UUID
  ): ConnectionIO[List[AclEntry]] =
    (fr"select principal_type, principal_id, role from workspace_acl where workspace_id = $id and" ++ notExpired())
      .query[(PrincipalType, UUID, Role)].to[List].map(aclEntries)

  def workspaceAcl(
    id: UUID
  ): IO[List[AclEntry]] = tx(workspaceAclC(id))

  /** 期限付きのエントリの期限 */
  def workspaceAclExpiry(
    id: UUID
  ): IO[Map[Principal, Instant]] = tx(
    sql"""select principal_type, principal_id, expires_at from workspace_acl where workspace_id = $id and expires_at > now()"""
      .query[(PrincipalType, UUID, Instant)].to[List].map(_.map(
        (
          t,
          i,
          at
        ) => Principal(t, i) -> at
      ).toMap)
  )

  /** 期限を過ぎた権限を消す（消した件数） */
  def purgeExpiredAcl: IO[Int] = tx(
    (
      sql"delete from page_acl where expires_at <= now()".update.run,
      sql"delete from workspace_acl where expires_at <= now()".update.run
    ).mapN(_ + _)
  )

  def setWorkspaceAcl(
    id: UUID,
    defaultRole: Option[Role],
    entries: List[AclEntry],
    expires: Map[Principal, Instant] = Map.empty
  ): IO[Either[AppError, Unit]] = tx {
    getWorkspaceC(id).flatMap {
      case None => Left(AppError(ErrorCode.WorkspaceNotFound)).pure[ConnectionIO].widen
      case Some(_) =>
        for {
          _ <- sql"update workspace set default_role = $defaultRole where id = $id".update.run
          _ <- replaceAclC("workspace_acl", "workspace_id", id, entries, expires)
        } yield Right(())
    }
  }

  /** ACL を入れ替える（table / key は固定の名前だけを渡す） */
  private def replaceAclC(
    table: String,
    key: String,
    id: UUID,
    entries: List[AclEntry],
    expires: Map[Principal, Instant]
  ): ConnectionIO[Unit] = {
    val rows = entries.map(e => (id, e.principal.tpe, e.principal.id, e.role, expires.get(e.principal)))
    (Fragment.const(s"delete from $table where $key =") ++ fr"$id").update.run *>
      Update[(UUID, PrincipalType, UUID, Role, Option[Instant])](
        s"insert into $table ($key, principal_type, principal_id, role, expires_at) values (?, ?, ?, ?, ?)"
      ).updateMany(rows).void
  }

  /** ワークスペース直下の権限（管理者は編集） */
  private def wsRole(
    ws: Workspace,
    acl: List[AclEntry],
    s: AuthSession
  ): Option[Role] =
    if s.isAdmin then Some(Role.Edit) else Acl.resolve(Nil, acl, ws.defaultRole, s.principals)

  /** 見えるワークスペース（権限付き）。ワークスペース権限があるもの＋ページ単位で共有されているもの。 */
  def listWorkspaces(
    s: AuthSession
  ): IO[List[(Workspace, Role)]] = tx {
    for {
      all <- sql"select id, name, default_role from workspace where deleted_at is null order by name".query[
        Workspace
      ].to[List]
      acls <- (fr"select workspace_id, principal_type, principal_id, role from workspace_acl where" ++ notExpired())
        .query[(UUID, PrincipalType, UUID, Role)].to[List]
      shared <- (fr"select distinct p.workspace_id" ++ sharedPagesFr(s)).query[UUID].to[List].map(_.toSet)
    } yield {
      val byWs = acls.groupMap(_._1)(r => AclEntry(Principal(r._2, r._3), r._4))
      all.flatMap { ws =>
        wsRole(ws, byWs.getOrElse(ws.id, Nil), s) match {
          case Some(r) => Some(ws -> r)
          case None if shared(ws.id) => Some(ws -> Role.View) // 一部ページのみ共有: ツリー側で絞り込む
          case None => None
        }
      }
    }
  }

  /** 本人（または所属グループ）に個別に共有されたページ（from 句以降） */
  private def sharedPagesFr(
    s: AuthSession
  ): Fragment =
    fr"from page_acl a join page p on p.id = a.page_id where p.deleted_at is null and" ++ notExpired("a.expires_at") ++
      fr"and" ++
      Fragments.in(fr"a.principal_id", NonEmptyList.fromListUnsafe(s.principals.toList.map(_.id)))

  /** ワークスペースが見えるか（listWorkspaces に含まれるか）。直下の権限、またはページ単位の共有がある。 */
  def workspaceVisible(
    id: UUID,
    s: AuthSession
  ): IO[Boolean] = tx {
    workspaceRoleC(id, s, includeDeleted = false).flatMap {
      case Some(_) => true.pure[ConnectionIO]
      case None =>
        (fr"select exists(select 1" ++ sharedPagesFr(s) ++ fr"and p.workspace_id = $id" ++
          fr"and exists(select 1 from workspace w where w.id = $id and w.deleted_at is null))").query[Boolean].unique
    }
  }

  /** ワークスペース直下（ルート）に対する権限。ページ作成可否の判定に使う。 */
  def workspaceRole(
    id: UUID,
    s: AuthSession
  ): IO[Option[(Workspace, Role)]] = tx(workspaceRoleC(id, s, includeDeleted = false))

  private def workspaceRoleC(
    id: UUID,
    s: AuthSession,
    includeDeleted: Boolean
  ): ConnectionIO[Option[(Workspace, Role)]] =
    (if includeDeleted then getWorkspaceAnyC(id) else getWorkspaceC(id)).flatMap {
      case None => none[(Workspace, Role)].pure[ConnectionIO]
      case Some(ws) =>
        (if s.isAdmin then Nil.pure[ConnectionIO] else workspaceAclC(id)).map(acl => wsRole(ws, acl, s).map(ws -> _))
    }

  // ---------------------------------------------------------------- pages

  private val pageCols = fr"id, workspace_id, parent_id, position, title, icon, created_at, updated_at"

  private def getPageC(
    id: UUID
  )
    : ConnectionIO[Option[Page]] =
    (fr"select" ++ pageCols ++ fr"from page where id = $id and deleted_at is null").query[Page].option

  private def getPageAnyC(
    id: UUID
  ): ConnectionIO[Option[Page]] = (fr"select" ++ pageCols ++ fr"from page where id = $id").query[Page].option

  private def siblingsC(
    ws: UUID,
    parent: Option[UUID],
    excluding: Option[UUID]
  ): ConnectionIO[List[(UUID, String)]] = {
    val parentCond = parent match {
      case Some(p) => fr"parent_id = $p"
      case None => fr"parent_id is null"
    }
    val exclCond = excluding.fold(fr"true")(x => fr"id <> $x")
    (fr"select id, position from page where workspace_id = $ws and deleted_at is null and" ++ parentCond ++
      fr"and" ++ exclCond ++ fr"""order by position collate "C", id""").query[(UUID, String)].to[List]
  }

  /** 各ページから根までのACLノード列（先頭=対象ページ）。ページIDごとにまとめて1回で読む。 */
  private def aclChainsC(
    pageIds: List[UUID]
  ): ConnectionIO[Map[UUID, List[AclNode]]] =
    if pageIds.isEmpty then Map.empty[UUID, List[AclNode]].pure[ConnectionIO]
    else {
      val ids = pageIds.distinct.toArray
      (sql"""with recursive chain as (
              select id as root, id, parent_id, inherit_acl, 0 as depth from page where id = any($ids)
              union all
              select c.root, p.id, p.parent_id, p.inherit_acl, c.depth + 1
              from page p join chain c on p.id = c.parent_id
            )
            select c.root, c.depth, c.inherit_acl, a.principal_type, a.principal_id, a.role
            from chain c left join page_acl a on a.page_id = c.id and """ ++ notExpired("a.expires_at") ++ fr"""
            order by c.root, c.depth""")
        .query[(UUID, Int, Boolean, Option[PrincipalType], Option[UUID], Option[Role])].to[List].map { rows =>
          rows.groupBy(_._1).map {
            (
              root,
              rs
            ) =>
              root -> rs.groupBy(_._2).toList.sortBy(_._1).map {
                (
                  _,
                  ns
                ) =>
                  AclNode(
                    ns.head._3,
                    ns.collect { case (_, _, _, Some(t), Some(id), Some(r)) => AclEntry(Principal(t, id), r) }
                  )
              }
          }
        }
    }

  private def aclChainC(
    pageId: UUID
  ): ConnectionIO[List[AclNode]] = aclChainsC(List(pageId)).map(_.getOrElse(pageId, Nil))

  /** 複数ページの、保護を考えない権限をまとめて求める（ページ数によらずクエリ数は一定）。権限のないページは含めない。 */
  private def rawAccessManyC(
    ids: List[UUID],
    s: AuthSession,
    includeDeleted: Boolean
  ): ConnectionIO[Map[UUID, (Page, Role)]] =
    if ids.isEmpty then Map.empty[UUID, (Page, Role)].pure[ConnectionIO]
    else {
      val arr = ids.distinct.toArray
      val alive = if includeDeleted then fr"" else fr"and deleted_at is null"
      (fr"select" ++ pageCols ++ fr"from page where id = any($arr)" ++ alive).query[Page].to[List].flatMap { pages =>
        if s.isAdmin || pages.isEmpty then pages.map(p => p.id -> (p, Role.Edit)).toMap.pure[ConnectionIO]
        else {
          val wsIds = pages.map(_.workspaceId).distinct.toArray
          for {
            wss <- (fr"select id, name, default_role from workspace where id = any($wsIds)" ++ alive).query[
              Workspace
            ].to[List]
            acl <-
            (fr"select workspace_id, principal_type, principal_id, role from workspace_acl where workspace_id = any($wsIds) and" ++
              notExpired())
              .query[(UUID, PrincipalType, UUID, Role)].to[List]
            chains <- aclChainsC(pages.map(_.id))
          } yield {
            val defaults = wss.map(w => w.id -> w.defaultRole).toMap
            val byWs = acl.groupMap(_._1)(r => AclEntry(Principal(r._2, r._3), r._4))
            pages.flatMap { p =>
              Acl.resolve(
                chains.getOrElse(p.id, Nil),
                byWs.getOrElse(p.workspaceId, Nil),
                defaults.get(p.workspaceId).flatten,
                s.principals
              )
                .map(r => p.id -> (p, r))
            }.toMap
          }
        }
      }
    }

  /** 保護中のページは、保護した人・所有者・指定した人だけが編集できる（それ以外の人の編集権限を閲覧にする） */
  private def withLockC(
    raw: Map[UUID, (Page, Role)],
    s: AuthSession
  ): ConnectionIO[Map[UUID, (Page, Role)]] = {
    val editable = raw.collect { case (id, (_, Role.Edit)) => id }.toArray
    if editable.isEmpty then raw.pure[ConnectionIO]
    else
      (fr"select id from page where id = any($editable) and" ++ lockedOutFor(s.user.id)).query[UUID].to[List].map {
        ids =>
          val lockedOut = ids.toSet
          raw.map { case (id, (p, r)) => id -> (p, if lockedOut(id) then Role.View else r) }
      }
  }

  private def accessManyC(
    ids: List[UUID],
    s: AuthSession,
    includeDeleted: Boolean
  ): ConnectionIO[Map[UUID, (Page, Role)]] =
    rawAccessManyC(ids, s, includeDeleted).flatMap(withLockC(_, s))

  private def pageAccessC(
    id: UUID,
    s: AuthSession,
    includeDeleted: Boolean = false
  ): ConnectionIO[Option[(Page, Role)]] =
    accessManyC(List(id), s, includeDeleted).map(_.get(id))

  /** 保護を考えない権限と、実効の権限（保護の判定用）を1回で求める */
  def rawAndPageAccess(
    id: UUID,
    s: AuthSession
  ): IO[(Option[(Page, Role)], Option[(Page, Role)])] = tx(
    rawAccessManyC(List(id), s, includeDeleted = false).flatMap(raw =>
      withLockC(raw, s).map(eff => (raw.get(id), eff.get(id)))
    )
  )

  /** 全幅で表示するページ */
  def fullWidthIn(
    wsId: UUID
  ): IO[Set[UUID]] =
    tx(sql"select id from page where workspace_id = $wsId and full_width and deleted_at is null".query[
      UUID
    ].to[List].map(_.toSet))
  def isFullWidth(
    id: UUID
  ): IO[Boolean] =
    tx(sql"select full_width from page where id = $id".query[Boolean].option.map(_.getOrElse(false)))
  def setFullWidth(
    id: UUID,
    on: Boolean
  ): IO[Unit] = tx(sql"update page set full_width = $on where id = $id".update.run.void)

  /** ページのタイトル（権限の確認なし。自動化ルールなどサーバー内部用） */
  def pageTitle(
    id: UUID
  ): IO[Option[String]] = tx(sql"select title from page where id = $id and deleted_at is null".query[String].option)

  // ---------------------------------------------------------------- ページの保護

  def pageLock(
    id: UUID
  ): IO[Option[PageLock]] = tx(
    sql"""select p.locked_at, u.name, p.lock_reason, p.lock_editors, p.locked_by from page p left join app_user u on u.id = p.locked_by
          where p.id = $id and p.locked_at is not null and p.deleted_at is null"""
      .query[(Instant, Option[String], Option[String], List[UUID], Option[UUID])].option
      .map(_.map(
        (
          at,
          by,
          reason,
          eds,
          byId
        ) => PageLock(at, byId, by, reason, eds)
      ))
  )

  def setPageLock(
    id: UUID,
    by: Option[UUID],
    reason: Option[String],
    editors: List[UUID]
  ): IO[Boolean] = tx(
    by match {
      case Some(u) =>
        val eds = editors.distinct.toArray
        sql"""update page set locked_at = coalesce(locked_at, now()), locked_by = coalesce(locked_by, $u), lock_reason = $reason, lock_editors = $eds
              where id = $id and deleted_at is null""".update.run.map(_ > 0)
      case None =>
        sql"update page set locked_at = null, locked_by = null, lock_reason = null, lock_editors = '{}' where id = $id".update.run.map(
          _ > 0
        )
    }
  )

  /** ワークスペース内の保護中のページ */
  def lockedIn(
    wsId: UUID
  ): IO[Set[UUID]] = tx(
    sql"select id from page where workspace_id = $wsId and locked_at is not null and deleted_at is null".query[
      UUID
    ].to[List].map(_.toSet)
  )

  /** ページと、そのユーザーの実効権限。権限がなければ None（存在しない場合と区別しない）。 */
  def pageAccess(
    id: UUID,
    s: AuthSession
  ): IO[Option[(Page, Role)]] = tx(pageAccessC(id, s))

  /** 複数ページの pageAccess をまとめて行う（権限のないページは含めない）。 */
  def pagesAccess(
    ids: List[UUID],
    s: AuthSession
  ): IO[Map[UUID, (Page, Role)]] = tx(accessManyC(ids, s, includeDeleted = false))

  /** ids のうち見えるものを、順序を保って返す。 */
  def visiblePages(
    ids: List[UUID],
    s: AuthSession
  ): IO[List[(Page, Role)]] = pagesAccess(ids, s).map(m => ids.flatMap(m.get))

  /** ワークスペース内の閲覧可能な全ページ（権限付き）。 */
  def listPages(
    wsId: UUID,
    s: AuthSession
  ): IO[List[(Page, Role)]] = tx {
    for {
      ws <- getWorkspaceC(wsId)
      wsAcl <- workspaceAclC(wsId)
      pages <-
      (fr"select" ++ pageCols ++
        fr"""from page where workspace_id = $wsId and deleted_at is null
             order by position collate "C", id""").query[Page].to[List]
      rows <-
      (fr"""select p.id, p.inherit_acl, a.principal_type, a.principal_id, a.role
                    from page p left join page_acl a on a.page_id = p.id and""" ++ notExpired("a.expires_at") ++
        fr"where p.workspace_id = $wsId and p.deleted_at is null")
        .query[(UUID, Boolean, Option[PrincipalType], Option[UUID], Option[Role])].to[List]
      lockedOut <-
      (fr"select id from page where workspace_id = $wsId and deleted_at is null and" ++ lockedOutFor(s.user.id))
        .query[UUID].to[List].map(_.toSet)
    } yield {
      val raw =
        if s.isAdmin then pages.map(_ -> Role.Edit)
        else {
          val nodes = rows.groupBy(_._1).map {
            (
              id,
              rs
            ) =>
              id -> AclNode(
                rs.head._2,
                rs.collect { case (_, _, Some(t), Some(i), Some(r)) => AclEntry(Principal(t, i), r) }
              )
          }
          val roles = Acl.resolveAll(pages, nodes, wsAcl, ws.flatMap(_.defaultRole), s.principals)
          pages.flatMap(p => roles.get(p.id).map(p -> _))
        }
      // 保護中のページは、編集できる人以外は閲覧のみ
      raw.map(
        (
          p,
          r
        ) => if r == Role.Edit && lockedOut(p.id) then p -> Role.View else p -> r
      )
    }
  }

  def createPage(
    wsId: UUID,
    parentId: Option[UUID],
    placement: Placement,
    title: String,
    icon: Option[String],
    creator: UUID,
    /** データベースの項目の値（JSON） */
    props: String = "{}"
  ): IO[Either[AppError, Page]] = tx {
    val parentOk: ConnectionIO[Either[AppError, Unit]] = parentId match {
      case None => Right(()).pure[ConnectionIO]
      case Some(pid) =>
        getPageC(pid).map {
          case Some(p) if p.workspaceId == wsId => Right(())
          case _ => Left(AppError(ErrorCode.ParentPageNotFound))
        }
    }
    parentOk.flatMap {
      case Left(e) => Left(e).pure[ConnectionIO].widen
      case Right(_) =>
        siblingsC(wsId, parentId, None).flatMap { sib =>
          FractionalIndex.place(sib, placement) match {
            case Left(msg) => Left(AppError(ErrorCode.InvalidInput, msg)).pure[ConnectionIO].widen
            case Right(pos) =>
              (fr"""insert into page (workspace_id, parent_id, position, title, icon, created_by, props)
                    values ($wsId, $parentId, $pos, $title, $icon, $creator, jsonb_strip_nulls('{}'::jsonb || $props::jsonb)) returning""" ++
                pageCols)
                .query[Page].unique.map(Right(_))
          }
        }
    }
  }

  /**
   * 親の下の末尾に、ページをまとめて作る（データベースの項目の取り込み用）。items: (タイトル, 項目の値の JSON)。
   * 1件ずつ createPage してから値を入れるのと同じ結果になる（作成日時も並べた順に少しずつずらす）。
   */
  def createPages(
    wsId: UUID,
    parentId: UUID,
    items: List[(String, String)],
    creator: UUID
  ): IO[Either[AppError, Int]] =
    if items.isEmpty then IO.pure(Right(0))
    else
      tx {
        getPageC(parentId).flatMap {
          case Some(p) if p.workspaceId == wsId =>
            siblingsC(wsId, Some(parentId), None).flatMap { sib =>
              val positions = items.scanLeft(sib.lastOption.map(_._2))(
                (
                  last,
                  _
                ) => Some(FractionalIndex.between(last, None))
              ).tail.flatten.toArray
              val titles = items.map(_._1).toArray
              val props = items.map(_._2).toArray
              sql"""insert into page (workspace_id, parent_id, position, title, created_by, created_at, updated_at, props)
                  select $wsId, $parentId, m.pos, m.title, $creator, now() + (m.ord - 1) * interval '1 microsecond',
                         now() + (m.ord - 1) * interval '1 microsecond', jsonb_strip_nulls('{}'::jsonb || m.props::jsonb)
                  from unnest($positions::text[], $titles::text[], $props::text[]) with ordinality as m(pos, title, props, ord)"""
                .update.run.map(Right(_))
            }
          case _ => Left(AppError(ErrorCode.ParentPageNotFound)).pure[ConnectionIO].widen
        }
      }

  def updatePage(
    id: UUID,
    title: Option[String],
    icon: Option[Option[String]]
  ): IO[Option[Page]] = tx {
    val t = title.fold(fr"title = title")(x => fr"title = $x")
    val i = icon.fold(fr"icon = icon")(x => fr"icon = $x")
    (fr"update page set" ++ t ++ fr"," ++ i ++
      fr", updated_at = now() where id = $id and deleted_at is null returning" ++ pageCols)
      .query[Page].option
  }

  /** 親・位置を変更する。自分自身や子孫の下への移動は拒否。 */
  def movePage(
    id: UUID,
    newParent: Option[UUID],
    placement: Placement
  ): IO[Either[AppError, Page]] = tx {
    getPageC(id).flatMap {
      case None => Left(AppError(ErrorCode.PageNotFound)).pure[ConnectionIO].widen
      case Some(page) =>
        val cycle: ConnectionIO[Boolean] = newParent match {
          case None => false.pure[ConnectionIO]
          case Some(np) =>
            sql"""with recursive anc as (
                    select id, parent_id from page where id = $np
                    union all select p.id, p.parent_id from page p join anc a on p.id = a.parent_id
                  ) select exists(select 1 from anc where id = $id)""".query[Boolean].unique
        }
        val parentOk: ConnectionIO[Boolean] = newParent match {
          case None => true.pure[ConnectionIO]
          case Some(np) => getPageC(np).map(_.exists(_.workspaceId == page.workspaceId))
        }
        (parentOk, cycle).tupled.flatMap {
          case (false, _) => Left(AppError(ErrorCode.ParentPageNotFound)).pure[ConnectionIO].widen
          case (_, true) => Left(AppError(ErrorCode.PageMoveIntoSelf)).pure[ConnectionIO].widen
          case _ =>
            siblingsC(page.workspaceId, newParent, Some(id)).flatMap { sib =>
              FractionalIndex.place(sib, placement) match {
                case Left(msg) => Left(AppError(ErrorCode.InvalidInput, msg)).pure[ConnectionIO].widen
                case Right(pos) =>
                  (fr"update page set parent_id = $newParent, position = $pos, updated_at = now() where id = $id returning" ++
                    pageCols)
                    .query[Page].unique.map(Right(_))
              }
            }
        }
    }
  }

  /** ページと子孫をゴミ箱へ移す（ソフトデリート）。削除件数を返す。まとめて消したページは deleted_root で復元する。 */
  /** 削除（inTrash=false）・完全削除（inTrash=true）でいっしょに消える子孫（自身は除く） */
  def subtreeIds(
    id: UUID,
    inTrash: Boolean
  ): IO[List[UUID]] = tx(
    (if inTrash then
       sql"""with recursive sub as (
               select id from page where id = $id
               union all select p.id from page p join sub s on p.parent_id = s.id where p.deleted_at is not null)
             select id from sub where id <> $id"""
     else
       sql"""with recursive sub as (
               select id, deleted_at from page where id = $id
               union all select p.id, p.deleted_at from page p join sub s on p.parent_id = s.id)
             select id from sub where id <> $id and deleted_at is null"""
    ).query[UUID].to[List]
  )

  def deletePage(
    id: UUID,
    by: Option[UUID] = None
  ): IO[Int] = tx(
    sql"""with recursive sub as (
            select id from page where id = $id
            union all select p.id from page p join sub s on p.parent_id = s.id
          )
          update page set deleted_at = now(), deleted_by = $by, deleted_root = $id
          where id in (select id from sub) and deleted_at is null""".update.run
  )

  // ---------------------------------------------------------------- 閲覧できる人

  /** 有効なユーザー全員について、このページへの実効権限と根拠を求める。chainTitles は対象ページから根までのタイトル。 */
  def audience(
    pageId: UUID,
    adminGroups: Set[String]
  ): IO[Option[Audience]] = tx {
    getPageC(pageId).flatMap {
      case None => none[Audience].pure[ConnectionIO]
      case Some(p) =>
        for {
          ws <- getWorkspaceC(p.workspaceId)
          wsAcl <- workspaceAclC(p.workspaceId)
          chain <- aclChainC(pageId)
          titles <- sql"""with recursive c as (
                             select id, parent_id, title, 0 as d from page where id = $pageId
                             union all select p.id, p.parent_id, p.title, c.d + 1 from page p join c on p.id = c.parent_id
                           ) select title from c order by d""".query[String].to[List]
          users <-
            sql"""select u.id, u.sub, u.name, u.email,
                                  coalesce(array_agg(g.id) filter (where g.id is not null), '{}'),
                                  coalesce(array_agg(g.external_id) filter (where g.id is not null), '{}')
                           from app_user u left join group_member m on m.user_id = u.id left join user_group g on g.id = m.group_id
                           where not u.disabled group by u.id, u.sub, u.name, u.email order by u.name"""
              .query[(UUID, String, String, Option[String], List[UUID], List[String])].to[List]
        } yield {
          val entries = users.flatMap {
            (
              id,
              sub,
              name,
              email,
              gids,
              gext
            ) =>
              if AgentAccounts.isAdmin(sub, gext, adminGroups) then
                Some(AudienceEntry(id, name, email, Role.Edit, None))
              else {
                val who = gids.map(Principal(PrincipalType.Group, _)).toSet + Principal(PrincipalType.User, id)
                Acl.explain(chain, wsAcl, ws.flatMap(_.defaultRole), who).map(
                  (
                    r,
                    src
                  ) => AudienceEntry(id, name, email, r, Some(src))
                )
              }
          }
          Some(Audience(entries, titles))
        }
    }
  }

  /**
   * 指定したユーザーそれぞれの、このページへの実効権限（pageAccess と同じ規則で、保護も考える）。
   * 無効なユーザー・権限のないユーザーは含めない。adminGroups: 管理者とみなすグループ（外部ID）。
   */
  def userRoles(
    pageId: UUID,
    users: Set[UUID],
    adminGroups: Set[String]
  ): IO[Map[UUID, Role]] =
    if users.isEmpty then IO.pure(Map.empty)
    else
      tx {
        val arr = users.toArray
        getPageC(pageId).flatMap {
          case None => Map.empty[UUID, Role].pure[ConnectionIO]
          case Some(p) =>
            for {
              ws <- getWorkspaceC(p.workspaceId)
              wsAcl <- workspaceAclC(p.workspaceId)
              chain <- aclChainC(pageId)
              us <-
                sql"""select u.id, u.sub, coalesce(array_agg(g.id) filter (where g.id is not null), '{}'),
                                   coalesce(array_agg(g.external_id) filter (where g.id is not null), '{}')
                            from app_user u left join group_member m on m.user_id = u.id left join user_group g on g.id = m.group_id
                            where not u.disabled and u.id = any($arr) group by u.id, u.sub"""
                  .query[(UUID, String, List[UUID], List[String])].to[List]
              lockedOut <-
              (fr"select x from unnest($arr::uuid[]) x join page on page.id = $pageId where" ++ lockedOutForFr(fr"x"))
                .query[UUID].to[List].map(_.toSet)
            } yield us.flatMap {
              (
                id,
                sub,
                gids,
                gext
              ) =>
                val role =
                  if AgentAccounts.isAdmin(sub, gext, adminGroups) then Some(Role.Edit)
                  else
                    Acl.resolve(
                      chain,
                      wsAcl,
                      ws.flatMap(_.defaultRole),
                      gids.map(Principal(PrincipalType.Group, _)).toSet + Principal(PrincipalType.User, id)
                    )
                role.map(r => id -> (if r == Role.Edit && lockedOut(id) then Role.View else r))
            }.toMap
        }
      }

  // ---------------------------------------------------------------- ゴミ箱

  /** 削除済みページも含めた権限（ゴミ箱の操作用）。 */
  def pageAccessAny(
    id: UUID,
    s: AuthSession
  ): IO[Option[(Page, Role)]] = tx(pageAccessC(id, s, includeDeleted = true))
  def pagesAccessAny(
    ids: List[UUID],
    s: AuthSession
  ): IO[Map[UUID, (Page, Role)]] = tx(accessManyC(ids, s, includeDeleted = true))

  /** ワークスペースのゴミ箱（削除操作の単位ごと。新しい順）。ワークスペースごと削除した分は含めない。 */
  def trashedPages(
    wsId: UUID
  ): IO[List[TrashedPage]] = tx(
    sql"""select p.id, p.workspace_id, p.parent_id, p.position, p.title, p.icon, p.created_at, p.updated_at,
                 p.deleted_at, u.name,
                 (select count(*) from page c where c.deleted_root = p.id and c.deleted_at = p.deleted_at)
          from page p join workspace w on w.id = p.workspace_id
          left join app_user u on u.id = p.deleted_by
          where p.workspace_id = $wsId and w.deleted_at is null and p.deleted_at is not null and p.deleted_root = p.id
          order by p.deleted_at desc limit 500""".query[TrashedPage].to[List]
  )

  /** ゴミ箱から戻すと、元の親がもう無い（削除済み）ためにワークスペース直下へ移るか */
  def restoreMovesToRoot(
    id: UUID
  ): IO[Boolean] = tx(
    getPageAnyC(id).flatMap {
      case Some(p) if p.parentId.isDefined => p.parentId.flatTraverse(getPageC).map(_.isEmpty)
      case _ => false.pure[ConnectionIO]
    }
  )

  /** ゴミ箱から戻す。親がもう無い（削除済み）場合はワークスペース直下の末尾に戻す。 */
  def restorePage(
    id: UUID
  ): IO[Either[AppError, Page]] = tx {
    sql"select deleted_at from page where id = $id and deleted_root = $id and deleted_at is not null"
      .query[Instant].option.flatMap {
        case None => Left(AppError(ErrorCode.TrashedPageNotFound)).pure[ConnectionIO].widen
        case Some(at) =>
          for {
            page <- getPageAnyC(id).map(_.get)
            parent <- page.parentId.flatTraverse(getPageC).map(_.map(_.id))
            sib <- siblingsC(page.workspaceId, parent, Some(id))
            pos = if parent == page.parentId && !sib.exists(_._2 == page.position) then page.position
            else FractionalIndex.place(sib, Placement.Last).getOrElse(page.position)
            _ <- sql"""update page set deleted_at = null, deleted_by = null, deleted_root = null
                       where deleted_root = $id and deleted_at = $at""".update.run
            np <-
            (fr"update page set parent_id = $parent, position = $pos, updated_at = now() where id = $id returning" ++
              pageCols)
              .query[Page].unique
          } yield Right(np)
      }
  }

  /** ゴミ箱から完全に削除する（子孫の削除済みページも含む）。添付ファイルの保存先キーを返す（実体の削除用）。 */
  def purgePage(
    id: UUID
  ): IO[Option[List[String]]] = tx {
    sql"select exists(select 1 from page where id = $id and deleted_at is not null)".query[Boolean].unique.flatMap {
      case false => none[List[String]].pure[ConnectionIO]
      case true =>
        val sub = fr"""with recursive sub as (
                         select id from page where id = $id and deleted_at is not null
                         union all select p.id from page p join sub s on p.parent_id = s.id where p.deleted_at is not null
                       )"""
        for {
          keys <- (sub ++ fr"select a.s3_key from attachment a where a.page_id in (select id from sub)").query[
            String
          ].to[List]
          _ <- (sub ++ fr"delete from page where id in (select id from sub)").update.run
        } yield Some(keys)
    }
  }

  /** 保存期間を過ぎたゴミ箱の中身（ページ・ワークスペース）を完全に削除する。添付ファイルの保存先キーを返す。 */
  def purgeExpired(
    cutoff: Instant
  ): IO[List[String]] = tx {
    for {
      keys <- sql"""select a.s3_key from attachment a join page p on p.id = a.page_id
                    left join workspace w on w.id = p.workspace_id
                    where p.deleted_at < $cutoff or w.deleted_at < $cutoff""".query[String].to[List]
      _ <- sql"delete from workspace where deleted_at < $cutoff".update.run
      _ <- sql"delete from page where deleted_at < $cutoff".update.run
    } yield keys
  }

  // ---------------------------------------------------------------- ワークスペースの名前変更・削除

  def renameWorkspace(
    id: UUID,
    name: String
  ): IO[Option[Workspace]] = tx(
    sql"update workspace set name = $name where id = $id and deleted_at is null returning id, name, default_role".query[
      Workspace
    ].option
  )

  /** ワークスペースをゴミ箱へ（中のページも同じ時刻で削除扱い）。 */
  def deleteWorkspace(
    id: UUID,
    by: UUID
  ): IO[Boolean] = tx {
    sql"update workspace set deleted_at = now(), deleted_by = $by where id = $id and deleted_at is null".update.run.flatMap {
      case 0 => false.pure[ConnectionIO]
      case _ =>
        sql"""update page set deleted_at = now(), deleted_by = $by, deleted_root = id
              where workspace_id = $id and deleted_at is null""".update.run.as(true)
    }
  }

  def restoreWorkspace(
    id: UUID
  ): IO[Option[Workspace]] = tx {
    sql"select deleted_at from workspace where id = $id and deleted_at is not null".query[Instant].option.flatMap {
      case None => none[Workspace].pure[ConnectionIO]
      case Some(at) =>
        sql"""update page set deleted_at = null, deleted_by = null, deleted_root = null
              where workspace_id = $id and deleted_at = $at""".update.run *>
          sql"update workspace set deleted_at = null, deleted_by = null where id = $id returning id, name, default_role"
            .query[Workspace].option
    }
  }

  def purgeWorkspace(
    id: UUID
  ): IO[Option[List[String]]] = tx {
    sql"select exists(select 1 from workspace where id = $id and deleted_at is not null)".query[
      Boolean
    ].unique.flatMap {
      case false => none[List[String]].pure[ConnectionIO]
      case true =>
        for {
          keys <-
            sql"select a.s3_key from attachment a join page p on p.id = a.page_id where p.workspace_id = $id".query[
              String
            ].to[List]
          _ <- sql"delete from workspace where id = $id".update.run
        } yield Some(keys)
    }
  }

  /** 削除済みワークスペースの権限（ゴミ箱の操作用）。 */
  def workspaceRoleAny(
    id: UUID,
    s: AuthSession
  ): IO[Option[(Workspace, Role)]] = tx(workspaceRoleC(id, s, includeDeleted = true))

  /** 削除済みワークスペースのうち、編集権限があったもの（新しい順）。 */
  def trashedWorkspaces(
    s: AuthSession
  ): IO[List[TrashedWorkspace]] = tx {
    sql"""select w.id, w.name, w.default_role, w.deleted_at, u.name from workspace w
          left join app_user u on u.id = w.deleted_by where w.deleted_at is not null order by w.deleted_at desc"""
      .query[(Workspace, Instant, Option[String])].to[List].flatMap { ws =>
        // ワークスペースの権限はまとめて読む
        val ids = ws.map(_._1.id).toArray
        val acls =
          if s.isAdmin || ids.isEmpty then Map.empty[UUID, List[AclEntry]].pure[ConnectionIO]
          else
            (fr"select workspace_id, principal_type, principal_id, role from workspace_acl where workspace_id = any($ids) and" ++
              notExpired())
              .query[(UUID, PrincipalType, UUID, Role)].to[List].map(_.groupMap(_._1)(r =>
                AclEntry(Principal(r._2, r._3), r._4)
              ))
        acls.map { byWs =>
          ws.flatMap(
            (
              w,
              at,
              by
            ) =>
              Option.when(wsRole(
                w,
                byWs.getOrElse(w.id, Nil),
                s
              ).contains(Role.Edit))(TrashedWorkspace(w.id, w.name, at, by))
          )
        }
      }
  }

  // ---------------------------------------------------------------- 所有者・状態

  /** ページの詳細に付ける情報（所有者・状態・データベースか・保護中か・全幅）を1回で読む */
  def pageDetail(
    id: UUID
  ): IO[PageDetail] = tx(
    sql"""select p.owner_id, u.name, p.status,
                 exists(select 1 from page_database d where d.page_id = p.id),
                 p.locked_at is not null and p.deleted_at is null, p.full_width
          from page p left join app_user u on u.id = p.owner_id where p.id = $id"""
      .query[(Option[UUID], Option[String], Option[String], Boolean, Boolean, Boolean)].option
      .map(_.fold(PageDetail(PageMeta(None, None, None), false, false, false))(
        (
          o,
          n,
          st,
          db,
          locked,
          fw
        ) => PageDetail(PageMeta(o, n, st), db, locked, fw)
      ))
  )

  def pageMeta(
    ids: List[UUID]
  ): IO[Map[UUID, PageMeta]] =
    if ids.isEmpty then IO.pure(Map.empty)
    else
      tx(
        sql"""select p.id, p.owner_id, u.name, p.status from page p left join app_user u on u.id = p.owner_id
            where p.id = any(${ids.toArray}) and (p.owner_id is not null or p.status is not null)"""
          .query[(UUID, Option[UUID], Option[String], Option[String])].to[List]
          .map(_.map(
            (
              id,
              o,
              n,
              st
            ) => id -> PageMeta(o, n, st)
          ).toMap)
      )

  /** owner / status: None=変更しない、Some(None)=外す。所有者は存在するユーザーのみ（いなければ false） */
  def setPageMeta(
    id: UUID,
    owner: Option[Option[UUID]],
    status: Option[Option[String]]
  ): IO[Boolean] = tx {
    val ownerOk = owner.flatten.fold(true.pure[ConnectionIO])(o =>
      sql"select exists(select 1 from app_user where id = $o and not disabled)".query[Boolean].unique
    )
    ownerOk.flatMap {
      case false => false.pure[ConnectionIO]
      case true =>
        val sets = owner.map(o => fr"owner_id = $o").toList ++ status.map(st => fr"status = $st").toList
        if sets.isEmpty then true.pure[ConnectionIO]
        else
          (fr"update page set" ++ sets.reduce(_ ++ fr"," ++ _) ++
            fr", updated_at = now() where id = $id and deleted_at is null").update.run.map(_ > 0)
    }
  }

  // ---------------------------------------------------------------- カスタムCSS

  def workspaceCss(
    id: UUID
  ): IO[Option[String]] =
    tx(sql"select custom_css from workspace where id = $id".query[Option[String]].option.map(_.flatten))

  def setWorkspaceCss(
    id: UUID,
    css: Option[String]
  ): IO[Boolean] =
    tx(sql"update workspace set custom_css = $css where id = $id and deleted_at is null".update.run.map(_ > 0))

  def pageCss(
    id: UUID
  ): IO[Option[(Option[String], Boolean)]] =
    tx(sql"select custom_css, css_inherit from page where id = $id and deleted_at is null".query[(
      Option[String],
      Boolean
    )].option)

  def setPageCss(
    id: UUID,
    css: Option[String],
    inherit: Boolean
  ): IO[Boolean] =
    tx(sql"update page set custom_css = $css, css_inherit = $inherit where id = $id and deleted_at is null".update.run.map(
      _ > 0
    ))

  /** 適用するCSSを重ねる順に返す: ワークスペース → 子にも適用する祖先（根から） → そのページ。空のものは除く。 */
  def cssLayers(
    pageId: UUID
  ): IO[List[CssLayer]] = tx {
    for {
      chain <- sql"""with recursive c as (
                       select id, parent_id, workspace_id, title, custom_css, css_inherit, 0 as d from page where id = $pageId and deleted_at is null
                       union all
                       select p.id, p.parent_id, p.workspace_id, p.title, p.custom_css, p.css_inherit, c.d + 1 from page p join c on p.id = c.parent_id
                     ) select id, workspace_id, title, custom_css, css_inherit, d from c order by d desc"""
        .query[(UUID, UUID, String, Option[String], Boolean, Int)].to[List]
      wsCss <- chain.headOption.fold(none[String].pure[ConnectionIO])(
        (
          _,
          ws,
          _,
          _,
          _,
          _
        ) =>
          sql"select custom_css from workspace where id = $ws".query[Option[String]].unique
      )
    } yield {
      val pages = chain.collect {
        case (id, _, title, Some(css), inh, d) if css.trim.nonEmpty && (d == 0 || inh) =>
          CssLayer(Some(id), Some(d), Some(title), css, inh)
      }
      wsCss.filter(_.trim.nonEmpty).map(c => CssLayer(None, None, None, c, true)).toList ++ pages
    }
  }

  // ---------------------------------------------------------------- ゲスト向けHTML

  def workspaceGuestHtml(
    id: UUID
  ): IO[Option[(Option[String], Option[String])]] =
    tx(sql"select guest_top_html, guest_bottom_html from workspace where id = $id and deleted_at is null".query[(
      Option[String],
      Option[String]
    )].option)

  def setWorkspaceGuestHtml(
    id: UUID,
    top: Option[String],
    bottom: Option[String]
  ): IO[Boolean] =
    tx(
      sql"update workspace set guest_top_html = $top, guest_bottom_html = $bottom where id = $id and deleted_at is null".update.run.map(
        _ > 0
      )
    )

  def pageGuestHtml(
    id: UUID
  ): IO[Option[(Option[String], Option[String], Boolean)]] =
    tx(sql"select guest_top_html, guest_bottom_html, guest_html_inherit from page where id = $id and deleted_at is null"
      .query[(Option[String], Option[String], Boolean)].option)

  def setPageGuestHtml(
    id: UUID,
    top: Option[String],
    bottom: Option[String],
    inherit: Boolean
  ): IO[Boolean] =
    tx(sql"""update page set guest_top_html = $top, guest_bottom_html = $bottom, guest_html_inherit = $inherit
            where id = $id and deleted_at is null""".update.run.map(_ > 0))

  /** 外側から内側の順（ワークスペース → 子にも入れる祖先（根から） → そのページ）。中身のないものは除く。 */
  def guestHtmlLayers(
    pageId: UUID
  ): IO[List[GuestHtmlLayer]] = tx {
    for {
      chain <-
        sql"""with recursive c as (
                       select id, parent_id, workspace_id, title, guest_top_html, guest_bottom_html, guest_html_inherit, 0 as d
                       from page where id = $pageId and deleted_at is null
                       union all
                       select p.id, p.parent_id, p.workspace_id, p.title, p.guest_top_html, p.guest_bottom_html, p.guest_html_inherit, c.d + 1
                       from page p join c on p.id = c.parent_id
                     ) select id, workspace_id, title, guest_top_html, guest_bottom_html, guest_html_inherit, d from c order by d desc"""
          .query[(UUID, UUID, String, Option[String], Option[String], Boolean, Int)].to[List]
      ws <- chain.headOption.fold(none[(Option[String], Option[String])].pure[ConnectionIO])(r =>
        sql"select guest_top_html, guest_bottom_html from workspace where id = ${r._2}".query[(
          Option[String],
          Option[String]
        )].option
      )
    } yield {
      val ne = (o: Option[String]) => o.filter(_.trim.nonEmpty)
      val pages = chain.collect {
        case (id, _, title, t, b, inh, d) if (ne(t).isDefined || ne(b).isDefined) && (d == 0 || inh) =>
          GuestHtmlLayer(Some(id), Some(d), Some(title), ne(t), ne(b), inh)
      }
      ws.flatMap(
        (
          t,
          b
        ) => Option.when(ne(t).isDefined || ne(b).isDefined)(GuestHtmlLayer(None, None, None, ne(t), ne(b), true))
      ).toList ++ pages
    }
  }

  // ---------------------------------------------------------------- リンク切れ

  /** ワークスペース内のページから出ているリンクのうち、先が存在しない・削除済みのもの、および先が別にあるもの（権限確認用）。 */
  def linkTargets(
    wsId: UUID
  ): IO[List[LinkTargetRow]] = tx(
    sql"""select l.source_id, s.title, l.target_id, t.id is not null, t.deleted_at is not null
          from page_link l join page s on s.id = l.source_id left join page t on t.id = l.target_id
          where s.workspace_id = $wsId and s.deleted_at is null
          order by s.title, l.target_id""".query[LinkTargetRow].to[List]
  )

  /** ワークスペースの点検用: ページごとの被リンク数（削除済み・自分自身からを除く）、本文の最終更新、最後に編集した人、子ページ数。 */
  def pageHealth(
    wsId: UUID
  ): IO[List[PageHealthRow]] = tx(
    sql"""select p.id,
                 (select count(distinct l.source_id) from page_link l join page s on s.id = l.source_id
                   where l.target_id = p.id and l.source_id <> p.id and s.deleted_at is null),
                 greatest(p.updated_at, coalesce((select c.created_at from page_snapshot c where c.page_id = p.id and c.kind = 'compact'), p.updated_at)),
                 (select u.name from page_edit e join app_user u on u.id = e.user_id where e.page_id = p.id order by e.edited_at desc limit 1),
                 (select count(*) from page c where c.parent_id = p.id and c.deleted_at is null)
          from page p where p.workspace_id = $wsId and p.deleted_at is null""".query[PageHealthRow].to[List]
  )

  // ---------------------------------------------------------------- ワークスペースをまたぐ移動

  /** ページを子孫ごと別のワークスペースへ移す（親は移動先のページ、なければ直下）。 */
  def moveToWorkspace(
    id: UUID,
    targetWs: UUID,
    newParent: Option[UUID],
    placement: Placement
  ): IO[Either[AppError, Page]] = tx {
    (getPageC(id), getWorkspaceC(targetWs), newParent.flatTraverse(getPageC)).tupled.flatMap {
      case (None, _, _) => Left(AppError(ErrorCode.PageNotFound)).pure[ConnectionIO].widen
      case (_, None, _) => Left(AppError(ErrorCode.WorkspaceNotFound)).pure[ConnectionIO].widen
      case (_, _, None) if newParent.isDefined => Left(AppError(ErrorCode.ParentPageNotFound)).pure[ConnectionIO].widen
      case (_, _, Some(pp)) if pp.workspaceId != targetWs =>
        Left(AppError(ErrorCode.ParentInOtherWorkspace)).pure[ConnectionIO].widen
      case (Some(_), Some(_), _) =>
        siblingsC(targetWs, newParent, Some(id)).flatMap { sib =>
          FractionalIndex.place(sib, placement) match {
            case Left(msg) => Left(AppError(ErrorCode.InvalidInput, msg)).pure[ConnectionIO].widen
            case Right(pos) =>
              for {
                _ <- sql"""with recursive sub as (
                             select id from page where id = $id
                             union all select p.id from page p join sub s on p.parent_id = s.id where p.deleted_at is null
                           ) update page set workspace_id = $targetWs where id in (select id from sub)""".update.run
                np <-
                (fr"update page set parent_id = $newParent, position = $pos, updated_at = now() where id = $id returning" ++
                  pageCols)
                  .query[Page].unique
              } yield Right(np)
          }
        }
    }
  }

  // ---------------------------------------------------------------- ラベル

  /** ワークスペース内の全ラベル（ページID → ラベル）。 */
  def labelsIn(
    wsId: UUID
  ): IO[Map[UUID, List[String]]] = tx(
    sql"""select l.page_id, l.label from page_label l join page p on p.id = l.page_id
          where p.workspace_id = $wsId and p.deleted_at is null order by l.label"""
      .query[(UUID, String)].to[List].map(_.groupMap(_._1)(_._2))
  )

  def labels(
    pageId: UUID
  ): IO[List[String]] = tx(
    sql"select label from page_label where page_id = $pageId order by label".query[String].to[List]
  )

  def setLabels(
    pageId: UUID,
    labels: List[String]
  ): IO[Unit] = tx {
    val ls = labels.toArray
    sql"delete from page_label where page_id = $pageId".update.run *>
      sql"insert into page_label (page_id, label) select $pageId, l from unnest($ls::text[]) l on conflict do nothing".update.run.void
  }

  // ---------------------------------------------------------------- page acl

  def getPageAcl(
    id: UUID
  ): IO[Option[PageAcl]] = tx {
    sql"select inherit_acl from page where id = $id and deleted_at is null".query[Boolean].option.flatMap {
      case None => none[PageAcl].pure[ConnectionIO]
      case Some(inh) =>
        (fr"select principal_type, principal_id, role, expires_at from page_acl where page_id = $id and" ++
          notExpired())
          .query[(PrincipalType, UUID, Role, Option[Instant])].to[List]
          .map(rs =>
            Some(PageAcl(
              inh,
              aclEntries(rs.map(r => (r._1, r._2, r._3))),
              rs.collect { case (t, i, _, Some(at)) => Principal(t, i) -> at }.toMap
            ))
          )
    }
  }

  def setPageAcl(
    id: UUID,
    acl: PageAcl
  ): IO[Boolean] = tx {
    sql"update page set inherit_acl = ${acl.inherit}, updated_at = now() where id = $id and deleted_at is null".update.run.flatMap {
      case 0 => false.pure[ConnectionIO]
      case _ =>
        replaceAclC("page_acl", "page_id", id, acl.entries.distinctBy(_.principal), acl.expires).as(true)
    }
  }

  // ---------------------------------------------------------------- ページの複製

  /**
   * ページ（と子孫）の複製を作る。本文と添付ファイルは呼び出し側で入れる。
   *  - visible: 複製する人が閲覧できるページ（閲覧できない子ページとその下は複製しない）
   *  - タイトル・アイコン・ラベル・カスタムCSS・ゲスト向けHTML・個別の共有設定を引き継ぐ（公開リンク・所有者・状態は引き継がない）
   *  - 個別に権限を絞ったページ（継承しない）は、複製した人が編集できるようにする（本文を入れるため）
   * 戻り値: (元のページID, 複製のID) の一覧（先頭が複製の起点）。
   */
  def duplicatePages(
    src: UUID,
    includeChildren: Boolean,
    visible: Set[UUID],
    targetWs: UUID,
    targetParent: Option[UUID],
    placement: Placement,
    rootTitle: String,
    by: UUID,
    maxPages: Int = 500
  ): IO[Either[AppError, List[(UUID, UUID)]]] = tx {
    type Row = (UUID, Option[UUID], String, String)
    val rowsC =
      sql"""with recursive sub as (
              select id, 0 as depth from page where id = $src and deleted_at is null
              union all
              select p.id, sub.depth + 1 from page p join sub on p.parent_id = sub.id where p.deleted_at is null and $includeChildren
            )
            select p.id, p.parent_id, p.position, p.title
            from page p join sub on sub.id = p.id order by sub.depth, p.position collate "C", p.id""".query[
        Row
      ].to[List]
    val parentOk: ConnectionIO[Boolean] = targetParent match {
      case None => true.pure[ConnectionIO]
      case Some(pid) => getPageC(pid).map(_.exists(_.workspaceId == targetWs))
    }
    (rowsC, parentOk).tupled.flatMap {
      case (Nil, _) => Left(AppError(ErrorCode.PageNotFound)).pure[ConnectionIO].widen
      case (_, false) => Left(AppError(ErrorCode.ParentPageNotFound)).pure[ConnectionIO].widen
      case (rows, _) =>
        // 起点は必ず含める。子は、閲覧でき、かつ親が複製されるものだけ（rows は浅い順なので親が先に決まる）
        val kept = rows.tail.foldLeft((Vector(rows.head), Set(rows.head._1))) { case ((acc, seen), r) =>
          if visible(r._1) && r._2.exists(seen) then (acc :+ r, seen + r._1) else (acc, seen)
        }._1.toList
        if kept.size > maxPages then
          Left(AppError(ErrorCode.TooManyPages, s"ページが多すぎます（${maxPages}ページまで）")).pure[ConnectionIO].widen
        else {
          val ids = kept.map(r => r._1 -> UUID.randomUUID()).toMap
          siblingsC(targetWs, targetParent, None).flatMap { sib =>
            FractionalIndex.place(sib, placement) match {
              case Left(msg) => Left(AppError(ErrorCode.InvalidInput, msg)).pure[ConnectionIO].widen
              case Right(rootPos) =>
                // ページ数によらず、まとめて入れる（m: 元のID → 複製のID・親・位置・タイトル）
                val olds = kept.map(_._1).toArray
                val nids = kept.map(r => ids(r._1)).toArray
                // 親なし（起点をワークスペース直下へ）は空文字で渡す（配列に null を入れられないため）
                val parents = kept.map(r =>
                  (if r._1 == src then targetParent else r._2.flatMap(ids.get)).fold("")(_.toString)
                ).toArray
                val poss = kept.map(r => if r._1 == src then rootPos else r._3).toArray
                val titles = kept.map(r => if r._1 == src then rootTitle else r._4).toArray
                val m = fr"unnest($olds::uuid[], $nids::uuid[]) as m(old, nid)"
                for {
                  _ <-
                    sql"""insert into page (id, workspace_id, parent_id, position, title, icon, inherit_acl, created_by,
                                               custom_css, css_inherit, guest_top_html, guest_bottom_html, guest_html_inherit, props)
                             select m.nid, $targetWs, nullif(m.parent, '')::uuid, m.pos, m.title, p.icon, p.inherit_acl, $by,
                                    p.custom_css, p.css_inherit, p.guest_top_html, p.guest_bottom_html, p.guest_html_inherit, p.props
                             from unnest($olds::uuid[], $nids::uuid[], $parents::text[], $poss::text[], $titles::text[]) as m(old, nid, parent, pos, title)
                             join page p on p.id = m.old""".update.run
                  _ <-
                  (fr"""insert into page_acl (page_id, principal_type, principal_id, role, expires_at)
                             select m.nid, a.principal_type, a.principal_id, a.role, a.expires_at from""" ++ m ++
                    fr"join page_acl a on a.page_id = m.old").update.run
                  // 個別に権限を絞ったページは、複製した人が編集できるようにする
                  _ <-
                  (fr"insert into page_acl (page_id, principal_type, principal_id, role) select m.nid, 'user', $by, 'edit' from" ++
                    m ++
                    fr"""join page p on p.id = m.old where not p.inherit_acl
                              on conflict (page_id, principal_type, principal_id) do update set role = 'edit'""").update.run
                  _ <-
                  (fr"insert into page_label (page_id, label) select m.nid, l.label from" ++ m ++
                    fr"join page_label l on l.page_id = m.old").update.run
                  // データベースの定義と、項目の値（項目の値は page.props で入れた）
                  _ <-
                  (fr"insert into page_database (page_id, props, views, next_seq) select m.nid, d.props, d.views, d.next_seq from" ++
                    m ++
                    fr"join page_database d on d.page_id = m.old").update.run
                } yield Right(kept.map(r => r._1 -> ids(r._1)))
            }
          }
        }
    }
  }
}
