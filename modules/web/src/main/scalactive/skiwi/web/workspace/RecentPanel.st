<script lang="scala">
import skiwi.web.parts.PageIcon
import skiwi.web.Nav
import skiwi.web.Ui

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 最近見たページ・自分が編集したページ（すべてのワークスペース）
case class Props(wid: String)

case class State(items: RV[Option[List[RecentItem]]] = RV(None))

def initState() = State()

/** visited / edited（このブラウザに保存） */
val kind: RV[String] = WsPrefs.stored("skiwi.recentKind", r => if (r.contains("edited")) "edited" else "visited")

def load(s: State): Unit = {
  s.items := None
  WorkspaceApi.recent(kind.v).map(Some(_)).recover { case _ => Some(Nil) }.foreach(s.items := _)
}

def mounted(s: State): Unit = {
  load(s)
  watch(kind)((_, _) => load(s))
}

def segClass(k: String): String = if (kind.v == k) "on" else ""
</script>

<template>
  <div class="recent" data-testid="recent">
    <div class="seg">
      <button :class='segClass("visited")' data-testid="recent-visited" @click='kind := "visited"'>最近見た</button>
      <button :class='segClass("edited")' data-testid="recent-edited" @click='kind := "edited"'>自分が編集した</button>
    </div>
    <p v-if="s.items.v.isEmpty" class="muted">読み込み中…</p>
    <p v-else-if="s.items.v.exists(_.isEmpty)" class="muted">まだありません。</p>
    <a v-for="r in s.items.v.getOrElse(Nil)" :key="r.page.id" :href="Nav.pageHref(r.page.workspaceId, r.page.id)"
       :class='Map("item" -> true, "router-link-active" -> WsRoute.isActive(Nav.pageHref(r.page.workspaceId, r.page.id)))'
       @click="Nav.link(event, Nav.pageHref(r.page.workspaceId, r.page.id))">
      <div class="t"><PageIcon :icon="r.page.icon" :database="r.page.isDatabase" /> {{ r.page.displayTitle }}</div>
      <div class="m">{{ Ui.date(r.at) }}<template v-if="r.page.workspaceId != props.wid"> ・ 🗂 {{ r.workspaceName }}</template></div>
    </a>
  </div>
</template>

<style scoped>
.recent { padding: 0 .3rem; }
.seg { display: flex; gap: 2px; margin: 0 .3rem .4rem; font-size: .75rem; }
.seg button { flex: 1; border: 1px solid var(--line); padding: 1px 4px; color: var(--muted); }
.seg button.on { background: var(--hover); color: var(--fg); border-color: var(--accent); }
.item { display: block; padding: .35rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.item:hover, .item.router-link-active { background: var(--hover); }
.t { font-size: .9rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.m { font-size: .72rem; color: var(--muted); }
.muted { font-size: .85rem; padding: 0 .5rem; }
</style>
