<script lang="scala">
import skiwi.shared.Titles
import skiwi.web.{Backdrop, Dialog, Nav, Store, Ui}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js

// ゴミ箱. 削除したページ（このワークスペース）と、削除したワークスペースを戻す・完全に削除する.
case class Props(wid: Option[String], onClose: Unit => Unit)

case class State(tab: RV[String],
                 pages: RV[List[TrashedPage]] = RV(Nil),
                 workspaces: RV[List[TrashedWorkspace]] = RV(Nil),
                 loading: RV[Boolean] = RV(true),
                 busy: RV[Boolean] = RV(false))

def initState(p: Props) = State(tab = RV(if (p.wid.isDefined) "pages" else "workspaces"))

def load(s: State, p: Props): Future[Unit] = {
  s.loading := true
  p.wid.fold(Future.successful(List.empty[TrashedPage]))(WorkspaceApi.trash).zip(WorkspaceApi.trashedWorkspaces()).map { case (ps, ws) =>
    s.pages := ps
    s.workspaces := ws
  }.recoverWith { case e => Dialog.alertError("ゴミ箱を読み込めませんでした", e) }.andThen(_ => s.loading := false)
}

def mounted(s: State, props: Props): Unit = load(s, props)

def guard(s: State)(f: => Future[Any]): Future[Unit] = Dialog.guarded(Some(s.busy))(f)

def restorePage(s: State, p: Props, t: TrashedPage): Unit = guard(s) {
  for {
    r <- WorkspaceApi.restorePage(t.id)
    _ <- Store.reloadPages()
  } yield {
    p.onClose(())
    Nav.go(s"/w/${r.workspaceId}/p/${r.id}")
  }
}

def purgePage(s: State, p: Props, t: TrashedPage): Unit = {
  val extra = if (t.count > 1) s"（子ページ ${t.count - 1} 件を含む）" else ""
  Dialog.confirm(s"「${Titles.display(t.title)}」${extra}を完全に削除しますか？\n元に戻せません。添付ファイルも削除されます。", okLabel = "完全に削除", danger = true)
    .foreach(ok => if (ok) guard(s)(WorkspaceApi.purgePage(t.id).flatMap(_ => load(s, p))))
}

def restoreWs(s: State, p: Props, w: TrashedWorkspace): Unit = guard(s) {
  for {
    _ <- WorkspaceApi.restoreWorkspace(w.id)
    _ <- Store.reloadWorkspaces()
  } yield {
    p.onClose(())
    Nav.go(s"/w/${w.id}")
  }
}

def purgeWs(s: State, p: Props, w: TrashedWorkspace): Unit =
  Dialog.confirm(s"ワークスペース「${w.name}」と中のすべてのページを完全に削除しますか？\n元に戻せません。", okLabel = "完全に削除", danger = true)
    .foreach(ok => if (ok) guard(s)(WorkspaceApi.purgeWorkspace(w.id).flatMap(_ => load(s, p))))

def day(iso: String): String = new js.Date(iso).asInstanceOf[js.Dynamic].toLocaleDateString("ja-JP").asInstanceOf[String]

def tabClass(s: State, t: String): String = if (s.tab.v == t) "on" else ""

def onKey(p: Props, e: org.scalajs.dom.Event): Unit = if (e.asInstanceOf[org.scalajs.dom.KeyboardEvent].key == "Escape") p.onClose(())
</script>

<template>
  <div class="backdrop" @mousedown="Backdrop.down(event)" @mouseup="Backdrop.up(event)" @click="Backdrop.click(event)(props.onClose(()))" @keydown="onKey(props, event)">
    <div class="dialog" role="dialog" aria-label="ゴミ箱" data-testid="trash">
      <header>
        <h2>🗑 ゴミ箱</h2>
        <div class="tabs" role="tablist">
          <button v-if="props.wid.isDefined" role="tab" :class='tabClass(s, "pages")' @click='s.tab := "pages"'>このワークスペースのページ（{{ s.pages.v.size }}）</button>
          <button role="tab" :class='tabClass(s, "workspaces")' data-testid="trash-ws-tab" @click='s.tab := "workspaces"'>削除したワークスペース（{{ s.workspaces.v.size }}）</button>
        </div>
        <button class="close" title="閉じる" @click="props.onClose(())">✕</button>
      </header>
      <p class="note">削除したものは、ここに一定期間残ってから自動で完全に削除されます。</p>
      <div class="body">
        <p v-if="s.loading.v" class="muted">読み込み中…</p>
        <template v-else-if='s.tab.v == "pages"'>
          <p v-if="s.pages.v.isEmpty" class="muted">ゴミ箱は空です。</p>
          <table v-else>
            <tr v-for="t in s.pages.v" :key="t.id" data-testid="trash-item">
              <td class="name">{{ t.icon.getOrElse("📄") }} {{ Titles.display(t.title) }}<span v-if="t.count > 1" class="muted">（子ページ {{ t.count - 1 }} 件）</span></td>
              <td class="muted small">{{ Ui.date(t.deletedAt) }} {{ t.deletedBy.getOrElse("") }} が削除<br>{{ day(t.purgeAt) }} に完全に削除</td>
              <td class="acts">
                <button :disabled="s.busy.v" data-testid="trash-restore" @click="restorePage(s, props, t)">↩ 戻す</button>
                <button :disabled="s.busy.v" class="danger" @click="purgePage(s, props, t)">完全に削除</button>
              </td>
            </tr>
          </table>
        </template>
        <template v-else>
          <p v-if="s.workspaces.v.isEmpty" class="muted">削除したワークスペースはありません。</p>
          <table v-else>
            <tr v-for="w in s.workspaces.v" :key="w.id" data-testid="trash-ws-item">
              <td class="name">🗂 {{ w.name }}</td>
              <td class="muted small">{{ Ui.date(w.deletedAt) }} {{ w.deletedBy.getOrElse("") }} が削除<br>{{ day(w.purgeAt) }} に完全に削除</td>
              <td class="acts">
                <button :disabled="s.busy.v" data-testid="trash-ws-restore" @click="restoreWs(s, props, w)">↩ 戻す</button>
                <button :disabled="s.busy.v" class="danger" @click="purgeWs(s, props, w)">完全に削除</button>
              </td>
            </tr>
          </table>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-start; justify-content: center; padding: 10vh 16px 16px; z-index: 60; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(760px, 100%); max-height: 75vh; display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.3); }
header { display: flex; align-items: center; gap: .8rem; padding: .8rem 1rem; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
h2 { font-size: 1.05rem; margin: 0; }
.tabs { display: flex; gap: 2px; flex: 1; }
.tabs button { font-size: .8rem; border: 1px solid var(--line); color: var(--muted); }
.tabs button.on { color: var(--fg); border-color: var(--accent); background: var(--hover); }
.close { border: none; color: var(--muted); }
.note { margin: .5rem 1rem 0; }
.body { overflow: auto; padding: .5rem 1rem 1rem; }
table { width: 100%; border-collapse: collapse; }
td { padding: .45rem .3rem; border-bottom: 1px solid var(--line); vertical-align: middle; }
.name { font-weight: 600; }
.small { white-space: nowrap; }
.acts { white-space: nowrap; text-align: right; }
.acts button { font-size: .8rem; margin-left: .3rem; }
.danger:hover { color: var(--danger); border-color: var(--danger); }
.muted { font-weight: 400; }
</style>
