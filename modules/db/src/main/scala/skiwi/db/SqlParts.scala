package skiwi.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import skiwi.core.*

import java.util.UUID

/** 複数のストアで使う SQL の部品。 */
private[db] object SqlParts {

  /** 期限のない、または期限内の行（col: 期限の列。別名付きなら "a.expires_at" など） */
  def notExpired(col: String = "expires_at"): Fragment = Fragment.const(s"($col is null or $col > now())")

  /** 保護中で、このユーザーは編集できないページ（保護した人・所有者・指定された人以外）の条件 */
  def lockedOutFor(userId: UUID): Fragment = lockedOutForFr(fr"$userId")

  /** lockedOutFor のユーザーを SQL の式で与える版（例: unnest した列） */
  def lockedOutForFr(user: Fragment): Fragment =
    fr"locked_at is not null and not (" ++ user ++ fr"= any(lock_editors) or locked_by =" ++ user ++
      fr"or owner_id is not distinct from" ++ user ++ fr")"

  /** ユーザーが属するグループ */
  def groupsOfC(userId: UUID): ConnectionIO[List[Group]] =
    sql"""select g.id, g.external_id, g.name from user_group g
          join group_member m on m.group_id = g.id where m.user_id = $userId""".query[Group].to[List]
}
