<script lang="scala">
import skiwi.web.Nav
import org.scalajs.dom

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ユーザーマニュアル（/help、/help/章のID#節）。左に章と節の目次、上に検索
case class Props(chapter: Option[String])

case class State(docs: RV[Option[HelpDocs]] = RV(None),
                 q: RV[String] = RV(Nav.query("q").getOrElse("")),
                 hits: RV[List[HelpHit]] = RV(Nil),
                 sideOpen: RV[Boolean] = RV(false),
                 body: ElementRef[dom.HTMLElement] = ElementRef())

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  pageTitle(s.docs.mapC(d => current(d, props).fold("使い方 - skiwi")(c => s"${c.title} - 使い方 - skiwi")))
  HelpDocs.load().foreach { d =>
    s.docs := Some(d)
    s.hits := d.search(s.q.v)
    scrollToHash(s)
  }
  // 検索語を URL に残す（戻るで検索結果に戻れる）。画面は作り直さない
  watch(s.q, false) { (_, v) =>
    s.docs.v.foreach(d => s.hits := d.search(v))
    val u = new dom.URL(dom.window.location.href)
    if (Option(u.searchParams.get("q")).getOrElse("") != v.trim) {
      if (v.trim.nonEmpty) u.searchParams.set("q", v.trim) else u.searchParams.delete("q")
      Nav.replaceQuiet(u.pathname + u.search + u.hash)
    }
  }
  // 同じ章の中の移動（# の変化）では画面が作り直されないので、ここで見出しへ移動する。章を移ったら検索を閉じる
  watch(Router.href, false) { (_, _) =>
    s.sideOpen := false
    if (Nav.query("q").isEmpty && s.q.v.nonEmpty) s.q := ""
    scrollToHash(s)
  }
}

def current(d: Option[HelpDocs], props: Props): Option[HelpChapter] =
  d.flatMap(_.chapters.find(c => props.chapter.contains(c.id)))

def chapters(s: State): List[HelpChapter] = s.docs.v.fold(Nil)(_.chapters)

def cur(s: State, props: Props): Option[HelpChapter] = current(s.docs.v, props)

def curC(s: State, props: Props): HelpChapter = cur(s, props).getOrElse(HelpChapter.empty)

def index(s: State, props: Props): Int = cur(s, props).fold(-1)(c => chapters(s).indexOf(c))

def prev(s: State, props: Props): List[HelpChapter] = {
  val i = index(s, props)
  if (i > 0) List(chapters(s)(i - 1)) else Nil
}

def next(s: State, props: Props): List[HelpChapter] = {
  val i = index(s, props)
  val cs = chapters(s)
  if (i >= 0 && i < cs.size - 1) List(cs(i + 1)) else if (i < 0) cs.take(1) else Nil
}

def h2s(c: HelpChapter): List[HelpSection] = c.sections.filter(_.level == 2)

def chCls(s: State, props: Props, c: HelpChapter): String = if (props.chapter.contains(c.id)) "ch on" else "ch"

def hitTo(h: HelpHit): String = s"/help/${h.chapter.id}" + h.section.fold("")(x => s"#${x.id}")

def hitTitle(h: HelpHit): String = s"${h.chapter.icon} ${h.chapter.title}" + h.section.fold("")(x => s" › ${x.title}")

def searching(s: State): Boolean = s.q.v.trim.nonEmpty

def scrollToHash(s: State): Unit =
  dom.window.setTimeout(() => {
    val id = js.URIUtils.decodeURIComponent(dom.window.location.hash.drop(1))
    Option(if (id.nonEmpty) dom.document.getElementById(id) else null) match {
      case Some(el) =>
        el.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "start"))
        el.classList.add("flash")
        dom.window.setTimeout(() => el.classList.remove("flash"), 1400)
      case None => s.body.foreach(_.asInstanceOf[js.Dynamic].scrollTo(0, 0))
    }
  }, 0)

/** 本文のリンク: アプリ内（/help・/w など）はページを切り替える */
def onBodyClick(e: dom.Event): Unit = {
  val m = e.asInstanceOf[dom.MouseEvent]
  if (!e.defaultPrevented && !m.metaKey && !m.ctrlKey) {
    val a = e.target.asInstanceOf[dom.Element].closest("a")
    if (a != null && a.getAttribute("target") != "_blank") {
      val href = Option(a.getAttribute("href")).getOrElse("")
      if (href.startsWith("#")) {
        e.preventDefault()
        val l = dom.window.location
        Nav.replace(l.pathname + l.search + href)
      } else if (href.startsWith("/")) {
        e.preventDefault()
        Nav.go(href)
      }
    }
  }
}

def back(): Unit = if (dom.window.history.length > 1) dom.window.history.back() else Nav.go("/")
</script>

