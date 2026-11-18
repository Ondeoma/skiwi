<script lang="scala">
import skiwi.shared.Titles
import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.*
import skiwi.web.model.*
import skiwi.web.database.{DatabaseView, RowProps}
import skiwi.web.dialogs.{CssDialog, LockDialog, ShareDialog}
import skiwi.web.parts.IconPicker

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ページ（ツールバー・パンくず・タイトル・属性・本文のエディタ・コメント・版履歴）.
// WorkspaceView が pid ごとに作り直すので、同じページの中のクエリ文字列（?edit=1・?access=requests・?comment=…）はマウント時に読む.
case class Props(wid: String, pid: String)

case class State(title: RV[String] = RV(""),
                 titleDeb: Debounced = new Debounced(400),
                 /** サーバーが接続中に通知する権限（共有設定の変更で変わる）. 未通知ならページ一覧の権限 */
                 liveRole: RV[Option[String]] = RV(None),
                 /** 表示モード（view）/ 編集モード（edit） */
                 mode: RV[String] = RV("view"),
                 touched: RV[Boolean] = RV(false),
                 picking: RV[Boolean] = RV(false),
                 watchState: RV[Option[WatchState]] = RV(None),
                 watchMenu: RV[Boolean] = RV(false),
                 sharing: RV[Boolean] = RV(false),
                 history: RV[Boolean] = RV(false),
                 handle: RV[Option[EditorHandle]] = RV(None),
                 cssLayers: RV[List[CssLayer]] = RV(Nil),
                 cssPreview: RV[Option[String]] = RV(None),
                 cssOpen: RV[Boolean] = RV(false),
                 cssApplier: RV[Option[CustomCss.Applier]] = RV(None),
                 /** このページを開いているほかの人 */
                 people: RV[List[Person]] = RV(Nil),
                 denied: RV[Boolean] = RV(false),
                 comments: RV[List[PageComment]] = RV(Nil),
                 commentsOpen: RV[Boolean] = RV(false),
                 commentDraft: RV[Option[CommentDraft]] = RV(None),
                 commentFocus: RV[Option[String]] = RV(None),
                 quietStyle: RV[Option[dom.Element]] = RV(None),
                 backlinks: RV[List[Page]] = RV(Nil),
                 lockOpen: RV[Boolean] = RV(false),
                 lock: RV[Option[PageLockState]] = RV(None),
                 exportMenu: RV[Boolean] = RV(false),
                 toolbarEl: ElementRef[dom.HTMLElement] = ElementRef(),
                 toolbarWatching: RV[Boolean] = RV(false),
                 cleanups: RV[List[() => Unit]] = RV(Nil),
                 gone: RV[Boolean] = RV(false))

def initState() = State()

// ---------------------------------------------------------------- ページ・権限・モード

/** このページ（一覧にまだなければ空のページ. 表示は hasPage のときだけ） */
def pg(props: Props): Page =
  Store.byId(props.pid).getOrElse(Page(props.pid, props.wid, None, "", "", None, "view", ""))

def hasPage(props: Props) = Store.pages.mapC(_.exists(_.id == props.pid))

def role(s: State, props: Props): String = s.liveRole.v.getOrElse(Store.byId(props.pid).map(_.role).getOrElse("view"))

def canEdit(s: State, props: Props): Boolean = role(s, props) == "edit"

def canEditC(s: State, props: Props) = computed { canEdit(s, props) }

def editing(s: State, props: Props): Boolean = canEdit(s, props) && s.mode.v == "edit"

def editingC(s: State, props: Props) = computed { editing(s, props) }

def setMode(s: State, m: String): Unit = {
  s.touched := true
  s.mode := m
}

/** 「E」で編集モード（入力欄・エディタ内の入力は除く） */
def onKey(s: State, props: Props, e: dom.KeyboardEvent): Unit = {
  val composing = e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
  if (!composing && !e.metaKey && !e.ctrlKey && !e.altKey && !Ui.isTyping(e.target) && e.key.toLowerCase == "e" &&
      canEdit(s, props) && s.mode.v == "view") {
    e.preventDefault()
    setMode(s, "edit")
  }
}

def onTitleInput(s: State, props: Props, e: dom.Event): Unit = {
  s.title := e.target.asInstanceOf[dom.HTMLInputElement].value
  s.titleDeb.run { _ =>
    Store.rename(props.pid, s.title.v).failed.foreach(e => Dialog.alert(s"保存できませんでした: ${e.getMessage}"))
  }
}

def onRole(s: State, r: String): Unit = {
  val changed = s.liveRole.v.exists(_ != r)
  s.liveRole := Some(r)
  if (changed || r == "none") Store.reloadPages() // ツリー上の権限表示も更新
}

// ---------------------------------------------------------------- アイコン

def pickIcon(s: State, props: Props, icon: Option[String]): Unit = {
  s.picking := false
  Store.setIcon(props.pid, icon).failed.foreach(e => Dialog.alertError("変更できませんでした", e))
}

// ---------------------------------------------------------------- ウォッチ
// 受け取る内容（本文の更新・コメント）と、子ページも含めるかを選べる. 祖先を「子ページも含める」でウォッチしていれば、その設定で届く

/** 通知が届く状態か（このページの設定、なければ祖先の設定） */
def watchOn(w: WatchState): Boolean = w.watching || w.inherited.isDefined

def watchLabel(w: WatchState): String =
  if (!w.watching) { if (w.inherited.isDefined) "ウォッチ中（親）" else "ウォッチ" }
  else if (w.edits && w.comments) "ウォッチ中"
  else if (w.comments) "コメントのみ"
  else "更新のみ"

