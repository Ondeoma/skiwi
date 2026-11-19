package skiwi.web.editor

import org.scalajs.dom
import skiwi.web.AppNames

import scala.scalajs.js

/**
 * 本文の中の外部サービス（skea のタスクID・skawau のファイルのリンク）の表示. 本文は書き換えず、エディタの飾り（decoration）で見せる.
 * 本文から位置を探して飾りを付けるのはエディタの JS（editor/SkeaTasks・SkawauFiles）. ここでは見つけ方（正規表現）・説明の文・横に出す小さな表示を作る.
 * item: 分かっている項目（undefined はまだ分からない・取れなかった、null は見つからない・見る権限がない）
 */
object Linked {

  def openNewTab(
    url: String
  ): Unit = dom.window.open(url, "_blank", "noopener,noreferrer")

  private def has(
    v: js.Any
  ): Boolean = !js.isUndefined(v) && v != null

  private def span(
    cls: String,
    text: String
  ): dom.HTMLElement = {
    val s = dom.document.createElement("span").asInstanceOf[dom.HTMLElement]
    if (cls.nonEmpty) s.className = cls
    s.textContent = text
    s
  }

  /** 横に出す小さな表示. クリックすると、いつでも新しいタブで開く（エディタのカーソルは動かさない） */
  private def chip(
    cls: String,
    url: String,
    title: String,
    testid: String
  )(
    children: dom.Node*
  ): dom.HTMLElement = {
    val a = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
    a.className = s"linked-chip $cls"
    a.href = url
    a.target = "_blank"
    a.rel = "noopener noreferrer"
    a.contentEditable = "false"
    a.title = title
    a.setAttribute("data-testid", testid)
    children.foreach(a.appendChild)
    a.addEventListener("mousedown", (e: dom.Event) => e.preventDefault())
    a.addEventListener("click", (e: dom.Event) => { e.preventDefault(); e.stopPropagation(); openNewTab(url) })
    a
  }

  // ---------------------------------------------------------------- skawau のファイル

  private val Id = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
  private def escapeRe(
    s: String
  ): String = s.replaceAll("[.*+?^${}()|\\[\\]\\\\]", "\\\\$0")

  /** skawau のリンク（{URL}/view/{ID}・/files/{ID}・/office/{ID}）. href: リンク先の全体、text: 文字の中（g） */
  def skawauPatterns(
    base: String
  ): js.Dynamic = {
    val prefix = s"${escapeRe(base.replaceAll("/+$", ""))}/(?:view|files|office)/($Id)"
    js.Dynamic.literal(
      href = new js.RegExp(s"^$prefix(?:[/?#].*)?$$"),
      text = new js.RegExp(s"$prefix(?![0-9A-Za-z-])(?:[/?#][^\\s]*)?", "g")
    )
  }

  /** 種類のアイコン（フォルダ・画像・表計算など. ほかは 📄） */
  def fileIcon(
    name: String,
    isDir: Boolean,
    mime: String
  ): String = {
    val ext = name.toLowerCase.split('.').lastOption.getOrElse("")
    if (isDir) "📁"
    else if (mime.startsWith("image/")) "🖼️"
    else if (mime.startsWith("video/")) "🎬"
    else if (mime.startsWith("audio/")) "🎵"
    else if ("^(xlsx?|ods|csv)$".r.matches(ext) || mime.contains("spreadsheet")) "📊"
    else if ("^(pptx?|odp)$".r.matches(ext) || mime.contains("presentation")) "📽️"
    else if ("^(zip|gz|tgz|7z|rar|tar)$".r.matches(ext)) "🗜️"
    else "📄"
  }

  /** 線の SVG アイコンで出す種類（画像・動画）のクラス. ほかは fileIcon の絵文字 */
  def fileIconClass(
    isDir: Boolean,
    mime: String
  ): Option[String] =
    if (isDir) None
    else if (mime.startsWith("image/")) Some("ico ico-image")
    else if (mime.startsWith("video/")) Some("ico ico-video")
    else None

  private def mimeOf(
    f: js.Dynamic
  ): String = if (has(f.mimeType)) f.mimeType.toString else ""

  /** マウスを乗せたときの説明 */
  def skawauTitle(
    connected: Boolean,
    file: js.Any
  ): String =
    if (!connected) s"${AppNames.nameOf("skawau")} と連携するとファイル名を表示します"
    else if (has(file)) s"${file.asInstanceOf[js.Dynamic].name}（${AppNames.nameOf("skawau")}）"
    else if (file == null) s"${AppNames.nameOf("skawau")} で見つからないか、見る権限がありません"
    else s"${AppNames.nameOf("skawau")} のファイル"

  /** リンクの後ろに出すファイル名（「📄 ファイル名」） */
  def skawauChip(
    url: String,
    file: js.Dynamic
  ): dom.HTMLElement = {
    val name = file.name.toString
    val isDir = file.isDir.asInstanceOf[Boolean]
    val icon = fileIconClass(isDir, mimeOf(file)).fold(span("", fileIcon(name, isDir, mimeOf(file))))(c => span(c, ""))
    chip(
      "skawau-chip",
      url,
      s"$name を ${AppNames.nameOf("skawau")} で開く",
      "skawau-chip"
    )(icon, span("linked-name", name))
  }

  // ---------------------------------------------------------------- skea のタスク

  private val KeyRe = "^[A-Z][A-Z0-9_]*$".r
  private val ColorRe = "^#[0-9a-fA-F]{3,8}$".r

  /** タスクID（例: SKEA-123）を探す正規表現（g）. 対象のプロジェクトキーがなければ null */
  def skeaPattern(
    projectKeys: js.Array[String]
  ): js.RegExp = {
    val keys = projectKeys.toList.filter(KeyRe.matches)
    if (keys.isEmpty) null else new js.RegExp(s"\\b(?:${keys.mkString("|")})-\\d+\\b", "g")
  }

  def skeaTitle(
    connected: Boolean,
    task: js.Any
  ): String =
    if (!connected) s"${AppNames.nameOf("skea")} と連携するとタイトルを表示します"
    else if (has(task)) { val t = task.asInstanceOf[js.Dynamic]; s"${t.title}（${t.status.name}）" }
    else if (task == null) s"${AppNames.nameOf("skea")} で見つからないか、見る権限がありません"
    else s"${AppNames.nameOf("skea")} のタスク"

  /** タスクIDの後ろに出すタイトルと状態（分からなければ ↗） */
  def skeaChip(
    url: String,
    task: js.Any
  ): dom.HTMLElement =
    if (!has(task)) {
      val a = chip("skea-chip", url, s"${AppNames.nameOf("skea")} で開く", "skea-chip")(dom.document.createTextNode("↗"))
      a.classList.add("icon")
      a
    } else {
      val t = task.asInstanceOf[js.Dynamic]
      val st = span("skea-status", t.status.name.toString)
      val color = t.status.color.toString
      if (ColorRe.matches(color)) st.style.background = color
      chip("skea-chip", url, s"${t.title}（${t.status.name}）を ${AppNames.nameOf("skea")} で開く", "skea-chip")(
        span("linked-name", t.title.toString),
        st
      )
    }

}
