<script lang="scala">
import org.scalajs.dom
import skiwi.web.*
import skiwi.web.model.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// ワークスペースの点検: リンク切れ・孤立ページ（本文からリンクされていない）・長く更新されていないページ. 見えるページだけが対象.
// initialTab: broken / orphans / stale
case class Props(wid: String, onClose: Unit => Unit, initialTab: String = "broken")

case class State(tab: RV[String],
                 items: RV[Option[List[BrokenLink]]] = RV(None),
                 health: RV[Option[List[PageHealth]]] = RV(None),
                 error: RV[String] = RV(""),
                 includeTop: RV[Boolean] = RV(false),
                 months: RV[Int] = RV(6))

def initState(props: Props) = State(RV(props.initialTab))

def mounted(s: State, props: Props): Unit =
  DialogsApi.brokenLinks(props.wid).zip(DialogsApi.health(props.wid)).onComplete {
    case Success((b, h)) =>
      s.items := Some(b)
      s.health := Some(h)
    case Failure(e) => s.error := e.getMessage
  }

val Reason: Map[String, String] = Map("missing" -> "存在しないページ", "deleted" -> "ゴミ箱にあるページ", "no_access" -> "閲覧できないページ")

/** リンク元のページごと（最初に出た順） */
def bySource(s: State): List[(String, String, List[BrokenLink])] = {
  val xs = s.items.v.getOrElse(Nil)
  xs.map(_.sourceId).distinct.map { id =>
    val ls = xs.filter(_.sourceId == id)
    (id, ls.head.sourceTitle, ls)
  }
}

// --- 孤立ページ（ほかのページの本文からリンクされていない）. 最上位・子ページのあるページは、ツリーからたどりやすいので既定では除く ---
def orphans(s: State): List[PageHealth] =
  s.health.v.getOrElse(Nil)
    .filter(h => h.incoming == 0 && (s.includeTop.v || (h.page.parentId.isDefined && h.children == 0)))
    .sortBy(_.lastEditedAt)

// --- 長く更新されていないページ（古い順） ---
def stale(s: State): List[PageHealth] = {
  val cutoff = new js.Date()
  cutoff.setMonth(cutoff.getMonth() - s.months.v)
  s.health.v.getOrElse(Nil)
    .filter(h => new js.Date(h.lastEditedAt).getTime() < cutoff.getTime() && !h.page.status.contains("deprecated"))
    .sortBy(_.lastEditedAt)
}

def ago(iso: String): String = {
  val d = math.floor((js.Date.now() - new js.Date(iso).getTime()) / 86400000).toInt
  if (d >= 365) s"${d / 365}年以上前" else if (d >= 30) s"${d / 30}か月前" else s"${d}日前"
}

/** 祖先のタイトル（どこにあるページかを示す） */
def pathOf(s: State, h: PageHealth): String = {
  val byId = s.health.v.getOrElse(Nil).map(x => x.page.id -> x.page).toMap
  var out = List.empty[String]
  var cur = h.page.parentId.flatMap(byId.get)
  while (cur.isDefined && out.size < 4) {
    out = Dlg.untitled(cur.get.title) :: out
    cur = cur.get.parentId.flatMap(byId.get)
  }
  out.mkString(" › ")
}

/** ページを開く（ダイアログは閉じる） */
def open(p: Props, e: dom.Event, pageId: String): Unit = {
  val m = e.asInstanceOf[dom.MouseEvent]
  if (!(m.ctrlKey || m.metaKey || m.shiftKey || m.button != 0)) {
    e.preventDefault()
    p.onClose(())
    Nav.go(Nav.pageHref(p.wid, pageId))
  }
}

def tabClass(s: State, t: String): String = if (s.tab.v == t) "on" else ""

def when(h: PageHealth): String = s"${ago(h.lastEditedAt)}${h.lastEditor.fold("")(n => s"（$n）")}"

def owner(h: PageHealth): String = h.page.ownerName.fold("所有者なし")(n => s"所有者: $n")
</script>

