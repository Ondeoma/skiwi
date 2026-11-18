<script lang="scala">
import skiwi.shared.Titles
import skiwi.web.parts.PageIcon
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Choice, Dialog, Nav, Store}
import skiwi.web.model.*
import skiwi.web.parts.IconPicker

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ページツリー（再帰）. nodes: この階層のページ / depth: 字下げの深さ
// 開いているメニュー・アイコン選択・ドラッグ・名前の変更・開閉は、ツリー全体（再帰した各階層）で共有する（同時に1つだけ）.
case class Props(nodes: Reactive[List[TreeNode]], wid: String, depth: Int)

case class State(ls: Listeners = new Listeners)

def initState() = State()

val menuFor: RV[Option[String]] = RV(None)
val iconFor: RV[Option[String]] = RV(None)
/** 画面座標（ツリーのスクロール領域で切れないよう fixed で出す） */
val popAt: RV[Option[(Double, Double)]] = RV(None)
/** ドラッグ中のページと、落とす位置（before / inside / after） */
val dragging: RV[Option[String]] = RV(None)
val dropAt: RV[Option[(String, String)]] = RV(None)
/** 範囲選択の起点 */
val anchor: RV[Option[String]] = RV(None)
/** 閉じている（子を隠している）ページ */
val collapsed: RV[Set[String]] = RV(Set.empty)
val renaming: RV[Option[String]] = RV(None)
val draft: RV[String] = RV("")

def mounted(s: State, props: Props): Unit =
  // メニューの外をクリック・Esc で閉じる（いちばん上の階層だけが受け持つ）
  if (props.depth == 0) {
    s.ls.on[dom.MouseEvent](dom.document, "mousedown") { e =>
      val inside = e.target match {
        case el: dom.Element => el.closest(".tree-menu, [data-testid=tree-menu]") != null
        case _ => false
      }
      if (menuFor.v.isDefined && !inside) menuFor := None
    }
    s.ls.on[dom.KeyboardEvent](dom.document, "keydown")(e => if (e.key == "Escape") menuFor := None)
  }

def unmounted(s: State): Unit = s.ls.clear()

def toggle(id: String): Unit = collapsed := (if (collapsed.v(id)) collapsed.v - id else collapsed.v + id)

def guard(f: => Future[Any]): Future[Unit] = Dialog.guarded()(f)

def addChild(parentId: String, wid: String): Unit = {
  menuFor := None
  guard {
    Store.createPage(Some(parentId)).map { p =>
      collapsed := collapsed.v - parentId
      Nav.go(s"/w/$wid/p/${p.id}?edit=1")
    }
  }
}

/** 開いているページが id か、その子孫か */
def isOpenWithin(id: String): Boolean = WsRoute.currentPid.exists(pid => pid == id || Store.ancestors(pid).exists(_.id == id))

def del(id: String, title: String, wid: String): Unit = {
  menuFor := None
  // このページへのリンクがあれば知らせる（リンクは「アクセスできないページ」表示になる）
  skiwi.web.page.PageApi.backlinks(id).recover { case _ => Nil }.foreach { refs =>
    val warn =
      if (refs.isEmpty) ""
      else s"\n\n⚠ このページは他の ${refs.size} ページからリンクされています（${refs.take(3).map(r => s"「${r.displayTitle}」").mkString}${if (refs.size > 3) " など" else ""}）。"
    Dialog.confirm(s"「${Titles.display(title)}」とその子ページをゴミ箱に移しますか？\nゴミ箱から元に戻せます。$warn", okLabel = "ゴミ箱に移す", danger = true)
      .foreach { ok =>
        if (ok) guard {
          val wasOpen = isOpenWithin(id)
          Store.remove(id).map(_ => if (wasOpen) Nav.go(s"/w/$wid"))
        }
      }
  }
}

