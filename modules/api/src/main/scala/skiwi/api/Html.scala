package skiwi.api

/** HTML の文字参照（エスケープ）。 */
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
