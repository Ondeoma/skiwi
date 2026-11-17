<script lang="scala">
import org.scalajs.dom
import skiwi.web.Store
import skiwi.web.parts.HelpLink

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 開けないページ（存在しない・閲覧権限がない）. 閲覧権限を申請できる（ページの有無は申請者には分からない）.
case class Props(pid: String)

case class State(status: RV[Option[AccessStatus]] = RV(None),
                 role: RV[String] = RV("view"),
                 message: RV[String] = RV(""),
                 busy: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""),
                 editing: RV[Boolean] = RV(false))

def initState() = State()

def mounted(s: State, props: Props): Unit =
  PageApi.myRequest(props.pid).recover(_ => AccessStatus("none")).foreach { st =>
    s.status := Some(st)
    st.role.foreach(s.role := _)
  }

def submit(s: State, props: Props): Unit = {
  s.busy := true
  s.error := ""
  PageApi.requestAccess(props.pid, s.role.v, s.message.v.trim).onComplete {
    case Success(st) =>
      s.status := Some(st)
      s.editing := false
      s.busy := false
      if (st.status == "has_access") Store.reloadPages().recover(_ => ()).foreach(_ => dom.window.location.reload())
    case Failure(e) =>
      s.error := e.getMessage
      s.busy := false
  }
}

def pendingView(s: State) = computed { s.status.v.exists(_.status == "pending") && !s.editing.v }

def showForm(s: State) = computed { s.status.v.isDefined && !(s.status.v.exists(_.status == "pending") && !s.editing.v) }

def doneText(s: State) = s.status.mapC(st => s"✅ ${if (st.flatMap(_.role).contains("edit")) "編集" else "閲覧"}権限を申請しました。承認されると通知が届きます。")

def submitLabel(s: State) = s.status.mapC(st => if (st.exists(_.status == "pending")) "申請し直す" else "権限を申請する")
</script>

<template>
  <section class="access" data-testid="access-request">
    <h1>🔒 このページを表示できません <HelpLink to="sharing#閲覧権限の申請" /></h1>
    <p class="muted">ページが存在しないか、閲覧する権限がありません。必要な場合は、権限を申請できます（ページの所有者・編集者に通知されます）。</p>
    <div v-if="pendingView(s)" class="done" data-testid="access-status">
      {{ doneText(s) }}
      <button class="link" @click="s.editing := true">申請内容を変更</button>
    </div>
    <form v-if="showForm(s)" class="form" @submit.prevent="submit(s, props)">
      <div class="roles">
        <label><input type="radio" name="access-role" value="view" :checked='s.role.v == "view"' @change='s.role := "view"'> 閲覧</label>
        <label><input type="radio" name="access-role" value="edit" :checked='s.role.v == "edit"' data-testid="access-role-edit" @change='s.role := "edit"'> 編集</label>
      </div>
      <textarea v-model="s.message" rows="3" maxlength="500" placeholder="申請の理由（任意）。例: 〇〇の作業で手順を確認したいため" data-testid="access-message"></textarea>
      <div class="actions">
        <button v-if="s.editing" type="button" @click="s.editing := false">やめる</button>
        <button type="submit" class="primary" :disabled="s.busy" data-testid="access-submit">{{ submitLabel(s) }}</button>
      </div>
      <p v-if="s.error.mapC(_.nonEmpty)" class="error">{{ s.error }}</p>
    </form>
  </section>
</template>

<style scoped>
.access { max-width: 34rem; margin: 4rem auto; padding: 0 1.2rem; }
h1 { font-size: 1.35rem; }
.muted { font-size: .9rem; line-height: 1.6; }
.form { margin-top: 1rem; display: flex; flex-direction: column; gap: .6rem; }
.roles { display: flex; gap: 1.2rem; font-size: .9rem; }
.roles label { display: flex; gap: .3rem; align-items: center; }
textarea { font: inherit; padding: .5rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; resize: vertical; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; }
.primary:disabled { opacity: .6; }
.done { margin-top: 1rem; padding: .7rem .9rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); font-size: .9rem; }
button.link { border: none; background: none; color: var(--accent); padding: 0 .3rem; font-size: .85rem; }
.error { font-size: .85rem; }
</style>
