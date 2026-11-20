<script lang="scala">
import sbirds.web.Dom
import org.scalajs.dom
import skiwi.web.{Api, Ui}
import skiwi.web.editor.PageLinks

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// PDF（印刷）用の表示: ページと子ページを、目次付きで1つにまとめる。
//  - /print/w/:wid/p/:pid … ログインユーザー（閲覧できるページだけ。現在の内容）
//  - /print/s/:token       … 公開リンク（ゲストに見えている内容。公開版を固定していればその版）
//  ブラウザの印刷画面で「PDFに保存」を選ぶと、目次のリンク付きのPDFになる。
case class Props(wid: Option[String], pid: Option[String], token: Option[String])

/** item: ページ（ゲストのページと同じ形）, num: 見出しの番号（1.2 など）, content: 本文（JS の値） */
case class Section(item: GuestPage, depth: Int, num: String, content: js.Any)

case class Progress(done: Int, total: Int)

case class State(children: RV[Boolean] = RV(!Router.query.v.get("children").contains("0")),
                 toc: RV[Boolean] = RV(!Router.query.v.get("toc").contains("0")),
                 loading: RV[Boolean] = RV(true),
                 progress: RV[Progress] = RV(Progress(0, 0)),
                 error: RV[String] = RV(""),
                 sections: RV[List[Section]] = RV(Nil),
                 docTitle: RV[String] = RV(""),
                 generated: String = Ui.date(new js.Date().toISOString()))

def initState() = State()

def mounted(s: State, props: Props): Unit = {
  pageTitle(s.docTitle.mapC(t => if (t.isEmpty) "PDF" else s"$t - PDF"))
  load(s, props).foreach { _ =>
    // 自動で印刷画面を開く（図・数式の描画を少し待つ）
    if (s.error.v.isEmpty && !Router.query.v.get("auto").contains("0")) dom.window.setTimeout(() => dom.window.print(), 1200)
  }
}

def unmounted(props: Props): Unit =
  if (props.token.isDefined) PageLinks.setGuest(None)

/** 起点の下を深さ優先で並べる（番号付き） */
def order(all: List[GuestPage], rootId: String, withChildren: Boolean): List[(GuestPage, Int, String)] = {
  val kids = GuestApi.groupByParent(all, orphansToRoot = false, thenId = true)
  all.find(_.id == rootId).fold(Nil) { root =>
    val out = List.newBuilder[(GuestPage, Int, String)]
    out += ((root, 0, ""))
    def walk(id: String, depth: Int, prefix: String): Unit =
      kids.getOrElse(Some(id), Nil).zipWithIndex.foreach { (c, i) =>
        val num = if (prefix.nonEmpty) s"$prefix.${i + 1}" else s"${i + 1}"
        out += ((c, depth, num))
        walk(c.id, depth + 1, num)
      }
    if (withChildren) walk(root.id, 1, "")
    out.result()
  }
}

def load(s: State, props: Props): Future[Unit] = {
  s.loading := true
  s.error := ""
  s.sections := Nil
  val listed: Future[(List[(GuestPage, Int, String)], String => Future[js.Any])] = props.token match {
    case Some(token) =>
      GuestApi.tree(token).map { t =>
        PageLinks.setGuest(Some((token, t.pages)))
        (order(t.pages, t.rootId, s.children.v), (id: String) => GuestApi.page(token, id).map(r => GuestApi.toJs(r.content)))
      }
    case None =>
      Api.pages(props.wid.getOrElse("")).map { ps =>
        val items = ps.map(p => GuestPage(p.id, p.parentId, p.position, p.title, p.icon, p.updatedAt))
        (order(items, props.pid.getOrElse(""), s.children.v), (id: String) => GuestApi.pageContent(id).map(r => GuestApi.toJs(r.content)))
      }
  }
  listed.flatMap { (list, fetchContent) =>
    if (list.isEmpty) throw new Exception("ページが見つかりません（削除されたか、閲覧する権限がありません）")
    s.docTitle := list.head._1.displayTitle
    s.progress := Progress(0, list.size)
    // 本文は少しずつ並行して取る
    val contents = new Array[js.Any](list.size)
    var next = 0
    def worker(): Future[Unit] =
      if (next >= list.size) Future.successful(())
      else {
        val i = next
        next += 1
        fetchContent(list(i)._1.id).recover { case _ => "": js.Any }.flatMap { c =>
          contents(i) = c
          s.progress := s.progress.v.copy(done = s.progress.v.done + 1)
          worker()
        }
      }
    Future.sequence(List.fill(math.min(4, list.size))(worker())).map { _ =>
      s.sections := list.zipWithIndex.map { case ((item, depth, num), i) =>
        val c = contents(i)
        Section(item, depth, num, if (c != null && js.typeOf(c) == "object") c else "")
      }
    }
  }.recover { case e => s.error := e.getMessage }
    .map(_ => s.loading := false)
}

def setOpt(s: State, props: Props, k: String, v: Boolean): Unit = {
  if (k == "children") { s.children := v; load(s, props) } else s.toc := v
  def flag(b: Boolean) = Some(if (b) "1" else "0")
  Router.setQuery("children" -> flag(s.children.v), "toc" -> flag(s.toc.v), "auto" -> Some("0"))
}


