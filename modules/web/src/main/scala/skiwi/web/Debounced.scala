package skiwi.web

import org.scalajs.dom

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
