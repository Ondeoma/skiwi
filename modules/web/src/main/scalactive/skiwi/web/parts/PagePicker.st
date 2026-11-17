<script lang="scala">
import org.scalajs.dom
import skiwi.web.{Debounced, Host, Store}
import skiwi.web.model.*
import skiwi.web.workspace.WorkspaceApi

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// リンクするページを選ぶ（Host.pickPage で開く）. 空欄のときは開いているワークスペースのページ、入力するとすべてのワークスペースから検索する.
case class State(q: RV[String] = RV(""),
                 selected: RV[Int] = RV(0),
                 input: ElementRef[dom.HTMLInputElement] = ElementRef(),
                 found: RV[List[Page]] = RV(Nil),
                 loading: RV[Boolean] = RV(false),
                 search: Debounced = new Debounced(200))

def initState() = State()

def wsName(id: String): String = Store.workspaces.v.find(_.id == id).map(_.name).getOrElse("")

def items(s: State): List[Page] =
  if (s.q.v.trim.nonEmpty) s.found.v
  // 最近更新した順
  else Store.pages.v.sortBy(_.updatedAt)(using Ordering[String].reverse).take(50)

def mounted(s: State): Unit = {
  watch(Host.pagePicker)((_, r) => if (r.isDefined) {
    s.q := ""
    s.found := Nil
    s.selected := 0
    nextTick().foreach(_ => s.input.foreach(_.focus()))
  })
  watch(s.q)((_, v) => {
    s.selected := 0
    val query = v.trim
    if (query.isEmpty) {
      s.search.cancel()
      s.found := Nil
    } else {
      s.loading := true
      s.search.run { latest =>
        WorkspaceApi.searchPages(query).map(r => if (latest()) s.found := r.map(_.page))
          .onComplete(_ => if (latest()) s.loading := false)
      }
    }
  })
}

def unmounted(s: State): Unit = s.search.cancel()

def pick(s: State, i: Int): Unit = items(s).lift(i).foreach(p => Host.pagePicker.v.foreach(_.trySuccess(Some(p))))

def close(): Unit = Host.pagePicker.v.foreach(_.trySuccess(None))

def onKey(s: State, e: dom.Event): Unit = {
  val k = e.asInstanceOf[dom.KeyboardEvent]
  if (!e.asInstanceOf[js.Dynamic].isComposing.asInstanceOf[Boolean]) {
    val n = items(s).size
    if (k.key == "ArrowDown" && n > 0) { e.preventDefault(); s.selected := (s.selected.v + 1) % n }
    else if (k.key == "ArrowUp" && n > 0) { e.preventDefault(); s.selected := (s.selected.v - 1 + n) % n }
    else if (k.key == "Enter") { e.preventDefault(); pick(s, s.selected.v) }
    else if (k.key == "Escape") { e.preventDefault(); close() }
  }
}

def notFound(s: State): Boolean = s.q.v.trim.nonEmpty && !s.loading.v && items(s).isEmpty
</script>

<template>
  <div v-if="Host.pagePicker.v.isDefined" class="backdrop" @mousedown.self="close()" @keydown="onKey(s, event)">
    <div class="modal" role="dialog" aria-modal="true" aria-label="ページへのリンク" data-testid="page-picker">
      <header>
        <h2>ページへのリンク</h2>
        <input ref="s.input" v-model="s.q" type="search" placeholder="ページ名で検索（すべてのワークスペース）" data-testid="page-picker-search">
      </header>
      <ul class="list">
        <li v-if="s.q.v.trim.isEmpty" class="hint">このワークスペースの最近更新したページ</li>
        <li v-for="(it, i) in items(s)" :key="it.id" :class='Map("active" -> (i == s.selected.v))' @mouseenter="s.selected := i" @click="pick(s, i)">
          <span class="icon">{{ it.iconOr }}</span>
          <span class="title">{{ it.displayTitle }}</span>
          <span v-if="!Store.currentId.v.contains(it.workspaceId)" class="ws">🗂 {{ wsName(it.workspaceId) }}</span>
        </li>
        <li v-if="notFound(s)" class="hint">「{{ s.q.v }}」に一致するページはありません</li>
      </ul>
      <footer>↑↓ で選択 ・ Enter でリンクを挿入 ・ Esc で閉じる ・ ページのURLを貼り付けてもリンクになります</footer>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-start; justify-content: center; z-index: 900; padding: 12vh 16px 16px; }
.modal { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(600px, 100%); max-height: 70vh; display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.3); overflow: hidden; }
header { display: flex; align-items: center; gap: .8rem; padding: .8rem 1rem; border-bottom: 1px solid var(--line); }
h2 { font-size: 1rem; margin: 0; white-space: nowrap; }
header input { flex: 1; min-width: 0; padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); }
.list { list-style: none; margin: 0; padding: 4px; overflow: auto; flex: 1; }
.list li { display: flex; align-items: center; gap: .5rem; padding: .4rem .6rem; border-radius: 6px; cursor: pointer; }
.list li.active { background: var(--hover); }
.list li.hint { cursor: default; color: var(--muted); font-size: .8rem; }
.icon { width: 1.4em; text-align: center; }
.title { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ws { color: var(--muted); font-size: .78rem; white-space: nowrap; }
footer { padding: .5rem 1rem; border-top: 1px solid var(--line); font-size: .78rem; color: var(--muted); }
</style>
