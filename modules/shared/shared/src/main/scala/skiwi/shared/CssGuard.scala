package skiwi.shared

import scala.collection.mutable

/**
 * カスタム CSS の検査のうち、文字列だけで決まる部分（画面の CustomCss から使う. DOM を使わないので JVM のテストで確かめられる）.
 * 渡すのは、ブラウザが解析して書き出し直した文字列（宣言の値・cssText・selectorText）.
 * 文字列リテラルの中の「url(」を読み違えないよう、正規表現ではなく字句を順に読む.
 */
object CssGuard {

  /** url(...) の中身. start・end は text の中での位置（引用符の内側. 引用符なしなら前後の空白を除いた範囲） */
  final case class Url(value: String, start: Int, end: Int)

  /**
   * urls: 文字列リテラルの外にある url() / functions: 文字列リテラルの外にある関数の名前（小文字）/
   * plain: 文字列リテラルの中身とコメントを除き、エスケープを戻して小文字にしたもの（宣言の名前と値を見るため）
   */
  final case class Scan(urls: List[Url], functions: Set[String], plain: String)

  private def isHex(c: Char): Boolean =
    (c >= '0' && c <= '9') ||
      (c >= 'a' && c <= 'f') ||
      (c >= 'A' && c <= 'F')

  private def isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'

  private def isNameChar(c: Char): Boolean = c.isLetterOrDigit || c == '-' || c == '_' || c > 127

  /** i は「\」の位置. エスケープが表す文字と、その次の位置（CSS の規則: 16進6桁まで＋空白1つ、またはその1文字） */
  private def escape(text: String, i: Int): (String, Int) =
    if (i + 1 >= text.length) ("", i + 1)
    else if (isHex(text.charAt(i + 1))) {
      var j = i + 1
      while (j < text.length && j < i + 7 && isHex(text.charAt(j))) j += 1
      val code = Integer.parseInt(text.substring(i + 1, j), 16)
      val next = if (j < text.length && isSpace(text.charAt(j))) j + 1 else j
      (if (code == 0 || code > 0x10ffff) "\uFFFD" else new String(Character.toChars(code)), next)
    } else if (text.charAt(i + 1) == '\n') ("", i + 2)
    else (text.charAt(i + 1).toString, i + 2)

  /** i は開きの引用符の位置. 文字列の中身（エスケープを戻したもの）・中身の範囲・閉じの引用符の次の位置 */
  private def string(text: String, i: Int): (String, Int, Int, Int) = {
    val q = text.charAt(i)
    val sb = new StringBuilder
    var j = i + 1
    var open = true
    while (open && j < text.length) {
      val c = text.charAt(j)
      if (c == q) open = false
      else if (c == '\\') { val (s, n) = escape(text, j); sb ++= s; j = n }
      else { sb += c; j += 1 }
    }
    (sb.toString, i + 1, j, math.min(j + 1, text.length))
  }

  def scan(text: String): Scan = {
    val urls = mutable.ListBuffer.empty[Url]
    val functions = mutable.Set.empty[String]
    val plain = new StringBuilder
    var i = 0
    // 直前の名前（関数名になりうるもの）の、plain の中での開始位置
    def nameBefore(): String = {
      var k = plain.length
      while (k > 0 && isNameChar(plain.charAt(k - 1))) k -= 1
      plain.substring(k)
    }
    while (i < text.length) {
      val c = text.charAt(i)
      if (c == '"' || c == '\'') {
        val (_, _, _, next) = string(text, i)
        plain ++= "\"\""
        i = next
      } else if (c == '/' && i + 1 < text.length && text.charAt(i + 1) == '*') {
        val e = text.indexOf("*/", i + 2)
        plain += ' '
        i = if (e < 0) text.length else e + 2
      } else if (c == '\\') {
        val (s, next) = escape(text, i)
        plain ++= s.toLowerCase
        i = next
      } else if (c == '(') {
        val name = nameBefore()
        plain += '('
        i += 1
        if (name == "url") {
          while (i < text.length && isSpace(text.charAt(i))) i += 1
          if (i < text.length && (text.charAt(i) == '"' || text.charAt(i) == '\'')) {
            val (v, s, e, next) = string(text, i)
            urls += Url(v, s, e)
            i = next
          } else {
            // 引用符なし: 「)」まで
            val start = i
            val sb = new StringBuilder
            while (i < text.length && text.charAt(i) != ')') {
              if (text.charAt(i) == '\\') { val (s, n) = escape(text, i); sb ++= s; i = n }
              else { sb += text.charAt(i); i += 1 }
            }
            var end = i
            while (end > start && isSpace(text.charAt(end - 1))) end -= 1
            urls += Url(sb.toString.trim, start, end)
          }
        } else if (name.nonEmpty) functions += name
      } else {
        plain += c.toLower
        i += 1
      }
    }
    Scan(urls.toList, functions.toSet, plain.toString)
  }

  /** 文字列リテラルの外にある url(...) の中身 */
  def urls(text: String): List[String] = scan(text).urls.map(_.value)

