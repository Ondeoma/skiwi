<script lang="scala">
import skiwi.web.parts.PageIcon
import skiwi.web.Nav
import io.circe.parser.parse
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{ApiError, CustomCss, Debounced, JsModule, Prefs, Theme, Ui}
import skiwi.web.parts.SandboxedHtml

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// ゲスト向けの公開ページ（ログイン不要・閲覧のみ）。公開範囲のページを左に一覧する。
case class Props(token: String, pid: Option[String])

/** サイドメニューの1行（深さ・子の有無・開閉付き） */
case class Row(page: GuestPage, depth: Int, hasKids: Boolean, open: Boolean, hit: Boolean)

case class State(pages: RV[List[GuestPage]] = RV(Nil),
                 rootId: RV[Option[String]] = RV(None),
                 current: RV[Option[GuestPage]] = RV(None),
                 error: RV[String] = RV(""),
                 loading: RV[Boolean] = RV(true),
                 /** 全幅で表示するページか（ページの「全幅」の設定） */
                 fullWidth: RV[Boolean] = RV(false),
                 // 公開リンクの設定: ヘッダーを出さない / 本文の上下のHTML / サイドメニュー（auto | show | hide）
                 hideHeader: RV[Boolean] = RV(false),
                 topHtml: RV[List[String]] = RV(Nil),
                 bottomHtml: RV[List[String]] = RV(Nil),
                 sidebarMode: RV[String] = RV("auto"),
                 // 畳んだ階層（このブラウザに公開リンクごとに保存）
                 collapsed: RV[Set[String]] = RV(Set.empty),
                 filter: RV[String] = RV(""),
                 hits: RV[Option[List[GuestSearchHit]]] = RV(None),
                 search: Debounced = new Debounced(250),
                 // メニューの開閉. 広い画面では横に置き（開閉をこのブラウザに保存）、狭い画面では本文に重ねて開く
                 narrow: RV[Boolean] = RV(false),
                 sideOpen: RV[Boolean] = RV(false),
                 sideEl: ElementRef[dom.HTMLElement] = ElementRef(),
                 // 更新履歴（公開日時と変更点のメモ。公開した人が表示する設定にしたとき）
                 hasReleases: RV[Boolean] = RV(false),
                 releases: RV[Option[List[GuestRelease]]] = RV(None),
                 releasesOpen: RV[Boolean] = RV(false),
                 // パスワード付きのリンク
                 needPassword: RV[Boolean] = RV(false),
                 password: RV[String] = RV(""),
                 pwError: RV[String] = RV(""),
                 // 本文（JS の renderStatic）とカスタムCSS
                 area: ElementRef[dom.HTMLElement] = ElementRef(),
                 doc: RV[Option[(dom.HTMLElement, js.Dynamic)]] = RV(None),
                 css: RV[Option[CustomCss.Applier]] = RV(None),
                 mq: RV[Option[(dom.MediaQueryList, js.Function1[dom.Event, Unit])]] = RV(None))

def initState() = State()

val SideKey = "skiwi.guestSide"

def collapsedKey(props: Props): String = s"skiwi.guestCollapsed.${props.token}"

def mounted(s: State, props: Props): Unit = {
  pageTitle(s.current.mapC(_.fold("skiwi")(p => s"${p.displayTitle} - skiwi")))
  // サイドメニュー
  val mq = dom.window.matchMedia("(max-width: 720px)")
  s.narrow := mq.matches
  s.sideOpen := !mq.matches && !Prefs.get(SideKey).contains("0")
  val onMq: js.Function1[dom.Event, Unit] = _ => {
    s.narrow := mq.matches
    if (mq.matches) s.sideOpen := false
  }
  mq.addEventListener("change", onMq)
  s.mq := Some((mq, onMq))
  s.collapsed := Prefs.get(collapsedKey(props)).flatMap(r => parse(r).flatMap(_.as[List[String]]).toOption).fold(Set.empty[String])(_.toSet)
  // 本文の検索（公開範囲のページだけ。サーバーで照合し、一致箇所を含む抜粋を返す）
  watch(s.filter, false) { (_, q) =>
    if (q.trim.isEmpty) { s.search.cancel(); s.hits := None }
    else s.search.run { latest =>
      GuestApi.search(props.token, q.trim).onComplete {
        case Success(r) => if (latest()) s.hits := Some(r.filter(h => !h.titleHit || h.snippet.isDefined))
        case Failure(_) => if (latest()) s.hits := Some(Nil)
      }
    }
  }
  // カスタムCSS（添付ファイルの url() は公開リンク経由に読み替える）
  s.css := Some(CustomCss.applier())
  loadTree(s, props)
}

