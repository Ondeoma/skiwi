package skiwi.api

import io.circe.{Json, JsonObject}
import io.circe.syntax.*

import scala.collection.mutable.ArrayBuffer

/**
 * Markdown を本文（Tiptap / ProseMirror の JSON）のブロックにする（API・MCP で本文を書くときに使う）。
 * 対応: 見出し・段落（行内の改行は改行）・箇条書き・番号付き・ToDo（入れ子も）・引用・コード（```言語）・区切り線・表・画像だけの行、
 * 行内の太字・斜体・取り消し線・コード・リンク・<u>・<mark>・URL。それ以外の HTML は文字のまま。
 */
object MarkdownParse {

  private def node(tpe: String, attrs: (String, Json)*)(content: Vector[Json]): Json = {
    val base = Vector("type" -> tpe.asJson) ++ Option.when(attrs.nonEmpty)("attrs" -> Json.obj(attrs*)) ++
      Option.when(content.nonEmpty)("content" -> Json.fromValues(content))
    Json.obj(base*)
  }

  // ---------------------------------------------------------------- ブロック

  /** 見出し。末尾の「 ###」は正規表現ではなく後から除く（遅延一致と空白の組み合わせは、長い行で二乗の時間がかかるため） */
  private object Heading {
    private val Re = """^ {0,3}(#{1,6})(?:[ \t]+(.*))?$""".r
    def unapply(l: String): Option[(String, String)] = l match {
      case Re(hs, rest) =>
        val t = Option(rest).getOrElse("").trim
        val closed = t.reverse.dropWhile(_ == '#').reverse
        Some((hs, if closed.isEmpty then "" else if closed.last == ' ' || closed.last == '\t' then closed.trim else t))
      case _ => None
    }
    def matches(l: String): Boolean = Re.matches(l)
  }
  private val Fence = """^ {0,3}(`{3,}|~{3,})[ \t]*([^`\s]*).*$""".r
  private val Quote = """^ {0,3}> ?(.*)$""".r
  private val Bullet = """^( *)([-*+])[ \t]+(.*)$""".r
  private val Ordered = """^( *)(\d{1,9})[.)][ \t]+(.*)$""".r
  private val Task = """^\[([ xX])\][ \t]+(.*)$""".r
  private val ImageAlone = """^ *!\[([^\]]*)\]\(([^)\s]+)(?: +"[^"]*")?\) *$""".r

  private def blank(l: String) = l.trim.isEmpty

  // 区切り線と表の区切り行は正規表現を使わずに判定する
  // （Java の正規表現はグループの繰り返しを再帰で処理するため、長い行で StackOverflowError になる）
  private def isRule(l: String): Boolean = {
    val t = l.trim
    indentOf(l) <= 3 && t.nonEmpty && "-*_".contains(t.head) && t.forall(c => c == t.head || c == ' ' || c == '\t') &&
    t.count(_ == t.head) >= 3
  }
  private def isTableSep(l: String): Boolean = {
    val t = l.trim.stripPrefix("|").stripSuffix("|")
    t.nonEmpty && t.split("\\|", -1).forall(_.trim.matches(":?-+:?"))
  }

  /** 入れ子（引用・リスト・行内の書式）の深さの上限。悪意のある入力でスタックを使い切らせない */
  private val MaxDepth = 32
  private def indentOf(l: String) = l.takeWhile(_ == ' ').length

  /** Markdown の文字を、本文の最上位のブロックの並びにする。 */
  def blocks(markdown: String): Vector[Json] =
    parseBlocks(markdown.replace("\r\n", "\n").replace("\t", "    ").split("\n", -1).toVector, 0)

