package skiwi.api

import skiwi.shared.Titles

import io.circe.{Json, JsonObject}

/**
 * ページ本文（Tiptap の JSON）を Markdown にする（バックアップ・MCP 用）。
 * 画面の skiwi.web.content.Markdown（modules/web）と同じ規則にしている。片方を変えたらもう片方も直すこと。
 * url: 画像・添付・ページへのリンクのURLを書き換える。whiteboard: ホワイトボードの書き方。
 */
final class MarkdownExport(
  url: String => String,
  whiteboard: JsonObject => String) {

  private def attrs(
    n: JsonObject
  ): JsonObject = n("attrs").flatMap(_.asObject).getOrElse(JsonObject.empty)
  private def str(
    o: JsonObject,
    k: String
  ): String =
    o(k).fold("")(v => v.asString.orElse(v.asNumber.map(_.toString)).orElse(v.asBoolean.map(_.toString)).getOrElse(""))
  private def kids(
    n: JsonObject
  ): List[JsonObject] = n("content").flatMap(_.asArray).toList.flatten.flatMap(_.asObject)
  private def tpe(
    n: JsonObject
  ) = str(n, "type")

  private def escape(
    s: String
  ) = s.replaceAll("""([\\`*_\[\]#|<>])""", """\\$1""")

  private def inl(
    ns: List[JsonObject]
  ): String = ns.map { n =>
    tpe(n) match {
      case "text" =>
        val marks = n("marks").flatMap(_.asArray).toList.flatten.flatMap(_.asObject)
        val text = str(n, "text")
        var t = if marks.exists(m => tpe(m) == "code") then s"`$text`" else escape(text)
        for m <- marks do
          tpe(m) match {
            case "bold" => t = s"**$t**"
            case "italic" => t = s"*$t*"
            case "strike" => t = s"~~$t~~"
            case "underline" => t = s"<u>$t</u>"
            case "highlight" => t = s"<mark>$t</mark>"
            case "link" => t = s"[$t](${url(str(attrs(m), "href"))})"
            case _ => ()
          }
        t
      case "hardBreak" => "  \n"
      case "pageLink" =>
        val a = attrs(n)
        s"[${escape(Titles.display(Option(str(a, "title"))))}](${url(s"/w/${str(a, "workspaceId")}/p/${str(a, "pageId")}")})"
      case "inlineMath" => "$" + str(attrs(n), "latex") + "$"
      case "image" => s"![${str(attrs(n), "alt")}](${url(str(attrs(n), "src"))})"
      case _ => inl(kids(n))
    }
  }.mkString

  private def indent(
    s: String,
    pad: String
  ) =
    s.split("\n", -1).zipWithIndex.map(
      (
        l,
        i
      ) => if i == 0 || l.isEmpty then l else pad + l
    ).mkString("\n")

  def block(
    n: JsonObject
  ): String = {
    def children(
      sep: String = "\n\n"
    ) = kids(n).map(block).filter(_.nonEmpty).mkString(sep)
    val a = attrs(n)
    tpe(n) match {
      case "doc" => children()
      case "paragraph" => inl(kids(n))
      case "heading" => "#" * Option(str(a, "level")).flatMap(_.toIntOption).getOrElse(1) + " " + inl(kids(n))
      case "blockquote" => children().split("\n", -1).map(l => s"> $l".replaceAll("\\s+$", "")).mkString("\n")
      case "horizontalRule" => "---"
      case "codeBlock" => "```" + str(a, "language") + "\n" + kids(n).map(str(_, "text")).mkString + "\n```"
      case "mermaid" => "```mermaid\n" + str(a, "code").replaceAll("\n+$", "") + "\n```"
      case "blockMath" => "$$\n" + str(a, "latex") + "\n$$"
      case "image" => s"![${str(a, "alt")}](${url(str(a, "src"))})"
      case "drawio" =>
        val title = Option(str(a, "title")).filter(_.nonEmpty)
        if Option(str(a, "src")).exists(_.nonEmpty) then s"![${title.getOrElse("draw.io の図")}](${url(str(a, "src"))})"
        else if Option(str(a, "xmlSrc")).exists(_.nonEmpty) then
          s"[📎 ${title.getOrElse("図")}.drawio](${url(str(a, "xmlSrc"))})"
        else ""
      case "fileBlock" =>
        s"[📎 ${Option(str(a, "filename")).filter(_.nonEmpty).getOrElse("file")}](${url(str(a, "url"))})"
      case "embed" => s"[${str(a, "src")}](${str(a, "src")})"
      case "linkCard" =>
        s"[${escape(Option(str(a, "title")).filter(_.nonEmpty).getOrElse(str(a, "url")))}](${str(a, "url")})"
      case "databaseBlock" => "（📊 データベース）"
      case "smohuaForm" => "（📋 フォームの回答）"
      case "toc" => ""
      case "whiteboard" => whiteboard(a)
      case "syncedRef" => s"[🔗 同期ブロック](${url(s"/w/${str(a, "workspaceId")}/p/${str(a, "pageId")}")})"
      case "bulletList" => kids(n).map(li => "- " + indent(listBody(li), "  ")).mkString("\n")
      case "orderedList" =>
        val start = Option(str(a, "start")).flatMap(_.toIntOption).getOrElse(1)
        kids(n).zipWithIndex.map(
          (
            li,
            i
          ) => s"${start + i}. " + indent(listBody(li), "   ")
        ).mkString("\n")
      case "taskList" =>
        kids(n).map(li =>
          s"- [${if attrs(li)("checked").flatMap(_.asBoolean).contains(true) then "x" else " "}] " +
            indent(listBody(li), "  ")
        ).mkString("\n")
      case "panel" =>
        val icon = Option(str(a, "icon")).filter(_.nonEmpty).getOrElse("ℹ️")
        children().split("\n", -1).zipWithIndex.map(
          (
            l,
            i
          ) => (if i == 0 then s"> $icon $l" else s"> $l").replaceAll("\\s+$", "")
        ).mkString("\n")
      case "details" =>
        kids(n) match {
          case summary :: rest =>
            s"<details>\n<summary>${inl(kids(summary))}</summary>\n\n${rest.map(block).mkString("\n\n")}\n\n</details>"
          case Nil => ""
        }
      case "detailsContent" => children()
      case "table" =>
        val rows = kids(n).map(r =>
          kids(r).map(c =>
            kids(c).map(block).mkString("<br>").replaceAll("""(?<!\\)\|""", """\\|""").replace("\n", " ")
          )
        )
        if rows.isEmpty then ""
        else {
          val w = rows.map(_.size).max
          def line(
            r: List[String]
          ) = "| " + (0 until w).map(i => r.lift(i).getOrElse("")).mkString(" | ") + " |"
          (line(rows.head) :: ("|" + " --- |" * w) :: rows.tail.map(line)).mkString("\n")
        }
      case _ => if n("content").isDefined then children() else inl(List(n))
    }
  }

  private def listBody(
    li: JsonObject
  ): String = kids(li).map(block).mkString("\n")

  /** 本文だけ（タイトルなし）。 */
  def body(
    doc: Json
  ): String = doc.asObject.fold("")(block).replaceAll("\n{3,}", "\n\n").trim

  def page(
    doc: Json,
    title: String
  ): String = s"# ${Titles.display(title)}\n\n${body(doc)}\n"
}