def unmounted(s: State): Unit = {
  s.search.cancel()
  s.mq.v.foreach((mq, f) => mq.removeEventListener("change", f))
  s.doc.v.foreach(_._2.destroy())
  s.css.v.foreach(_.destroy())
  JsModule.load("editor").foreach(_.setGuestContext(null))
}

def setSide(s: State, v: Boolean): Unit = {
  s.sideOpen := v
  if (!s.narrow.v) Prefs.set(SideKey, if (v) "1" else "0")
}

def loadTree(s: State, props: Props): Unit = {
  s.loading := true
  s.error := ""
  s.needPassword := false
  GuestApi.tree(props.token).onComplete {
    case Success(t) =>
      s.pages := t.pages
      s.rootId := Some(t.rootId)
      s.hideHeader := t.hideHeader.contains(true)
      s.sidebarMode := t.sidebar.getOrElse("auto")
      s.hasReleases := t.releases.contains(true)
      JsModule.load("editor").foreach(_.setGuestContext(js.Dynamic.literal(token = props.token, pages = GuestApi.toJs(t.pages.asJson))))
      pageId(s, props).foreach(id => loadPage(s, props, id))
    case Failure(e) =>
      val st = e match { case a: ApiError => a.status; case _ => 0 }
      if (st == 401) s.needPassword := true
      else if (st == 403 && e.getMessage == "not_started") {
        val at = e.asInstanceOf[ApiError].body.flatMap(_.hcursor.get[String]("startsAt").toOption)
        s.error := at.fold("このページはまだ公開されていません。")(a => s"このページは ${Ui.date(a)} から公開されます。")
      }
      else s.error := (if (st == 410) "このリンクは公開期間が終わりました。" else "このリンクは無効です。公開が停止されたか、URLが間違っています。")
      s.loading := false
  }
}

def loadPage(s: State, props: Props, id: String): Unit = {
  s.loading := true
  GuestApi.page(props.token, id).onComplete {
    case Success(r) =>
      s.current := Some(r.page)
      s.fullWidth := r.fullWidth.contains(true)
      s.topHtml := r.top.getOrElse(Nil)
      s.bottomHtml := r.bottom.getOrElse(Nil)
      s.css.v.foreach(_.set(r.styles.getOrElse(Nil), Some(props.token)))
      JsModule.load("editor").foreach { m =>
        showContent(s, m, GuestApi.toJs(r.content))
      }
      dom.window.scrollTo(0, 0)
      s.loading := false
      scrollSideToActive(s)
    case Failure(e) =>
      s.current := None
      // 一覧の読み込みと同時に走った場合（パスワード・期限切れ）は、一覧側の案内に任せる
      val st = e match { case a: ApiError => a.status; case _ => 0 }
      if (st != 401 && st != 410 && st != 403) s.error := "ページが見つかりません。公開範囲外か、削除された可能性があります。"
      s.loading := false
  }
}

/** 本文を表示する（表示する場所が作り直されていたら、描き直す） */
def showContent(s: State, m: js.Dynamic, content: js.Any): Unit = s.area.get.foreach { el =>
  s.doc.v match {
    case Some((cur, h)) if cur eq el => h.setContent(content)
    case old =>
      old.foreach(_._2.destroy())
      s.doc := Some((el, m.renderStatic(el, content, js.Dynamic.literal(testid = "guest-content", selectAll = true))))
  }
}

def scrollSideToActive(s: State): Unit =
  dom.window.setTimeout(() => s.sideEl.foreach { el =>
    Option(el.querySelector(".item.active")).foreach(_.asInstanceOf[js.Dynamic].scrollIntoView(js.Dynamic.literal(block = "nearest")))
  }, 0)