  private def parseBlocks(lines: Vector[String], depth: Int): Vector[Json] = {
    if depth >= MaxDepth then
      return Vector(node("paragraph")(inlines(lines.filterNot(blank).map(_.trim).mkString(" "))))
    val out = ArrayBuffer.empty[Json]
    var i = 0
    // 各行から見て次の空でない行の位置（リストの空行の扱いで、毎回先を探し直さないように）
    val nextNonBlank = Array.fill(lines.size + 1)(lines.size)
    for k <- (lines.size - 1) to 0 by -1 do nextNonBlank(k) = if blank(lines(k)) then nextNonBlank(k + 1) else k
    def tableStart(k: Int): Boolean = lines(k).contains('|') && k + 1 < lines.size && isTableSep(lines(k + 1))
    def startsBlock(k: Int): Boolean = {
      val l = lines(k)
      Heading.matches(l) || Fence.matches(l) || isRule(l) || Quote.matches(l) || Bullet.matches(l) ||
      Ordered.matches(l) || tableStart(k)
    }
    while i < lines.size do {
      val l = lines(i)
      l match {
        case _ if blank(l) => i += 1
        case Fence(fence, lang) =>
          val body = ArrayBuffer.empty[String]
          i += 1
          while i < lines.size && !lines(i).trim.startsWith(fence) do { body += lines(i); i += 1 }
          i += 1 // 閉じる行
          val text = body.mkString("\n")
          out +=
            (if lang == "mermaid" then node("mermaid", "code" -> text.asJson)(Vector.empty)
             else
               node("codeBlock", "language" -> (if lang.isEmpty then Json.Null else lang.asJson))(
                 if text.isEmpty then Vector.empty else Vector(Json.obj("type" -> "text".asJson, "text" -> text.asJson))
               ))
        case Heading(hs, text) =>
          out += node("heading", "level" -> hs.length.asJson)(inlines(Option(text).getOrElse("")))
          i += 1
        case _ if isRule(l) =>
          out += node("horizontalRule")(Vector.empty)
          i += 1
        case Quote(_) =>
          val body = ArrayBuffer.empty[String]
          while i < lines.size && (lines(i) match { case Quote(_) => true; case _ => false }) do {
            body += (lines(i) match { case Quote(rest) => rest; case x => x })
            i += 1
          }
          out += node("blockquote")(nonEmpty(parseBlocks(body.toVector, depth + 1)))
        case Bullet(_, _, _) | Ordered(_, _, _) =>
          val (list, next) = parseList(lines, i, nextNonBlank, depth)
          out += list
          i = next
        case _ if tableStart(i) =>
          val rows = ArrayBuffer(cells(l))
          i += 2
          while i < lines.size && !blank(lines(i)) && lines(i).contains('|') do { rows += cells(lines(i)); i += 1 }
          val w = rows.map(_.size).max
          out += node("table")(rows.toVector.zipWithIndex.map {
            (r, ri) =>
              node("tableRow")((0 until w).toVector.map { ci =>
                node(
                  if ri == 0 then "tableHeader" else "tableCell"
                )(Vector(node("paragraph")(inlines(r.lift(ci).getOrElse("")))))
              })
          })
        case _ =>
          // 段落: 空行かほかのブロックの始まりまで。行内の改行は改行（hardBreak）にする
          val body = ArrayBuffer(l.trim)
          i += 1
          while i < lines.size && !blank(lines(i)) && !startsBlock(i) do {
            body += lines(i).trim; i += 1
          }
          body.toList match {
            case List(ImageAlone(alt, src)) => out +=
                node("image", "src" -> src.asJson, "alt" -> alt.asJson)(Vector.empty)
            case ls =>
              val content = ls.map(inlines).reduceLeft((a, b) => (a :+ Json.obj("type" -> "hardBreak".asJson)) ++ b)
              out += node("paragraph")(content)
          }
      }
    }
    out.toVector
  }

  private def nonEmpty(bs: Vector[Json]): Vector[Json] =
    if bs.isEmpty then Vector(node("paragraph")(Vector.empty)) else bs

  /** 表の行をセルに分ける（\| はセルの区切りにしない）。 */
  private def cells(line: String): Vector[String] = {
    val t = line.trim.stripPrefix("|")
    val body = if t.endsWith("|") && !t.endsWith("\\|") then t.dropRight(1) else t
    body.split("""(?<!\\)\|""", -1).toVector.map(_.trim.replace("\\|", "|"))
  }

  /** リスト（箇条書き・番号付き・ToDo）。同じ字下げの同じ種類の項目が続く間。戻り値: (リストのノード, 次の行) */
  private def parseList(lines: Vector[String], start: Int, nextNonBlank: Array[Int], depth: Int): (Json, Int) = {
    def nextLine(from: Int): Option[String] =
      Option(nextNonBlank(math.min(from, lines.size))).filter(_ < lines.size).map(lines)
    val first = lines(start)
    val base = indentOf(first)
    val ordered = Ordered.matches(first)
    // ToDo と普通の箇条書きは別のリスト（エディタでは別の種類）
    val firstTask = first match { case Bullet(_, _, rest) => Task.matches(rest); case _ => false }
    def marker(l: String): Option[(String, Int)] = l match { // (本文, 本文の始まりの桁)
      case Bullet(sp, m, rest) if !ordered && sp.length == base && Task.matches(rest) == firstTask =>
        Some((rest, sp.length + m.length + 1))
      case Ordered(sp, n, rest) if ordered && sp.length == base => Some((rest, sp.length + n.length + 2))
      case _ => None
    }
    val items = ArrayBuffer.empty[Vector[String]]
    var i = start
    var continue = true
    while continue && i < lines.size do
      marker(lines(i)) match {
        case Some((rest, col)) =>
          val body = ArrayBuffer(rest)
          i += 1
          // 字下げされた続きの行（空行を挟んでもよい）はこの項目に入れる
          var more = true
          while more && i < lines.size do {
            val l = lines(i)
            if blank(l) then {
              val nextIndented = nextLine(i + 1).exists(n => indentOf(n) > base)
              if nextIndented then { body += ""; i += 1 }
              else more = false
            } else if indentOf(l) > base && marker(l).isEmpty then {
              body += l.drop(math.min(indentOf(l), math.max(col, base + 2))); i += 1
            } else more = false
          }
          items += body.toVector
        case None =>
          // 空行の後に同じ種類の項目が続くなら同じリスト
          if blank(lines(i)) && nextLine(i).exists(marker(_).isDefined) then i += 1
          else continue = false
      }
    val listNode =
      if firstTask then
        node("taskList")(items.toVector.map { it =>
          val (checked, head) = it.head match {
            case Task(c, rest) => (c != " ", rest)
            case other => (false, other)
          }
          node("taskItem", "checked" -> checked.asJson)(itemContent(head +: it.tail, depth))
        })
      else if ordered then {
        val startNo = first match { case Ordered(_, n, _) => n.toIntOption.getOrElse(1); case _ => 1 }
        node("orderedList", "start" -> startNo.asJson)(items.toVector.map(it =>
          node("listItem")(itemContent(it, depth))
        ))
      } else node("bulletList")(items.toVector.map(it => node("listItem")(itemContent(it, depth))))
    (listNode, i)
  }