def startRename(id: String, title: String): Unit = {
  menuFor := None
  renaming := Some(id)
  draft := title
  nextTick().foreach { _ =>
    Option(dom.document.querySelector("[data-testid=tree-rename]")).foreach { el =>
      val i = el.asInstanceOf[dom.HTMLInputElement]
      i.focus()
      i.select()
    }
  }
}

def commitRename(): Unit = renaming.v.foreach { id =>
  renaming := None
  val t = draft.v.trim
  Store.byId(id).foreach(cur => if (t != cur.title) guard(Store.rename(id, t)))
}

def onRenameKey(e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean]) {
    if (k.key == "Enter") { e.preventDefault(); commitRename() }
    if (k.key == "Escape") { e.preventDefault(); renaming := None }
  }
}

def openIcon(id: String): Unit = {
  menuFor := None
  iconFor := Some(id)
}

def pickIcon(id: String, icon: Option[String]): Unit = {
  iconFor := None
  guard(Store.setIcon(id, icon))
}

val MenuW = 260
val MenuH = 320

def openMenuAt(id: String, x: Double, y: Double): Unit = {
  iconFor := None
  popAt := Some((math.max(4, math.min(x, dom.window.innerWidth - MenuW - 4)), math.max(4, math.min(y, dom.window.innerHeight - MenuH - 4))))
  menuFor := Some(id)
}

def toggleMenu(e: dom.Event, id: String): Unit =
  if (menuFor.v.contains(id)) menuFor := None
  else {
    val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect()
    openMenuAt(id, r.right - 180, r.bottom + 2)
  }

/** 右クリック: 編集できるページなら独自メニュー（それ以外はブラウザ標準のメニュー） */
def onContext(e: dom.Event, p: Page): Unit =
  if (p.canEdit && !renaming.v.contains(p.id)) {
    e.preventDefault()
    val m = e.asInstanceOf[dom.MouseEvent]
    openMenuAt(p.id, m.clientX, m.clientY)
  }

// --- 別のワークスペースへ移動（子ページごと. 移動先のワークスペース直下の末尾に置く） ---
def moveToWorkspace(p: Page): Unit = {
  menuFor := None
  val targets = Store.workspaces.v.filter(w => w.role == "edit" && w.id != p.workspaceId)
  if (targets.isEmpty) Dialog.alert("移動できるワークスペース（編集権限のあるもの）がありません")
  else Dialog.choose(s"「${p.displayTitle}」と子ページを、どのワークスペースへ移しますか？\n移動先の権限が適用されます（このページに個別に付けた共有設定はそのまま残ります）。",
    targets.map(w => Choice(s"🗂 ${w.name}", w.id))).foreach(_.foreach { to =>
    guard {
      val wasOpen = isOpenWithin(p.id)
      val pid = WsRoute.currentPid
      WorkspaceApi.movePage(p.id, Json.obj("workspaceId" -> to.asJson, "placement" -> "last".asJson))
        .flatMap(_ => Store.reloadPages())
        .map(_ => if (wasOpen) pid.foreach(id => Nav.go(s"/w/$to/p/$id")))
    }
  })
}

def importChild(wid: String, parentId: String): Unit = {
  menuFor := None
  skiwi.web.content.Importer.importPage(wid, Some(parentId))
}

def duplicate(p: Page): Unit = {
  menuFor := None
  Store.duplicateTarget := Some(p)
}

// --- ドラッグ＆ドロップで並べ替え・移動 ---
/** candidate が ancestor 自身か、その子孫か */
def isWithin(candidate: String, ancestor: String): Boolean = Store.ancestors(candidate, includeSelf = true).exists(_.id == ancestor)

def onDragStart(e: dom.Event, id: String): Unit = {
  e.stopPropagation()
  dragging := Some(id)
  menuFor := None
  Option(e.asInstanceOf[dom.DragEvent].dataTransfer).foreach { dt =>
    dt.setData("application/x-skiwi-page", id)
    dt.effectAllowed = dom.DataTransferEffectAllowedKind.move
  }
}