<template>
  <div class="help" data-testid="help">
    <header class="bar">
      <button class="menu-btn" title="目次" @click="s.sideOpen := !s.sideOpen.v">☰</button>
      <a href="/help" @click='Nav.link(event, "/help")' class="brand">📖 skiwi の使い方</a>
      <input v-model="s.q" type="search" class="search" placeholder="🔍 マニュアルを検索（例: 共有、期限、データベース）" data-testid="help-search">
      <button class="back" @click="back()">← skiwi に戻る</button>
    </header>
    <div class="layout">
      <nav :class='if (s.sideOpen.v) "side open" else "side"' aria-label="マニュアルの目次" data-testid="help-toc">
        <template v-for="c in chapters(s)" :key="c.id">
          <a :href='"/help/" + c.id' @click='Nav.link(event, "/help/" + c.id)' :class="chCls(s, props, c)" data-testid="help-chapter">{{ c.icon }} {{ c.title }}</a>
          <div v-if="props.chapter.contains(c.id)" class="secs">
            <a v-for="x in h2s(c)" :key="x.id" :href='"/help/" + c.id + "#" + x.id' @click='Nav.link(event, "/help/" + c.id + "#" + x.id)' class="sec">{{ x.title }}</a>
          </div>
        </template>
      </nav>
      <main ref="s.body" class="main">
        <template v-if="s.docs.v.isDefined">
          <!-- 検索結果 -->
          <section v-if="searching(s)" class="hits" data-testid="help-hits">
            <h1>「{{ s.q }}」の検索結果（{{ s.hits.v.size }}件）</h1>
            <p v-if="s.hits.v.isEmpty" class="muted">見つかりませんでした。言葉を変えて試してください（例: 「権限」→「共有」）。</p>
            <a v-for="h in s.hits" :key="hitTo(h) + h.snippet" :href="hitTo(h)" @click="Nav.link(event, hitTo(h))" class="hit" data-testid="help-hit">
              <div class="hit-t">{{ hitTitle(h) }}</div>
              <div class="hit-s">{{ h.snippet }}</div>
            </a>
          </section>

          <!-- 章 -->
          <article v-else-if="cur(s, props).isDefined" class="doc" data-testid="help-doc" @click="onBodyClick(event)">
            <p class="crumb"><a href="/help" @click='Nav.link(event, "/help")'>目次</a> › {{ curC(s, props).title }}</p>
            <!-- 本文はこのアプリに同梱したマニュアル（Markdown）を変換したもの -->
            <div class="md" v-html="curC(s, props).html"></div>
            <nav class="pager">
              <a v-for="p in prev(s, props)" :key="p.id" :href='"/help/" + p.id' @click='Nav.link(event, "/help/" + p.id)' class="pg">← {{ p.icon }} {{ p.title }}</a>
              <span v-if="prev(s, props).isEmpty"></span>
              <a v-for="p in next(s, props)" :key="p.id" :href='"/help/" + p.id' @click='Nav.link(event, "/help/" + p.id)' class="pg next">{{ p.icon }} {{ p.title }} →</a>
            </nav>
          </article>

          <!-- 目次（トップ） -->
          <section v-else class="top">
            <h1>📖 skiwi の使い方</h1>
            <p class="lead">チームの知識をページにまとめ、同時に編集し、必要な人と共有するための Wiki です。知りたいことを上の検索欄で探すか、下の章から読んでください。
              画面の「?」キーでキーボードショートカットの一覧、各画面の「？ 使い方」からその機能の説明を開けます。</p>
            <div class="cards">
              <a v-for="c in chapters(s)" :key="c.id" :href='"/help/" + c.id' @click='Nav.link(event, "/help/" + c.id)' class="card" data-testid="help-card">
                <span class="ic">{{ c.icon }}</span>
                <span class="ct">{{ c.title }}</span>
                <span class="cs">{{ c.summary }}</span>
              </a>
            </div>
          </section>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.help { height: 100%; display: flex; flex-direction: column; background: var(--bg); }
