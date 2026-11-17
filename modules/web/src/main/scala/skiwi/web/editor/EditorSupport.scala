package skiwi.web.editor

import org.scalajs.dom
import skiwi.web.ApiError
import skiwi.web.content.{Attachment, Bytes, ContentApi}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

/** エディタへのファイルの追加（貼り付け・ドロップ・「/画像」など）. アップロードして、本文に入れるノードを作る */
object Uploads {

  private val Unnamed = "(?i)^image\\.(png|jpe?g|gif|webp)$".r

  /** 貼り付けた画像に名前がない（スクリーンショットなど）ときは、日時から名前を付ける */
  def named(files: List[dom.File]): List[dom.File] = {
    val stamp = new js.Date().toISOString().take(19).replaceAll("[-:]", "").replace('T', '-')
    files.zipWithIndex.map { (f, i) =>
      if (f.name.nonEmpty && !Unnamed.matches(f.name)) f
      else {
        val ext = f.`type`.split('/').lift(1).filter(_.nonEmpty).getOrElse("png").replace("jpeg", "jpg")
        new dom.File(js.Array(f: dom.BlobPart), s"貼り付け-$stamp${if (files.size > 1) s"-${i + 1}" else ""}.$ext", new dom.FilePropertyBag { `type` = f.`type` })
      }
    }
  }

  /** draw.io の図のファイル（図を埋め込んだ PNG・図の XML）なら、図の中の文字 */
  private def drawioText(f: dom.File): Future[Option[String]] = {
    val xml: Future[Option[String]] =
      if (Drawio.isPng(f.name)) Bytes.read(f).flatMap(Drawio.xmlFromPng)
      else if (Drawio.isXml(f.name)) f.asInstanceOf[js.Dynamic].text().asInstanceOf[js.Promise[String]].toFuture.map(Some(_))
      else Future.successful(None)
    xml.flatMap {
      case Some(x) if x.contains("<mx") => Drawio.labelsOf(x).map(Some(_))
      case _ => Future.successful(None)
    }.recover { case _ => None }
  }

  /** アップロードした添付を本文に入れる形に（画像は画像、それ以外はファイルのカード. draw.io の図は図のブロック） */
  def nodeOf(a: Attachment, drawio: Option[String]): js.Any = drawio match {
    case Some(text) =>
      val title = "(?i)\\.(drawio|dio)(\\.png)?$".r.replaceFirstIn(a.filename, "")
      if (Drawio.isPng(a.filename)) js.Dynamic.literal(`type` = "drawio", attrs = js.Dynamic.literal(src = a.url, title = title, text = text))
      else js.Dynamic.literal(`type` = "drawio", attrs = js.Dynamic.literal(xmlSrc = a.url, title = title, text = text))
    case None =>
      if (a.contentType.startsWith("image/") && a.contentType != "image/svg+xml")
        js.Dynamic.literal(`type` = "image", attrs = js.Dynamic.literal(src = a.url, alt = a.filename))
      else
        js.Dynamic.literal(`type` = "fileBlock", attrs = js.Dynamic.literal(id = a.id, url = a.url, filename = a.filename, size = a.size, contentType = a.contentType))
  }

  private def message(e: Throwable): String = e match {
    case a: ApiError => a.message
    case other => Bytes.message(other)
  }

  /**
   * 複数のファイルを同時にアップロードし、選んだ順のノードにする.
   * 戻り値: { nodes: 入れるノード, failed: 失敗したファイルの知らせ（すべて成功なら null） }
   */
  def uploadAll(pageId: String, files: List[dom.File]): Future[js.Dynamic] = {
    val fs = named(files)
    Future.sequence(fs.map(drawioText)).flatMap { drawio =>
      Future.sequence(fs.map(f => ContentApi.uploadAttachment(pageId, f).transform(Success(_)))).map { results =>
        val nodes = results.zip(drawio).collect { case (Success(a), d) => nodeOf(a, d) }
        val failed = results.zip(fs).collect { case (Failure(e), f) => s"・${f.name}: ${message(e)}" }
        js.Dynamic.literal(nodes = js.Array(nodes*),
          failed = if (failed.isEmpty) null else s"アップロードに失敗したファイルがあります:\n${failed.mkString("\n")}")
      }
    }
  }

  /** 添付の JS の形（エディタの uploadFile などに返す） */
  def toJs(a: Attachment): js.Any =
    js.Dynamic.literal(id = a.id, url = a.url, filename = a.filename, contentType = a.contentType, size = a.size)

}

/** 同じページを開いている人（同時編集の awareness から） */
object Presence {