/** 今届く内容（edits, comments）. このページの設定、なければ祖先の設定 */
def effWatch(w: WatchState): (Boolean, Boolean) =
  if (w.watching) (w.edits, w.comments) else (w.inherited.exists(_.edits), w.inherited.exists(_.comments))

def setWatchOpts(s: State, props: Props, edits: Option[Boolean] = None, comments: Option[Boolean] = None, subtree: Option[Boolean] = None): Unit =
  PageApi.setWatchOpts(props.pid, edits, comments, subtree).map(w => s.watchState := Some(w))
    .failed.foreach(e => Dialog.alertError("変更できませんでした", e))

def setWatchKind(s: State, props: Props, kind: String, v: Boolean): Unit = s.watchState.v.foreach { w =>
  val (e, c) = effWatch(w)
  if (kind == "edits") setWatchOpts(s, props, Some(v), Some(c)) else setWatchOpts(s, props, Some(e), Some(v))
}

def stopWatch(s: State, props: Props): Unit = {
  PageApi.setWatch(props.pid, false).map(w => s.watchState := Some(w)).failed.foreach(e => Dialog.alertError("変更できませんでした", e))
  s.watchMenu := false
}

def checked(e: dom.Event): Boolean = e.target.asInstanceOf[dom.HTMLInputElement].checked

def watchInheritedNote(w: WatchState): String = w.inherited.fold("") { i =>
  val what = if (i.edits && i.comments) "更新とコメント" else if (i.edits) "更新" else "コメント"
  s"「${Titles.display(i.title)}」のウォッチ（子ページも含む）で、${what}の通知が届きます。ここで選ぶと、このページはその設定が優先されます。"
}

def watchOverrideNote(w: WatchState): String =
  w.inherited.fold("")(i => s"親の「${Titles.display(i.title)}」のウォッチより、このページの設定が優先されます。")

// ---------------------------------------------------------------- 共有・カスタムCSS

def onShareClosed(s: State): Unit = {
  s.sharing := false
  Store.reloadPages()
}

def loadCss(s: State, props: Props): Unit =
  PageApi.pageStyles(props.pid).map(_.layers).recover(_ => Nil).foreach(s.cssLayers := _)

/** 適用する CSS（ワークスペース → 子にも適用する祖先 → このページ. 編集中は入力中の CSS をその場で反映） */
def appliedCss(s: State): List[String] = {
  val ls = s.cssLayers.v
  ls.filterNot(_.own).map(_.css) :+ s.cssPreview.v.orElse(ls.find(_.own).map(_.css)).getOrElse("")
}

def applyCss(s: State): Unit = s.cssApplier.v.foreach(_.set(appliedCss(s)))

def onCssClosed(s: State): Unit = {
  s.cssOpen := false
  s.cssPreview := None
}

// ---------------------------------------------------------------- 所有者・状態

def updateMeta(props: Props, body: Json): Unit =
  Api.updatePage(props.pid, body).map { np =>
    Store.patchLocal(props.pid, _.copy(ownerId = np.ownerId, ownerName = np.ownerName, status = np.status))
  }.failed.foreach(e => Dialog.alertError("変更できませんでした", e))

def pickStatus(s: State, props: Props): Unit = if (canEdit(s, props)) {
  val opts = Page.Statuses.map(k => Choice(Page.StatusLabel(k), k)) :+ Choice("（状態なし）", "")
  Dialog.choose("ページの状態", opts).foreach(_.foreach { v =>
    updateMeta(props, Json.obj("status" -> (if (v.isEmpty) Json.Null else v.asJson)))
  })
}

def pickOwner(s: State, props: Props): Unit = if (canEdit(s, props)) {
  Dialog.pickUser("所有者（名前・メールで検索。空欄で外す）", "所有者を選んでください", initial = pg(props).ownerName.getOrElse(""), allowEmpty = true)
    .foreach(_.foreach(u => updateMeta(props, Json.obj("ownerId" -> u.map(_.id).asJson))))
}

// ---------------------------------------------------------------- 別のワークスペースへ移ったページ
// 古いリンク・ブックマークは、正しいワークスペースで開き直す. 開けなければ閲覧権限を申請できる画面を出す

def relocate(s: State, props: Props): Unit =
  if (Store.currentId.v.contains(props.wid) && Store.byId(props.pid).isEmpty)
    Api.page(props.pid).andThen {
      case scala.util.Success(p) if p.workspaceId != props.wid =>
        val l = dom.window.location
        Nav.replace(s"${Nav.pageHref(p.workspaceId, p.id)}${l.search}${l.hash}")
    }.failed.foreach {
      // 権限なし・削除済み・存在しない
      case e: ApiError if e.status == 404 || e.status == 403 => s.denied := true
      case _ => ()
    }

// ---------------------------------------------------------------- コメント

def loadComments(s: State, props: Props): Unit = PageApi.comments(props.pid).foreach(s.comments := _)

def openThreads(s: State) = s.comments.mapC(_.count(c => c.threadId.isEmpty && c.resolvedAt.isEmpty && !c.deleted))

def onSelectionComment(s: State, d: CommentDraft): Unit = {
  s.commentDraft := Some(d)
  s.commentFocus := None
  s.commentsOpen := true
}

def onCommentClick(s: State, anchorId: String): Unit = {
  s.commentFocus := Some(s"text:$anchorId")
  s.commentsOpen := true
}

def closeComments(s: State): Unit = {
  s.commentsOpen := false
  s.commentDraft := None
}

