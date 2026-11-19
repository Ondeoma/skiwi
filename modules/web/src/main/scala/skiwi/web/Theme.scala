package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

/**
 * 画面の明暗（ライト / ダーク）. OS の設定に合わせるか、手で選ぶ（このブラウザに保存）.
 * 実際の切り替えは <html> の class "dark" で行い、CSS はそれを見る（style.css）.
 * 読み込み直後のちらつきを防ぐため、index.html でも同じ判定で先に class を付けている.
 */
object Theme {

  private val Key = "skiwi.theme"

  val modes: List[String] = List("system", "light", "dark")
  val labels: Map[String, String] = Map("system" -> "OS に合わせる", "light" -> "ライト", "dark" -> "ダーク")
  val icons: Map[String, String] = Map("system" -> "🖥", "light" -> "☀️", "dark" -> "🌙")

  val mode: RV[String] = RV(Prefs.get(Key).filter(v => v == "light" || v == "dark").getOrElse("system"))

  private val media = dom.window.matchMedia("(prefers-color-scheme: dark)")
  val systemDark: RV[Boolean] = RV(media.matches)

  /** いまダークで表示しているか */
  def isDark: Boolean = mode.v == "dark" || (mode.v == "system" && systemDark.v)

  def apply(): Unit = {
    media.addEventListener("change", (e: dom.Event) => { systemDark := media.matches; reflect() })
    reflect()
  }

  private def reflect(): Unit = dom.document.documentElement.classList.toggle("dark", isDark)

  def set(
    v: String
  ): Unit = {
    mode := v
    if (v == "system") Prefs.remove(Key) else Prefs.set(Key, v)
    reflect()
  }

  /** OS に合わせる → ライト → ダーク の順に切り替える */
  def cycle(): Unit = set(modes((modes.indexOf(mode.v) + 1) % modes.size))

}
