<script lang="scala">
import io.circe.*
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.{Dialog, JsModule, Ui}
import skiwi.web.content.{Diff, Markdown}
import skiwi.web.parts.HelpLink

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// 版履歴パネル. 版の一覧・プレビュー・手動保存・復元.
// 復元は、現在の状態をサーバーに退避してから、版の本文をエディタに適用する（Yjs経由で全員に同期）.
// getState: エディタの現在の文書状態（{ydoc, content, text}）/ applyContent: 版の本文をエディタに適用する
case class Props(pageId: String,
                 canEdit: Reactive[Boolean],
                 getState: () => Json,
                 applyContent: Json => Unit,
                 onClose: Unit => Unit)

case class State(versions: RV[List[Version]] = RV(Nil),
                 selected: RV[Option[Version]] = RV(None),
                 busy: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""),
                 /** 表示: content（内容）/ current（現在との差分）/ prev（ひとつ前の版との差分） */
                 view: RV[String] = RV("content"),
                 selectedContent: RV[Json] = RV(Json.Null),
                 diffRows: RV[Option[List[DiffRow]]] = RV(None),
                 diffNote: RV[String] = RV(""),
                 previewEl: ElementRef[dom.HTMLElement] = ElementRef(),
                 preview: RV[Option[js.Dynamic]] = RV(None),
                 gone: RV[Boolean] = RV(false))

def initState() = State()

def editorModule: Future[js.Dynamic] = JsModule.load("editor")

def load(s: State, props: Props): Future[Unit] = PageApi.versions(props.pageId).map(s.versions := _)

def mounted(s: State, props: Props): Unit = {
  editorModule.foreach { m =>
    if (!s.gone.v) s.previewEl.foreach { el =>
      s.preview := Some(m.renderStatic(el, js.Dynamic.literal(`type` = "doc", content = js.Array()), js.Dynamic.literal(testid = "hist-preview")))
    }
  }
  load(s, props).failed.foreach(e => s.error := e.getMessage)
}

def unmounted(s: State, props: Props): Unit = {
  s.gone := true
  s.preview.v.foreach(_.destroy())
}

def select(s: State, props: Props, v: Version): Unit = {
  s.selected := Some(v)
  PageApi.version(props.pageId, v.id).foreach { c =>
    s.selectedContent := c.content
    s.preview.v.foreach(_.setContent(EditorHandle.toJs(c.content)))
    refreshDiff(s, props)
  }
}

/** 差分（本文の最上位のブロックごとの Markdown を比べる） */
def diff(before: Json, after: Json): List[DiffRow] = Diff.diffBlocks(Markdown.blockUnits(before), Markdown.blockUnits(after))

def changeCount(rows: List[DiffRow]): Int = Diff.changeCount(rows)

def refreshDiff(s: State, props: Props): Unit = {
  s.diffRows := None
  s.diffNote := ""
  s.selected.v.filter(_ => s.view.v != "content").foreach { v =>
    {
      if (s.view.v == "current") {
        s.diffRows := Some(diff(s.selectedContent.v, props.getState().hcursor.downField("content").focus.getOrElse(Json.Null)))
        s.diffNote := s"この版（${Ui.date(v.createdAt)}）→ 現在"
      } else {
        val vs = s.versions.v
        vs.lift(vs.indexWhere(_.id == v.id) + 1) match {
          case None =>
            s.diffRows := Some(diff(Json.obj("type" -> "doc".asJson, "content" -> Json.arr()), s.selectedContent.v))
            s.diffNote := "最初の版です（すべて追加として表示）"
          case Some(prev) =>
            PageApi.version(props.pageId, prev.id).foreach { p =>
              s.diffRows := Some(diff(p.content, s.selectedContent.v))
              s.diffNote := s"ひとつ前の版（${Ui.date(prev.createdAt)}）→ この版"
            }
        }
      }
    }
  }
}

def setView(s: State, props: Props, v: String): Unit = {
  s.view := v
  refreshDiff(s, props)
}

