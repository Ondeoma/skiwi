<script lang="scala">
import org.scalajs.dom
import skiwi.web.{Backdrop, Dialog, Nav, Progress, Store}
import skiwi.web.content.Duplicate
import skiwi.web.model.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// ページの複製（子ページ・添付ファイル・ホワイトボードごと. 別のワークスペースへも）
case class Props(page: Page, onClose: Unit => Unit)

/** place: sibling（元のページのすぐ下・同じ階層）/ root（ワークスペースの直下） */
case class State(title: RV[String],
                 workspaceId: RV[String],
                 place: RV[String] = RV("sibling"),
                 includeChildren: RV[Boolean] = RV(true),
                 busy: RV[Boolean] = RV(false),
                 titleEl: ElementRef[dom.HTMLInputElement] = ElementRef())

def targets = Store.workspaces.v.filter(_.role == "edit")

def initState(p: Props) = {
  val ts = targets
  State(title = RV(s"${p.page.displayTitle} のコピー"),
    workspaceId = RV(if (ts.exists(_.id == p.page.workspaceId)) p.page.workspaceId else ts.headOption.map(_.id).getOrElse("")))
}

def mounted(s: State): Unit = s.titleEl.foreach { el => el.focus(); el.select() }

def sameWs(s: State, p: Props): Boolean = s.workspaceId.v == p.page.workspaceId

def descendants(p: Props): Int = {
  val kids = Store.pages.v.groupBy(_.parentId)
  def walk(id: String): Int = kids.getOrElse(Some(id), Nil).map(c => 1 + walk(c.id)).sum
  walk(p.page.id)
}

def run(s: State, p: Props): Unit = if (s.workspaceId.v.nonEmpty && !s.busy.v) {
  s.busy := true
  // parentId: None=元と同じ階層（同じワークスペースのとき）、Some(None)=ワークスペースの直下
  val parentId = if (sameWs(s, p) && s.place.v == "sibling") None else Some(None)
  Store.progress := Some(Progress("複製中", 0, 1))
  Duplicate.duplicatePage(p.page, s.workspaceId.v, parentId, s.includeChildren.v, s.title.v.trim,
    (done, total) => Store.progress := Some(Progress("複製中", done, total))).flatMap { r =>
    val wid = r.workspaceId
    val rootId = r.rootId
    val failed = r.failed
    val reload = if (Store.currentId.v.contains(wid)) Store.reloadPages() else scala.concurrent.Future.successful(())
    reload.flatMap { _ =>
      p.onClose(())
      Nav.go(s"/w/$wid/p/$rootId")
      if (failed > 0) Dialog.alert(s"$failed ページの本文を複製できませんでした（ページは作成済みです）。") else scala.concurrent.Future.successful(())
    }
  }.recoverWith { case e => Dialog.alertError("複製できませんでした", e) }.andThen { _ =>
    s.busy := false
    Store.progress := None
  }
}

def wsLabel(p: Props, w: Workspace): String = w.name + (if (w.id == p.page.workspaceId) "（このワークスペース）" else "")
</script>

<template>
  <div class="backdrop" @mousedown="Backdrop.down(event)" @mouseup="Backdrop.up(event)" @click="Backdrop.click(event)(props.onClose(()))">
    <form class="dialog" role="dialog" aria-label="ページを複製" data-testid="duplicate-dialog" @submit.prevent="run(s, props)">
      <h2>⧉ ページを複製</h2>
      <label class="field">
        <span>新しいページの名前</span>
        <input ref="s.titleEl" v-model="s.title" data-testid="duplicate-title">
      </label>
      <label class="field">
        <span>複製先のワークスペース</span>
        <select v-model="s.workspaceId" data-testid="duplicate-ws">
          <option v-for="w in targets" :key="w.id" :value="w.id" :selected="w.id == s.workspaceId.v">{{ wsLabel(props, w) }}</option>
        </select>
      </label>
      <div v-if="sameWs(s, props)" class="field">
        <span>置く場所</span>
        <label class="row"><input v-model="s.place" type="radio" value="sibling"> 元のページのすぐ下（同じ階層）</label>
        <label class="row"><input v-model="s.place" type="radio" value="root" data-testid="duplicate-root"> ワークスペースの直下（末尾）</label>
      </div>
      <p v-else class="note">複製先のワークスペースの直下（末尾）に置きます。</p>
      <label v-if="descendants(props) > 0" class="row">
        <input v-model="s.includeChildren" type="checkbox" data-testid="duplicate-children">
        子ページも複製する（{{ descendants(props) }}ページ）
      </label>
      <p class="note">
        本文・添付ファイル・ホワイトボード・ラベル・スタイル・個別の共有設定を複製します。一緒に複製したページへのリンクは複製したページに付け替えます。
        コメント・版の履歴・公開リンクは複製しません。
      </p>
      <p v-if="targets.isEmpty" class="error">複製先にできるワークスペース（編集権限のあるもの）がありません。</p>
      <div class="actions">
        <button type="button" @click="props.onClose(())">キャンセル</button>
        <button type="submit" class="primary" :disabled="s.busy.v || s.workspaceId.v.isEmpty" data-testid="duplicate-run">{{ if (s.busy.v) "複製中…" else "複製" }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 8vh; z-index: 50; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; width: min(480px, 92vw); max-height: 84vh; overflow: auto; padding: 1rem 1.2rem; box-shadow: 0 20px 50px rgba(0,0,0,.25); }
h2 { font-size: 1.05rem; margin: 0 0 .8rem; }
.field { display: flex; flex-direction: column; gap: .25rem; margin: .6rem 0; font-size: .88rem; }
.field > span { font-weight: 600; font-size: .82rem; color: var(--muted); }
.field input:not([type]), .field select { font: inherit; padding: .35rem .55rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.row { display: flex; gap: .45rem; align-items: center; margin: .2rem 0; font-size: .88rem; }
.note { margin: .5rem 0; }
.error { font-size: .85rem; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.primary:disabled { opacity: .6; }
</style>
