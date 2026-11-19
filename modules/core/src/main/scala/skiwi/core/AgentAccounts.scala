package skiwi.core

import java.util.UUID

/**
 * エージェント専用アカウント（skakapo などの外部の AI エージェントが API キーで使う、ログインしないアカウント）.
 * app_user の sub を `agent:<uuid>` にして見分ける（メールは持たない）。OIDC・SCIM がこの sub を作ることはない.
 */
object AgentAccounts {

  val Prefix = "agent:"

  /** SQL の like で使う形（`sub like AgentAccounts.LikePattern`） */
  val LikePattern = "agent:%"

  def isAgent(sub: String): Boolean = sub.startsWith(Prefix)

  /**
   * 管理者か（ADMIN_GROUPS のグループの所属者。エージェント専用アカウントは、グループに入っていても管理者にしない）.
   * ログイン・通知・メンションなど、管理者を判定するところはすべてこれを使う
   */
  def isAdmin(sub: String, groupExternalIds: Iterable[String], adminGroups: Set[String]): Boolean =
    !isAgent(sub) && groupExternalIds.exists(adminGroups)

  def newSub(): String = Prefix + UUID.randomUUID()

  /** 名前の確認（前後の空白を除いて1〜60文字・制御文字なし）。確認済みの名前かエラーの文言 */
  def validName(raw: String): Either[String, String] = {
    val n = raw.trim
    if n.isEmpty || n.length > 60 then Left("名前は1〜60文字にしてください")
    else if n.exists(_.isControl) then Left("名前に制御文字は使えません")
    else Right(n)
  }
}
