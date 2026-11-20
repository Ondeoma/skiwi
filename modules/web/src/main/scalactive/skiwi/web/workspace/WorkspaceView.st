<script lang="scala">
import skiwi.web.parts.{Combo, PageIcon}
import org.scalajs.dom
import skiwi.web.{Dialog, Nav, Opt, Prefs, Store, Ui}
import skiwi.web.dialogs.*
import skiwi.web.model.*
import skiwi.web.page.PageView

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ワークスペースの画面（ヘッダ・サイドメニュー・ページ）. URL（/w/:wid/p/:pid）は自分で読む.
// ページ（PageView）は pid をキーにして描き、クエリ文字列だけが変わったときは作り直さない.
case class State(wid: RV[String] = RV(""),
                 /** 開いているページ（(wid, pid). 0 か 1 件） */
                 page: RV[List[(String, String)]] = RV(Nil),
                 wsSel: RV[String] = RV(""),
                 // 検索
                 query: RV[String] = RV(""),
                 results: RV[Option[List[SearchResult]]] = RV(None),
                 wsHits: RV[List[WorkspaceHit]] = RV(Nil),
                 searching: RV[Boolean] = RV(false),
                 search: DebouncedFn[() => Unit] = useDebounce[() => Unit](300)(_()),
                 latest: LatestFn = useLatest(),
                 searchInput: ElementRef[dom.HTMLInputElement] = ElementRef(),
                 // 検索の絞り込み（ラベル・更新日・状態・自分が作成・自分が所有者）
                 filterOpen: RV[Boolean] = RV(false),
                 fLabel: RV[String] = RV(""),
                 fDays: RV[Int] = RV(0),
                 fStatus: RV[String] = RV(""),
                 fMine: RV[Boolean] = RV(false),
                 fOwnerMe: RV[Boolean] = RV(false),
                 // ワークスペースのメニューとダイアログ
                 wsMenu: RV[Boolean] = RV(false),
                 wsMenuShift: RV[Double] = RV(0),
                 wsMenuEl: ElementRef[dom.HTMLElement] = ElementRef(),
                 sharingWs: RV[Boolean] = RV(false),
                 trashOpen: RV[Boolean] = RV(false),
                 brokenOpen: RV[Boolean] = RV(false),
                 wsCssOpen: RV[Boolean] = RV(false),
                 webhooksOpen: RV[Boolean] = RV(false),
                 automationsOpen: RV[Boolean] = RV(false),
                 inboundOpen: RV[Boolean] = RV(false),
                 backupOpen: RV[Boolean] = RV(false),
                 wsGuestHtmlOpen: RV[Boolean] = RV(false))

def initState() = State()

val NewWs = "__new__"

/** 検索範囲: ws（このワークスペース）/ all（このブラウザに保存） */
val scope: RV[String] = Prefs.stored("skiwi.searchScope", r => if (r.contains("all")) "all" else "ws")

/** お気に入りの開閉（このブラウザに保存） */
val favOpen: RV[Boolean] = Prefs.stored("skiwi.favOpen", r => !r.contains("0"), (v: Boolean) => Some(if (v) "1" else "0"))

val shortcut = if (Ui.isMac) "⌘ + \\" else "Ctrl + \\"
val paletteKey = if (Ui.isMac) "⌘K" else "Ctrl+K"

def mounted(s: State): Unit = {
  // URL からワークスペース・ページを読む. ワークスペースが変わったら読み込み直す
  watch(Router.href, true)((_, h) => {
    val (w, p) = WsRoute.parse(h)
    w.foreach { wid =>
      if (wid != s.wid.v) {
        s.wid := wid
        s.wsSel := wid
        load(wid)
      }
      s.page := p.map(pid => (wid, pid)).toList
    }
  })
  // 狭い画面では、移動したらサイドメニューを閉じる
  watch(Router.href)((_, _) => if (SideMenu.narrow.v) SideMenu.open := false)
  watch(scope)((_, _) => runSearch(s))
  watch(computed((s.fDays.v, s.fMine.v, s.fStatus.v, s.fOwnerMe.v)))((_, _) => runSearch(s))
  useEventListener[dom.KeyboardEvent](dom.document, "keydown")(e => onKey(e))
  useEventListener[dom.Event](dom.window, WsCommand.Event)(e => onCommand(s, WsCommand.name(e)))
}