def onDragOver(e: dom.Event, target: Page): Unit = {
  e.stopPropagation()
  dragging.v match {
    case Some(id) if !isWithin(target.id, id) =>
      val r = e.currentTarget.asInstanceOf[dom.HTMLElement].getBoundingClientRect()
      val y = (e.asInstanceOf[dom.MouseEvent].clientY - r.top) / r.height
      // 編集できないページの中には入れない
      val where = if (y < 0.28) "before" else if (y > 0.72) "after" else if (target.canEdit) "inside" else if (y < 0.5) "before" else "after"
      e.preventDefault()
      Option(e.asInstanceOf[dom.DragEvent].dataTransfer).foreach(_.dropEffect = dom.DataTransferDropEffectKind.move)
      dropAt := Some((target.id, where))
    case _ => dropAt := None
  }
}

def onDragLeave(e: dom.Event, id: String): Unit =
  if (dropAt.v.exists(_._1 == id)) {
    val related = e.asInstanceOf[dom.MouseEvent].relatedTarget
    val inside = related != null && e.currentTarget.asInstanceOf[dom.Node].contains(related.asInstanceOf[dom.Node])
    if (!inside) dropAt := None
  }

def onDragEnd(): Unit = {
  dragging := None
  dropAt := None
}

def comparePositionThenId(a: Page, b: Page): Boolean = if (a.position != b.position) a.position < b.position else a.id < b.id

def onDrop(e: dom.Event, target: Page): Unit = {
  e.stopPropagation()
  e.preventDefault()
  val id = dragging.v
  val at = dropAt.v
  onDragEnd()
  (id, at) match {
    case (Some(id), Some((tid, where))) if tid == target.id && !isWithin(target.id, id) =>
      val body = where match {
        case "inside" =>
          collapsed := collapsed.v - target.id
          Json.obj("parentId" -> target.id.asJson, "placement" -> "last".asJson)
        case "after" => Json.obj("parentId" -> target.parentId.asJson, "afterId" -> target.id.asJson)
        case _ =>
          val sibs = Store.pages.v.filter(p => p.parentId == target.parentId && p.workspaceId == target.workspaceId && p.id != id)
            .sortWith(comparePositionThenId)
          val i = sibs.indexWhere(_.id == target.id)
          sibs.lift(i - 1) match {
            case Some(prev) if i > 0 => Json.obj("parentId" -> target.parentId.asJson, "afterId" -> prev.id.asJson)
            case _ => Json.obj("parentId" -> target.parentId.asJson, "placement" -> "first".asJson)
          }
      }
      guard(WorkspaceApi.movePage(id, body).flatMap(_ => Store.reloadPages()))
    case _ => ()
  }
}

// --- 複数選択（⌘/Ctrl＋クリックで追加・解除、Shift＋クリックで範囲） ---
def onTitleClick(e: dom.Event, id: String, wid: String): Unit = {
  val m = e.asInstanceOf[dom.MouseEvent]
  if (m.metaKey || m.ctrlKey) {
    e.preventDefault()
    Store.toggleSelected(id)
    anchor := Some(id)
  } else if (m.shiftKey) {
    e.preventDefault()
    // 画面に出ている行の並びで、最後に選んだ行（なければ開いているページ）からこの行まで
    val ids = dom.document.querySelectorAll("[data-testid=tree-row][data-id]").toList.map(_.asInstanceOf[dom.HTMLElement].getAttribute("data-id"))
    val start = anchor.v.orElse(Store.selected.v.lastOption).orElse(WsRoute.currentPid).getOrElse("")
    val from = ids.indexOf(start)
    val to = ids.indexOf(id)
    if (from < 0) {
      Store.toggleSelected(id)
      anchor := Some(id)
    } else {
      val range = ids.slice(math.min(from, to), math.max(from, to) + 1)
      Store.selected := Store.selected.v ++ range.filterNot(Store.selected.v.contains)
    }
  } else Nav.link(e, Nav.pageHref(wid, id)) // 普通のクリックはページを開く（選択はそのまま）
}