/** 開いているページ（URLの末尾はページのIDかURL名） */
def pageId(s: State, props: Props): Option[String] = props.pid match {
  case None => s.rootId.v
  case Some(pid) =>
    val k = pid.toLowerCase
    val ps = s.pages.v
    ps.find(p => p.id == k || p.slug.contains(k)).map(_.id).orElse(Option.when(ps.nonEmpty)(pid))
}

def href(props: Props, p: GuestPage): String = GuestApi.guestHref(props.token, p)

// ---------------------------------------------------------------- サイドメニュー（公開範囲のページ一覧）

def hasSidebar(s: State): Boolean = s.sidebarMode.v == "show" || (s.sidebarMode.v == "auto" && s.pages.v.size > 1)

def toggle(s: State, props: Props, id: String): Unit = {
  val c = s.collapsed.v
  val next = if (c.contains(id)) c - id else c + id
  s.collapsed := next
  Prefs.set(collapsedKey(props), next.toList.asJson.noSpaces)
}

def ancestorsOfCurrent(s: State, props: Props, byId: Map[String, GuestPage]): Set[String] = {
  var out = Set.empty[String]
  var cur = pageId(s, props).flatMap(byId.get)
  while (cur.exists(_.parentId.exists(byId.contains)) && out.size <= byId.size) {
    val pid = cur.get.parentId.get
    out += pid
    cur = byId.get(pid)
  }
  out
}

def norm(v: String): String = Ui.normalizeForSearch(v)

/** 絞り込み（ひらがな/カタカナ・全角/半角・大文字/小文字を区別しない）。一致したページと、その祖先を出す */
def matched(s: State): Option[Set[String]] = {
  val q = norm(s.filter.v.trim)
  if (q.isEmpty) None
  else {
    val byId = s.pages.v.map(p => p.id -> p).toMap
    var keep = Set.empty[String]
    s.pages.v.filter(p => norm(p.displayTitle).contains(q)).foreach { p =>
      var cur: Option[GuestPage] = Some(p)
      while (cur.exists(c => !keep.contains(c.id))) {
        keep += cur.get.id
        cur = cur.get.parentId.flatMap(byId.get)
      }
    }
    Some(keep)
  }
}

def rows(s: State, props: Props): List[Row] = {
  val m = matched(s)
  val children = GuestApi.groupByParent(s.pages.v)
  val byId = s.pages.v.map(p => p.id -> p).toMap
  val anc = ancestorsOfCurrent(s, props, byId)
  val q = norm(s.filter.v.trim)
  val out = List.newBuilder[Row]
  def walk(parent: Option[String], depth: Int): Unit =
    children.getOrElse(parent, Nil).foreach { p =>
      if (m.forall(_.contains(p.id))) {
        val hasKids = children.getOrElse(Some(p.id), Nil).nonEmpty
        val open = m.isDefined || !s.collapsed.v.contains(p.id) || anc.contains(p.id)
        out += Row(p, depth, hasKids, open, m.isDefined && norm(p.displayTitle).contains(q))
        if (hasKids && open) walk(Some(p.id), depth + 1)
      }
    }
  walk(None, 0)
  out.result()
}

def itemCls(s: State, props: Props, r: Row): String =
  "item" + (if (pageId(s, props).contains(r.page.id)) " active" else "") + (if (r.hit) " hit" else "")

def hitCls(s: State, props: Props, h: GuestSearchHit): String = if (pageId(s, props).contains(h.page.id)) "hit active" else "hit"

def layoutCls(s: State): String = if (!hasSidebar(s) || !s.sideOpen.v || s.narrow.v) "layout single" else "layout"

def hitList(s: State): List[GuestSearchHit] = s.hits.v.getOrElse(Nil)

def noMatch(s: State, props: Props): Boolean = matched(s).isDefined && rows(s, props).isEmpty && s.hits.v.exists(_.isEmpty)

// ---------------------------------------------------------------- 更新履歴・パスワード

def openReleases(s: State, props: Props): Unit = {
  s.releasesOpen := !s.releasesOpen.v
  if (s.releasesOpen.v && s.releases.v.isEmpty)
    GuestApi.releases(props.token).onComplete(r => s.releases := Some(r.getOrElse(Nil)))
}

