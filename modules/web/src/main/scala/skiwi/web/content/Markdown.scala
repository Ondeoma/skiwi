package skiwi.web.content

import io.circe.{Json, JsonObject}
import org.scalajs.dom
import skiwi.web.Nav

import scala.scalajs.js

/** JS と同じ扱いをするための小さな補助（String()・trim・真偽など） */
private[content] object JsLike {

  /** JS の String(x)（項目がなければ "undefined"） */
  def str(v: Option[Json]): String = v match {
    case None => "undefined"
    case Some(j) => j.asString.getOrElse(js.Dynamic.global.String(js.JSON.parse(j.noSpaces)).asInstanceOf[String])
  }

  /** JS の String(x ?? '') */
  def strOrEmpty(v: Option[Json]): String = v.filterNot(_.isNull).fold("")(j => str(Some(j)))

  /** JS で真になる値か */
  def truthy(v: Option[Json]): Boolean = v.exists(j =>
    j.fold(false, b => b, n => { val d = n.toDouble; d != 0 && !d.isNaN }, s => s.nonEmpty, _ => true, _ => true))

  /** JS の Number(x ?? d) */
  def num(v: Option[Json], default: Double): Double = v.filterNot(_.isNull) match {
    case None => default
    case Some(j) => js.Dynamic.global.Number(js.JSON.parse(j.noSpaces)).asInstanceOf[Double]
  }

  /** 数を JS と同じ書き方で文字列にする */
  def numStr(d: Double): String = js.Dynamic.global.String(d).asInstanceOf[String]

  def trim(s: String): String = s.asInstanceOf[js.Dynamic].trim().asInstanceOf[String]

  def trimEnd(s: String): String = s.asInstanceOf[js.Dynamic].trimEnd().asInstanceOf[String]

  /** JS の s.split(sep)（末尾の空も残す） */
  def lines(s: String): List[String] = s.split("\n", -1).toList

  /** JS の s.replace(a, b)（文字列の最初の1つだけ） */
  def replaceFirst(s: String, a: String, b: String): String = {
    val i = s.indexOf(a)
    if (i < 0) s else s.substring(0, i) + b + s.substring(i + a.length)
  }

}

/** ProseMirror（Tiptap）の JSON → Markdown（旧 editor/markdown.ts の toMarkdown・blockUnits） */
object Markdown {

  import JsLike.*

  private final case class N(o: JsonObject) {
    def tpe: String = o("type").flatMap(_.asString).getOrElse("")
    def attr(k: String): Option[Json] = o("attrs").flatMap(_.asObject).flatMap(_(k))
    /** content が配列のときだけ Some */
    def contentOpt: Option[List[N]] = o("content").flatMap(_.asArray).map(_.toList.flatMap(_.asObject).map(N(_)))
    def content: List[N] = contentOpt.getOrElse(Nil)
    def text: String = o("text").flatMap(_.asString).getOrElse("")
    def marks: List[(String, Option[Json])] = o("marks").flatMap(_.asArray).fold(Nil)(_.toList.flatMap(_.asObject).map(m =>
      (m("type").flatMap(_.asString).getOrElse(""), m("attrs").flatMap(_.asObject).flatMap(_("href")))))
    def withType(t: String): N = N(o.add("type", Json.fromString(t)))
  }

  private val Escape = """([\\`*_\[\]#|<>])""".r

  private def escapeText(s: String): String = Escape.replaceAllIn(s, m => scala.util.matching.Regex.quoteReplacement("\\" + m.group(1)))

  private def indent(s: String, pad: String): String =
    lines(s).zipWithIndex.map((l, i) => if (i == 0 || l.isEmpty) l else pad + l).mkString("\n")

  private def origin: String = dom.window.location.origin

  /** URL を絶対URLにする（既定） */
  def toAbsolute(s: String): String = if (s.startsWith("/")) origin + s else s

  private val noBoard: JsonObject => String = _ => "（ホワイトボード）"