.bar { display: flex; align-items: center; gap: .8rem; padding: .5rem 1rem; border-bottom: 1px solid var(--line); background: var(--side); }
.brand { font-weight: 700; color: inherit; text-decoration: none; white-space: nowrap; }
.search { flex: 1; max-width: 36rem; font: inherit; font-size: .88rem; padding: .35rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: inherit; }
.back { margin-left: auto; font-size: .85rem; white-space: nowrap; }
.menu-btn { display: none; border: none; font-size: 1.1rem; padding: 0 .3rem; }
.layout { flex: 1; display: flex; min-height: 0; }
.side { width: 260px; flex: none; overflow: auto; padding: .8rem .5rem 2rem; border-right: 1px solid var(--line); background: var(--side); }
.ch { display: block; padding: .35rem .6rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; }
.ch:hover, .ch.on { background: var(--hover); }
.ch.on { font-weight: 600; }
.secs { margin: .1rem 0 .4rem; }
.sec { display: block; padding: .2rem .6rem .2rem 1.8rem; font-size: .8rem; color: var(--muted); text-decoration: none; border-radius: 6px; }
.sec:hover { color: var(--fg); background: var(--hover); }
.main { flex: 1; overflow: auto; padding: 1.4rem 2.4rem 4rem; }
.doc, .top, .hits { max-width: 820px; margin: 0 auto; }
.crumb { font-size: .8rem; color: var(--muted); margin: 0 0 .4rem; }
.crumb a { color: var(--muted); }
.lead { line-height: 1.8; color: var(--muted); }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: .7rem; margin-top: 1.2rem; }
.card { display: grid; grid-template-columns: 2rem 1fr; grid-template-rows: auto auto; gap: .1rem .5rem; padding: .8rem; border: 1px solid var(--line); border-radius: 10px; color: inherit; text-decoration: none; }
.card:hover { border-color: var(--accent); background: var(--hover); }
.card .ic { grid-row: 1 / 3; font-size: 1.4rem; }
.card .ct { font-weight: 600; }
.card .cs { font-size: .78rem; color: var(--muted); line-height: 1.5; }
.hit { display: block; padding: .6rem .7rem; border-radius: 8px; color: inherit; text-decoration: none; border-bottom: 1px solid var(--line); }
.hit:hover { background: var(--hover); }
.hit-t { font-weight: 600; font-size: .92rem; }
.hit-s { font-size: .8rem; color: var(--muted); margin-top: .2rem; }
.pager { display: flex; justify-content: space-between; gap: 1rem; margin-top: 3rem; padding-top: 1rem; border-top: 1px solid var(--line); }
.pg { color: var(--accent); text-decoration: none; font-size: .9rem; }
.md { line-height: 1.85; }
@media (max-width: 768px) {
  .menu-btn { display: block; }
  .brand { display: none; }
  .back { font-size: .78rem; }
  .side { position: fixed; z-index: 30; top: 0; bottom: 0; left: 0; width: min(290px, 85vw); transform: translateX(-100%); transition: transform .18s; box-shadow: 8px 0 24px rgba(0,0,0,.2); }
  .side.open { transform: none; }
  .main { padding: 1rem 1rem 3rem; }
}
@media print { .bar, .side, .pager { display: none !important; } .main { overflow: visible; padding: 0; } }
</style>

<style>
/* 本文（v-html で入れるマニュアル. スコープ付きの CSS が届かないので、ここだけ全体に効かせる） */
.help .md h1 { font-size: 1.9rem; margin: .2rem 0 1rem; }
.help .md h2 { font-size: 1.35rem; margin: 2.2rem 0 .6rem; padding-bottom: .25rem; border-bottom: 1px solid var(--line); }
.help .md h3 { font-size: 1.08rem; margin: 1.6rem 0 .4rem; }
.help .md h1, .help .md h2, .help .md h3 { position: relative; scroll-margin-top: 1rem; }
.help .md .anchor { position: absolute; left: -1.1em; color: var(--muted); text-decoration: none; opacity: 0; font-weight: 400; }
.help .md h2:hover .anchor, .help .md h3:hover .anchor { opacity: .6; }
.help .md .flash { animation: help-flash 1.4s ease; }
@keyframes help-flash { 0% { background: rgba(250,204,21,.45); } 100% { background: transparent; } }
.help .md a { color: var(--accent); }
.help .md code { font-size: .86em; background: var(--hover); padding: 1px 5px; border-radius: 4px; }
.help .md pre { background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .7rem .9rem; overflow: auto; line-height: 1.5; }
.help .md pre code { background: none; padding: 0; }
.help .md kbd { display: inline-block; font: inherit; font-size: .8em; padding: 0 6px; border: 1px solid var(--line); border-bottom-width: 2px; border-radius: 5px; background: var(--side); }
.help .md blockquote { margin: 1rem 0; padding: .5rem .9rem; border-left: 4px solid var(--accent); background: var(--side); border-radius: 0 8px 8px 0; color: inherit; }
.help .md blockquote p { margin: .2rem 0; }
.help .md table { border-collapse: collapse; margin: 1rem 0; font-size: .9rem; width: 100%; }
.help .md th, .help .md td { border: 1px solid var(--line); padding: .35rem .6rem; text-align: left; vertical-align: top; }
.help .md th { background: var(--side); }
.help .md li { margin: .2rem 0; }
.help .md ol > li::marker { font-weight: 600; color: var(--accent); }
</style>
