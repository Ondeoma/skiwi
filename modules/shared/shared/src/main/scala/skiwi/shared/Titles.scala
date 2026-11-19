package skiwi.shared

/** ページの題名の表示（画面・サーバーで共通） */
object Titles {
  val Untitled = "無題"

  /** 空（空白だけを含む）なら「無題」 */
  def display(t: String): String = if t.trim.isEmpty then Untitled else t

  def display(t: Option[String]): String = display(t.getOrElse(""))
}