def printNow(): Unit = dom.window.print()

def status(s: State): String =
  if (s.loading.v) s"読み込み中… ${s.progress.v.done}/${s.progress.v.total}" else s"${s.sections.v.size} ページ"

def tocItems(s: State): List[Section] = {
  val xs = s.sections.v
  xs.filter(x => x.depth > 0 || xs.size > 1)
}

def first(s: State): Option[GuestPage] = s.sections.v.headOption.map(_.item)

def indexed(s: State): List[(Section, Int)] = s.sections.v.zipWithIndex

def secCls(i: Int): String = if (i == 0) "sec first" else "sec"

def headCls(x: Section): String = s"d${math.min(x.depth, 3)}"

def secTitle(x: Section): String = x.item.icon.fold("")(_ + " ") + x.item.displayTitle
</script>

<template>
  <div class="print-root">
    <div class="bar no-print" data-testid="print-bar">
      <b>🖨 PDF・印刷</b>
      <label><input type="checkbox" :checked="s.children.v" data-testid="print-children" @change='setOpt(s, props, "children", Dom.checked(event))'> 子ページも含める</label>
      <label><input type="checkbox" :checked="s.toc.v" data-testid="print-toc" @change='setOpt(s, props, "toc", Dom.checked(event))'> 目次を付ける</label>
      <span class="muted">{{ status(s) }}</span>
      <button class="primary" :disabled="s.loading.v || s.error.v.nonEmpty" data-testid="print-run" @click="printNow()">印刷 / PDFに保存</button>
      <span class="muted small">印刷画面の「送信先」で「PDFに保存」を選んでください</span>
    </div>
    <p v-if="s.error.v.nonEmpty" class="error" data-testid="print-error">{{ s.error }}</p>
    <template v-else-if="!s.loading.v">
      <section class="cover">
        <div v-if="first(s).exists(_.icon.isDefined)" class="cover-icon">{{ first(s).flatMap(_.icon).getOrElse("") }}</div>
        <h1 class="cover-title" data-testid="print-title">{{ s.docTitle }}</h1>
        <p class="muted">{{ s.sections.v.size }} ページ ・ {{ s.generated }} 作成</p>
        <nav v-if="s.toc.v && tocItems(s).size > 1" class="toc" data-testid="print-toc-list">
          <h2>目次</h2>
          <ol>
            <li v-for="x in tocItems(s)" :key="x.item.id" :style='s"padding-left: ${math.max(0, x.depth - 1) * 1.2}rem"'>
              <a :href='"#p-" + x.item.id'><span v-if="x.num.nonEmpty" class="num">{{ x.num }}</span>{{ x.item.displayTitle }}</a>
            </li>
          </ol>
        </nav>
      </section>
      <article v-for="xi in indexed(s)" :id='"p-" + xi._1.item.id' :key="xi._1.item.id" :class="secCls(xi._2)" data-testid="print-section">
        <header v-if="xi._2 > 0 || s.sections.v.size > 1" class="sec-head">
          <h1 :class="headCls(xi._1)"><span v-if="xi._1.num.nonEmpty" class="num">{{ xi._1.num }}</span>{{ secTitle(xi._1) }}</h1>
          <p class="muted small">最終更新 {{ Ui.date(xi._1.item.updatedAt) }}</p>
        </header>
        <StaticDoc :content="xi._1.content" class="editor-area readonly" />
      </article>
    </template>
  </div>
</template>

<style scoped>
.print-root { max-width: 820px; margin: 0 auto; padding: 0 1.5rem 3rem; background: var(--bg); min-height: 100vh; }
.bar { position: sticky; top: 0; z-index: 5; display: flex; flex-wrap: wrap; align-items: center; gap: .4rem 1rem; padding: .6rem 0; background: var(--bg); border-bottom: 1px solid var(--line); font-size: .85rem; }
.bar label { display: flex; align-items: center; gap: .3rem; }
.error { padding: 2rem 0; }
.cover { padding: 3rem 0 1rem; }
.cover-icon { font-size: 3rem; }
.cover-title { font-size: 2.2rem; margin: .2rem 0; }
.toc h2 { font-size: 1.1rem; margin: 2rem 0 .5rem; }
.toc ol { list-style: none; padding: 0; margin: 0; }
.toc li { padding: .15rem 0; border-bottom: 1px dotted var(--line); }
.toc a { color: inherit; text-decoration: none; }
.num { color: var(--muted); margin-right: .6em; font-variant-numeric: tabular-nums; }
.sec { padding-top: 1.5rem; }
.sec-head h1 { margin: 0 0 .2rem; font-size: 1.8rem; }
.sec-head h1.d2 { font-size: 1.5rem; }
.sec-head h1.d3 { font-size: 1.3rem; }
.sec-head { border-bottom: 1px solid var(--line); margin-bottom: 1rem; }
@media print {
  .no-print { display: none !important; }
  .print-root { max-width: none; padding: 0; }
  .cover { padding-top: 0; }
  .toc { break-after: page; }
  .sec { break-before: page; padding-top: 0; }
  .sec.first { break-before: auto; }
  .toc a::after { content: none; }
}
</style>