/** 解決済み・削除済みのスレッドの印は目立たせない（本文の印はそのまま残す） */
def quietCss(cs: List[PageComment]): String =
  cs.filter(c => c.threadId.isEmpty && c.anchor.exists(_.startsWith("text:")) && (c.resolvedAt.isDefined || c.deleted))
    .map(c => s".ProseMirror .comment-mark[data-comment=\"${esc(c.anchor.get.drop(5))}\"]{background:none;border-bottom:none}")
    .mkString("\n")

def esc(v: String): String = js.Dynamic.global.CSS.escape(v).asInstanceOf[String]

/** 同期ブロック・ブロックへのリンク（#sync-ID・#block-ID）で開いたら、そのブロックへスクロール */
def scrollToHash(): Unit =
  """^#(sync|block)-([0-9a-zA-Z]+)$""".r.findFirstMatchIn(dom.window.location.hash).foreach { m =>
    val sync = m.group(1).equalsIgnoreCase("sync")
    val sel = if (sync) s".ProseMirror [data-synced-id=\"${esc(m.group(2))}\"]" else s".ProseMirror [data-block-id=\"${esc(m.group(2))}\"]"
    var tries = 0
    var t = 0
    t = dom.window.setInterval(() => {
      val el = dom.document.querySelector(sel)
      tries += 1
      if (el != null || tries > 20) {
        dom.window.clearInterval(t)
        val cls = if (sync) "flash" else "block-flash"
        if (el != null) {
          el.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "center"))
          el.classList.add(cls)
          dom.window.setTimeout(() => el.classList.remove(cls), 1600)
        }
      }
    }, 250)
  }

// ---------------------------------------------------------------- ラベル

def saveLabels(props: Props, ls: List[String]): Unit =
  PageApi.setLabels(props.pid, ls).map(r => Store.patchLocal(props.pid, _.copy(labels = Some(r))))
    .failed.foreach(e => Dialog.alertError("ラベルを保存できませんでした", e))

def addLabel(props: Props): Unit =
  Dialog.prompt("追加するラベル（「,」区切りで複数可）", "", okLabel = "追加", placeholder = "例: 手順書, API").foreach {
    case Some(v) if v.trim.nonEmpty => saveLabels(props, pg(props).labelList ++ v.split("[,、，]").toList)
    case _ => ()
  }

def removeLabel(props: Props, l: String): Unit = saveLabels(props, pg(props).labelList.filterNot(_ == l))

// ---------------------------------------------------------------- データベース・パンくず・全幅

/** 親がデータベースなら、このページはその項目（値を上部に出す） */
def inDatabase(props: Props): Boolean = pg(props).parentId.flatMap(Store.byId).exists(_.isDatabase)

def makeDatabase(props: Props): Unit =
  skiwi.web.database.DbApi.createDefault(props.pid).flatMap(_ => Store.reloadPages()).failed.foreach(e => Dialog.alertError("データベースにできませんでした", e))

/** パンくず（親ページの階層. 見られない親は省く） */
def crumbs(props: Props): List[Page] = Store.ancestors(props.pid).take(20).reverse

/** リンク（Ctrl/⌘/Shift を押しながら・中クリックはブラウザに任せる） */
def go(e: dom.Event, href: String): Unit = e match {
  case m: dom.MouseEvent if m.button == 0 && !m.ctrlKey && !m.metaKey && !m.shiftKey && !m.altKey =>
    e.preventDefault()
    Nav.go(href)
  case _ => ()
}

def toggleFullWidth(props: Props): Unit = Store.byId(props.pid).foreach { p =>
  val v = !p.fullWidth.contains(true)
  Store.patchLocal(p.id, _.copy(fullWidth = Some(v)))
  Api.updatePage(p.id, Json.obj("fullWidth" -> v.asJson)).failed.foreach { e =>
    Store.patchLocal(p.id, _.copy(fullWidth = Some(!v)))
    Dialog.alertError("変更できませんでした", e)
  }
}

// ---------------------------------------------------------------- 保護

def onLockChanged(s: State): Unit = {
  Store.reloadPages()
  s.liveRole := None // 保護による権限の変化はページ一覧の権限で反映する
}

def lockNote(s: State) = s.lock.mapC(l => s"（${if (l.exists(_.canEdit)) "あなたは編集できます" else "編集できるのは、保護した人・所有者・指定された人だけです"}）")

def lockReason(s: State) = s.lock.mapC(_.flatMap(_.reason).filter(_.nonEmpty).fold("")(r => s": $r"))

// ---------------------------------------------------------------- 書き出し

def exportMarkdown(s: State, props: Props): Unit = {
  s.exportMenu := false
  for (h <- s.handle.v; p <- Store.byId(props.pid))
    skiwi.web.content.Export.markdown(p, EditorHandle.toJson(h.getJSON()))
}

def exportTree(s: State, props: Props): Unit = {
  s.exportMenu := false
  skiwi.web.content.Export.zip(props.pid, s.handle.v.map(h => EditorHandle.toJson(h.getJSON())))
}

def printPage(s: State): Unit = {
  s.exportMenu := false
  if (s.mode.v == "edit") setMode(s, "view")
  dom.window.setTimeout(() => dom.window.print(), 100)
}

def duplicate(s: State, props: Props): Unit = {
  s.exportMenu := false
  Store.duplicateTarget := Store.byId(props.pid)
}

// ---------------------------------------------------------------- 右上のボタン
// 文字が入りきらないときは、アイコンだけにする（折り返して不恰好にならないように）

