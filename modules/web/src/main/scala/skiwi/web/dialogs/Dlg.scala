package skiwi.web.dialogs

import org.scalajs.dom
import scala.scalajs.js
import skiwi.shared.Titles

/** ダイアログで共通の小さな補助 */
object Dlg {

  /** 入力欄・選択の値 */
  def value(e: dom.Event): String = e.target.asInstanceOf[js.Dynamic].value.asInstanceOf[String]

  /** チェックボックスの状態 */
  def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

  /** 入力欄の文字を選ぶ */
  def select(e: dom.Event): Unit = e.target.asInstanceOf[js.Dynamic].select()

  def untitled(t: String): String = Titles.display(t)

  def untitled(t: Option[String]): String = untitled(t.getOrElse(""))

  /** 空白だけなら None */
  def nonBlank(s: String): Option[String] = Some(s.trim).filter(_.nonEmpty)

  /** 3桁ごとのカンマ（toLocaleString） */
  def num(n: Int): String = n.toString.reverse.grouped(3).mkString(",").reverse

  private def p2(n: Int): String = if (n < 10) s"0$n" else n.toString

  /** この端末の時刻での日付 YYYY-MM-DD */
  def ymd(d: js.Date): String = skiwi.web.database.Formula.ymd(d)

  /** この端末の時刻での日時 YYYY-MM-DDTHH:mm（input[type=datetime-local] の値） */
  def localDateTime(d: js.Date): String = s"${ymd(d)}T${p2(d.getHours().toInt)}:${p2(d.getMinutes().toInt)}"

  /** 権限の期限: ISO → 日付の入力欄の値 */
  def dateOf(iso: Option[String]): String = iso.fold("")(i => ymd(new js.Date(i)))

  /** 日付の入力欄の値 → その日の終わり（この端末の時刻）. 空欄は None */
  def endOf(v: String): Option[String] = if (v.isEmpty) None else Some(new js.Date(s"${v}T23:59:00").toISOString())

  /** d 日後の終わり */
  def daysLater(d: Int): String = {
    val t = new js.Date()
    t.setDate(t.getDate() + d)
    t.setHours(23, 59, 0, 0)
    t.toISOString()
  }

  def toLocal(iso: Option[String]): String = iso.fold("")(i => localDateTime(new js.Date(i)))

  def fromLocal(v: String): Option[String] = if (v.isEmpty) None else Some(new js.Date(v).toISOString())

  /** クリップボードへコピーし、copied を 1.5 秒 true にする. flashOnError: コピーできなくても表示する */
  def copy(text: String,
           copied: io.github.ondeoma.scalactive.reactive.RV[Boolean],
           flashOnError: Boolean = false): Unit = {
    def flash(): Unit = {
      copied := true
      dom.window.setTimeout(() => copied := false, 1500)
    }
    dom.window.navigator.clipboard.writeText(text).`then`[Unit](_ => flash(), (_: Any) => if (flashOnError) flash())
  }

}