def onTitleDblClick(e: dom.Event, p: Page): Unit = {
  e.preventDefault()
  if (p.canEdit) startRename(p.id, p.title)
}

def startSelect(id: String): Unit = {
  menuFor := None
  if (!Store.selected.v.contains(id)) Store.toggleSelected(id)
  anchor := Some(id)
}

def onSelectChange(id: String): Unit = {
  Store.toggleSelected(id)
  anchor := Some(id)
}

def rowClass(p: Page): List[String] = {
  val id = p.id
  List("row") ++
    Option.when(WsRoute.currentPid.contains(id))("active") ++
    Option.when(menuFor.v.contains(id) || iconFor.v.contains(id))("pinned") ++
    Option.when(dragging.v.contains(id))("dragging") ++
    Option.when(Store.selected.v.contains(id))("selected") ++
    dropAt.v.filter(_._1 == id).map(d => s"drop-${d._2}")
}

def rowStyle(depth: Int): String = s"padding-left: ${depth * 14 + 4}px"

def draggable(p: Page): String = if (p.canEdit && !renaming.v.contains(p.id)) "true" else "false"

def menuStyle: String = popAt.v.fold("")((x, y) => s"left: ${x}px; top: ${y}px")

</script>

<template>
  <ul class="list">
    <li v-for="n in props.nodes.v" :key="n.page.id">
      <div :class="rowClass(n.page)" :style="rowStyle(props.depth)" :draggable="draggable(n.page)" :data-id="n.page.id" data-testid="tree-row"
           @contextmenu="onContext(event, n.page)" @dragstart="onDragStart(event, n.page.id)" @dragover="onDragOver(event, n.page)"
           @dragleave="onDragLeave(event, n.page.id)" @drop="onDrop(event, n.page)" @dragend="onDragEnd()">
        <input v-if="Store.selected.v.nonEmpty" type="checkbox" class="sel" :checked="Store.selected.v.contains(n.page.id)"
               :aria-label='s"「${n.page.displayTitle}」を選択"' data-testid="tree-select" @change="onSelectChange(n.page.id)">
        <button class="twisty" :style='if (n.children.nonEmpty) "visibility: visible" else "visibility: hidden"' @click="toggle(n.page.id)">{{ if (collapsed.v(n.page.id)) "▸" else "▾" }}</button>
        <template v-if="renaming.v.contains(n.page.id)">
          <span class="icon"><PageIcon :icon="n.page.icon" :database="n.page.isDatabase" /></span>
          <input v-model="draft" class="rename" placeholder="無題" data-testid="tree-rename" @keydown="onRenameKey(event)" @blur="commitRename()">
        </template>
        <a v-else :href="Nav.pageHref(props.wid, n.page.id)" draggable="false" class="title"
           @click="onTitleClick(event, n.page.id, props.wid)" @dblclick="onTitleDblClick(event, n.page)">
          <span class="icon"><PageIcon :icon="n.page.icon" :database="n.page.isDatabase" /></span>{{ n.page.displayTitle }}
          <span v-if='n.page.status.exists(_ != "published")' :class='"st " + n.page.status.getOrElse("")' data-testid="tree-status">{{ n.page.status.flatMap(Page.StatusLabel.get).getOrElse("") }}</span>
          <span v-if="n.page.isLocked" class="pub" title="保護中" data-testid="tree-locked">🔒</span>
          <span v-if="n.page.isPublished" class="pub" title="ゲストに公開中" data-testid="tree-published">🌐</span>
        </a>
        <span v-if="n.page.canEdit && !renaming.v.contains(n.page.id)" class="actions">
          <button title="子ページを追加" data-testid="tree-add" @click="addChild(n.page.id, props.wid)">＋</button>
          <button title="その他の操作（右クリックでも開けます）" data-testid="tree-menu" @click="toggleMenu(event, n.page.id)">⋯</button>
        </span>
        <div v-if="menuFor.v.contains(n.page.id)" class="menu tree-menu" role="menu" :style="menuStyle" @contextmenu="event.preventDefault()">
          <button role="menuitem" @click="startRename(n.page.id, n.page.title)">✏️ 名前を変更</button>
          <button role="menuitem" @click="openIcon(n.page.id)">😀 アイコンを変更</button>
          <button role="menuitem" @click="addChild(n.page.id, props.wid)">＋ 子ページを追加</button>
          <button role="menuitem" data-testid="tree-duplicate" @click="duplicate(n.page)">⧉ 複製…</button>
          <button role="menuitem" data-testid="tree-move-ws" @click="moveToWorkspace(n.page)">🗂 別のワークスペースへ移動…</button>
          <button role="menuitem" data-testid="tree-select-start" @click="startSelect(n.page.id)">☑ 選択（まとめて操作）</button>
          <button role="menuitem" @click="importChild(props.wid, n.page.id)">⤒ 子ページとして取り込む<span class="sub">Markdown・Word・Notion・Confluence など</span></button>
          <hr>
          <button role="menuitem" class="danger" data-testid="tree-delete" @click="del(n.page.id, n.page.title, props.wid)">🗑 削除</button>
        </div>
        <IconPicker v-if="iconFor.v.contains(n.page.id)" :at="popAt.v" @select="pickIcon(n.page.id, event)" @close="iconFor := None" />
      </div>
      <PageTree v-if="n.children.nonEmpty && !collapsed.v(n.page.id)" :nodes="n.children" :wid="props.wid" :depth="props.depth + 1" />
    </li>
  </ul>