  /** 表のセルの「|」を「\|」にする（すでに \ の付いたものはそのまま） */
  private def escapePipes(s: String): String = {
    val b = new StringBuilder
    s.indices.foreach { i =>
      if (s(i) == '|' && (i == 0 || s(i - 1) != '\\')) b ++= "\\|" else b += s(i)
    }
    b.result()
  }

  /** abs: URL の変換（引数は String(url ?? '')）、boardOf: ホワイトボードの書き出し */
  private final class Writer(abs: String => String, boardOf: JsonObject => String) {

    private def absOf(v: Option[Json]): String = abs(strOrEmpty(v))

    private def pageLinkHref(n: N): String = abs(Nav.pageHref(str(n.attr("workspaceId")), str(n.attr("pageId"))))

    def inlines(nodes: Option[List[N]]): String = nodes.fold("")(_.map { n =>
      n.tpe match {
        case "text" =>
          val ms = n.marks
          var t = if (ms.exists(_._1 == "code")) "`" + n.text + "`" else escapeText(n.text)
          ms.foreach {
            case ("bold", _) => t = s"**$t**"
            case ("italic", _) => t = s"*$t*"
            case ("strike", _) => t = s"~~$t~~"
            case ("underline", _) => t = s"<u>$t</u>"
            case ("highlight", _) => t = s"<mark>$t</mark>"
            case ("link", href) => t = s"[$t](${absOf(href)})"
            case _ =>
          }
          t
        case "hardBreak" => "  \n"
        case "pageLink" =>
          val title = if (truthy(n.attr("title"))) str(n.attr("title")) else "無題"
          s"[${escapeText(title)}](${pageLinkHref(n)})"
        case "inlineMath" => "$" + strOrEmpty(n.attr("latex")) + "$"
        case "image" => s"![${strOrEmpty(n.attr("alt"))}](${absOf(n.attr("src"))})"
        case _ => inlines(n.contentOpt)
      }
    }.mkString)

    private def or(n: N, k: String, d: => String): String = if (truthy(n.attr(k))) str(n.attr(k)) else d

    def block(n: N): String = {
      def kids(sep: String = "\n\n") = n.content.map(block).filter(_ != "").mkString(sep)
      n.tpe match {
        case "doc" => kids()
        case "paragraph" => inlines(n.contentOpt)
        case "heading" => "#" * math.max(0, num(n.attr("level"), 1).toInt) + " " + inlines(n.contentOpt)
        case "blockquote" => lines(kids()).map(l => trimEnd(s"> $l")).mkString("\n")
        case "horizontalRule" => "---"
        case "codeBlock" => "```" + strOrEmpty(n.attr("language")) + "\n" + n.content.map(_.text).mkString + "\n```"
        case "mermaid" => "```mermaid\n" + strOrEmpty(n.attr("code")).reverse.dropWhile(_ == '\n').reverse + "\n```"
        case "blockMath" => "$$\n" + strOrEmpty(n.attr("latex")) + "\n$$"
        case "image" => s"![${strOrEmpty(n.attr("alt"))}](${absOf(n.attr("src"))})"
        case "drawio" =>
          if (truthy(n.attr("src"))) s"![${or(n, "title", "draw.io の図")}](${absOf(n.attr("src"))})"
          else if (truthy(n.attr("xmlSrc"))) s"[📎 ${or(n, "title", "図")}.drawio](${absOf(n.attr("xmlSrc"))})"
          else ""
        case "fileBlock" => s"[📎 ${n.attr("filename").filterNot(_.isNull).fold("file")(j => str(Some(j)))}](${absOf(n.attr("url"))})"
        case "embed" => s"[${str(n.attr("src"))}](${str(n.attr("src"))})"
        case "linkCard" =>
          val t = if (truthy(n.attr("title"))) str(n.attr("title")) else str(n.attr("url"))
          s"[${escapeText(t)}](${str(n.attr("url"))})"
        case "databaseBlock" => "（📊 データベース）"
        case "toc" => ""
        case "whiteboard" => boardOf(n.o("attrs").flatMap(_.asObject).getOrElse(JsonObject.empty))
        case "syncedRef" => s"[🔗 同期ブロック](${pageLinkHref(n)})"
        case "bulletList" => n.content.map(li => "- " + indent(block(li.withType("listBody")), "  ")).mkString("\n")
        case "orderedList" =>
          val start = num(n.attr("start"), 1)
          n.content.zipWithIndex.map((li, i) => s"${numStr(start + i)}. " + indent(block(li.withType("listBody")), "   ")).mkString("\n")
        case "taskList" =>
          n.content.map(li => s"- [${if (truthy(li.attr("checked"))) "x" else " "}] " + indent(block(li.withType("listBody")), "  ")).mkString("\n")
        case "listBody" => n.content.map(block).mkString("\n")
        case "panel" =>
          val icon = n.attr("icon").filterNot(_.isNull).fold("ℹ️")(j => str(Some(j)))
          lines(kids()).zipWithIndex.map((l, i) => trimEnd(if (i == 0) s"> $icon $l" else s"> $l")).mkString("\n")
        case "details" =>
          val cs = n.content
          s"<details>\n<summary>${inlines(cs.headOption.flatMap(_.contentOpt))}</summary>\n\n${cs.drop(1).map(block).mkString("\n\n")}\n\n</details>"
        case "detailsContent" => kids()
        case "table" =>
          val rows = n.content.map(_.content.map(c => escapePipes(c.content.map(block).mkString("<br>")).replace("\n", " ")))
          if (rows.isEmpty) ""
          else {
            val w = rows.map(_.length).max
            def line(r: List[String]) = s"| ${(0 until w).map(i => r.lift(i).getOrElse("")).mkString(" | ")} |"
            (line(rows.head) :: s"|${" --- |" * w}" :: rows.tail.map(line)).mkString("\n")
          }
        case _ => if (n.contentOpt.isDefined) kids() else inlines(Some(List(n)))
      }
    }

  }

