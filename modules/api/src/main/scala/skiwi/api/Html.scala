package skiwi.api

/** HTML の文字参照（エスケープ）と、外部・添付の HTML の読み取り。 */
object Html:

  /** 本文・属性値に入れる文字を文字参照にする。 */
  def escape(s: String): String =
    s.flatMap {
      case '<' => "&lt;"; case '>' => "&gt;"; case '&' => "&amp;"; case '"' => "&quot;"; case '\'' => "&#39;"
      case c => c.toString
    }

  /** よく使う文字参照を元の文字に戻す（&amp; は最後に戻す。"&amp;lt;" を "<" にしないため）。 */
  def unescape(s: String): String =
    s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
      .replace("&#39;", "'").replace("&#x27;", "'").replace("&amp;", "&")

  // ---------------------------------------------------------------- 外部・添付の HTML の読み取り
  // 正規表現（[^>]* や .*? の組み合わせ）は、「<」だけが並ぶ壊れた入力で二乗の時間がかかる。
  // ここでは1回の走査で読む（閉じが見つからなかった位置を覚えて、同じ探索を繰り返さない）。

  private def indexOfIgnoreCase(s: String, needle: String, from: Int): Int =
    var i = math.max(from, 0)
    val last = s.length - needle.length
    while i <= last && !s.regionMatches(true, i, needle, 0, needle.length) do i += 1
    if i <= last then i else -1

  /** "<" の直後のタグ名（"/p" のような閉じタグは "/" 付き。小文字） */
  private def tagName(s: String, lt: Int): String =
    var j = lt + 1
    if j < s.length && s.charAt(j) == '/' then j += 1
    while j < s.length && s.charAt(j).isLetterOrDigit do j += 1
    s.substring(lt + 1, j).toLowerCase(java.util.Locale.ROOT)

  /** 名前が names のタグ（"<meta …>" の全体）を順に返す */
  def tags(html: String, names: Set[String]): Vector[String] =
    val out = Vector.newBuilder[String]
    var i = html.indexOf('<')
    while i >= 0 do
      val gt = html.indexOf('>', i + 1)
      if gt < 0 then i = -1 // この先に「>」はない
      else
        if names.contains(tagName(html, i)) then out += html.substring(i, gt + 1)
        i = html.indexOf('<', i + 1)
    out.result()

  /** 最初の要素 name の中身（"<title>…</title>" の …） */
  def firstElementText(html: String, name: String): Option[String] =
    val open = indexOfIgnoreCase(html, s"<$name", 0)
    val gt = if open < 0 then -1 else html.indexOf('>', open)
    val close = if gt < 0 then -1 else indexOfIgnoreCase(html, s"</$name", gt + 1)
    Option.when(close >= 0)(html.substring(gt + 1, close))

  private val Hidden = Set("script", "style", "head")
  private val Breaks = Set("br", "/p", "/div", "/li", "/tr", "/h1", "/h2", "/h3", "/h4", "/h5", "/h6")

  /** HTML を平文にする（script・style・head の中は除き、段落の終わりなどは改行に）。 */
  def toText(html: String): String =
    val out = new java.lang.StringBuilder(html.length)
    val noClose = scala.collection.mutable.Map.empty[String, Int] // この位置より後に閉じタグがない
    var i = 0
    while i < html.length do
      val lt = html.indexOf('<', i)
      if lt < 0 then { out.append(html, i, html.length); i = html.length }
      else
        out.append(html, i, lt)
        val gt = html.indexOf('>', lt + 1)
        if gt < 0 then { out.append(html, lt, html.length); i = html.length } // 閉じない「<」は文字のまま
        else
          val name = tagName(html, lt)
          if Hidden.contains(name) && noClose.get(name).forall(_ > gt) then
            val close = indexOfIgnoreCase(html, s"</$name", gt + 1)
            if close < 0 then { noClose(name) = gt; out.append(' '); i = gt + 1 }
            else
              val end = html.indexOf('>', close)
              out.append(' ')
              i = if end < 0 then html.length else end + 1
          else
            out.append(if Breaks.contains(name) then '\n' else ' ')
            i = gt + 1
    unescape(out.toString)