def saveNow(s: State, props: Props): Unit =
  Dialog.prompt("版の名前（任意）", "", okLabel = "保存").foreach {
    case None => ()
    case Some(label) =>
      s.busy := true
      val state = props.getState().deepMerge(Json.obj("label" -> label.asJson))
      PageApi.saveVersion(props.pageId, state).flatMap(_ => load(s, props))
        .failed.map(e => s.error := e.getMessage)
        .onComplete(_ => s.busy := false)
  }

def restore(s: State, props: Props): Unit = s.selected.v.foreach { v =>
  Dialog.confirm(s"${Ui.date(v.createdAt)} の版に戻しますか？\n現在の内容は「復元前の状態」として履歴に残ります。", okLabel = "復元").foreach { ok =>
    if (ok) {
      s.busy := true
      PageApi.restoreVersion(props.pageId, v.id, props.getState()).flatMap { c =>
        props.applyContent(c.content)
        load(s, props).map(_ => props.onClose(()))
      }.failed.map(e => s.error := e.getMessage)
        .onComplete(_ => s.busy := false)
    }
  }
}

def segCls(s: State, v: String) = s.view.mapC(x => if (x == v) "on" else "")

def itemCls(s: State, v: Version) = s.selected.mapC(x => if (x.exists(_.id == v.id)) "on" else "")

def selectedDate(s: State) = s.selected.mapC(_.map(v => Ui.date(v.createdAt)).getOrElse(""))

def showPreview(s: State) = computed { s.selected.v.isDefined && s.view.v == "content" }

def showDiff(s: State) = computed { s.selected.v.isDefined && s.view.v != "content" }

def countText(s: State) = s.diffRows.mapC(_.fold("")(r => s"（変更 ${changeCount(r)} か所）"))

def noChange(s: State) = s.diffRows.mapC(_.exists(r => changeCount(r) == 0))

def rows(s: State) = s.diffRows.mapC(_.getOrElse(Nil).zipWithIndex)

def mark(kind: String): String = kind match {
  case "add" => "+"
  case "del" => "−"
  case _ => " "
}
</script>

<template>
  <aside class="history" aria-label="版履歴">
    <header>
      <strong>版履歴</strong> <HelpLink to="manage#版の履歴" />
      <span class="grow"></span>
      <button v-if="props.canEdit" :disabled="s.busy" title="今の内容を版として保存" @click="saveNow(s, props)">現在の版を保存</button>
      <button title="閉じる" @click="props.onClose(())">✕</button>
    </header>
    <p class="note">編集が続くと約10分ごとに自動で版が残ります。</p>
    <div class="body">
      <ul class="list">
        <li v-for="v in s.versions" :key="v.id" :class="itemCls(s, v)" @click="select(s, props, v)">
          <div class="when">{{ Ui.date(v.createdAt) }}</div>
          <div v-if="v.label.exists(_.nonEmpty)" class="label">{{ v.label.getOrElse("") }}</div>
          <div class="who">{{ if (v.authors.nonEmpty) v.authors.mkString("、") else v.createdBy.getOrElse("") }}</div>
        </li>
        <li v-if="s.versions.mapC(_.isEmpty)" class="muted">まだ版がありません</li>
      </ul>
      <div class="preview">
        <div v-if="s.selected.mapC(_.isDefined)" class="preview-head">
          <span>{{ selectedDate(s) }}</span>
          <div class="seg" role="tablist">
            <button :class='segCls(s, "content")' data-testid="hist-content" @click='setView(s, props, "content")'>内容</button>
            <button :class='segCls(s, "current")' data-testid="hist-diff-current" @click='setView(s, props, "current")'>現在との差分</button>
            <button :class='segCls(s, "prev")' data-testid="hist-diff-prev" @click='setView(s, props, "prev")'>前の版との差分</button>
          </div>
          <button v-if="props.canEdit" class="primary" :disabled="s.busy" @click="restore(s, props)">この版に戻す</button>
        </div>
        <div ref="s.previewEl" v-show="showPreview(s)" class="preview-doc"></div>
        <div v-if="showDiff(s)" class="diff" data-testid="diff">
          <p class="diff-note">{{ s.diffNote }}{{ countText(s) }}</p>
          <p v-if="noChange(s)" class="muted">違いはありません。</p>
          <template v-for="ri in rows(s)" :key="ri._2">
            <div v-if='ri._1.kind == "fold"' class="d-fold">⋯ 変更のない {{ ri._1.count.getOrElse(0) }} ブロック</div>
            <pre v-else-if='ri._1.kind == "change"' class="d-row d-change"><template v-for="p in ri._1.pieces.getOrElse(Nil)"><del v-if='p.kind == "del"'>{{ p.text }}</del><ins v-else-if='p.kind == "add"'>{{ p.text }}</ins><span v-else>{{ p.text }}</span></template></pre>
            <pre v-else :class='s"d-row d-${ri._1.kind}"'><span class="d-mark">{{ mark(ri._1.kind) }}</span>{{ ri._1.text.getOrElse("") }}</pre>
          </template>
        </div>
        <p v-if="s.selected.mapC(_.isEmpty)" class="muted">左の一覧から版を選ぶと内容を表示します。</p>
      </div>
    </div>
    <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
  </aside>
