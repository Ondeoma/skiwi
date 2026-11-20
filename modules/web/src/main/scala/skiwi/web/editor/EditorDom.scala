package skiwi.web.editor

import org.scalajs.dom

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.Try

/** エディタのまわりの画面の小さな処理 */
object EditorDom {

  /** エディタの上の状態（接続・同期・閲覧のみ・アップロード中）. state: connecting / connected / disconnected */
  def drawStatus(el: dom.HTMLElement,
                 revoked: Boolean,
                 state: String,
                 synced: Boolean,
                 canEdit: Boolean,
                 uploading: Int): Unit = {
    el.className = s"status ${if (revoked) "disconnected" else state}"
    el.replaceChildren()
    def span(text: String, testid: Option[String] = None): Unit = {
      val s = dom.document.createElement("span")
      s.textContent = text
      testid.foreach(s.setAttribute("data-testid", _))
      el.appendChild(s)
    }
    span(
      if (revoked) "● このページへのアクセス権がなくなりました"
      else if (state == "connected" && synced) "● 同期済み"
      else if (state == "connected") "● 同期中…"
      else if (state == "connecting") "● 接続中…"
      else "● オフライン（再接続します。編集は保持されます）"
    )
    if (!canEdit && !revoked) span(" ・ 閲覧のみ")
    if (uploading > 0) span(s" ・ アップロード中…（${uploading}件）", Some("uploading"))
  }

  /**
   * メニューなどの外をクリック（mousedown）したら閉じる. escape を渡すと Esc キーでも呼ぶ.
   * defer: 開いたクリック自体で閉じないよう、次のタスクで登録する. 戻り値で解除する
   */
  def onDismiss(inside: dom.Node => Boolean,
                close: () => Unit,
                escape: Option[() => Unit],
                defer: Boolean): () => Unit = {
    val onDoc: js.Function1[dom.MouseEvent, Unit] = e => if (!inside(e.target.asInstanceOf[dom.Node])) close()
    val onKey: js.Function1[dom.KeyboardEvent, Unit] = e => if (e.key == "Escape") escape.foreach(_())
    var alive = true
    if (defer) dom.window.setTimeout(() => if (alive) dom.document.addEventListener("mousedown", onDoc), 0)
    else dom.document.addEventListener("mousedown", onDoc)
    if (escape.isDefined) dom.document.addEventListener("keydown", onKey)
    () => {
      alive = false
      dom.document.removeEventListener("mousedown", onDoc)
      dom.document.removeEventListener("keydown", onKey)
    }
  }

  /** クリップボードへコピーし、ボタンの文字を1.5秒「コピーしました」にする. label: ふだんの文字（戻すときに使う） */
  def copyWithFlash(btn: dom.HTMLElement, text: String, label: String, done: String = "コピーしました"): Future[Unit] =
    dom.window.navigator.clipboard.writeText(text).toFuture.map { _ =>
      btn.textContent = done
      dom.window.setTimeout(() => btn.textContent = label, 1500)
      ()
    }.recover { case _ => () }

  /** ファイルを選んでもらう（取り消したら空） */
  def pickFiles(accept: String, multiple: Boolean): Future[List[dom.File]] = {
    val p = scala.concurrent.Promise[List[dom.File]]()
    val input = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
    input.`type` = "file"
    input.multiple = multiple
    input.accept = accept
    input.onchange =
      _ => p.trySuccess(Option(input.files).fold(List.empty[dom.File])(fs => (0 until fs.length).map(fs(_)).toList))
    input.asInstanceOf[js.Dynamic].oncancel = (() => p.trySuccess(Nil)): js.Function0[Unit]
    input.click()
    p.future
  }

  /**
   * ファイルのドラッグ＆ドロップの受け皿.
   *  - 編集中のエディタの上では、落とせる場所を点線で示す（エディタがアップロードする）
   *  - それ以外（表示モードの本文・余白・サイドメニューなど）に落としても、ブラウザがファイルを開いてページから離れないようにする
   * ホワイトボード（Excalidraw）は自分で受け取るので邪魔しない.
   */
  def installFileDropGuard(): Unit = {
    val Accepts = ".ProseMirror[contenteditable=\"true\"], .excalidraw"
    def hasFiles(e: dom.DragEvent): Boolean =
      e.dataTransfer != null && js.Array.from(e.dataTransfer.types.asInstanceOf[js.Iterable[String]]).contains("Files")
    def accepts(t: dom.EventTarget): Boolean = t match {
      case el: dom.Element => el.closest(Accepts) != null
      case _ => false
    }
    var depth = 0
    def set(on: Boolean): Unit = dom.document.body.classList.toggle("dragging-files", on)
    dom.window.addEventListener("dragenter", (e: dom.DragEvent) => if (hasFiles(e)) { depth += 1; set(true) })
    dom.window.addEventListener(
      "dragleave",
      (e: dom.DragEvent) => if (hasFiles(e)) { depth -= 1; if (depth <= 0) { depth = 0; set(false) } }
    )
    dom.window.addEventListener(
      "dragover",
      (e: dom.DragEvent) =>
        if (hasFiles(e) && !e.defaultPrevented && !accepts(e.target)) {
          e.preventDefault()
          e.dataTransfer.dropEffect = dom.DataTransferDropEffectKind.none
        }
    )
    dom.window.addEventListener(
      "drop",
      (e: dom.DragEvent) => {
        depth = 0
        set(false)
        if (hasFiles(e) && !e.defaultPrevented && !accepts(e.target)) e.preventDefault()
      }
    )
  }

  /**
   * ⌘A / Ctrl+A を、入力欄やエディタの外（表示モードなど）で押したとき、ページ全体ではなく本文（el の中）だけを選ぶ.
   * ダイアログを開いているときは何もしない. 戻り値で解除
   */
  def selectArticleOnModA(el: () => dom.HTMLElement): () => Unit = {
    val onKey: js.Function1[dom.KeyboardEvent, Unit] = e =>
      if (
        (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase == "a" && !e.defaultPrevented &&
        !sbirds.web.Dom.isTyping(e) && dom.document.querySelector("[role=dialog], [aria-modal=true]") == null
      ) {
        val target = el()
        if (target.isConnected) {
          e.preventDefault()
          Try(dom.window.getSelection().selectAllChildren(target))
        }
      }
    dom.document.addEventListener("keydown", onKey)
    () => dom.document.removeEventListener("keydown", onKey)
  }

  /** いまダークで表示しているか（明暗の切り替えは skiwi.web.Theme が <html> の class "dark" で行う） */
  def isDark: Boolean = dom.document.documentElement.classList.contains("dark")

  /** 明暗が変わったら f を呼ぶ. 戻り値で解除 */
  def onThemeChange(f: Boolean => Unit): () => Unit = {
    val mo = new dom.MutationObserver((_, _) => f(isDark))
    mo.observe(
      dom.document.documentElement,
      new dom.MutationObserverInit { attributes = true; attributeFilter = js.Array("class") }
    )
    () => mo.disconnect()
  }

}