  /** サーバーが発行するアイコンの URL の形（同時編集で他の人から届いた値は、この形のものだけを使う） */
  private val AvatarUrl = "^/api/users/[0-9a-f-]{36}/avatar\\?v=\\d+$".r
  private val ColorRe = "(?i)^#[0-9a-f]{6}$".r

  def safeAvatarUrl(u: Any): Option[String] = u match {
    case s: String if AvatarUrl.matches(s) => Some(s)
    case _ => None
  }

  private final case class Person(name: String, color: String, editing: Boolean, avatar: Option[String])

  /** awareness の状態（クライアント ID → 状態）から、自分以外の人. 同じ人の複数タブは1人にまとめ、名前の順に並べる */
  def people(states: js.Any, self: Double): js.Array[js.Any] = {
    var people = Map.empty[String, Person]
    states.asInstanceOf[js.Dynamic].forEach((st: js.Dynamic, client: Double) => {
      val user: js.Dynamic = if (js.isUndefined(st) || st == null) js.Dynamic.literal() else st.user
      val name = if (js.isUndefined(user) || user == null || js.isUndefined(user.name) || user.name == null) "" else user.name.toString
      if (client != self && name.nonEmpty) {
        val cur = people.get(name)
        val color = String.valueOf(user.color)
        people += name -> Person(name, if (ColorRe.matches(color)) color else "#888888",
          (!js.isUndefined(st.mode) && st.mode.toString == "edit") || cur.exists(_.editing),
          safeAvatarUrl(user.avatar.asInstanceOf[Any]).orElse(cur.flatMap(_.avatar)))
      }
    })
    js.Array(people.values.toList.sortWith((a, b) => skiwi.web.workspace.WsText.jaLess(a.name, b.name)).map { p =>
      js.Dynamic.literal(name = p.name, color = p.color, editing = p.editing, avatar = p.avatar.orNull): js.Any
    }*)
  }

}

/** エディタのまわりの画面の小さな処理 */
object EditorDom {

  /** エディタの上の状態（接続・同期・閲覧のみ・アップロード中）. state: connecting / connected / disconnected */
  def drawStatus(el: dom.HTMLElement, revoked: Boolean, state: String, synced: Boolean, canEdit: Boolean, uploading: Int): Unit = {
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
      else "● オフライン（再接続します。編集は保持されます）")
    if (!canEdit && !revoked) span(" ・ 閲覧のみ")
    if (uploading > 0) span(s" ・ アップロード中…（${uploading}件）", Some("uploading"))
  }

  /**
   * メニューなどの外をクリック（mousedown）したら閉じる. escape を渡すと Esc キーでも呼ぶ.
   * defer: 開いたクリック自体で閉じないよう、次のタスクで登録する. 戻り値で解除する
   */
  def onDismiss(inside: dom.Node => Boolean, close: () => Unit, escape: Option[() => Unit], defer: Boolean): () => Unit = {
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
    input.onchange = _ => p.trySuccess(Option(input.files).fold(List.empty[dom.File])(fs => (0 until fs.length).map(fs(_)).toList))
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
    dom.window.addEventListener("dragleave", (e: dom.DragEvent) => if (hasFiles(e)) { depth -= 1; if (depth <= 0) { depth = 0; set(false) } })
    dom.window.addEventListener("dragover", (e: dom.DragEvent) =>
      if (hasFiles(e) && !e.defaultPrevented && !accepts(e.target)) {
        e.preventDefault()
        e.dataTransfer.dropEffect = dom.DataTransferDropEffectKind.none
      })
    dom.window.addEventListener("drop", (e: dom.DragEvent) => {
      depth = 0
      set(false)
      if (hasFiles(e) && !e.defaultPrevented && !accepts(e.target)) e.preventDefault()
    })
  }

  /**
   * ⌘A / Ctrl+A を、入力欄やエディタの外（表示モードなど）で押したとき、ページ全体ではなく本文（el の中）だけを選ぶ.
   * ダイアログを開いているときは何もしない. 戻り値で解除
   */
  def selectArticleOnModA(el: () => dom.HTMLElement): () => Unit = {
    val onKey: js.Function1[dom.KeyboardEvent, Unit] = e =>
      if ((e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey && e.key.toLowerCase == "a" && !e.defaultPrevented &&
          !skiwi.web.Ui.isTyping(e.target) && dom.document.querySelector("[role=dialog], [aria-modal=true]") == null) {
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
    mo.observe(dom.document.documentElement, new dom.MutationObserverInit { attributes = true; attributeFilter = js.Array("class") })
    () => mo.disconnect()
  }

}