</template>

<style scoped>
.history { position: fixed; top: 44px; right: 0; bottom: 0; width: min(860px, 96vw); background: var(--bg); border-left: 1px solid var(--line); box-shadow: -10px 0 30px rgba(0,0,0,.15); z-index: 40; display: flex; flex-direction: column; }
header { display: flex; gap: .5rem; align-items: center; padding: .7rem 1rem; border-bottom: 1px solid var(--line); }
.grow { flex: 1; }
.note { margin: .4rem 1rem; }
.body { flex: 1; display: flex; min-height: 0; }
.list { width: 240px; flex: none; overflow: auto; list-style: none; margin: 0; padding: .3rem; border-right: 1px solid var(--line); }
.list li { padding: .45rem .6rem; border-radius: 6px; cursor: pointer; }
.list li:hover { background: var(--hover); }
.list li.on { background: var(--hover); outline: 1px solid var(--accent); }
.when { font-weight: 600; font-size: .9rem; }
.label { font-size: .8rem; color: var(--accent); }
.who { font-size: .78rem; color: var(--muted); }
.preview { flex: 1; overflow: auto; padding: .6rem 1.2rem; }
.preview-head { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: space-between; align-items: center; color: var(--muted); font-size: .85rem; margin-bottom: .5rem; position: sticky; top: 0; background: var(--bg); padding: .3rem 0; }
.seg { display: flex; gap: 2px; }
.seg button { font-size: .75rem; padding: 1px 8px; color: var(--muted); }
.seg button.on { color: var(--fg); border-color: var(--accent); background: var(--hover); }
.diff-note { font-size: .78rem; color: var(--muted); margin: 0 0 .5rem; }
.d-row { margin: 0 0 4px; padding: .3rem .6rem; border-radius: 6px; white-space: pre-wrap; word-break: break-word; font: inherit; font-size: .88rem; line-height: 1.6; }
.d-mark { display: inline-block; width: 1.2em; color: var(--muted); font-family: monospace; }
.d-same { color: var(--muted); }
.d-add { background: rgba(22,163,74,.14); }
.d-add .d-mark { color: #16a34a; }
.d-del { background: rgba(220,38,38,.12); text-decoration: line-through; text-decoration-color: rgba(220,38,38,.5); }
.d-del .d-mark { color: var(--danger); }
.d-change { background: rgba(234,179,8,.08); }
.d-change ins { background: rgba(22,163,74,.28); text-decoration: none; border-radius: 2px; }
.d-change del { background: rgba(220,38,38,.25); border-radius: 2px; }
.d-fold { font-size: .75rem; color: var(--muted); text-align: center; padding: .2rem; border-top: 1px dashed var(--line); border-bottom: 1px dashed var(--line); margin: 4px 0; }
.muted { font-size: .85em; }
.error { padding: 0 1rem; }
@media (max-width: 768px) {
  .history { width: 100vw; }
  .body { flex-direction: column; }
  .list { width: auto; max-height: 30vh; border-right: none; border-bottom: 1px solid var(--line); }
  .preview { padding: .6rem; }
}
</style>

<style>
/* 版のプレビュー（JS が描く本文. スコープ付きの CSS が届かないので、ここだけ全体に効かせる） */
.history .preview-doc .ProseMirror { min-height: 0; padding-bottom: 2rem; }
</style>