def unmounted(s: State): Unit = {
  s.search.cancel()
  s.latest.cancel()
}

def load(wid: String): Unit = {
  Store.clearSelection()
  val ready = if (Store.me.v.isEmpty) Store.init() else Future.successful(())
  ready.flatMap(_ => Store.select(wid)).recover { case e => dom.console.error(e.getMessage) }
}

def canEditWs: Boolean = Store.current.exists(_.role == "edit")

def newRoot(s: State): Unit =
  Dialog.guarded()(Store.createPage(None).map(p => Nav.go(s"/w/${s.wid.v}/p/${p.id}?edit=1")))

// --- 検索（このワークスペース / すべて）. ワークスペース名も検索する ---

def filterCount(s: State): Int =
  List(s.fLabel.v.trim.nonEmpty, s.fDays.v != 0, s.fMine.v, s.fStatus.v.nonEmpty, s.fOwnerMe.v).count(identity)

def labelOptions: List[String] = Store.pages.v.flatMap(_.labelList).distinct.sortWith(WsText.jaLess)

def onSearch(s: State): Unit = s.search(() => doSearch(s))

def runSearch(s: State): Unit = {
  s.search.cancel()
  doSearch(s)
}

/** 古い応答で新しい結果を上書きしない（s.latest） */
def doSearch(s: State): Unit = {
  val q = s.query.v.trim
  if (q.isEmpty && filterCount(s) == 0) {
    s.latest.cancel()
    s.results := None
    s.wsHits := Nil
  } else {
    s.searching := true
    val f = SearchFilter(label = Option(s.fLabel.v.trim).filter(_.nonEmpty), days = Option(s.fDays.v).filter(_ != 0), mine = s.fMine.v,
      status = Option(s.fStatus.v).filter(_.nonEmpty), ownerMe = s.fOwnerMe.v)
    val pagesF = WorkspaceApi.searchPages(q, Option.when(scope.v == "ws")(s.wid.v), f)
    val wssF = if (q.nonEmpty) WorkspaceApi.searchWorkspaces(q) else Future.successful(Nil)
    s.latest(pagesF.zip(wssF)).map { case (ps, ws) =>
      s.results := Some(ps)
      s.wsHits := ws
    }.recoverWith { case e => Dialog.alertError("検索できませんでした", e) }
      .andThen(_ => s.searching := false)
  }
}

def clearSearch(s: State): Unit = {
  s.query := ""
  s.search.cancel()
  s.latest.cancel()
  if (filterCount(s) > 0) runSearch(s)
  else {
    s.results := None
    s.wsHits := Nil
  }
}

def clearFilters(s: State): Unit = {
  s.fLabel := ""
  s.fDays := 0
  s.fMine := false
  s.fStatus := ""
  s.fOwnerMe := false
  runSearch(s)
}

def onSearchKey(s: State, e: dom.Event): Unit = if (e.asInstanceOf[dom.KeyboardEvent].key == "Escape") clearSearch(s)

def wsName(id: String): String = Store.workspaces.v.find(_.id == id).map(_.name).getOrElse("")

def openUrl(e: dom.Event, u: String): Unit = {
  e.preventDefault()
  e.stopPropagation()
  dom.window.open(u, "_blank", "noopener")
}

def exactCount(s: State): Int = s.results.v.fold(0)(_.count(!_.fuzzy))

def resultCountLabel(s: State): String = {
  val rs = s.results.v.getOrElse(Nil)
  if (s.searching.v) "検索中…"
  else s"${rs.size} 件" + (if (rs.size > exactCount(s)) s"（うち あいまい一致 ${rs.size - exactCount(s)} 件）" else "")
}

def onResultWs(s: State, e: dom.Event, id: String): Unit = {
  clearSearch(s)
  Nav.link(e, s"/w/$id")
}

// --- ワークスペースの切り替え・作成 ---