def unlock(s: State, props: Props): Unit = {
  s.pwError := ""
  GuestApi.unlock(props.token, s.password.v).onComplete {
    case Success(true) =>
      s.password := ""
      loadTree(s, props)
    case Success(false) => s.pwError := "パスワードが違います"
    case Failure(e) => s.pwError := e.getMessage
  }
}

/** 開閉ブロックのタイトルをクリックで開閉する（閲覧専用なのでタイトルは編集できない） */
def onAreaClick(e: dom.Event): Unit = {
  val summary = e.target.asInstanceOf[dom.Element].closest("summary")
  val details = if (summary == null) null else summary.closest(".details")
  val btn = if (details == null) null else details.querySelector(":scope > button")
  if (btn != null) {
    e.preventDefault()
    btn.asInstanceOf[dom.HTMLElement].click()
  }
}

def cur(s: State): GuestPage = s.current.v.getOrElse(GuestPage(""))

def tops(s: State): List[(String, Int)] = s.topHtml.v.zipWithIndex

def bottoms(s: State): List[(String, Int)] = s.bottomHtml.v.zipWithIndex

def themeTitle: String = s"表示: ${Theme.labels(Theme.mode.v)}（クリックで切り替え）"

def themeLabel: String = s"表示の明暗: ${Theme.labels(Theme.mode.v)}"
</script>

