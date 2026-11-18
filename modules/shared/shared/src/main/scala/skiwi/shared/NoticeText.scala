package skiwi.shared

/**
 * 画面内の通知の見出し・文・開く場所. 画面のベル（NotificationBell）とプッシュ通知で同じ文言にする.
 * kind: page_edited / shared / shared_workspace / mention / comment / task_due / access_request / access_granted / access_denied / automation
 */
object NoticeText:

  def roleLabel(role: Option[String]): String = if role.contains("edit") then "編集" else "閲覧"

  /** 通知の見出し（ページのアイコンとタイトル、またはワークスペースの名前） */
  def heading(kind: String, hasPage: Boolean, pageIcon: Option[String], pageTitle: Option[String], workspaceName: Option[String]): String =
    if hasPage then s"${pageIcon.getOrElse("📄")} ${Titles.display(pageTitle)}"
    else if kind == "access_denied" then "🔒 権限の申請"
    else s"🗂 ${workspaceName.getOrElse("")}"

  /** 通知の文（誰が・何をしたか） */
  def text(kind: String, actors: List[String], role: Option[String], excerpt: Option[String], due: Option[String]): String =
    val who = if actors.size > 2 then s"${actors.take(2).mkString("、")} ほか${actors.size - 2}人" else actors.mkString("、")
    val r = roleLabel(role)
    val ex = excerpt.getOrElse("")
    kind match
      case "page_edited"    => s"$who が更新しました"
      case "mention"        => if ex.nonEmpty then s"$who がコメントであなたをメンションしました: $ex" else s"$who が本文であなたをメンションしました"
      case "comment"        => s"$who がコメントしました: $ex"
      case "task_due"       => s"ToDo の期限（${due.getOrElse("")}）が近づいています: $ex"
      case "shared"         => s"$who が共有しました（$r）"
      case "access_request" => s"$who が${r}権限を申請しました${if ex.nonEmpty then s": $ex" else ""}"
      case "access_granted" => s"$who が${r}権限の申請を承認しました"
      case "access_denied"  => s"$who が権限の申請を承認しませんでした"
      case "automation"     => s"⚙ $ex（ルール: $who）"
      case _                => s"$who がワークスペースを共有しました（$r）"

  /** 通知を押したときに開くパス（開く先がなければ None） */
  def link(kind: String, pageId: Option[String], workspaceId: Option[String], threadId: Option[String]): Option[String] =
    (pageId, workspaceId) match
      case (Some(pid), Some(wid)) =>
        val q = threadId.map(t => s"?comment=$t").getOrElse(if kind == "access_request" then "?access=requests" else "")
        Some(s"/w/$wid/p/$pid$q")
      case (_, Some(wid)) => Some(s"/w/$wid")
      case _              => None