def onSelectWs(s: State, v: String): Unit =
  if (v == NewWs) {
    // 選択欄の表示を元に戻す
    s.wsSel := s.wid.v
    createWorkspaceFlow()
  } else if (v.nonEmpty && v != s.wid.v) Nav.go(s"/w/$v")

def createWorkspaceFlow(): Unit =
  Dialog.prompt("新しいワークスペースの名前", "", okLabel = "作成", placeholder = "例: 開発チーム").foreach(_.map(_.trim).filter(_.nonEmpty).foreach { name =>
    Store.createWorkspace(name).map(w => Nav.go(s"/w/${w.id}")).recover { case e => Dialog.alertError("作成できませんでした", e) }
  })

/** ワークスペースの選択肢（お気に入りが先頭. 最後に「新しいワークスペースを作成」） */
def wsOptions = Store.workspaces.mapC { ws =>
  val (fav, rest) = ws.partition(_.favorite)
  fav.map(w => Opt(w.id, w.name, "★", hint = "お気に入り")) ++ rest.map(w => Opt(w.id, w.name)) :+ Opt(NewWs, "新しいワークスペースを作成…", "＋")
}



def toggleFavorite(): Unit = Store.current.foreach { w =>
  Store.toggleFavorite(w.id).recover { case e => Dialog.alertError("お気に入りを変更できませんでした", e) }
}

// --- ワークスペースのメニュー（名前の変更・取り込み・ゴミ箱・削除） ---

/** メニューを開き、画面の端からはみ出す分だけ内側へずらす */
def toggleWsMenu(s: State): Unit = {
  s.wsMenu := !s.wsMenu.v
  if (s.wsMenu.v) {
    s.wsMenuShift := 0
    nextTick().foreach(_ => s.wsMenuEl.foreach { el =>
      val r = el.getBoundingClientRect()
      s.wsMenuShift := math.max(8 - r.left, math.min(0, dom.window.innerWidth - 8 - r.right))
    })
  }
}

def openDialog(s: State, flag: RV[Boolean]): Unit = {
  s.wsMenu := false
  flag := true
}

def renameWs(s: State): Unit = {
  s.wsMenu := false
  Store.current.foreach { cur =>
    Dialog.prompt("ワークスペースの名前", cur.name, okLabel = "変更").foreach(_.map(_.trim).filter(n => n.nonEmpty && n != cur.name).foreach { name =>
      Store.renameWorkspace(cur.id, name).recover { case e => Dialog.alertError("変更できませんでした", e) }
    })
  }
}

def deleteWs(s: State): Unit = {
  s.wsMenu := false
  Store.current.foreach { cur =>
    Dialog.confirm(s"ワークスペース「${cur.name}」をゴミ箱に移しますか？\n中のページも見られなくなります。ゴミ箱から元に戻せます。", okLabel = "ゴミ箱に移す", danger = true)
      .foreach { ok =>
        if (ok) WorkspaceApi.deleteWorkspace(cur.id).flatMap(_ => Store.reloadWorkspaces()).map(_ => Nav.go("/"))
          .recover { case e => Dialog.alertError("削除できませんでした", e) }
      }
  }
}

def importRoot(s: State): Unit = {
  s.wsMenu := false
  skiwi.web.content.Importer.importPage(s.wid.v, None)
}

// --- キー操作・⌘K のクイック切り替えからの操作 ---

def onKey(e: dom.KeyboardEvent): Unit = {
  // 選択中は Esc で解除（入力欄・ダイアログでの Esc は除く）
  if (e.key == "Escape" && Store.selected.v.nonEmpty) {
    val inField = e.target match {
      case el: dom.Element => el.closest("input, textarea, [role=dialog], .ProseMirror") != null
      case _ => false
    }
    if (!inField) Store.clearSelection()
  }
  // JISキーボードの「¥」「ろ」位置も受け付ける
  if ((e.metaKey || e.ctrlKey) && !e.altKey && (Set("Backslash", "IntlYen", "IntlRo").contains(e.code) || e.key == "\\")) {
    e.preventDefault()
    SideMenu.toggle()
  }
}

