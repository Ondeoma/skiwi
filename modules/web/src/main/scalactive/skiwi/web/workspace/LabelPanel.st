<script lang="scala">
import skiwi.web.parts.PageIcon
import skiwi.web.Nav
import skiwi.web.Store
import skiwi.web.model.*

// このワークスペースのラベル一覧と、ラベルでの絞り込み
case class Props(wid: String)

final case class LabelCount(label: String, count: Int)

def all: List[LabelCount] = {
  val m = scala.collection.mutable.LinkedHashMap.empty[String, LabelCount]
  for (p <- Store.pages.v; l <- p.labelList) {
    val k = l.toLowerCase
    val e = m.getOrElse(k, LabelCount(l, 0))
    m(k) = e.copy(count = e.count + 1)
  }
  m.values.toList.sortWith((a, b) => if (a.count != b.count) a.count > b.count else WsText.jaLess(a.label, b.label))
}

def selected: Option[String] = Store.labelFilter.v

def pages: List[Page] = selected.map(_.toLowerCase).fold(List.empty[Page]) { k =>
  Store.pages.v.filter(_.labelList.exists(_.toLowerCase == k)).sortBy(_.updatedAt)(using Ordering[String].reverse)
}

def isOn(l: String): Boolean = selected.exists(_.toLowerCase == l.toLowerCase)

def pick(l: String): Unit = Store.labelFilter := (if (isOn(l)) None else Some(l))
</script>

<template>
  <div class="labels" data-testid="label-panel">
    <p v-if="all.isEmpty" class="muted">ラベルの付いたページはありません。ページの編集中にタイトルの下の「＋ ラベル」から付けられます。</p>
    <div class="chips">
      <button v-for="l in all" :key="l.label" :class='Map("chip" -> true, "on" -> isOn(l.label))' data-testid="label-chip" @click="pick(l.label)">🏷 {{ l.label }} <span class="n">{{ l.count }}</span></button>
    </div>
    <template v-if="selected.isDefined">
      <p class="head">「{{ selected.getOrElse("") }}」のページ（{{ pages.size }}）</p>
      <a v-for="p in pages" :key="p.id" :href="Nav.pageHref(props.wid, p.id)"
         :class='Map("item" -> true, "router-link-active" -> WsRoute.isActive(Nav.pageHref(props.wid, p.id)))'
         @click="Nav.link(event, Nav.pageHref(props.wid, p.id))"><PageIcon :icon="p.icon" :database="p.isDatabase" /> {{ p.displayTitle }}</a>
    </template>
  </div>
</template>

<style scoped>
.labels { padding: 0 .5rem; }
.chips { display: flex; flex-wrap: wrap; gap: .3rem; }
.chip { font-size: .78rem; border: 1px solid var(--line); border-radius: 999px; padding: 1px 8px; color: var(--muted); background: none; }
.chip.on { border-color: var(--accent); color: var(--fg); background: var(--hover); }
.n { font-size: .7rem; opacity: .7; }
.head { font-size: .8rem; font-weight: 600; color: var(--muted); margin: .8rem 0 .2rem; }
.item { display: block; padding: .3rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; }
.item:hover, .item.router-link-active { background: var(--hover); }
.muted { font-size: .8rem; }
</style>
