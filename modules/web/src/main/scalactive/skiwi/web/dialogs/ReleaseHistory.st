<script lang="scala">
import io.circe.Json
import skiwi.web.*
import skiwi.web.parts.*

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.util.{Failure, Success}

// 公開の履歴（共有画面の中）. メモの編集と、ひとつ前の公開との比較.
// reload: 値が変わると読み直す（「現在の内容を公開」の後など）
case class Props(pageId: String, reload: RV[Int] = RV(0))

/** 差分の行（editor/diff.ts の Row）. kind: same / add / del / change / fold */
case class Piece(kind: String, text: String)
case class Row(kind: String, text: String, count: Int, pieces: List[Piece])
/** kind: added / removed / changed. renamed: 旧タイトル（変わったとき） */
case class Change(pageId: String, title: String, icon: Option[String], kind: String, renamed: Option[String], rows: List[Row], count: Int)
case class Cmp(release: Release, prev: Option[Release], changes: List[Change])

case class State(releases: RV[Option[List[Release]]] = RV(None),
                 open: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""),
                 cmp: RV[Option[Cmp]] = RV(None),
                 picked: RV[Option[String]] = RV(None))

def initState() = State()

def load(s: State, p: Props): Unit =
  DialogsApi.releases(p.pageId).onComplete {
    case Success(r) => s.releases := Some(r)
    case Failure(e) => s.error := e.getMessage
  }

def mounted(s: State, props: Props): Unit = {
  load(s, props)
  watch(props.reload, false)((_, _) => load(s, props))
}

def editNote(s: State, p: Props, r: Release): Unit =
  Dialog.prompt("変更点のメモ（ゲスト向けの更新履歴に表示されます）", r.note.getOrElse(""), okLabel = "保存").foreach {
    case Some(v) => DialogsApi.setReleaseNote(p.pageId, r.id, Dlg.nonBlank(v)).foreach(_ => load(s, p))
    case None => ()
  }

private def toRows(rs: List[skiwi.web.page.DiffRow]): List[Row] =
  rs.map(r => Row(r.kind, r.text.getOrElse(""), r.count.getOrElse(0), r.pieces.getOrElse(Nil).map(x => Piece(x.kind, x.text))))

// --- 比較: この公開 と ひとつ前の公開 ---
def compare(s: State, p: Props, r: Release): Unit = {
  val all = s.releases.v.getOrElse(Nil)
  val prev = all.drop(all.indexWhere(_.id == r.id) + 1).headOption
  val fa = prev.fold(Future.successful(List.empty[ReleasePage]))(x => DialogsApi.release(p.pageId, x.id).map(_.pages))
  val fb = DialogsApi.release(p.pageId, r.id).map(_.pages)
  (for { a <- fa; b <- fb } yield (a, b)).onComplete {
    case Failure(e) => s.error := e.getMessage
    case Success((a, b)) =>
      def units(c: Json): List[String] = skiwi.web.content.Markdown.blockUnits(c)
      def diff(x: List[String], y: List[String]): (List[Row], Int) = {
        val rs = skiwi.web.content.Diff.diffBlocks(x, y)
        (toRows(rs), skiwi.web.content.Diff.changeCount(rs))
      }
      val before = a.map(x => x.pageId -> x).toMap
      val after = b.map(_.pageId).toSet
      val changes = b.flatMap { pg =>
        before.get(pg.pageId) match {
          case None =>
            val (rows, n) = diff(Nil, units(pg.content))
            List(Change(pg.pageId, pg.title, pg.icon, "added", None, rows, n))
          case Some(o) =>
            val (rows, n) = diff(units(o.content), units(pg.content))
            val renamed = if (o.title != pg.title) Some(o.title) else None
            if (n > 0 || renamed.isDefined || o.icon != pg.icon) List(Change(pg.pageId, pg.title, pg.icon, "changed", renamed, rows, n))
            else Nil
        }
      } ++ a.filterNot(o => after.contains(o.pageId)).map(o => Change(o.pageId, o.title, o.icon, "removed", None, Nil, 0))
      s.cmp := Some(Cmp(r, prev, changes))
      s.picked := changes.find(_.kind != "removed").map(_.pageId)
  }
}

def pickedChange(s: State): Option[Change] = s.cmp.v.flatMap(c => c.changes.find(x => s.picked.v.contains(x.pageId)))

val Kind: Map[String, String] = Map("added" -> "追加", "removed" -> "公開範囲から外れた", "changed" -> "変更")

def head(c: Cmp): String = c.prev match {
  case Some(p) => s"前回の公開（${Ui.date(p.publishedAt)}）→ この公開（${Ui.date(c.release.publishedAt)}）"
  case None => s"最初の公開（${Ui.date(c.release.publishedAt)}）です。すべて追加として表示します"
}

def cmpList(s: State): List[Cmp] = s.cmp.v.toList

def rowsOf(s: State): List[Row] = pickedChange(s).fold(Nil)(_.rows)
</script>