def onCommand(s: State, cmd: String): Unit = cmd match {
  case "new-page" => if (canEditWs) newRoot(s)
  case "trash" => s.trashOpen := true
  case "toggle-side" => SideMenu.toggle()
  case "new-workspace" => createWorkspaceFlow()
  case "search" =>
    SideMenu.open := true
    Store.sidePanel := "tree"
    s.filterOpen := true
    nextTick().foreach(_ => s.searchInput.foreach(_.focus()))
  case _ => ()
}

def sideClass: String = List("side") ++ Option.when(!SideMenu.open.v)("closed") ++ Option.when(SideMenu.narrow.v)("overlay") mkString " "

def tabClass(name: String): String = if (Store.sidePanel.v == name) "on" else ""

def scopeClass(v: String): String = if (scope.v == v) "on" else ""

def filterBtnClass(s: State): String = if (s.filterOpen.v || filterCount(s) > 0) "filter-btn on" else "filter-btn"

def favTitle: String = if (Store.current.exists(_.favorite)) "お気に入りから外す" else "お気に入りに登録（一覧の上に並びます）"

def pageHref(p: Page): String = Nav.pageHref(p.workspaceId, p.id)

def favItemClass(p: Page): String = if (WsRoute.isActive(pageHref(p))) "fav-item router-link-active" else "fav-item"
</script>