  private lazy val defaultWriter = new Writer(toAbsolute, noBoard)

  private def nodeOf(doc: Json): Option[N] = doc.asObject.map(N(_))

  private val ImageRe = """!\[([^\]]*)\]\([^)]*\)""".r
  private val LinkRe = """\[([^\]]*)\]\([^)]*\)""".r
  private val UnescapeRe = """\\([\\`*_\[\]#|<>])""".r

  /** 差分を読みやすくする: 画像は「🖼 説明」、リンクは文字だけ（↗）、Markdownのエスケープを外す */
  private def readable(s: String): String = {
    import scala.util.matching.Regex.quoteReplacement as q
    val a = ImageRe.replaceAllIn(s, m => q(s"🖼 ${if (m.group(1).isEmpty) "画像" else m.group(1)}"))
    val b = LinkRe.replaceAllIn(a, m => q(s"${m.group(1)}↗"))
    UnescapeRe.replaceAllIn(b, m => q(m.group(1)))
  }

  /** 差分表示用: 本文の最上位のブロックごとの Markdown */
  def blockUnits(doc: Json): List[String] =
    nodeOf(doc).fold(List.empty[String])(_.content.map(defaultWriter.block).filter(x => trim(x) != "").map(readable))

  /**
   * ページ本文を Markdown にする（タイトルは先頭の見出しとして付ける）. 画像・添付・ページへのリンクは絶対URL.
   * url: URL の変換（ZIP の書き出しで相対パスにする. 引数はサイト内のパス）、whiteboard: ホワイトボードの書き出し
   */
  def toMarkdown(doc: Json, title: String, url: Option[String => String] = None, whiteboard: Option[JsonObject => String] = None): String = {
    val abs: String => String = url match {
      case Some(f) => u => { val r = replaceFirst(toAbsolute(u), origin, ""); f(if (r.isEmpty) u else r) }
      case None => toAbsolute
    }
    val w = new Writer(abs, whiteboard.getOrElse(noBoard))
    val body = nodeOf(doc).filterNot(_ => doc.isNull).fold("")(n => trim(w.block(n).replaceAll("\n{3,}", "\n\n")))
    s"# ${if (title.isEmpty) "無題" else title}\n\n$body\n"
  }

}