<template>
  <Modal label="ワークスペースの点検" title="🩺 ワークスペースの点検" testid="broken-links" :width="680" :max-height="75" :top="10" @close="props.onClose(())">
    <template #head><HelpLink to="manage#ワークスペースの点検" /></template>
    <div class="tabs" role="tablist">
      <button role="tab" :class='tabClass(s, "broken")' data-testid="health-tab-broken" @click='s.tab := "broken"'>🔗 リンク切れ<span v-if="s.items.v.isDefined" class="n">{{ s.items.v.fold(0)(_.size) }}</span></button>
      <button role="tab" :class='tabClass(s, "orphans")' data-testid="health-tab-orphans" @click='s.tab := "orphans"'>🏝 孤立ページ<span v-if="s.health.v.isDefined" class="n">{{ orphans(s).size }}</span></button>
      <button role="tab" :class='tabClass(s, "stale")' data-testid="health-tab-stale" @click='s.tab := "stale"'>🕸 更新されていない<span v-if="s.health.v.isDefined" class="n">{{ stale(s).size }}</span></button>
    </div>
    <p v-if="s.error.v.nonEmpty" class="err">{{ s.error }}</p>
    <p v-else-if="s.items.v.isEmpty" class="muted">確認中…</p>

    <template v-else-if='s.tab.v == "broken"'>
      <p class="note">このワークスペースのページにある、ページへのリンク・同期ブロックの埋め込みのうち、先がなくなっているものです（本文の保存時点の内容で判定）。</p>
      <p v-if="s.items.v.exists(_.isEmpty)" class="muted ok">リンク切れはありません。</p>
      <ul v-else class="list">
        <li v-for="x in bySource(s)" :key="x._1">
          <a :href="Nav.pageHref(props.wid, x._1)" class="src" @click="open(props, event, x._1)">📄 {{ Dlg.untitled(x._2) }}</a>
          <ul>
            <li v-for="b in x._3" :key="b.targetId" :class="b.reason">
              <span class="reason">{{ Reason.getOrElse(b.reason, b.reason) }}</span>
              <span>{{ b.targetTitle.fold("")(t => s"「$t」") }}</span>
              <span v-if='b.reason == "deleted" && b.targetTitle.exists(_.nonEmpty)' class="muted">（ゴミ箱から戻せます）</span>
            </li>
          </ul>
        </li>
      </ul>
    </template>

    <template v-else-if='s.tab.v == "orphans"'>
      <p class="note">ほかのページの本文から一度もリンクされていないページです。ツリーをたどらないと見つからないため、関連するページからリンクするか、不要なら整理を検討してください。</p>
      <label class="opt"><input v-model="s.includeTop" type="checkbox" data-testid="health-include-top"> 最上位のページ・子ページのあるページも含める</label>
      <p v-if="orphans(s).isEmpty" class="muted ok">孤立しているページはありません。</p>
      <ul v-else class="rows" data-testid="health-orphans">
        <li v-for="h in orphans(s)" :key="h.page.id" data-testid="health-row">
          <a :href="Nav.pageHref(props.wid, h.page.id)" class="src" @click="open(props, event, h.page.id)">{{ h.page.iconOr }} {{ h.page.displayTitle }}</a>
          <span class="where">{{ pathOf(s, h) }}</span>
          <span class="when">{{ ago(h.lastEditedAt) }}に更新</span>
        </li>
      </ul>
    </template>

    <template v-else>
      <p class="note">本文・タイトルが長く変わっていないページです（「廃止」のページは除きます）。内容が今も正しいか、所有者に確認してもらいましょう。</p>
      <label class="opt">
        最終更新が
        <select v-model="s.months" data-testid="health-months">
          <option value="3">3か月</option>
          <option value="6">6か月</option>
          <option value="12">1年</option>
          <option value="24">2年</option>
        </select>
        より前
      </label>
      <p v-if="stale(s).isEmpty" class="muted ok">該当するページはありません。</p>
      <ul v-else class="rows" data-testid="health-stale">
        <li v-for="h in stale(s)" :key="h.page.id" data-testid="health-row">
          <a :href="Nav.pageHref(props.wid, h.page.id)" class="src" @click="open(props, event, h.page.id)">{{ h.page.iconOr }} {{ h.page.displayTitle }}</a>
          <span v-if="h.page.status.isDefined" class="badge">{{ h.page.status.fold("")(st => Page.StatusLabel.getOrElse(st, st)) }}</span>
          <span class="where">{{ pathOf(s, h) }}</span>
          <span class="when" :title="Ui.date(h.lastEditedAt)">{{ when(h) }}</span>
          <span class="owner">{{ owner(h) }}</span>
        </li>
      </ul>
    </template>
  </Modal>
</template>

<style scoped>
.tabs { display: flex; gap: 2px; margin: .6rem 0 .2rem; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.tabs button { border: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 4px 10px; color: var(--muted); font-size: .85rem; }
.tabs button.on { color: var(--fg); border-bottom-color: var(--accent); }
.n { margin-left: 4px; font-size: .72rem; background: var(--hover); border-radius: 8px; padding: 0 6px; }
.opt { display: flex; align-items: center; gap: .35rem; font-size: .82rem; margin-bottom: .4rem; }
.opt select { font: inherit; padding: 1px 4px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; }
.list { list-style: none; padding: 0; margin: 0; }
.list > li { padding: .4rem 0; border-bottom: 1px solid var(--line); }
.src { font-weight: 600; color: inherit; text-decoration: none; }
.src:hover { color: var(--accent); }
.list ul { list-style: none; margin: .2rem 0 0 1.2rem; padding: 0; font-size: .85rem; }
.reason { font-size: .72rem; border-radius: 4px; padding: 0 6px; margin-right: .3rem; background: var(--hover); }
li.missing .reason { color: var(--danger); }
li.deleted .reason { color: #d97706; }
.rows { list-style: none; padding: 0; margin: 0; }
.rows li { display: flex; flex-wrap: wrap; align-items: baseline; gap: .2rem .6rem; padding: .4rem 0; border-bottom: 1px solid var(--line); font-size: .85rem; }
.rows .src { font-size: .9rem; }
.where { color: var(--muted); font-size: .75rem; flex: 1; min-width: 6rem; }
.when, .owner { color: var(--muted); font-size: .75rem; white-space: nowrap; }
.badge { font-size: .68rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; }
.ok { padding: 1rem 0; }
</style>