</template>

<style scoped>
.list { list-style: none; margin: 0; padding: 0; }
.row { display: flex; align-items: center; border-radius: 6px; min-height: 30px; position: relative; }
.row:hover, .row.active, .row.pinned { background: var(--hover); }
.row.dragging { opacity: .4; }
.row.selected { background: color-mix(in srgb, var(--accent) 16%, transparent); }
.sel { margin: 0 0 0 2px; flex: none; accent-color: var(--accent); }
.row.drop-before { box-shadow: inset 0 2px 0 var(--accent); }
.row.drop-after { box-shadow: inset 0 -2px 0 var(--accent); }
.row.drop-inside { background: var(--hover); outline: 2px solid var(--accent); outline-offset: -2px; }
.twisty { border: none; padding: 0 4px; width: 20px; color: var(--muted); }
.title { flex: 1; color: inherit; text-decoration: none; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.icon { margin-right: .35em; }
.st { margin-left: .35em; font-size: .65em; padding: 0 4px; border-radius: 3px; background: var(--hover); color: var(--muted); vertical-align: 1px; }
.st.review { color: #b45309; }
.st.deprecated { text-decoration: line-through; }
.pub { margin-left: .3em; font-size: .75em; opacity: .7; }
.rename { flex: 1; min-width: 0; padding: 1px 4px; border: 1px solid var(--accent); border-radius: 4px; background: var(--bg); margin-right: 4px; }
.actions { display: none; gap: 2px; padding-right: 4px; }
.row:hover .actions, .row.pinned .actions { display: flex; }
.actions button { border: none; padding: 0 4px; }
.menu { position: fixed; z-index: 40; min-width: 180px; max-width: min(260px, calc(100vw - 8px)); background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; }
.menu button { text-align: left; padding: 4px 8px; white-space: normal; overflow-wrap: anywhere; }
.menu button .sub { display: block; font-size: .78em; color: var(--muted); padding-left: 1.4em; }
.menu hr { border: none; border-top: 1px solid var(--line); margin: 3px 0; width: 100%; }
.menu .danger:hover { color: var(--danger); }
</style>
