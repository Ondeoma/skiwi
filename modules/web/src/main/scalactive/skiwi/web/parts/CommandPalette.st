<script lang="scala">
import org.scalajs.dom
import skiwi.web.{Nav, Store, Ui}
import skiwi.web.model.*
import skiwi.web.workspace.{Listeners, RecentItem, WorkspaceApi, WsCommand, WsRoute}
import skiwi.web.Debounced

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ⌘K / Ctrl+K のクイック切り替え. ページ・ワークスペースへの移動と、よく使う操作をキーボードだけで行う.
// サイドバー側の操作は window の 'skiwi:command' イベントで頼む（WorkspaceView・PageView などが受け取る）.
final case class Item(key: String, group: String, icon: String, title: String, sub: Option[String], run: () => Unit, kw: String = "")

case class State(open: RV[Boolean] = RV(false),
                 q: RV[String] = RV(""),
                 selected: RV[Int] = RV(0),
                 input: ElementRef[dom.HTMLInputElement] = ElementRef(),
                 list: ElementRef[dom.HTMLElement] = ElementRef(),
                 recent: RV[List[RecentItem]] = RV(Nil),
                 found: RV[List[Item]] = RV(Nil),
                 loading: RV[Boolean] = RV(false),
                 search: Debounced = new Debounced(150),
                 ls: Listeners = new Listeners)

def initState() = State()

def go(path: String): Unit = Nav.go(path)

def pageItem(p: Page, group: String, wsName: Option[String]): Item =
  Item(s"$group:${p.id}", group, p.icon.getOrElse("📄"), p.displayTitle,
    if (Store.currentId.v.contains(p.workspaceId)) None else wsName, () => go(s"/w/${p.workspaceId}/p/${p.id}"))

def commands(s: State): List[Item] = {
  val onPage = WsRoute.currentPid.isDefined
  val canEditWs = Store.current.exists(_.role == "edit")
  def c(key: String, icon: String, title: String, kw: String, sub: Option[String] = None)(run: => Unit) = Item(key, "操作", icon, title, sub, () => run, kw)
  val all =
    (if (canEditWs) List(c("c:new", "＋", "新しいページを作成", "new page create あたらしい ぺーじ さくせい")(WsCommand.send("new-page"))) else Nil) ++
      (if (onPage) List(
        c("c:edit", "✏️", "このページを編集", "edit へんしゅう")(WsCommand.send("edit-page")),
        c("c:comments", "💬", "コメントを開く", "comment こめんと")(WsCommand.send("comments")),
      ) else Nil) ++
      List(
        c("c:search", "🔍", "検索（絞り込み）", "search filter けんさく しぼりこみ")(WsCommand.send("search")),
        c("c:notif", "🔔", "通知を開く", "notification つうち")(WsCommand.send("notifications")),
        c("c:side", "☰", "サイドメニューを開閉", "sidebar menu さいど めにゅー")(WsCommand.send("toggle-side")),
        c("c:trash", "🗑", "ゴミ箱を開く", "trash restore ごみばこ ふっかつ")(WsCommand.send("trash")),
        c("c:newws", "🗂", "ワークスペースを作成", "workspace create わーくすぺーす さくせい")(WsCommand.send("new-workspace")),
        c("c:help", "📖", "使い方（ユーザーマニュアル）", "help manual guide つかいかた まにゅある へるぷ")(go("/help")),
        c("c:keys", "⌨️", "キーボードショートカット一覧", "shortcut keyboard key しょーとかっと きー", Some("?"))(WsCommand.openShortcuts()),
        c("c:apidocs", "📘", "API の説明書", "api openapi docs せつめいしょ")(go("/settings/api")),
        c("c:tokens", "🔑", "APIトークン", "api token とーくん")(go("/settings/tokens")),
      ) ++
      (if (Store.me.v.exists(_.isAdmin)) List(
        c("c:audit", "📜", "監査ログ", "audit log かんさ")(go("/admin/audit")),
        c("c:shares", "🌐", "公開中のページ", "public share こうかい")(go("/admin/shares")),
      ) else Nil)
  val t = s.q.v.trim.toLowerCase
  if (t.isEmpty) all else all.filter(x => x.title.toLowerCase.contains(t) || x.kw.contains(t))
}

def items(s: State): List[Item] =
  if (s.q.v.trim.isEmpty) {
    val favs = Store.favoritePages.v.map(f => pageItem(f.page, "★ お気に入り", Some(f.workspaceName)))
    val favIds = Store.favoritePages.v.map(_.page.id).toSet
    val rec = s.recent.v.filterNot(r => favIds.contains(r.page.id)).take(8).map(r => pageItem(r.page, "🕘 最近見たページ", Some(r.workspaceName)))
    favs ++ rec ++ commands(s)
  } else s.found.v ++ commands(s)

/** 見出し（group）ごとにまとめる. 各項目は全体での位置と組にする */
def groups(s: State): List[(String, List[(Item, Int)])] =
  items(s).zipWithIndex.foldLeft(List.empty[(String, List[(Item, Int)])]) {
    case ((g, xs) :: rest, (it, i)) if g == it.group => (g, xs :+ (it, i)) :: rest
    case (acc, (it, i)) => (it.group, List((it, i))) :: acc
  }.reverse