<template>
  <div class="shell">
    <AppHeader :side-open="SideMenu.open.v" :shortcut="shortcut" @open-side="SideMenu.open := true" />
    <div class="layout">
      <div v-if="SideMenu.narrow.v && SideMenu.open.v" class="side-backdrop" @click="SideMenu.open := false"></div>
      <aside :class="sideClass" :inert="!SideMenu.open.v" data-testid="side">
        <div class="side-head">
          <Combo class="ws-combo" :bind="Some(s.wsSel)" :live="Some(wsOptions)" :onChange="v => onSelectWs(s, v)" placeholder="ワークスペースを検索…" testid="ws-select" />
          <button class="side-close" :title='s"サイドメニューを閉じる（$shortcut）"' data-testid="side-close" @click="SideMenu.open := false">«</button>
        </div>
        <div class="side-tools">
          <button v-if="Store.current.isDefined" :class='if (Store.current.exists(_.favorite)) "fav on" else "fav"' :title="favTitle" data-testid="ws-favorite"
                  @click="toggleFavorite()">{{ if (Store.current.exists(_.favorite)) "★" else "☆" }}<span class="tl">お気に入り</span></button>
          <span class="ws-menu-wrap">
            <button class="icon-btn" title="ワークスペースの操作" data-testid="ws-menu" @click="toggleWsMenu(s)">⋯<span class="tl">操作</span></button>
            <div v-if="s.wsMenu.v" ref="s.wsMenuEl" class="ws-menu" role="menu" :style='s"transform: translateX(${s.wsMenuShift.v}px)"' @mouseleave="s.wsMenu := false">
              <button v-if="canEditWs" role="menuitem" data-testid="ws-share" @click="openDialog(s, s.sharingWs)">👥 共有設定</button>
              <button v-if="canEditWs" role="menuitem" data-testid="ws-rename" @click="renameWs(s)">✏️ 名前を変更</button>
              <button v-if="canEditWs" role="menuitem" data-testid="ws-import" @click="importRoot(s)">⤒ 取り込む<small>（Markdown・Word・Notion・Confluence など）</small></button>
              <button v-if="canEditWs" role="menuitem" data-testid="ws-guest-html" @click="openDialog(s, s.wsGuestHtmlOpen)">🌐 ゲスト向けの表示（本文の上下のHTML）</button>
              <button v-if="canEditWs" role="menuitem" data-testid="ws-css" @click="openDialog(s, s.wsCssOpen)">🎨 スタイル（カスタムCSS）</button>
              <button v-if="canEditWs" role="menuitem" data-testid="ws-automations" @click="openDialog(s, s.automationsOpen)">⚙ 自動化ルール</button>
              <button role="menuitem" data-testid="ws-inbound" @click="openDialog(s, s.inboundOpen)">📥 受信用の Webhook</button>
              <button v-if="canEditWs" role="menuitem" data-testid="ws-webhooks" @click="openDialog(s, s.webhooksOpen)">📣 外部への通知<small>（Slack・Teams・Webhook）</small></button>
              <button role="menuitem" data-testid="ws-trash" @click="openDialog(s, s.trashOpen)">🗑 ゴミ箱</button>
              <button v-if="Store.me.v.exists(_.isAdmin)" role="menuitem" data-testid="ws-backup" @click="openDialog(s, s.backupOpen)">💾 定期バックアップ<small>（管理者）</small></button>
              <button role="menuitem" data-testid="ws-broken" @click="openDialog(s, s.brokenOpen)">🩺 点検<small>（リンク切れ・孤立・古いページ）</small></button>
              <template v-if="canEditWs">
                <hr>
                <button role="menuitem" class="danger" data-testid="ws-delete" @click="deleteWs(s)">ワークスペースを削除…</button>
              </template>
            </div>
          </span>
          <NotificationBell />
          <button class="icon-btn" :title='s"クイック切り替え（$paletteKey）"' data-testid="open-palette" @click="WsCommand.openPalette()">⌘<span class="tl">{{ paletteKey }}</span></button>
        </div>
        <div class="search">
          <div class="search-row">
            <input ref="s.searchInput" v-model="s.query" type="search" placeholder="🔍 検索（ページ・ワークスペース）" data-testid="search" @input="onSearch(s)" @keydown="onSearchKey(s, event)">
            <button :class="filterBtnClass(s)" title="絞り込み（ラベル・更新日・作成者）" data-testid="filter-toggle" @click="s.filterOpen := !s.filterOpen.v">
              ⚙<span v-if="filterCount(s) > 0" class="fcount">{{ filterCount(s) }}</span>
            </button>
          </div>
          <div v-if="s.filterOpen.v" class="filters" data-testid="filters">
            <label>🏷 <input v-model="s.fLabel" list="label-options" placeholder="ラベル" data-testid="filter-label" @input="onSearch(s)"></label>
            <datalist id="label-options"><option v-for="l in labelOptions" :key="l" :value="l"></option></datalist>
            <label>🕘
              <select v-model="s.fDays" data-testid="filter-days">
                <option value="0">更新日: 指定なし</option>
                <option value="7">1週間以内に更新</option>
                <option value="30">1か月以内に更新</option>
                <option value="365">1年以内に更新</option>
              </select>
            </label>
            <label>📌
              <select v-model="s.fStatus" data-testid="filter-status">
                <option value="">状態: 指定なし</option>
                <option value="draft">下書き</option>
                <option value="review">レビュー中</option>
                <option value="published">公開済み</option>
                <option value="deprecated">廃止</option>
                <option value="none">状態なし</option>
              </select>
            </label>
            <label class="chk"><input v-model="s.fMine" type="checkbox" data-testid="filter-mine"> 自分が作成したページ</label>
            <label class="chk"><input v-model="s.fOwnerMe" type="checkbox" data-testid="filter-owner"> 自分が所有者のページ</label>
            <button v-if="filterCount(s) > 0" class="link" @click="clearFilters(s)">絞り込みを解除</button>
          </div>
          <div class="scope" role="radiogroup" aria-label="検索範囲">
            <button role="radio" :aria-checked='(scope.v == "ws").toString' :class='scopeClass("ws")' data-testid="scope-ws" @click='scope := "ws"'>このワークスペース</button>
            <button role="radio" :aria-checked='(scope.v == "all").toString' :class='scopeClass("all")' data-testid="scope-all" @click='scope := "all"'>すべて</button>
          </div>
        </div>
        <nav v-if="s.results.v.isDefined" class="results" data-testid="results">
          <template v-if="s.wsHits.v.nonEmpty">
            <p class="muted small head">ワークスペース</p>
            <a v-for="w in s.wsHits.v" :key="w.id" :href='s"/w/${w.id}"' class="result ws-hit" @click="onResultWs(s, event, w.id)">
              <div class="r-title">🗂 {{ w.name }} <span v-if="w.fuzzy" class="badge">あいまい</span></div>
            </a>
            <p class="muted small head">ページ</p>
          </template>
          <p class="muted small">{{ resultCountLabel(s) }}</p>
          <a v-for="r in s.results.v.getOrElse(Nil)" :key="r.page.id" :href="pageHref(r.page)" :class='if (r.fuzzy) "result fuzzy" else "result"'
             @click="Nav.link(event, pageHref(r.page))">
            <div class="r-title">
              <PageIcon :icon="r.page.icon" :database="r.page.isDatabase" /> {{ r.page.displayTitle }}
              <span v-if="r.page.status.isDefined" class="badge">{{ r.page.status.flatMap(Page.StatusLabel.get).getOrElse("") }}</span>
              <span v-if="r.fuzzy" class="badge" title="入力した語そのものではなく、表記の近い語で見つかりました">あいまい</span>
            </div>
            <div v-if="r.page.workspaceId != s.wid.v" class="r-ws">🗂 {{ wsName(r.page.workspaceId) }}</div>
            <div v-if="r.attachment.isDefined" class="r-att" data-testid="search-attachment" :title='s"添付ファイル「${r.attachment.map(_.filename).getOrElse("")}」の中身に見つかりました"'>
              📎 {{ r.attachment.map(_.filename).getOrElse("") }}
              <button type="button" class="r-open" title="添付ファイルを開く" @click='openUrl(event, r.attachment.map(_.url).getOrElse(""))'>開く</button>
            </div>
            <div v-if="r.snippet.isDefined" class="snippet" v-html='r.snippet.getOrElse("")'></div>
          </a>
        </nav>
        <template v-else>
          <div class="panels" role="tablist">
            <button role="tab" :class='tabClass("tree")' data-testid="panel-tree" @click='Store.sidePanel := "tree"'>📄 ページ</button>
            <button role="tab" :class='tabClass("recent")' data-testid="panel-recent" @click='Store.sidePanel := "recent"'>🕘 最近</button>
            <button role="tab" :class='tabClass("labels")' data-testid="panel-labels" @click='Store.sidePanel := "labels"'>🏷 ラベル</button>
            <button role="tab" :class='tabClass("tasks")' data-testid="panel-tasks" @click='Store.sidePanel := "tasks"'>✅ ToDo</button>
          </div>
          <nav v-if='Store.sidePanel.v == "tree"' class="tree">
            <section v-if="Store.favoritePages.v.nonEmpty" class="favs" data-testid="fav-pages">
              <button class="favs-head" @click="favOpen := !favOpen.v">{{ if (favOpen.v) "▾" else "▸" }} ★ お気に入り</button>
              <template v-if="favOpen.v">
                <a v-for="f in Store.favoritePages.v" :key="f.page.id" :href="pageHref(f.page)" :class="favItemClass(f.page)" @click="Nav.link(event, pageHref(f.page))">
                  <span><PageIcon :icon="f.page.icon" :database="f.page.isDatabase" /> {{ f.page.displayTitle }}</span>
                  <span v-if="f.page.workspaceId != s.wid.v" class="fav-ws">{{ f.workspaceName }}</span>
                </a>
              </template>
            </section>
            <PageTree :nodes="Store.treeOf(Store.pages.v)" :wid="s.wid.v" :depth="0" />
            <p v-if="Store.pages.v.isEmpty" class="muted">ページがありません</p>
          </nav>
          <nav v-else-if='Store.sidePanel.v == "recent"' class="tree"><RecentPanel :wid="s.wid.v" /></nav>
          <nav v-else-if='Store.sidePanel.v == "tasks"' class="tree"><TasksPanel :wid="s.wid.v" /></nav>
          <nav v-else class="tree"><LabelPanel :wid="s.wid.v" /></nav>
        </template>
        <BulkBar :wid="s.wid.v" />
        <div v-if="canEditWs" class="side-foot">
          <button class="new-page" data-testid="new-page" @click="newRoot(s)">＋ 新規ページ</button>
        </div>
        <template v-if="Store.current.isDefined">
          <GuestHtmlDialog v-if="s.wsGuestHtmlOpen.v" kind="workspace" :target-id="Store.current.get.id" :title="Store.current.get.name" @close="s.wsGuestHtmlOpen := false" />
          <CssDialog v-if="s.wsCssOpen.v" kind="workspace" :target-id="Store.current.get.id" :title="Store.current.get.name" @close="s.wsCssOpen := false" />
          <BackupDialog v-if="s.backupOpen.v" :wid="s.wid.v" :name="Store.current.get.name" @close="s.backupOpen := false" />
          <InboundDialog v-if="s.inboundOpen.v" :wid="s.wid.v" :name="Store.current.get.name" @close="s.inboundOpen := false" />
          <AutomationDialog v-if="s.automationsOpen.v" :wid="s.wid.v" :name="Store.current.get.name" @close="s.automationsOpen := false" />
          <WebhookDialog v-if="s.webhooksOpen.v" :wid="s.wid.v" :name="Store.current.get.name" @close="s.webhooksOpen := false" />
          <ShareDialog v-if="s.sharingWs.v" kind="workspace" :target-id="Store.current.get.id" :title="Store.current.get.name" @close="s.sharingWs := false" />
        </template>
        <BrokenLinksDialog v-if="s.brokenOpen.v" :wid="s.wid.v" @close="s.brokenOpen := false" />
        <TrashDialog v-if="s.trashOpen.v" :wid="Some(s.wid.v)" @close="s.trashOpen := false" />
        <template v-for="p in Store.duplicateTarget.v.toList" :key="p.id">
          <DuplicateDialog :page="p" @close="Store.duplicateTarget := None" />
        </template>
      </aside>
      <section class="main">
        <template v-for="k in s.page.v" :key="k._2">
          <PageView :wid="k._1" :pid="k._2" />
        </template>
        <p v-if="s.page.v.isEmpty" class="muted" style="padding: 3rem">左のツリーからページを選択してください。</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.shell { display: flex; flex-direction: column; height: 100%; }
