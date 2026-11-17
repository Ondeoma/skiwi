package skiwi.web

import scala.scalajs.js

/**
 * 色と絵文字の選択肢. 本文（セルの背景・パネル・文字の色）には色名だけを保存し、実際の色は CSS 変数（ライト/ダーク別）で決める.
 * エディタ（JS）からは window.__skiwiLib（editor.EditorLib）で使う.
 */
object Palette {

  final case class Color(id: String, label: String)

  val Colors: List[Color] = List(
    Color("gray", "グレー"), Color("brown", "ブラウン"), Color("orange", "オレンジ"), Color("yellow", "イエロー"), Color("green", "グリーン"),
    Color("blue", "ブルー"), Color("purple", "パープル"), Color("pink", "ピンク"), Color("red", "レッド"),
  )

  def isColor(v: Any): Boolean = v match {
    case s: String => Colors.exists(_.id == s)
    case _ => false
  }

  /** アイコンの選択肢（ページ・パネル） */
  val Emojis: List[String] = List(
    "📄", "📝", "📘", "📗", "📕", "📙", "📒", "📚", "📌", "📍", "📎", "🔖",
    "💡", "ℹ️", "⚠️", "✅", "❌", "⛔", "❓", "❗", "🔥", "⭐", "✨", "🎯",
    "🚀", "🛠️", "🔧", "⚙️", "🔒", "🔑", "🧪", "🧭", "🗂️", "📁", "🗃️", "📦",
    "📊", "📈", "📉", "🗓️", "⏰", "⏳", "🔔", "📣", "💬", "👥", "👤", "🤝",
    "🎉", "🏢", "🏠", "🌐", "💻", "🖥️", "📱", "☁️", "🐛", "🧩", "🚚", "💰",
    "🧾", "📜", "✏️", "📐", "🎨", "🧠", "❤️", "💙", "💚", "💛", "💜", "🟢",
  )

  /** 文字列の先頭の絵文字（書記素）1つ. 入力欄に任意の絵文字を入れたとき用 */
  def firstGrapheme(s: String): String = {
    val t = s.trim
    if (t.isEmpty) ""
    else {
      val seg = js.Dynamic.newInstance(js.Dynamic.global.Intl.Segmenter)("ja", js.Dynamic.literal(granularity = "grapheme"))
      js.Array.from(seg.segment(t).asInstanceOf[js.Iterable[js.Dynamic]]).headOption.map(_.segment.asInstanceOf[String]).getOrElse("")
    }
  }

}