def fitToolbar(el: dom.HTMLElement): Unit = {
  // 右寄せのままだとはみ出しが左側に出て測れないので、測る間だけ左寄せにする
  // 1. そのまま → 2. 余白を詰める（tight） → 3. アイコンだけ（compact）の順に、入るものを選ぶ
  el.classList.remove("compact")
  el.classList.remove("tight")
  el.classList.add("measuring")
  def over = el.scrollWidth > el.clientWidth + 1
  var level = ""
  if (over) {
    el.classList.add("tight")
    level = if (over) "compact" else "tight"
    el.classList.remove("tight")
  }
  el.classList.remove("measuring")
  if (level.nonEmpty) el.classList.add(level)
}

def watchToolbar(s: State): Unit = if (!s.toolbarWatching.v) s.toolbarEl.foreach { el =>
  s.toolbarWatching := true
  val fit: js.Function0[Unit] = () => fitToolbar(el)
  val ro = js.Dynamic.newInstance(js.Dynamic.global.ResizeObserver)(fit)
  ro.observe(el)
  // ボタンの増減（編集モード・ウォッチ・保護の表示など）でも測り直す
  val mo = new dom.MutationObserver((_, _) => fitToolbar(el))
  mo.observe(el, new dom.MutationObserverInit { childList = true; subtree = true; characterData = true })
  fitToolbar(el)
  s.cleanups := s.cleanups.v :+ (() => { ro.disconnect(); mo.disconnect() })
}

// ---------------------------------------------------------------- マウント

def listen(s: State, target: dom.EventTarget, name: String)(f: dom.Event => Unit): Unit = {
  val h: js.Function1[dom.Event, Unit] = f
  target.addEventListener(name, h)
  s.cleanups := s.cleanups.v :+ (() => target.removeEventListener(name, h))
}

def mounted(s: State, props: Props): Unit = {
  // 既定は表示モード（誤って書き換えない・リンクや目次をそのまま使える）. 新規作成直後（?edit=1）と無題のページは編集モードで開く
  if (Nav.query("edit").contains("1")) s.mode := "edit"
  // 通知・メールの「権限の申請」から開いたときは共有画面を開く
  s.sharing := Nav.query("access").contains("requests")
  s.commentFocus := Nav.query("comment")
  if (s.commentFocus.v.isDefined) s.commentsOpen := true

  val page = Store.pages.mapC(_.find(_.id == props.pid))
  watch(page, true) { (_, p) =>
    p.foreach { p =>
      if (s.mode.v == "view" && p.title.isEmpty && p.role == "edit" && !s.touched.v) s.mode := "edit"
      if (Option(dom.document.activeElement).forall(_.id != "page-title")) s.title := p.title
    }
  }
  listen(s, dom.document, "keydown")(e => onKey(s, props, e.asInstanceOf[dom.KeyboardEvent]))

  // ウォッチ・コメント・被リンク・閲覧の記録
  PageApi.watch(props.pid).foreach(w => s.watchState := Some(w))
  loadComments(s, props)
  PageApi.backlinks(props.pid).foreach(s.backlinks := _)
  PageApi.visit(props.pid) // 記録できなくても表示は続ける

  // カスタムCSS
  loadCss(s, props)
  s.cssApplier := Some(CustomCss.applier())
  applyCss(s)
  watch(computed { appliedCss(s) }, false)((_, _) => applyCss(s))
  listen(s, dom.window, "skiwi:css-changed")(_ => loadCss(s, props))

  // ほかの画面からの依頼（ホワイトボードへのコメント・コマンドパレット）
  listen(s, dom.window, "skiwi:wb-comment") { e =>
    val id = e.asInstanceOf[dom.CustomEvent].detail.asInstanceOf[js.Dynamic].id
    s.commentDraft := Some(CommentDraft("wb", boardId = Some(String.valueOf(id))))
    s.commentsOpen := true
  }
  listen(s, dom.window, "skiwi:command") { e =>
    String.valueOf(e.asInstanceOf[dom.CustomEvent].detail) match {
      case "comments" => s.commentsOpen := true
      case "edit-page" if canEdit(s, props) => setMode(s, "edit")
      case _ => ()
    }
  }

  // 解決済み・削除済みのスレッドの印は目立たせない
  val quiet = dom.document.createElement("style")
  dom.document.head.appendChild(quiet)
  s.quietStyle := Some(quiet)
  watch(s.comments, true)((_, cs) => quiet.textContent = quietCss(cs))

  // 別のワークスペースへ移ったページ
  watch(computed { (Store.pages.v.size, Store.currentId.v) }, true)((_, _) => relocate(s, props))

  // 保護の内容
  watch(page.mapC(_.exists(_.isLocked)), true) { (_, locked) =>
    s.lock := None
    if (locked) PageApi.lock(props.pid).foreach(l => s.lock := Some(l))
  }

  // 右上のボタンの幅合わせ（ページが表示されてから）
  watch(hasPage(props), true)((_, b) => if (b) dom.window.setTimeout(() => if (!s.gone.v) watchToolbar(s), 0))

  scrollToHash()
}

def unmounted(s: State, props: Props): Unit = {
  s.gone := true
  s.titleDeb.cancel()
  s.cleanups.v.foreach(_())
  s.cleanups := Nil
  s.cssApplier.v.foreach(_.destroy())
  s.quietStyle.v.foreach(_.remove())
}

// ---------------------------------------------------------------- 表示

def articleCls(s: State, props: Props): String = {
  val p = pg(props)
  List("page skiwi-page-css", if (editing(s, props)) "editing" else "", if (p.fullWidth.contains(true)) "full-width" else "").filter(_.nonEmpty).mkString(" ")
}

def peopleTitle(s: State) = s.people.mapC(_.map(p => s"${p.name}${if (p.editing) "（編集中）" else "（閲覧中）"}").mkString("\n"))

