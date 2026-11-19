package skiwi.db

import cats.effect.*
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skiwi.core.AgentAccounts

import java.time.Instant
import java.util.UUID

/** SCIMのユーザー。userName は OIDC の sub と同じ値（IdP側でsubjectをユーザー名にする設定が前提）。 */
final case class ScimUser(id: UUID,
                          userName: String,
                          externalId: Option[String],
                          name: String,
                          email: Option[String],
                          active: Boolean,
                          createdAt: Instant,
                          updatedAt: Instant)

final case class ScimGroup(id: UUID,
                           displayName: String,
                           externalId: Option[String],
                           members: List[(UUID, String)],
                           createdAt: Instant,
                           updatedAt: Instant)

final case class ScimUserInput(userName: String,
                               externalId: Option[String],
                               name: String,
                               email: Option[String],
                               active: Boolean)

/**
 * SCIM 2.0（IdPからのプッシュ）用の永続化。
 * ユーザーは sub(=userName) で、グループは external_id(=表示名) でOIDCログイン側と突き合わせる。
 */
final class ScimStore(xa: Transactor[IO]) extends TxStore(xa) {

  private val userCols = fr"id, sub, scim_external_id, name, email, not disabled, created_at, updated_at"

  /** エージェント専用アカウント（管理者が作る）は SCIM から見せず、変えさせない */
  private val notAgent = fr"sub not like ${AgentAccounts.LikePattern}"

  // ---------------------------------------------------------------- users

  /** 作成（既存の同名ユーザーがいればそれを更新して返す。ログインが先行した場合に対応）。 */
  def upsertUser(in: ScimUserInput): IO[ScimUser] = tx(
    (fr"""insert into app_user (sub, username, scim_external_id, name, email, disabled)
          values (${in.userName}, ${in.userName}, ${in.externalId}, ${in.name}, ${in.email}, ${!in.active})
          on conflict (sub) do update set
            username = excluded.username, scim_external_id = excluded.scim_external_id,
            name = excluded.name, email = excluded.email, disabled = excluded.disabled, updated_at = now()
          returning""" ++ userCols).query[ScimUser].unique
  )

  def getUser(id: UUID): IO[Option[ScimUser]] =
    tx((fr"select" ++ userCols ++ fr"from app_user where id = $id and" ++ notAgent).query[ScimUser].option)

  /** 置き換え（PUT）。無効化された場合はセッションとグループ所属を消す。 */
  def replaceUser(id: UUID, in: ScimUserInput): IO[Option[ScimUser]] = tx {
    (fr"""update app_user set sub = ${in.userName}, username = ${in.userName}, scim_external_id = ${in.externalId},
            name = ${in.name}, email = ${in.email}, disabled = ${!in.active}, updated_at = now()
          where id = $id and""" ++ notAgent ++ fr"returning" ++ userCols).query[ScimUser].option.flatTap {
      case Some(u) if !u.active => deactivateC(id)
      case _ => ().pure[ConnectionIO]
    }
  }

  private def deactivateC(id: UUID): ConnectionIO[Unit] =
    sql"delete from session where user_id = $id".update.run *>
      sql"delete from group_member where user_id = $id".update.run.void

  /** 削除。作成者・版の作者などの参照を保つため、行は残して無効化する。 */
  def deleteUser(id: UUID): IO[Boolean] = tx {
    (fr"update app_user set disabled = true, updated_at = now() where id = $id and" ++ notAgent).update.run.flatMap {
      case 0 => false.pure[ConnectionIO]
      case _ => deactivateC(id).as(true)
    }
  }

  /** 一覧の絞り込み（attrs: 属性名 → 列。id は共通）と、件数・ページ分けした行（startIndex は1始まり） */
  private def page[A: Read](table: String,
                            cols: Fragment,
                            attrs: Map[String, String],
                            filter: Option[(String, String)],
                            startIndex: Int,
                            count: Int,
                            base: Fragment = fr"true"): ConnectionIO[(Int, List[A])] = {
    val cond = fr"where" ++ base ++
      (filter match {
        case Some(("id", v)) => fr"and id::text = $v"
        case Some((k, v)) if attrs.contains(k) => Fragment.const(s"and ${attrs(k)} =") ++ fr"$v"
        case Some(_) => fr"and false"
        case None => fr""
      })
    val from = Fragment.const(s"from $table")
    for {
      total <- (fr"select count(*)" ++ from ++ cond).query[Int].unique
      rows <-
      (fr"select" ++ cols ++ from ++ cond ++
        fr"order by created_at, id offset ${math.max(0, startIndex - 1)} limit $count").query[A].to[List]
    } yield (total, rows)
  }

  /** filter は userName / externalId の eq のみ対応。 */
  def listUsers(filter: Option[(String, String)], startIndex: Int, count: Int): IO[(Int, List[ScimUser])] =
    tx(page[ScimUser](
      "app_user",
      userCols,
      Map("username" -> "sub", "externalid" -> "scim_external_id"),
      filter,
      startIndex,
      count,
      notAgent
    ))

