package skiwi.web

import io.github.ondeoma.scalactive.components.ComponentManager.*
import org.scalajs.dom

import scala.scalajs.js
import scala.util.Try

/** このブラウザに保存する設定（localStorage. 使えない環境では読めない・保存しないだけ） */
object Prefs {

  def get(key: String): Option[String] = Try(Option(dom.window.localStorage.getItem(key))).toOption.flatten

  def set(key: String, value: String): Unit = Try(dom.window.localStorage.setItem(key, value))

  def remove(key: String): Unit = Try(dom.window.localStorage.removeItem(key))

  /**
   * 保存する RV. 値が変わると保存する（serialize が None なら保存しない）.
   * 部品の mounted の中で作っても、その部品の片付けで保存が止まらないよう addWatcher で監視する
   */
  def stored[A](key: String, parse: Option[String] => A, serialize: A => Option[String] = (a: A) => Some(a.toString)): RV[A] = {
    val r = RV(parse(get(key)))
    r.addWatcher { case (_, v) => serialize(v).foreach(set(key, _)) }
    r
  }

}

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

  def set(v: String): Unit = {
    mode := v
    if (v == "system") Prefs.remove(Key) else Prefs.set(Key, v)
    reflect()
  }

  /** OS に合わせる → ライト → ダーク の順に切り替える */
  def cycle(): Unit = set(modes((modes.indexOf(mode.v) + 1) % modes.size))

}

object Ui {

  /** Mac・iPhone・iPad（ショートカットの修飾キーが ⌘） */
  val isMac: Boolean = "Mac|iPhone|iPad".r.findFirstIn(dom.window.navigator.platform).isDefined

  /** 検索用の正規化: 全角/半角・大文字/小文字・カタカナ/ひらがなを区別しない */
  def normalizeForSearch(s: String): String =
    s.asInstanceOf[js.Dynamic].normalize("NFKC").asInstanceOf[String].toLowerCase.map(c => if (c >= 'ァ' && c <= 'ヶ') (c - 0x60).toChar else c)

  /** キー操作の対象が文字の入力欄・エディタか（ショートカットを効かせない） */
  def isTyping(t: dom.EventTarget): Boolean = t match {
    case el: dom.HTMLElement => el.isContentEditable || Set("INPUT", "TEXTAREA", "SELECT").contains(el.tagName)
    case _ => false
  }

  /** クリップボードへコピーし、copied を key にして 1.5 秒後に戻す */
  def copy[K](text: String, copied: RV[Option[K]], key: K): Unit =
    dom.window.navigator.clipboard.writeText(text).`then`[Unit](_ => {
      copied := Some(key)
      dom.window.setTimeout(() => copied := None, 1500)
      ()
    })

  /** 日時の表示（2026/11/13 10:48） */
  def date(iso: String): String =
    new js.Date(iso).asInstanceOf[js.Dynamic]
      .toLocaleString("ja-JP", js.Dynamic.literal(year = "numeric", month = "2-digit", day = "2-digit", hour = "2-digit", minute = "2-digit"))
      .asInstanceOf[String]

  /** ファイルを保存させる */
  def download(filename: String, data: dom.Blob): Unit = {
    val url = dom.URL.createObjectURL(data)
    val a = dom.document.createElement("a").asInstanceOf[dom.HTMLAnchorElement]
    a.href = url
    a.asInstanceOf[js.Dynamic].download = filename
    a.click()
    dom.window.setTimeout(() => dom.URL.revokeObjectURL(url), 1000)
  }

  def downloadText(filename: String, text: String, mime: String = "text/plain;charset=utf-8"): Unit =
    download(filename, new dom.Blob(js.Array(text), new dom.BlobPropertyBag { `type` = mime }))

}

/**
 * 入力が落ち着いてから実行する. 古い応答で新しい結果を上書きしないよう、関数には「まだ最新の呼び出しか」を返す latest() を渡す.
 * run: delay 後に実行 / now: すぐ実行 / cancel: 待っているものを取り消し、実行中の応答も無視する
 */
final class Debounced(delay: Int) {

  private var timer: Option[Int] = None
  private var seq = 0

  private def start(f: (() => Boolean) => Unit): () => Unit = {
    timer.foreach(dom.window.clearTimeout)
    seq += 1
    val my = seq
    () => f(() => my == seq)
  }

  def run(f: (() => Boolean) => Unit): Unit = {
    val g = start(f)
    timer = Some(dom.window.setTimeout(() => g(), delay))
  }

  def now(f: (() => Boolean) => Unit): Unit = start(f)()

  def cancel(): Unit = {
    timer.foreach(dom.window.clearTimeout)
    seq += 1
  }

}

/** オートコンプリート（parts/Combo.st）の選択肢. icon は前に付ける文字、hint は右に小さく出す説明（絞り込みにも使う） */
final case class Opt(value: String, label: String, icon: String = "", hint: String = "")