def initialOf(name: String): String = if (name.isEmpty) "" else name.substring(0, name.offsetByCodePoints(0, 1))

def onCls(b: Boolean): String = if (b) "on" else ""

def statusCls(p: Page): String = s"meta-chip st ${p.status.getOrElse("none")}"

/** ツールバーの左に出すモードの印（編集中・閲覧モード・閲覧のみ） */
def modeText(s: State, props: Props): String =
  if (editing(s, props)) "✏️ 編集中" else if (canEdit(s, props)) "📖 閲覧モード" else "📖 閲覧のみ"
def modeTitle(s: State, props: Props): String =
  if (editing(s, props)) "編集しています（変更は自動で保存されます）. 「完了」で閲覧モードに戻ります"
  else if (canEdit(s, props)) "閲覧モードです. 「編集」（E）で編集できます"
  else "このページは閲覧のみです"
def modeCls(s: State, props: Props): String = if (editing(s, props)) "mode-badge edit" else "mode-badge view"

def crumbIcon(c: Page): String = c.icon.getOrElse(if (c.isDatabase) "📊" else "📄")
</script>

<template>
  <article v-if="hasPage(props)" :class="articleCls(s, props)">
    <div ref="s.toolbarEl" class="toolbar">
      <span class="mode-label"><span :class="modeCls(s, props)" data-testid="mode-badge" :title="modeTitle(s, props)">{{ modeText(s, props) }}</span></span>
      <span v-if="s.people.mapC(_.nonEmpty)" class="people" data-testid="presence" :title="peopleTitle(s)">
        <span v-for="p in s.people.mapC(_.take(5))" :key="p.name" class="avatar" :class='if (p.editing) "editing" else ""' :style='s"background: ${p.color}"'>
          <img v-if="p.avatar.isDefined" :src='p.avatar.getOrElse("")' alt=""><template v-else>{{ initialOf(p.name) }}</template><span v-if="p.editing" class="pen">✎</span>
        </span>
        <span v-if="s.people.mapC(_.size > 5)" class="avatar more">+{{ s.people.mapC(l => (l.size - 5).toString) }}</span>
      </span>
      <span v-if="s.watchState.mapC(_.isDefined)" class="menu-wrap">
        <button
          :class="onCls(s.watchState.v.exists(watchOn))"
          title="ほかの人の更新・コメントを通知で受け取る（受け取る内容を選べます）"
          data-testid="watch"
          @click="s.watchMenu := !s.watchMenu.v"
        >{{ if (s.watchState.v.exists(watchOn)) "🔔" else "🔕" }}<span class="lbl"> {{ s.watchState.v.fold("")(watchLabel) }}</span></button>
        <div v-if="s.watchMenu" class="pop watch-pop" data-testid="watch-menu" @mouseleave="s.watchMenu := false">
          <p class="wp-title">このページの通知</p>
          <label><input type="checkbox" :checked="s.watchState.v.exists(w => effWatch(w)._1)" data-testid="watch-edits" @change='setWatchKind(s, props, "edits", checked(event))'> 本文の更新</label>
          <label><input type="checkbox" :checked="s.watchState.v.exists(w => effWatch(w)._2)" data-testid="watch-comments" @change='setWatchKind(s, props, "comments", checked(event))'> コメント</label>
          <label><input type="checkbox" :checked="s.watchState.v.exists(w => w.watching && w.subtree)" :disabled="!s.watchState.v.exists(_.watching)" data-testid="watch-subtree" @change="setWatchOpts(s, props, subtree = Some(checked(event)))"> 子ページも含める</label>
          <p v-if="s.watchState.v.exists(w => w.inherited.isDefined && !w.watching)" class="wp-note" data-testid="watch-inherited">{{ s.watchState.v.fold("")(watchInheritedNote) }}</p>
          <p v-else-if="s.watchState.v.exists(_.inherited.isDefined)" class="wp-note">{{ s.watchState.v.fold("")(watchOverrideNote) }}</p>
          <button v-if="s.watchState.v.exists(_.watching)" class="wp-stop" data-testid="watch-stop" @click="stopWatch(s, props)">ウォッチをやめる</button>
          <p class="wp-note">通知は画面内とメールで届きます。</p>
        </div>
      </span>
      <button
        :class="onCls(Store.favoritePages.v.exists(_.page.id == props.pid))"
        :title='if (Store.favoritePages.v.exists(_.page.id == props.pid)) "お気に入りから外す" else "お気に入りに追加（サイドバーの上部に並びます）"'
        data-testid="page-favorite"
        @click="Store.toggleFavoritePage(props.pid)"
      >{{ if (Store.favoritePages.v.exists(_.page.id == props.pid)) "★" else "☆" }}<span class="lbl"> お気に入り</span></button>
      <button :class="onCls(s.commentsOpen.v)" title="コメント" data-testid="comments-toggle" @click="s.commentsOpen := !s.commentsOpen.v">💬<span v-if="openThreads(s).v > 0" class="badge">{{ openThreads(s) }}</span><span class="lbl"> コメント</span></button>
      <button title="版履歴" data-testid="history" @click="s.history := true">🕘<span class="lbl"> 履歴</span></button>
      <span class="menu-wrap">
        <button title="書き出し" data-testid="export" @click="s.exportMenu := !s.exportMenu.v">⤓<span class="lbl"> 書き出し</span></button>
        <div v-if="s.exportMenu" class="pop" role="menu" @mouseleave="s.exportMenu := false">
          <button role="menuitem" data-testid="export-md" @click="exportMarkdown(s, props)">
            <span class="pi">📝</span><span class="pt">Markdown</span><small>このページを .md で保存</small>
          </button>
          <button role="menuitem" data-testid="export-zip" @click="exportTree(s, props)">
            <span class="pi">🗜</span><span class="pt">ZIP</span><small>子ページ・添付も含めて保存</small>
          </button>
          <button role="menuitem" data-testid="export-pdf" @click="printPage(s)">
            <span class="pi">🖨</span><span class="pt">印刷 / PDF</span><small>このページをブラウザの印刷画面で</small>
          </button>
          <a role="menuitem" class="pop-a" :href='s"/print/w/${props.wid}/p/${props.pid}?children=1&toc=1"' target="_blank" rel="noopener" data-testid="export-pdf-tree" @click="s.exportMenu := false">
            <span class="pi">📚</span><span class="pt">PDF（子ページも含めて）</span><small>目次付きで1つにまとめる</small>
          </a>
          <button role="menuitem" data-testid="duplicate" @click="duplicate(s, props)">
            <span class="pi">⧉</span><span class="pt">複製</span><small>子ページ・添付も含めてコピーを作る（別のワークスペースへも）</small>
          </button>
        </div>
      </span>
      <button v-if="canEdit(s, props)" title="このページのスタイル（カスタムCSS）" data-testid="page-css" @click="s.cssOpen := true">🎨<span class="lbl"> スタイル</span></button>
      <button v-if="canEdit(s, props)" :class="onCls(pg(props).fullWidth.contains(true))" :title='if (pg(props).fullWidth.contains(true)) "標準の幅に戻す" else "全幅で表示する（このページを見るすべての人に）"' data-testid="full-width" @click="toggleFullWidth(props)">↔<span class="lbl">{{ if (pg(props).fullWidth.contains(true)) " 標準の幅" else " 全幅" }}</span></button>
      <button v-if="canEdit(s, props) || pg(props).isLocked" :class="onCls(pg(props).isLocked)" :title='if (pg(props).isLocked) "保護中（編集できる人が限られています）" else "ページを保護する（編集できる人を限る）"' data-testid="lock" @click="s.lockOpen := true">🔒<span class="lbl">{{ if (pg(props).isLocked) " 保護中" else " 保護" }}</span></button>
      <button v-if="canEdit(s, props)" title="共有設定" data-testid="share" @click="s.sharing := true">👥<span class="lbl"> 共有</span></button>
      <button v-if="canEdit(s, props) && !editing(s, props)" class="primary" title="編集する（E）" data-testid="edit" @click='setMode(s, "edit")'>✏️ 編集</button>
      <button v-if="editing(s, props)" class="primary" title="編集を終えて表示モードに戻る（変更は自動で保存されています）" data-testid="done" @click='setMode(s, "view")'>完了</button>
    </div>
    <nav v-if="crumbs(props).nonEmpty" class="crumbs" aria-label="ページの階層" data-testid="breadcrumbs">
      <a :href='s"/w/${props.wid}"' class="crumb ws" @click='go(event, s"/w/${props.wid}")'>{{ Store.current.map(_.name).getOrElse("") }}</a>
      <template v-for="c in crumbs(props)" :key="c.id">
        <span class="sep">›</span>
        <a :href="Nav.pageHref(props.wid, c.id)" class="crumb" @click="go(event, Nav.pageHref(props.wid, c.id))">{{ crumbIcon(c) }} {{ c.displayTitle }}</a>
      </template>
    </nav>
    <div class="head">
      <div class="icon-wrap">
        <button
          v-if="pg(props).icon.isDefined || editing(s, props)"
          class="page-icon"
          :class='if (pg(props).icon.isEmpty) "empty" else ""'
          :disabled="!editing(s, props)"
          :title='if (editing(s, props)) "アイコンを変更" else ""'
          data-testid="page-icon"
          @click="s.picking := !s.picking.v"
        >{{ pg(props).icon.getOrElse("＋ アイコンを追加") }}</button>
        <IconPicker v-if="s.picking" :onSelect="i => pickIcon(s, props, i)" :onClose="_ => s.picking := false" />
      </div>
      <input
        id="page-title"
        class="title"
        :value="s.title"
        placeholder="無題"
        :readonly="!editing(s, props)"
        @input="onTitleInput(s, props, event)"
      >
      <div v-if="pg(props).isLocked" class="lock-banner" data-testid="lock-banner">
        🔒 このページは保護されています{{ lockReason(s) }}
        <span class="muted">{{ lockNote(s) }}</span>
      </div>
      <div v-if="pg(props).status.isDefined || pg(props).ownerName.isDefined || editing(s, props)" class="meta-row" data-testid="page-meta">
        <button :class="statusCls(pg(props))" :disabled="!canEdit(s, props)" :title='if (canEdit(s, props)) "状態を変更" else ""' data-testid="page-status" @click="pickStatus(s, props)">
          📌 {{ pg(props).status.flatMap(Page.StatusLabel.get).getOrElse("状態を設定") }}
        </button>
        <button class="meta-chip" :disabled="!canEdit(s, props)" :title='if (canEdit(s, props)) "所有者を変更" else ""' data-testid="page-owner" @click="pickOwner(s, props)">
          👤 {{ pg(props).ownerName.fold("所有者を設定")(n => s"所有者: $n") }}
        </button>
        <button v-if="editing(s, props) && !pg(props).isDatabase" class="meta-chip" title="子ページを項目として、表・ボード・カレンダーで見られるようにする" data-testid="make-database" @click="makeDatabase(props)">
          📊 データベースにする
        </button>
      </div>
      <div v-if="pg(props).labelList.nonEmpty || editing(s, props)" class="labels" data-testid="labels">
        <span v-for="l in pg(props).labelList" :key="l" class="label">
          <button class="label-name" title="このラベルのページを一覧" @click="Store.showLabel(l)">🏷 {{ l }}</button>
          <button v-if="editing(s, props)" class="label-x" title="ラベルを外す" @click="removeLabel(props, l)">×</button>
        </span>
        <button v-if="editing(s, props)" class="label-add" data-testid="label-add" @click="addLabel(props)">＋ ラベル</button>
      </div>
      <RowProps v-if="inDatabase(props)" :pageId="props.pid" :wid="props.wid" />
    </div>
    <DatabaseView v-if="pg(props).isDatabase" :pageId="props.pid" :wid="props.wid" />
    <PageEditor
      :pageId="props.pid"
      :wid="props.wid"
      :editable="editingC(s, props)"
      :onHandle="h => s.handle := h"
      :onRole="r => onRole(s, r)"
      :onPresence="ps => s.people := ps"
      :onComment="d => onSelectionComment(s, d)"
      :onCommentClick="id => onCommentClick(s, id)"
    />
    <CommentsPanel
      v-if="s.commentsOpen"
      :pageId="props.pid"
      :comments="s.comments"
      :canEdit="canEditC(s, props)"
      :editor="() => s.handle.v"
      :draft="s.commentDraft"
      :focus="s.commentFocus"
      :onClose="_ => closeComments(s)"
      :onReload="_ => loadComments(s, props)"
      :onClearDraft="_ => s.commentDraft := None"
    />
    <section v-if="s.backlinks.mapC(_.nonEmpty)" class="backlinks" data-testid="backlinks">
      <h3>↩ このページへのリンク（{{ s.backlinks.mapC(_.size.toString) }}）</h3>
      <ul>
        <li v-for="b in s.backlinks" :key="b.id">
          <a :href="Nav.pageHref(b.workspaceId, b.id)" @click="go(event, Nav.pageHref(b.workspaceId, b.id))">{{ b.iconOr }} {{ b.displayTitle }}</a>
        </li>
      </ul>
    </section>
    <LockDialog v-if="s.lockOpen" :pageId="props.pid" :title="untracked(pg(props).title)" :ownerName="untracked(pg(props).ownerName)" :onClose="_ => s.lockOpen := false" :onChanged="_ => onLockChanged(s)" />
    <CssDialog v-if="s.cssOpen" kind="page" :targetId="props.pid" :title="untracked(pg(props).title)" :onPreview="c => s.cssPreview := c" :onClose="_ => onCssClosed(s)" />
    <ShareDialog v-if="s.sharing.v && canEdit(s, props)" kind="page" :targetId="props.pid" :title="untracked(pg(props).title)" :onClose="_ => onShareClosed(s)" />
    <HistoryPanel
      v-if="s.history.v && s.handle.v.isDefined"
      :pageId="props.pid"
      :canEdit="canEditC(s, props)"
      :getState="() => s.handle.v.map(_.getState()).getOrElse(Json.Null)"
      :applyContent="j => s.handle.v.foreach(_.applyContent(j))"
      :onClose="_ => s.history := false"
    />
  </article>
  <AccessRequestPanel v-else-if="s.denied" :pid="props.pid" />
  <p v-else class="muted" style="padding: 3rem">ページを読み込み中…</p>