  // ---------------------------------------------------------------- groups

  private type GroupRow = (UUID, String, Option[String], Instant, Instant)
  private val groupCols = fr"id, name, scim_external_id, created_at, updated_at"

  /** グループの行にメンバーを付ける（メンバーはまとめて読む） */
  private def withMembersC(rows: List[GroupRow]): ConnectionIO[List[ScimGroup]] = {
    val ids = rows.map(_._1).toArray
    val members =
      if ids.isEmpty then Map.empty[UUID, List[(UUID, String)]].pure[ConnectionIO]
      else
        sql"""select m.group_id, u.id, u.name from group_member m join app_user u on u.id = m.user_id
                 where m.group_id = any($ids) order by u.name""".query[(UUID, UUID, String)].to[List]
          .map(_.groupMap(_._1)(r => (r._2, r._3)))
    members.map(ms =>
      rows.map((id, n, ext, c, u) => ScimGroup(id, n, ext, ms.getOrElse(id, Nil), c, u))
    )
  }

  private def groupRowC(gid: UUID): ConnectionIO[Option[ScimGroup]] =
    (fr"select" ++ groupCols ++ fr"from user_group where id = $gid").query[
      GroupRow
    ].to[List].flatMap(withMembersC).map(_.headOption)

  def getGroup(id: UUID): IO[Option[ScimGroup]] = tx(groupRowC(id))

  /** 存在するユーザーIDだけをメンバーにする（未知のIDは無視）。 */
  private def setMembersC(gid: UUID, members: List[UUID]): ConnectionIO[Unit] = {
    val arr = members.distinct.toArray
    sql"delete from group_member where group_id = $gid".update.run *>
      (fr"""insert into group_member (group_id, user_id)
            select $gid, id from app_user where id = any($arr) and not disabled and """ ++ notAgent ++
        fr"on conflict do nothing").update.run.void
  }

  def createGroup(displayName: String, externalId: Option[String], members: List[UUID]): IO[ScimGroup] = tx {
    for {
      gid <-
        sql"""insert into user_group (external_id, name, scim_external_id) values ($displayName, $displayName, $externalId)
                   on conflict (external_id) do update set name = excluded.name,
                     scim_external_id = excluded.scim_external_id, updated_at = now()
                   returning id""".query[UUID].unique
      _ <- setMembersC(gid, members)
      g <- groupRowC(gid)
    } yield g.get
  }

  /** 置き換え（PUT）。members が None なら所属は変更しない。 */
  def replaceGroup(id: UUID,
                   displayName: String,
                   externalId: Option[String],
                   members: Option[List[UUID]]): IO[Option[ScimGroup]] = tx {
    sql"""update user_group set external_id = $displayName, name = $displayName, scim_external_id = $externalId, updated_at = now()
          where id = $id""".update.run.flatMap {
      case 0 => none[ScimGroup].pure[ConnectionIO]
      case _ => members.traverse_(setMembersC(id, _)) *> groupRowC(id)
    }
  }

  def renameGroup(id: UUID, displayName: String): IO[Boolean] = tx(
    sql"update user_group set external_id = $displayName, name = $displayName, updated_at = now() where id = $id".update.run.map(
      _ > 0
    )
  )

  def addMembers(id: UUID, users: List[UUID]): IO[Unit] = tx {
    val arr = users.distinct.toArray
    (fr"""insert into group_member (group_id, user_id)
          select $id, u.id from app_user u where u.id = any($arr) and not u.disabled and""" ++ notAgent ++
      fr"on conflict do nothing").update.run *>
      sql"update user_group set updated_at = now() where id = $id".update.run.void
  }

  def removeMembers(id: UUID, users: List[UUID]): IO[Unit] = tx {
    val arr = users.distinct.toArray
    sql"delete from group_member where group_id = $id and user_id = any($arr)".update.run *>
      sql"update user_group set updated_at = now() where id = $id".update.run.void
  }

  def replaceMembers(id: UUID, users: List[UUID]): IO[Unit] = tx(setMembersC(id, users))

  /** グループ削除。このグループを指すACLエントリも消す。 */
  def deleteGroup(id: UUID): IO[Boolean] = tx {
    for {
      _ <- sql"delete from page_acl where principal_type = 'group' and principal_id = $id".update.run
      _ <- sql"delete from workspace_acl where principal_type = 'group' and principal_id = $id".update.run
      n <- sql"delete from user_group where id = $id".update.run
    } yield n > 0
  }

  def listGroups(filter: Option[(String, String)], startIndex: Int, count: Int): IO[(Int, List[ScimGroup])] =
    tx(page[GroupRow](
      "user_group",
      groupCols,
      Map("displayname" -> "name", "externalid" -> "scim_external_id"),
      filter,
      startIndex,
      count
    ).flatMap((total, rows) => withMembersC(rows).map(total -> _)))
}