  /** url(...) の中身を f で読み替える（文字列リテラルの中は変えない） */
  def mapUrls(text: String, f: String => String): String = {
    val sb = new StringBuilder
    var at = 0
    scan(text).urls.foreach { u =>
      val to = f(u.value)
      if (to != u.value) {
        sb ++= text.substring(at, u.start)
        // 引用符なしだったものは引用符で囲む
        val quoted = u.start > 0 && (text.charAt(u.start - 1) == '"' || text.charAt(u.start - 1) == '\'')
        val safe = to.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'").replace("\n", "\\a ")
        sb ++= (if (quoted) safe else "\"" + safe + "\"")
        at = u.end
      }
    }
    sb ++= text.substring(at)
    sb.toString
  }

  /** 使えない記述の種類 */
  enum Problem {

    /** 添付ファイル・data:image 以外の url() */
    case ExternalUrl

    /** url() を書かずに外部を読み込める関数（image-set()・src()・image()） */
    case UrlFunction

    /** position: fixed（var() で後から入れるものも含む） */
    case FixedPosition
  }

  private def urlFunction(name: String): Boolean = name.endsWith("image-set") || name == "src" || name == "image"

  private val PositionRe = """(^|[;{\s])position\s*:[^;}]*(fixed|var\()""".r

  /** 値に position: fixed になりうるものがあるか（position の値を渡す） */
  def fixedPosition(value: String): Boolean = {
    val p = scan(value).plain
    p.contains("fixed") || p.contains("var(")
  }

  /**
   * 値、または宣言のまとまり全体（cssText）に、使えない記述が残っていないか.
   * プロパティごとの値だけを見ると、var() を含む一括指定（個別のプロパティの値が空になる）などを見落とすので、まとまり全体にもかける
   */
  def problem(text: String, urlAllowed: String => Boolean): Option[Problem] = {
    val s = scan(text)
    if (s.urls.exists(u => !urlAllowed(u.value))) Some(Problem.ExternalUrl)
    else if (s.functions.exists(urlFunction)) Some(Problem.UrlFunction)
    else if (PositionRe.findFirstIn(s.plain).isDefined) Some(Problem.FixedPosition)
    else None
  }

  // ---------------------------------------------------------------- セレクタ

  /** 深さ0（括弧・文字列の外）の文字だけを f に渡す. f が true を返したらそこで止め、その位置を返す */
  private def findTop(s: String, from: Int)(f: Char => Boolean): Int = {
    var depth = 0
    var i = from
    var found = -1
    while (found < 0 && i < s.length) {
      val c = s.charAt(i)
      if (c == '"' || c == '\'') i = string(s, i)._4
      else if (c == '\\') i = escape(s, i)._2
      else {
        if (c == '(' || c == '[') depth += 1
        else if (c == ')' || c == ']') depth -= 1
        else if (depth == 0 && f(c)) found = i
        i += 1
      }
    }
    found
  }

  /** カンマで区切る（括弧・文字列の中の , では区切らない） */
  def splitSelectors(sel: String): List[String] = {
    val out = mutable.ListBuffer.empty[String]
    var at = 0
    var go = true
    while (go) {
      val k = findTop(sel, at)(_ == ',')
      if (k < 0) { out += sel.substring(at); go = false }
      else { out += sel.substring(at, k); at = k + 1 }
    }
    out.toList.map(_.trim).filter(_.nonEmpty)
  }

  /** 入れ子の「&」を使っているか（文字列・属性の値の外） */
  private def hasNesting(s: String): Boolean = {
    var i = 0
    var found = false
    while (!found && i < s.length) {
      val c = s.charAt(i)
      if (c == '"' || c == '\'') i = string(s, i)._4
      else if (c == '\\') i = escape(s, i)._2
      else { if (c == '&') found = true; i += 1 }
    }
    found
  }

  private val RootRe = """(?i)^(:root|html|body)(?![\w-])""".r

  /**
   * セレクタを表示エリア（scope）の中だけに効くよう書き換える（:root / html / body は scope に置き換え、ほかは scope の子孫にする）.
   * 範囲の外に効くもの（「body ~ *」「body:has(x) ~ *」のような兄弟の指定、入れ子の「&」）は取り除く.
   * 戻り値: (書き換えたセレクタ. 全部取り除いたら None, 取り除いたもの)
   */
  def scopeSelector(sel: String, scope: String): (Option[String], List[String]) = {
    val rejected = mutable.ListBuffer.empty[String]
    val out = splitSelectors(sel).flatMap { s =>
      if (hasNesting(s)) { rejected += s; Nil }
      else
        RootRe.findFirstIn(s) match {
          case None => List(s"$scope $s")
          case Some(root) =>
            val rest = s.substring(root.length)
            // 先頭のまとまり（:not(...) などが付いていてもよい）の次の結合子が兄弟（~ +）なら、scope の外に効く
            val k = findTop(rest, 0)(c => isSpace(c) || c == '>' || c == '~' || c == '+')
            val next = if (k < 0) "" else rest.substring(k).trim
            if (next.startsWith("~") || next.startsWith("+")) { rejected += s; Nil }
            else List((scope + rest).trim)
        }
    }
    (if (out.isEmpty) None else Some(out.mkString(", ")), rejected.toList)
  }

}