</template>

<style scoped>
.page { max-width: 860px; margin: 0 auto; padding: 1.2rem 2.4rem 3rem; }
.toolbar { display: flex; justify-content: flex-end; align-items: center; gap: .4rem; margin-bottom: 1.2rem; }
.toolbar > * { flex: none; white-space: nowrap; }
.toolbar button { font-size: .82rem; border-color: transparent; color: var(--muted); white-space: nowrap; }
/* 幅が足りないときはアイコンだけ（それでも入らなければ折り返す） */
.toolbar.measuring { justify-content: flex-start; }
.toolbar.tight { gap: .1rem; }
.toolbar.tight button:not(.primary) { padding-left: 5px; padding-right: 5px; font-size: .78rem; }
.toolbar.compact { gap: .1rem; flex-wrap: wrap; }
.toolbar.compact .lbl { display: none; }
.toolbar.compact button:not(.primary) { padding: 2px 6px; font-size: .9rem; }
.toolbar button:hover { color: var(--fg); }
.toolbar button.on { color: var(--accent); }
.toolbar button.primary { color: #fff; background: var(--accent); border-color: var(--accent); padding: 2px 12px; }
.toolbar button.primary:hover { filter: brightness(1.1); }
.toolbar > .mode-label { flex: 1 0 auto; font-size: .78rem; }
.mode-badge { display: inline-block; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--line); white-space: nowrap; }
.mode-badge.view { color: var(--muted); background: var(--hover, transparent); }
.mode-badge.edit { color: var(--bg); background: var(--accent); border-color: var(--accent); font-weight: 600; }
.head { margin-bottom: 1rem; }
.crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: .15rem .35rem; font-size: .78rem; color: var(--muted); margin: -.6rem 0 .6rem; }
.crumb { color: var(--muted); text-decoration: none; max-width: 16rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 1px 4px; border-radius: 4px; }
.crumb:hover { color: var(--fg); background: var(--hover); }
.crumbs .sep { opacity: .6; }
.icon-wrap { position: relative; display: inline-block; }
.page-icon { font-size: 2.8rem; line-height: 1.2; border: none; padding: 0 .1em; border-radius: 8px; }
.page-icon:disabled { cursor: default; background: transparent; }
.page-icon.empty { font-size: .85rem; color: var(--muted); padding: 2px 6px; opacity: .7; }
.page-icon.empty:hover { opacity: 1; }
.title { width: 100%; border: none; outline: none; background: transparent; font-size: 2.2rem; font-weight: 700; display: block; }
.menu-wrap { position: relative; }
.badge { display: inline-block; min-width: 15px; height: 15px; margin-left: 2px; border-radius: 8px; background: var(--accent); color: #fff; font-size: 10px; line-height: 15px; text-align: center; vertical-align: 2px; }
.pop { position: absolute; right: 0; top: calc(100% + 4px); z-index: 20; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; width: max-content; max-width: calc(100vw - 24px); }
.toolbar .pop button { display: grid; grid-template-columns: 1.6em auto; grid-template-rows: auto auto; column-gap: .3rem; text-align: left; color: var(--fg); padding: .4rem .7rem .4rem .5rem; border-radius: 6px; white-space: nowrap; }
.toolbar .pop .pi { grid-row: 1 / 3; align-self: center; font-size: 1.05rem; }
.toolbar .pop .pt { font-weight: 600; font-size: .85rem; }
.toolbar .pop small { color: var(--muted); font-size: .72rem; }
.toolbar .pop button:hover, .toolbar .pop .pop-a:hover { background: var(--hover); }
.toolbar .pop .pop-a { display: grid; grid-template-columns: 1.6em auto; grid-template-rows: auto auto; column-gap: .3rem; text-align: left; color: var(--fg); padding: .4rem .7rem .4rem .5rem; border-radius: 6px; white-space: nowrap; text-decoration: none; }
/* ボタンの右側に開く（左に開くとサイドメニューに隠れる） */
.watch-pop { left: 0; right: auto; padding: .5rem .7rem; gap: .3rem; font-size: .84rem; width: 17rem; white-space: normal; }
.watch-pop label { display: flex; align-items: center; gap: .4rem; cursor: pointer; }
.wp-title { margin: 0 0 .1rem; font-weight: 600; font-size: .8rem; color: var(--muted); }
.wp-note { margin: .2rem 0 0; font-size: .72rem; color: var(--muted); line-height: 1.5; }
.toolbar .watch-pop .wp-stop { align-self: flex-start; border: 1px solid var(--line); color: var(--fg); padding: 1px 8px; margin-top: .2rem; }
.people { display: inline-flex; margin-right: .4rem; }
.avatar { position: relative; width: 24px; height: 24px; border-radius: 50%; color: #fff; font-size: .72rem; font-weight: 700; display: inline-grid; place-items: center; margin-left: -6px; border: 2px solid var(--bg); }
.avatar:first-child { margin-left: 0; }
.avatar img { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: block; }
.avatar.more { background: var(--hover); color: var(--muted); }
.avatar .pen { position: absolute; right: -4px; bottom: -4px; font-size: .6rem; background: var(--bg); color: var(--fg); border-radius: 50%; width: 12px; height: 12px; line-height: 12px; text-align: center; }
.lock-banner { margin-top: .5rem; font-size: .82rem; padding: .35rem .6rem; border-radius: 6px; background: rgba(234,179,8,.14); border: 1px solid rgba(234,179,8,.4); }
.lock-banner .muted { font-size: .75rem; }
.meta-row { display: flex; flex-wrap: wrap; gap: .35rem; margin-top: .4rem; }
.meta-chip { font-size: .78rem; border: 1px solid var(--line); border-radius: 6px; padding: 1px 8px; background: none; color: var(--muted); }
.meta-chip:disabled { cursor: default; opacity: 1; }
.meta-chip.st.draft { color: var(--muted); }
.meta-chip.st.review { color: #b45309; border-color: rgba(217,119,6,.5); }
.meta-chip.st.published { color: #16a34a; border-color: rgba(22,163,74,.5); }
.meta-chip.st.deprecated { color: var(--danger); border-color: rgba(220,38,38,.4); text-decoration: line-through; }
.labels { display: flex; flex-wrap: wrap; gap: .35rem; margin-top: .5rem; }
.label { display: inline-flex; align-items: center; border: 1px solid var(--line); border-radius: 999px; font-size: .78rem; overflow: hidden; }
.label button { border: none; background: none; padding: 1px 8px; font-size: inherit; color: var(--muted); }
.label .label-name:hover { color: var(--accent); }
.label .label-x { padding: 1px 6px 1px 0; }
.label-add { border: 1px dashed var(--line); border-radius: 999px; font-size: .78rem; color: var(--muted); padding: 1px 8px; background: none; }
.backlinks { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid var(--line); }
.backlinks h3 { font-size: .9rem; color: var(--muted); margin: 0 0 .4rem; }
.backlinks ul { list-style: none; padding: 0; margin: 0; display: flex; flex-wrap: wrap; gap: .3rem .8rem; }
.backlinks a { color: var(--accent); text-decoration: none; font-size: .9rem; }
.backlinks a:hover { text-decoration: underline; }
@media (max-width: 768px) {
  .page { padding: .8rem 1rem 3rem; }
  .toolbar { gap: .1rem; margin-bottom: .6rem; padding-left: 2.4rem; }
  .toolbar .lbl { display: none; }
  .toolbar button { padding: 2px 6px; font-size: .9rem; }
  .title { font-size: 1.6rem; }
  .page-icon { font-size: 2.2rem; }
  .pop { right: auto; left: 50%; transform: translateX(-50%); }
}
@media print {
  .toolbar, .backlinks, .label-add, .label-x { display: none !important; }
  .page { max-width: none; padding: 0; }
}
</style>
