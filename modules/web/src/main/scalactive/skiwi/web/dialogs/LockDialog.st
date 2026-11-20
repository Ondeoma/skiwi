<script lang="scala">
import sbirds.web.Dom
import skiwi.web.*
import skiwi.web.model.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ページの保護: 保護中は、保護した人・所有者・ここで指定した人だけが編集できる（ほかの編集者は閲覧のみ）.
// onChanged: 保護した（true）・解除した（false）
case class Props(pageId: String, title: String, ownerName: Option[String], onClose: Unit => Unit, onChanged: Boolean => Unit = _ => ())

case class State(st: RV[Option[PageLockState]] = RV(None),
                 reason: RV[String] = RV(""),
                 editors: RV[List[LockEditor]] = RV(Nil),
                 error: RV[String] = RV(""),
                 busy: RV[Boolean] = RV(false),
                 q: RV[String] = RV(""),
                 found: RV[List[DirectoryEntry]] = RV(Nil),
                 search: DebouncedFn[() => Unit] = useDebounce[() => Unit](250)(_()),
                 latest: LatestFn = useLatest())

def initState() = State()

def mounted(s: State, props: Props): Unit =
  skiwi.web.page.PageApi.lock(props.pageId).onComplete {
    case Success(x) =>
      s.st := Some(x)
      s.reason := x.reason.getOrElse("")
      s.editors := x.editors
    case Failure(e) => s.error := e.getMessage
  }

// 編集できる人の追加（ユーザーのみ）
def onSearch(s: State, value: String): Unit =
  s.search(() => s.latest(DialogsApi.users(value)).foreach(s.found := _))

def addEditor(s: State, d: DirectoryEntry): Unit = {
  if (!s.editors.v.exists(_.id == d.id)) s.editors := s.editors.v :+ LockEditor(d.id, d.name)
  s.q := ""
  s.found := Nil
}

def save(s: State, p: Props): Unit = {
  s.busy := true
  s.error := ""
  DialogsApi.setLock(p.pageId, Dlg.nonBlank(s.reason.v), s.editors.v.map(_.id)).onComplete { r =>
    s.busy := false
    r match {
      case Success(x) =>
        s.st := Some(x)
        p.onChanged(true)
        p.onClose(())
      case Failure(e) => s.error := e.getMessage
    }
  }
}

def unlock(s: State, p: Props): Unit =
  Dialog.confirm(s"「${Dlg.untitled(p.title)}」の保護を解除しますか？\n編集権限のある人は、また編集できるようになります。", okLabel = "解除する").foreach { ok =>
    if (ok) {
      s.busy := true
      DialogsApi.unlock(p.pageId).onComplete { r =>
        s.busy := false
        r match {
          case Success(_) =>
            p.onChanged(false)
            p.onClose(())
          case Failure(e) => s.error := e.getMessage
        }
      }
    }
  }

def stOf(s: State): PageLockState = s.st.v.getOrElse(PageLockState(false, None, None, None, Nil, false, false))

def lockedText(x: PageLockState): String =
  s"${x.lockedAt.fold("")(Ui.date)} に${x.lockedBy.fold("")(n => s"${n}さんが")}保護しました。"

def ownerNote(p: Props): String = p.ownerName.fold("・所有者")(n => s"・所有者（$n）")
</script>

<template>
  <Modal label="ページの保護" title="🔒 ページの保護" testid="lock-dialog" :width="520" :max-height="80" :top="10" @close="props.onClose(())">
    <template #head><HelpLink to="sharing#ページの保護" /></template>
    <p class="note">保護すると、保護した人・所有者・下で指定した人だけが編集できます。ほかの編集者は閲覧のみになります（共有設定はそのまま）。規程や確定した手順書の誤編集を防げます。</p>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <template v-if="s.st.v.isDefined">
      <p v-if="stOf(s).locked" class="state" data-testid="lock-state">
        {{ lockedText(stOf(s)) }}
      </p>
      <fieldset :disabled="!stOf(s).canChange || s.busy.v">
        <label class="col">
          <span>理由（ページの上部に表示します）</span>
          <textarea v-model="s.reason" rows="2" maxlength="500" placeholder="例: 2026年度版として確定。変更は総務部まで" data-testid="lock-reason"></textarea>
        </label>
        <div class="col">
          <span>保護中も編集できる人<small>（保護した人{{ ownerNote(props) }}は常に編集できます）</small></span>
          <ul class="eds">
            <li v-for="e in s.editors" :key="e.id" data-testid="lock-editor">👤 {{ e.name }}<button class="x" title="外す" @click="s.editors := s.editors.v.filterNot(_.id == e.id)">×</button></li>
            <li v-if="s.editors.v.isEmpty" class="muted">（指定なし）</li>
          </ul>
          <input v-model="s.q" placeholder="名前・メールで検索して追加" data-testid="lock-editor-search" @input="onSearch(s, Dom.value(event))">
          <ul v-if="s.found.v.nonEmpty" class="found">
            <li v-for="d in s.found.v.take(8)" :key="d.id"><button data-testid="lock-editor-add" @click="addEditor(s, d)">👤 {{ d.name }} <small class="muted">{{ d.detail.getOrElse("") }}</small></button></li>
          </ul>
        </div>
      </fieldset>
      <p v-if="!stOf(s).canChange" class="muted">保護の設定・解除は、保護中も編集できる人と管理者だけができます。</p>
      <div class="actions">
        <button v-if="stOf(s).locked && stOf(s).canChange" class="danger" :disabled="s.busy.v" data-testid="lock-remove" @click="unlock(s, props)">保護を解除</button>
        <span class="spacer"></span>
        <button @click="props.onClose(())">閉じる</button>
        <button v-if="stOf(s).canChange" class="primary" :disabled="s.busy.v" data-testid="lock-save" @click="save(s, props)">{{ if (stOf(s).locked) "保存" else "保護する" }}</button>
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.x { padding: 0 4px; }
.note { line-height: 1.55; }
.state { font-size: .85rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .4rem .6rem; }
fieldset { border: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: .7rem; }
.col { display: flex; flex-direction: column; gap: .3rem; font-size: .85rem; }
.col small { color: var(--muted); font-size: .75rem; }
textarea, input { font: inherit; padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.eds { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: .3rem; }
.eds li { background: var(--hover); border-radius: 12px; padding: 1px 4px 1px 8px; font-size: .82rem; }
.found { list-style: none; margin: 0; padding: 0; border: 1px solid var(--line); border-radius: 6px; }
.found button { width: 100%; text-align: left; border: none; padding: .3rem .5rem; }
.found button:hover { background: var(--hover); }
.actions { display: flex; gap: .4rem; margin-top: 1rem; align-items: center; }
.spacer { flex: 1; }
.danger:hover { color: var(--danger); border-color: var(--danger); }
.muted { font-size: .8rem; }
.error { font-size: .85rem; }
</style>