<template>
  <div :class='if (s.hideHeader.v) "guest no-header" else "guest"'>
    <header v-if="!s.hideHeader.v" class="bar" data-testid="guest-header">
      <span class="badge" data-testid="guest-badge">🌐 公開ページ（閲覧のみ）</span>
      <span class="bar-right">
        <button class="theme-toggle" :title="themeTitle" :aria-label="themeLabel" data-testid="theme-toggle" :data-mode="Theme.mode.v"
                @click="Theme.cycle()">{{ Theme.icons(Theme.mode.v) }}</button>
        <a class="login" href="/" title="メンバーはログインすると編集できます">ログイン</a>
      </span>
    </header>
    <div :class="layoutCls(s)">
      <button v-if="hasSidebar(s) && !s.sideOpen.v" class="side-open" title="ページの一覧を開く" data-testid="guest-side-open" @click="setSide(s, true)">☰<span> ページ一覧</span></button>
      <div v-if="hasSidebar(s) && s.sideOpen.v && s.narrow.v" class="side-backdrop" @click="setSide(s, false)"></div>
      <nav v-if="hasSidebar(s) && s.sideOpen.v" ref="s.sideEl" :class='if (s.narrow.v) "side overlay" else "side"' aria-label="公開ページの一覧" data-testid="guest-side">
        <div class="side-head">
          <span class="side-title">ページ一覧<small>（{{ s.pages.v.size }}）</small></span>
          <button class="side-close" title="一覧を閉じる" data-testid="guest-side-close" @click="setSide(s, false)">«</button>
        </div>
        <input v-model="s.filter" type="search" class="side-filter" placeholder="🔍 ページ名・本文を検索" data-testid="guest-side-filter">
        <div v-for="r in rows(s, props)" :key="r.page.id" class="row" :style='s"padding-left: ${r.depth * 0.9}rem"'>
          <button v-if="r.hasKids" class="twisty" :title='if (r.open) "畳む" else "開く"' :disabled="matched(s).isDefined" @click="toggle(s, props, r.page.id)">{{ if (r.open) "▾" else "▸" }}</button>
          <span v-else class="twisty-space"></span>
          <a :href="href(props, r.page)" @click="Nav.link(event, href(props, r.page))" :class="itemCls(s, props, r)" data-testid="guest-side-item">
            <span class="ic"><PageIcon :icon="r.page.icon" /></span>{{ r.page.displayTitle }}
          </a>
        </div>
        <p v-if="noMatch(s, props)" class="muted small">一致するページはありません</p>
        <div v-if="hitList(s).nonEmpty" class="hits" data-testid="guest-search">
          <div class="hits-title">本文に一致（{{ hitList(s).size }}件）</div>
          <a v-for="h in hitList(s)" :key="h.page.id" :href="href(props, h.page)" @click="Nav.link(event, href(props, h.page))" :class="hitCls(s, props, h)" data-testid="guest-search-hit">
            <span class="hit-t"><span class="ic"><PageIcon :icon="h.page.icon" /></span>{{ h.page.displayTitle }}</span>
            <!-- サーバーがエスケープ済み（一致箇所の <mark> だけを含む） -->
            <span v-if="h.snippet.isDefined" class="hit-s" v-html='h.snippet.getOrElse("")'></span>
          </a>
        </div>
      </nav>
      <main :class='if (s.fullWidth.v) "page skiwi-page-css full-width" else "page skiwi-page-css"' data-testid="guest-page">
        <p v-if="s.error.v.nonEmpty" class="error" data-testid="guest-error">{{ s.error }}</p>
        <form v-else-if="s.needPassword.v" class="pw" data-testid="guest-password" @submit.prevent="unlock(s, props)">
          <h1>🔒 パスワードが必要です</h1>
          <p class="muted">このページを閲覧するには、公開した人から伝えられたパスワードを入力してください。</p>
          <div class="pw-row">
            <input v-model="s.password" type="password" autocomplete="off" placeholder="パスワード" autofocus>
            <button type="submit" :disabled="s.password.v.isEmpty">表示する</button>
          </div>
          <p v-if="s.pwError.v.nonEmpty" class="pw-error">{{ s.pwError }}</p>
        </form>
        <template v-else>
          <div v-for="h in tops(s)" :key='h._2.toString + ":" + h._1.length' class="guest-html top">
            <SandboxedHtml :html="h._1" label="ページ上部" />
          </div>
          <div v-if="s.current.v.isDefined" class="head">
            <div v-if="cur(s).icon.isDefined" class="page-icon">{{ cur(s).iconOr }}</div>
            <h1 class="title" data-testid="guest-title">{{ cur(s).displayTitle }}</h1>
            <p class="meta">
              最終更新 {{ Ui.date(cur(s).updatedAt) }}
              <button v-if="s.hasReleases.v" class="rel-btn" data-testid="guest-releases-toggle" @click="openReleases(s, props)">🕘 更新履歴</button>
              <a class="rel-btn" :href='"/print/s/" + props.token' target="_blank" rel="noopener" data-testid="guest-pdf">🖨 PDF</a>
            </p>
            <section v-if="s.releasesOpen.v" class="rel" data-testid="guest-releases">
              <p v-if="s.releases.v.isEmpty" class="muted">読み込み中…</p>
              <p v-else-if="s.releases.v.exists(_.isEmpty)" class="muted">更新履歴はまだありません。</p>
              <ul v-else>
                <li v-for="r in s.releases.v.getOrElse(Nil)"><time>{{ Ui.date(r.publishedAt) }}</time><div class="rel-note">{{ r.note }}</div></li>
              </ul>
            </section>
          </div>
          <div :class='if (s.loading.v) "editor-area readonly dim" else "editor-area readonly"' @click="onAreaClick(event)">
            <div ref="s.area"></div>
          </div>
          <div v-for="h in bottoms(s)" :key='h._2.toString + ":" + h._1.length' class="guest-html bottom">
            <SandboxedHtml :html="h._1" label="ページ下部" />
          </div>
        </template>
      </main>
    </div>
  </div>
</template>