<template>
  <div class="releases" data-testid="releases">
    <button class="toggle" data-testid="releases-toggle" @click="s.open := !s.open.v">{{ if (s.open.v) "▾" else "▸" }} 公開の履歴<span v-if="s.releases.v.isDefined">（{{ s.releases.v.fold(0)(_.size) }}）</span></button>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <ul v-if="s.open.v && s.releases.v.isDefined" class="list">
      <li v-if="s.releases.v.exists(_.isEmpty)" class="muted">まだ公開の履歴はありません。</li>
      <li v-for="r in s.releases.v.getOrElse(Nil)" :key="r.id" data-testid="release-item">
        <div class="r-head">
          <span>{{ Ui.date(r.publishedAt) }}<span v-if="r.publishedBy.isDefined" class="muted">（{{ r.publishedBy.getOrElse("") }}）</span></span>
          <span class="acts">
            <button class="link" data-testid="release-compare" @click="compare(s, props, r)">前回との差分</button>
            <button class="link" @click="editNote(s, props, r)">メモを編集</button>
          </span>
        </div>
        <div v-if="r.note.isDefined" class="note-text" data-testid="release-note">{{ r.note.getOrElse("") }}</div>
        <div v-else class="muted small">（メモなし）</div>
      </li>
    </ul>

    <template v-for="c in cmpList(s)">
      <Modal label="公開の差分" title="公開の差分" testid="release-diff" :width="900" :top="6" :z="80" @close="s.cmp := None">
        <p class="muted small">{{ head(c) }}</p>
        <p v-if="c.release.note.isDefined" class="note-text">{{ c.release.note.getOrElse("") }}</p>
        <p v-if="c.changes.isEmpty" class="muted">前回の公開から変わったページはありません。</p>
        <div v-else class="cmp">
          <ul class="pages">
            <li v-for="x in c.changes" :key="x.pageId">
              <button :class='List(x.kind, "on" -> s.picked.v.contains(x.pageId))' :disabled='x.kind == "removed"' data-testid="release-diff-page" @click="s.picked := Some(x.pageId)">
                <span class="kind">{{ Kind(x.kind) }}</span>{{ x.icon.getOrElse("📄") }} {{ Dlg.untitled(x.title) }}
                <small v-if="x.renamed.isDefined" class="muted">（旧: {{ Dlg.untitled(x.renamed) }}）</small>
                <small v-if='x.kind == "changed" && x.count > 0' class="muted">{{ x.count }}か所</small>
              </button>
            </li>
          </ul>
          <div v-if="pickedChange(s).isDefined" class="diff" data-testid="release-diff-body">
            <template v-for="ri in rowsOf(s).zipWithIndex" :key="ri._2">
              <div v-if='ri._1.kind == "fold"' class="fold">… 変更のない {{ ri._1.count }} ブロック …</div>
              <div v-else-if='ri._1.kind == "change"' class="blk change"><span v-for="pj in ri._1.pieces.zipWithIndex" :key="pj._2" :class="pj._1.kind">{{ pj._1.text }}</span></div>
              <div v-else :class='List("blk", ri._1.kind)'>{{ ri._1.text }}</div>
            </template>
            <p v-if="pickedChange(s).exists(_.count == 0)" class="muted small">本文の違いはありません（タイトル・アイコンのみ）。</p>
          </div>
        </div>
      </Modal>
    </template>
  </div>
</template>

<style scoped>
.releases { margin-top: .4rem; font-size: .85rem; }
.toggle { border: none; padding: 0; color: var(--muted); font-size: .82rem; }
.list { list-style: none; margin: .3rem 0 0; padding: 0; max-height: 14rem; overflow: auto; border: 1px solid var(--line); border-radius: 8px; }
.list li { padding: .4rem .6rem; border-bottom: 1px solid var(--line); }
.list li:last-child { border-bottom: none; }
.r-head { display: flex; justify-content: space-between; gap: .5rem; flex-wrap: wrap; }
.acts { display: flex; gap: .5rem; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .78rem; }
.note-text { white-space: pre-wrap; margin-top: .2rem; font-size: .82rem; }
.small { font-size: .76rem; }
.cmp { display: grid; grid-template-columns: minmax(180px, 240px) 1fr; gap: .8rem; margin-top: .5rem; }
.pages { list-style: none; margin: 0; padding: 0; }
.pages button { width: 100%; text-align: left; border: none; border-radius: 6px; padding: .3rem .4rem; font-size: .82rem; }
.pages button.on { background: var(--hover); }
.kind { font-size: .66rem; border-radius: 4px; padding: 0 5px; margin-right: .3rem; background: var(--hover); }
.added .kind { color: #16a34a; }
.removed .kind { color: var(--danger); }
.changed .kind { color: #d97706; }
.diff { border-left: 1px solid var(--line); padding-left: .8rem; min-width: 0; }
.blk { white-space: pre-wrap; padding: .2rem .4rem; border-radius: 4px; margin: 2px 0; font-size: .85rem; }
.blk.add { background: rgba(22,163,74,.12); }
.blk.del { background: rgba(220,38,38,.12); text-decoration: line-through; }
.blk.change .add { background: rgba(22,163,74,.25); }
.blk.change .del { background: rgba(220,38,38,.25); text-decoration: line-through; }
.fold { color: var(--muted); font-size: .75rem; text-align: center; }
@media (max-width: 640px) { .cmp { grid-template-columns: 1fr; } .diff { border-left: none; padding-left: 0; } }
</style>