.layout { display: flex; flex: 1; min-height: 0; position: relative; overflow: hidden; }
.side { width: 290px; flex: none; transition: margin-left .18s ease; background: var(--side); border-right: 1px solid var(--line); display: flex; flex-direction: column; }
.side-head { padding: .6rem .6rem .3rem; display: flex; gap: .3rem; align-items: center; }
.side-tools { display: flex; align-items: center; gap: 2px; padding: 0 .5rem .5rem; flex-wrap: wrap; }
.side-tools > button, .side-tools .ws-menu-wrap > button { border: none; border-radius: 6px; padding: 2px 7px; color: var(--muted); font-size: .9rem; display: inline-flex; align-items: center; gap: 3px; }
.side-tools > button:hover, .side-tools .ws-menu-wrap > button:hover { background: var(--hover); color: var(--fg); }
.tl { font-size: .72rem; }
.side.closed { margin-left: -290px; }
.fav { color: var(--muted); }
.fav.on { color: #eab308; }
.side-close { border: none; color: var(--muted); padding: 0 6px; font-size: 1rem; }
.side-head .ws-combo { flex: 1; min-width: 0; font-weight: 600; }
.tree { flex: 1; overflow: auto; padding: 0 .3rem; }
.side-foot { padding: .6rem; border-top: 1px solid var(--line); display: flex; flex-direction: column; gap: .45rem; }
.new-page { width: 100%; }
.main { flex: 1; overflow: auto; }
.search { padding: 0 .6rem .5rem; }
.search-row input { width: 100%; padding: .3rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
.results { flex: 1; overflow: auto; padding: 0 .6rem; }
.result { display: block; padding: .4rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.result:hover { background: var(--hover); }
.r-title { font-weight: 600; font-size: .92em; }
.scope { display: flex; gap: 2px; margin-top: .35rem; font-size: .75rem; }
.scope button { flex: 1; border: 1px solid var(--line); padding: 1px 4px; color: var(--muted); }
.scope button:first-child { border-radius: 6px 0 0 6px; }
.scope button:last-child { border-radius: 0 6px 6px 0; }
.scope button.on { background: var(--hover); color: var(--fg); border-color: var(--accent); }
.head { margin-top: .5rem; font-weight: 600; }
.badge { font-size: .68rem; font-weight: 400; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; margin-left: 4px; vertical-align: 1px; }
.result.fuzzy .r-title { font-weight: 500; }
.r-ws { font-size: .75em; color: var(--muted); }
.r-att { font-size: .78em; color: var(--muted); display: flex; gap: .5rem; align-items: center; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.r-open { color: var(--accent); flex: none; border: none; background: none; padding: 0; font-size: inherit; cursor: pointer; }
.snippet { font-size: .8em; color: var(--muted); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.small { font-size: .8em; margin: .2rem 0; }
.ws-menu-wrap { position: relative; display: flex; }
.icon-btn { border: none; color: var(--muted); padding: 0 5px; }
.ws-menu { position: absolute; top: calc(100% + 4px); left: 0; z-index: 70; width: max-content; min-width: 220px; max-width: calc(100vw - 24px); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 10px 28px rgba(0,0,0,.2); display: flex; flex-direction: column; }
.ws-menu button { text-align: left; border: none; padding: .35rem .6rem; border-radius: 6px; font-size: .88rem; white-space: nowrap; }
.ws-menu button:hover { background: var(--hover); }
.ws-menu hr { border: none; border-top: 1px solid var(--line); margin: 4px 0; width: 100%; }
.ws-menu .danger:hover { color: var(--danger); }
.ws-menu small { color: var(--muted); font-size: .75em; }
.panels { display: flex; gap: 2px; padding: 0 .6rem .4rem; font-size: .75rem; }
.panels button { flex: 1; white-space: nowrap; border: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 2px 0; color: var(--muted); }
.panels button.on { color: var(--fg); border-bottom-color: var(--accent); }
.search-row { display: flex; gap: 4px; }
.search-row input { flex: 1; min-width: 0; }
.filter-btn { border: 1px solid var(--line); border-radius: 6px; padding: 0 7px; color: var(--muted); position: relative; }
.filter-btn.on { color: var(--accent); border-color: var(--accent); }
.fcount { position: absolute; top: -5px; right: -5px; min-width: 14px; height: 14px; border-radius: 7px; background: var(--accent); color: #fff; font-size: 9px; line-height: 14px; text-align: center; }
.filters { display: flex; flex-direction: column; gap: .3rem; margin-top: .4rem; padding: .4rem .5rem; border: 1px solid var(--line); border-radius: 8px; font-size: .8rem; }
.filters label { display: flex; align-items: center; gap: .3rem; }
.filters input:not([type=checkbox]), .filters select { flex: 1; min-width: 0; font: inherit; padding: 2px 6px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; }
.filters .link { align-self: flex-start; border: none; color: var(--accent); padding: 0; font-size: .78rem; }
.favs { margin: 0 0 .4rem; padding-bottom: .3rem; border-bottom: 1px solid var(--line); }
.favs-head { border: none; font-size: .78rem; color: var(--muted); padding: 2px 4px; }
.fav-item { display: flex; justify-content: space-between; gap: .4rem; padding: .25rem .5rem .25rem 1.2rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; }
.fav-item span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fav-item:hover, .fav-item.router-link-active { background: var(--hover); }
.fav-ws { font-size: .7rem; color: var(--muted); white-space: nowrap; }
.side.overlay { position: absolute; top: 0; bottom: 0; left: 0; z-index: 60; width: min(300px, 86vw); box-shadow: 8px 0 24px rgba(0,0,0,.25); }
.side.overlay.closed { margin-left: max(-300px, -86vw); box-shadow: none; }
.side-backdrop { position: absolute; inset: 0; z-index: 55; background: rgba(0,0,0,.35); }
@media (max-width: 768px) {
  .side-foot { font-size: .9rem; }
}
@media print { .side { display: none !important; } .main { overflow: visible; } .shell, .layout { height: auto; overflow: visible; } }
</style>