  /** 項目の中身。最初は段落でなければならない（エディタの決まり）。 */
  private def itemContent(lines: Vector[String], depth: Int): Vector[Json] = {
    val bs = parseBlocks(lines, depth + 1)
    if bs.headOption.flatMap(_.hcursor.get[String]("type").toOption).contains("paragraph") then bs
    else node("paragraph")(Vector.empty) +: bs
  }

  // ---------------------------------------------------------------- 行内

  private final case class Mark(tpe: String, attrs: Option[Json] = None) {
    def json: Json = attrs.fold(Json.obj("type" -> tpe.asJson))(a => Json.obj("type" -> tpe.asJson, "attrs" -> a))
  }

  private val AutoLink = """<(https?://[^>\s]+)>""".r
  private val Url = """https?://[^\s<>()\[\]]*[^\s<>()\[\].,;:!?、。」）]""".r

  /** 行内の Markdown を文字のノードの並びにする（同じマークの続いた文字はまとめる）。 */
  private val MaxInline = 4000
  def inlines(s: String): Vector[Json] = {
    val out = ArrayBuffer.empty[(String, List[Mark])]
    def emit(t: String, marks: List[Mark]): Unit =
      if t.nonEmpty then
        if out.nonEmpty && out.last._2 == marks then out(out.size - 1) = (out.last._1 + t, marks)
        else out += ((t, marks))
    def isWordChar(c: Char) = c.isLetterOrDigit && c < 0x3000 // 英数字（和文の中の _ は区切りとして扱う）

    def parse(s: String, marks: List[Mark]): Unit = {
      // 入れ子が深すぎる・行が長すぎるときは書式を解釈しない（スタックの使い切りと、閉じ記号の探索が二乗で重くなるのを防ぐ）
      if marks.size >= MaxDepth || s.length > MaxInline then { emit(s, marks); return }
      val buf = StringBuilder()
      def flush() = { emit(buf.toString, marks); buf.clear() }
      var i = 0
      // 区切り記号 d で閉じる位置（なければ -1）
      def closing(d: String, from: Int): Int = {
        var j = from
        var found = -1
        while found < 0 && j <= s.length - d.length do
          if s.charAt(j) == '\\' then j += 2
          else if s.startsWith(d, j) && j > from && !s.charAt(j - 1).isWhitespace then found = j
          else j += 1
        found
      }
      // i から始まる区切り（** __ ~~ / * _）の閉じる位置（なければ -1）
      def pairEnd(k: Int): Int =
        if (s.startsWith("**", k) || s.startsWith("__", k) || s.startsWith("~~", k)) && k + 2 < s.length &&
          !s.charAt(k + 2).isWhitespace
        then closing(s.substring(k, k + 2), k + 2)
        else -1
      def singleEnd(k: Int): Int = {
        val c = s.charAt(k)
        if (c == '*' || (c == '_' && (k == 0 || !isWordChar(s.charAt(k - 1))))) && k + 1 < s.length &&
          !s.charAt(k + 1).isWhitespace
        then closing(c.toString, k + 1)
        else -1
      }
      // i から始まる正規表現の一致（文字列を切り出さずに調べる）
      def prefix(r: scala.util.matching.Regex, k: Int): Option[java.util.regex.Matcher] = {
        val m = r.pattern.matcher(s).region(k, s.length)
        Option.when(m.lookingAt())(m)
      }
      while i < s.length do {
        val c = s.charAt(i)
        lazy val pe = pairEnd(i)
        lazy val se = singleEnd(i)
        if c == '\\' && i + 1 < s.length && "\\`*_{}[]()#+-.!|~<>".contains(s.charAt(i + 1)) then {
          buf += s.charAt(i + 1); i += 2
        } else if c == '`' then {
          val n = s.drop(i).takeWhile(_ == '`').length
          val end = s.indexOf("`" * n, i + n)
          if end < 0 then { buf ++= "`" * n; i += n }
          else {
            flush()
            val code = s.substring(i + n, end)
            emit(
              if code.startsWith(" ") && code.endsWith(" ") && code.trim.nonEmpty then code.drop(1).dropRight(1)
              else code,
              marks :+ Mark("code")
            )
            i = end + n
          }
        } else if pe > 0 then {
          val d = s.substring(i, i + 2)
          val end = pe
          flush()
          parse(s.substring(i + 2, end), marks :+ Mark(if d == "~~" then "strike" else "bold"))
          i = end + 2
        } else if se > 0 then {
          val end = se
          flush()
          parse(s.substring(i + 1, end), marks :+ Mark("italic"))
          i = end + 1
        } else if c == '[' || (c == '!' && s.startsWith("![", i)) then {
          val open = if c == '!' then i + 1 else i
          // 対応する ] と、その直後の (URL)
          var depth = 0; var j = open; var close = -1
          while close < 0 && j < s.length do {
            s.charAt(j) match {
              case '\\' => j += 1
              case '[' => depth += 1
              case ']' => depth -= 1; if depth == 0 then close = j
              case _ => ()
            }
            j += 1
          }
          val paren = if close > 0 && s.startsWith("(", close + 1) then s.indexOf(')', close + 2) else -1
          if paren < 0 then { buf += c; i += 1 }
          else {
            flush()
            val href = s.substring(close + 2, paren).trim.takeWhile(!_.isWhitespace)
            val label = s.substring(open + 1, close)
            parse(if label.isEmpty then href else label, marks :+ Mark("link", Some(Json.obj("href" -> href.asJson))))
            i = paren + 1
          }
        } else if c == '<' then
          List("u" -> "underline", "mark" -> "highlight").find((t, _) =>
            s.startsWith(s"<$t>", i) && s.indexOf(s"</$t>", i) > 0
          ) match {
            case Some((t, m)) =>
              val end = s.indexOf(s"</$t>", i)
              flush()
              parse(s.substring(i + t.length + 2, end), marks :+ Mark(m))
              i = end + t.length + 3
            case None =>
              prefix(AutoLink, i) match {
                case Some(m) =>
                  flush()
                  emit(m.group(1), marks :+ Mark("link", Some(Json.obj("href" -> m.group(1).asJson))))
                  i = m.end
                case None => buf += c; i += 1
              }
          }
        else {
          val url =
            Option.when(c == 'h' && !marks.exists(_.tpe == "link") && (i == 0 || !isWordChar(s.charAt(i - 1))))(prefix(
              Url,
              i
            )).flatten
          url match {
            case Some(m) =>
              flush()
              emit(m.group(), marks :+ Mark("link", Some(Json.obj("href" -> m.group().asJson))))
              i = m.end
            case None => buf += c; i += 1
          }
        }
      }
      flush()
    }

    parse(s, Nil)
    out.toVector.map {
      (t, ms) =>
        if ms.isEmpty then Json.obj("type" -> "text".asJson, "text" -> t.asJson)
        else
          Json.obj(
            "type" -> "text".asJson,
            "text" -> t.asJson,
            "marks" -> Json.fromValues(ms.distinctBy(_.tpe).map(_.json))
          )
    }
  }

  // ---------------------------------------------------------------- 平文

  private val TextBlocks = Set("paragraph", "heading", "codeBlock")

  /** 本文の平文（検索・抜粋用。エディタの getText({ blockSeparator: '\n' }) に近い形）。 */
  def plainText(blocks: Vector[Json]): String = {
    val parts = ArrayBuffer.empty[String]
    def inlineText(n: JsonObject): String =
      n("content").flatMap(_.asArray).toVector.flatten.flatMap(_.asObject).map { k =>
        k("type").flatMap(_.asString) match {
          case Some("text") => k("text").flatMap(_.asString).getOrElse("")
          case Some("hardBreak") => "\n"
          case _ => inlineText(k)
        }
      }.mkString
    def walk(n: JsonObject): Unit =
      if n("type").flatMap(_.asString).exists(TextBlocks.contains) then parts += inlineText(n)
      else n("content").flatMap(_.asArray).toVector.flatten.flatMap(_.asObject).foreach(walk)
    blocks.flatMap(_.asObject).foreach(walk)
    parts.mkString("\n")
  }
}