def mounted(s: State): Unit = {
  watch(s.q)((_, v) => onQuery(s, v))
  s.ls.on[dom.KeyboardEvent](dom.document, "keydown", capture = true) { e =>
    if ((if (Ui.isMac) e.metaKey else e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase == "k") {
      // ゲスト表示（ログインなし）では使わない
      if (!dom.window.location.pathname.startsWith("/s/")) {
        e.preventDefault()
        e.stopPropagation()
        if (s.open.v) close(s) else show(s)
      }
    }
  }
  s.ls.on[dom.Event](dom.window, "skiwi:palette")(_ => show(s))
}

def unmounted(s: State): Unit = {
  s.ls.clear()
  s.search.cancel()
}

def onQuery(s: State, v: String): Unit = {
  s.selected := 0
  val query = v.trim
  if (query.isEmpty) {
    s.search.cancel()
    s.found := Nil
    s.loading := false
  } else {
    s.loading := true
    s.search.run { latest =>
      WorkspaceApi.searchPages(query).zip(WorkspaceApi.searchWorkspaces(query)).map { case (pages, wss) =>
        if (latest()) {
          def wsName(id: String) = Store.workspaces.v.find(_.id == id).map(_.name)
          s.found := pages.take(12).map(r => pageItem(r.page, "ページ", wsName(r.page.workspaceId))) ++
            wss.take(5).map(w => Item(s"w:${w.id}", "ワークスペース", "🗂", w.name, None, () => go(s"/w/${w.id}")))
        }
      }.recover { case _ => if (latest()) s.found := Nil }.foreach(_ => if (latest()) s.loading := false)
    }
  }
}

def show(s: State): Unit = {
  s.open := true
  s.q := ""
  s.found := Nil
  s.selected := 0
  nextTick().foreach(_ => s.input.foreach(_.focus()))
  WorkspaceApi.recent("visited").foreach(s.recent := _)
}

def close(s: State): Unit = s.open := false

def pick(s: State, i: Int): Unit = items(s).lift(i).foreach { it =>
  close(s)
  it.run()
}

def move(s: State, d: Int): Unit = {
  val n = items(s).size
  if (n > 0) {
    s.selected := (s.selected.v + d + n) % n
    nextTick().foreach(_ => s.list.foreach(l => Option(l.querySelector(".active")).foreach(_.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest")))))
  }
}

def onInputKey(s: State, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean]) k.key match {
    case "ArrowDown" => e.preventDefault(); move(s, 1)
    case "ArrowUp" => e.preventDefault(); move(s, -1)
    case "Enter" => e.preventDefault(); pick(s, s.selected.v)
    case "Escape" => e.preventDefault(); close(s)
    case _ => ()
  }
}

def empty(s: State): Boolean = s.q.v.trim.nonEmpty && !s.loading.v && items(s).isEmpty
</script>

<template>
  <div v-if="s.open.v" class="backdrop" @mousedown.self="close(s)">
    <div class="palette" role="dialog" aria-modal="true" aria-label="クイック切り替え" data-testid="palette">
      <div class="head">
        <span class="k">🔍</span>
        <input ref="s.input" v-model="s.q" placeholder="ページ・ワークスペースへ移動、操作を実行…" data-testid="palette-input" @keydown="onInputKey(s, event)">
        <kbd>Esc</kbd>
      </div>
      <div ref="s.list" class="list">
        <template v-for="g in groups(s)" :key="g._1">
          <div class="group">{{ g._1 }}</div>
          <div v-for="it in g._2" :key="it._1.key" :class='Map("item" -> true, "active" -> (it._2 == s.selected.v))' data-testid="palette-item"
               @mouseenter="s.selected := it._2" @click="pick(s, it._2)">
            <span class="icon">{{ it._1.icon }}</span>
            <span class="title">{{ it._1.title }}</span>
            <span v-if="it._1.sub.isDefined" class="sub">🗂 {{ it._1.sub.getOrElse("") }}</span>
          </div>
        </template>
        <p v-if="empty(s)" class="empty">「{{ s.q.v }}」に一致するものはありません</p>
        <p v-if="s.loading.v" class="empty">検索中…</p>
      </div>
      <div class="foot">↑↓ で選択 ・ Enter で開く ・ {{ if (Ui.isMac) "⌘" else "Ctrl" }}+K でいつでも開閉</div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; z-index: 980; background: rgba(0,0,0,.35); display: flex; justify-content: center; align-items: flex-start; padding: 12vh 16px 16px; }
.palette { width: min(640px, 100%); max-height: 70vh; display: flex; flex-direction: column; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; box-shadow: 0 20px 60px rgba(0,0,0,.35); overflow: hidden; }
.head { display: flex; align-items: center; gap: .5rem; padding: .7rem .9rem; border-bottom: 1px solid var(--line); }
.head input { flex: 1; border: none; outline: none; background: transparent; font-size: 1rem; color: inherit; }
kbd { font-size: .7rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; }
.list { overflow: auto; padding: 4px; }
.group { font-size: .72rem; color: var(--muted); padding: .5rem .6rem .2rem; }
.item { display: flex; align-items: center; gap: .55rem; padding: .4rem .6rem; border-radius: 7px; cursor: pointer; }
.item.active { background: var(--hover); }
.icon { width: 1.4em; text-align: center; flex: none; }
.title { flex: 1; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.sub { font-size: .75rem; color: var(--muted); white-space: nowrap; }
.empty { color: var(--muted); font-size: .85rem; padding: .8rem; text-align: center; }
.foot { font-size: .72rem; color: var(--muted); padding: .45rem .9rem; border-top: 1px solid var(--line); }
</style>