<style scoped>
.guest { min-height: 100vh; background: var(--bg); }
.bar { position: sticky; top: 0; z-index: 5; display: flex; align-items: center; justify-content: space-between; padding: .5rem 1rem; border-bottom: 1px solid var(--line); background: var(--side); font-size: .85rem; }
.badge { color: var(--muted); }
.login { color: var(--accent); text-decoration: none; }
.bar-right { display: flex; align-items: center; gap: .6rem; }
.theme-toggle { font-size: .8rem; padding: 1px 6px; line-height: 1.4; }
.layout { display: grid; grid-template-columns: 270px 1fr; position: relative; }
.layout.single { grid-template-columns: 1fr; }
.guest.no-header .side { top: 0; max-height: 100vh; min-height: 100vh; }
.side-head { display: flex; align-items: center; justify-content: space-between; padding: 0 .3rem .4rem .5rem; }
.side-title { font-size: .78rem; font-weight: 600; color: var(--muted); }
.side-title small { font-weight: 400; margin-left: 2px; }
.side-close { border: none; color: var(--muted); padding: 0 6px; }
.side-open { position: fixed; z-index: 6; left: .7rem; top: 3.2rem; border-radius: 6px; padding: 2px 9px; color: var(--muted); background: var(--bg); font-size: .85rem; }
.guest.no-header .side-open { top: .7rem; }
.side-open:hover { color: var(--fg); }
.side-filter { width: calc(100% - .6rem); margin: 0 .3rem .4rem; box-sizing: border-box; padding: .25rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; font-size: .82rem; }
.row { display: flex; align-items: center; }
.twisty { border: none; padding: 0; width: 18px; flex: none; color: var(--muted); font-size: .75rem; }
.twisty:disabled { opacity: .4; cursor: default; }
.twisty-space { width: 18px; flex: none; }
.ic { margin-right: .3em; }
.item.hit { font-weight: 600; }
.hits { margin-top: .6rem; padding-top: .5rem; border-top: 1px solid var(--line); }
.hits-title { font-size: .74rem; color: var(--muted); font-weight: 600; padding: 0 .5rem .2rem; }
.hit { display: block; padding: .35rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.hit:hover, .hit.active { background: var(--hover); }
.hit-t { display: block; font-size: .86rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.hit-s { display: block; font-size: .74rem; color: var(--muted); line-height: 1.45; overflow-wrap: anywhere; }
.side.overlay { position: fixed; top: 0; bottom: 0; left: 0; z-index: 40; width: min(300px, 86vw); max-height: none; min-height: 0; box-shadow: 8px 0 24px rgba(0,0,0,.25); }
.side-backdrop { position: fixed; inset: 0; z-index: 35; background: rgba(0,0,0,.35); }
.small { font-size: .8rem; padding: 0 .6rem; }
.guest-html.top { margin-bottom: 1rem; }
.guest-html.bottom { margin-top: 2rem; }
.side { position: sticky; top: 41px; align-self: start; max-height: calc(100vh - 41px); overflow: auto; padding: .8rem .4rem; border-right: 1px solid var(--line); background: var(--side); min-height: calc(100vh - 41px); }
.item { flex: 1; min-width: 0; display: block; padding: .3rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.item:hover { background: var(--hover); }
.item.active { background: var(--hover); font-weight: 600; }
.page { max-width: 860px; width: 100%; margin: 0 auto; padding: 2rem 2.4rem 4rem; box-sizing: border-box; }
.page-icon { font-size: 2.8rem; line-height: 1.2; }
.title { font-size: 2.2rem; margin: .2rem 0 .3rem; }
.meta { color: var(--muted); font-size: .8rem; margin: 0 0 1.5rem; }
.dim { opacity: .5; }
.rel-btn { text-decoration: none; margin-left: .6rem; border: none; background: none; color: var(--accent); padding: 0; font-size: .8rem; cursor: pointer; }
.rel { margin: -.8rem 0 1.5rem; padding: .6rem .8rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); font-size: .85rem; max-height: 18rem; overflow: auto; }
.rel ul { list-style: none; margin: 0; padding: 0; }
.rel li { padding: .35rem 0; border-bottom: 1px solid var(--line); }
.rel li:last-child { border-bottom: none; }
.rel time { font-size: .75rem; color: var(--muted); }
.rel-note { white-space: pre-wrap; }
.error { color: var(--muted); padding: 3rem 0; }
.pw { max-width: 26rem; margin: 4rem auto; }
.pw h1 { font-size: 1.3rem; }
.pw-row { display: flex; gap: .5rem; }
.pw-row input { flex: 1; padding: .4rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.pw-error { color: var(--danger); font-size: .9rem; }
@media (max-width: 720px) {
  .layout { grid-template-columns: 1fr; }
  .page { padding: 1.2rem 1rem 3rem; }
}
</style>

<style>
/* v-html の抜粋と JS が描く本文（スコープ付きの CSS が届かないので、ここだけ全体に効かせる） */
.guest .hit-s mark { background: rgba(250,204,21,.45); color: inherit; border-radius: 2px; }
.guest .editor-area.readonly .details summary { cursor: pointer; }
</style>
