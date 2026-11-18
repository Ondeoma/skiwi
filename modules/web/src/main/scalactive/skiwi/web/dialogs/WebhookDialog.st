<script lang="scala">
import io.circe.Json
import io.circe.syntax.*
import skiwi.web.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// ワークスペースの出来事を外部（Slack・Teams・任意のURL）へ送る設定
case class Props(wid: String, name: String, onClose: Unit => Unit)

case class State(hooks: RV[Option[List[Webhook]]] = RV(None),
                 error: RV[String] = RV(""),
                 busy: RV[Boolean] = RV(false),
                 secretShown: RV[Option[String]] = RV(None),
                 testResult: RV[Map[String, String]] = RV(Map.empty),
                 // 追加の入力欄
                 adding: RV[Boolean] = RV(false),
                 fName: RV[String] = RV(""),
                 fUrl: RV[String] = RV(""),
                 fFormat: RV[String] = RV("slack"),
                 fEvents: RV[List[String]] = RV(Defaults))

val Defaults: List[String] = List("page.created", "page.updated", "comment.created", "page.published")

def initState() = State()

def load(s: State, p: Props): Unit = DialogsApi.webhooks(p.wid).onComplete {
  case Success(h) => s.hooks := Some(h)
  case Failure(e) => s.error := e.getMessage
}

def mounted(s: State, props: Props): Unit = load(s, props)

val Formats: List[(String, String)] = List("slack" -> "Slack", "teams" -> "Microsoft Teams", "generic" -> "JSON（任意のURL）")

val Format: Map[String, String] = Formats.toMap

val Hint: Map[String, String] = Map(
  "slack" -> "Slack の「Incoming Webhook」のURL（https://hooks.slack.com/services/…）を貼り付けてください。",
  "teams" -> "Teams のチャネルの「ワークフロー」で「Webhook 要求を受信したらチャネルに投稿する」を作り、そのURLを貼り付けてください。",
  "generic" -> "JSON を POST します。X-Skiwi-Signature ヘッダー（sha256=…）で、作成時に表示される秘密の値による署名を確認できます。")

def message(e: Throwable): String = {
  val m = e.getMessage
  if (m.startsWith("URL が正しくありません")) "URLが正しくありません（https:// で始まるURL）"
  else if (m == "イベントが正しくありません") "送る出来事を1つ以上選んでください"
  else m
}

def create(s: State, p: Props): Unit = {
  s.busy := true
  s.error := ""
  val name = Dlg.nonBlank(s.fName.v).getOrElse(Format(s.fFormat.v))
  DialogsApi.createWebhook(p.wid, name, s.fUrl.v.trim, s.fFormat.v, s.fEvents.v).onComplete { r =>
    s.busy := false
    r match {
      case Success(h) =>
        if (h.format == "generic") h.secret.foreach(x => s.secretShown := Some(x))
        s.adding := false
        s.fName := ""
        s.fUrl := ""
        s.fFormat := "slack"
        s.fEvents := Defaults
        load(s, p)
      case Failure(e) => s.error := message(e)
    }
  }
}

def update(s: State, p: Props, h: Webhook, fields: (String, Json)*): Unit = {
  s.error := ""
  DialogsApi.updateWebhook(h.id, Json.obj(fields*)).onComplete {
    case Success(_) => load(s, p)
    case Failure(e) => s.error := message(e)
  }
}

def toggleEvent(s: State, p: Props, h: Webhook, ev: String, on: Boolean): Unit = {
  val events = if (on) h.events :+ ev else h.events.filterNot(_ == ev)
  if (events.isEmpty) s.error := "送る出来事を1つ以上選んでください"
  else update(s, p, h, "events" -> events.asJson)
}

def remove(s: State, p: Props, h: Webhook): Unit =
  Dialog.confirm(s"「${h.name}」を削除しますか？", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) DialogsApi.removeWebhook(h.id).foreach(_ => load(s, p))
  }

def test(s: State, p: Props, h: Webhook): Unit = {
  def set(v: String): Unit = s.testResult := s.testResult.v + (h.id -> v)
  set("送信中…")
  DialogsApi.testWebhook(h.id).onComplete {
    case Success(r) =>
      set(if (r.ok) s"✅ 送信しました（HTTP ${r.status.fold("")(_.toString)}）"
          else s"⚠ 送れませんでした（${r.error.getOrElse(r.status.fold("null")(_.toString))}）")
      load(s, p)
    case Failure(e) => set(s"⚠ ${e.getMessage}")
  }
}

def last(h: Webhook): String = h.lastAt match {
  case Some(at) => s"最後の送信: ${Ui.date(at)} ${h.lastError.fold(s"✅ HTTP ${h.lastStatus.fold("null")(_.toString)}")(e => s"⚠ $e")}"
  case None => "まだ送信していません"
}

def title(p: Props): String = s"📣 外部への通知（Webhook）— ${p.name}"
</script>

<template>
  <Modal label="外部への通知" :title="title(props)" testid="webhooks" :max-height="82" @close="props.onClose(())">
    <template #head><HelpLink to="automation#外部への通知slackteamswebhook" /></template>
    <p class="note">このワークスペースのページの作成・更新・コメント・公開などを、Slack・Teams・任意のURLへ送ります。個別に権限を絞ったページ（共有設定で「親の権限を引き継がない」にしたページとその下）の出来事は送りません。</p>
    <p v-if="s.error.v.nonEmpty" class="error" data-testid="webhook-error">{{ s.error }}</p>

    <div v-if="s.secretShown.v.isDefined" class="secret" data-testid="webhook-secret">
      署名の確認に使う秘密の値（この画面でしか表示しません）:
      <code>{{ s.secretShown.v.getOrElse("") }}</code>
      <button class="link" @click="s.secretShown := None">閉じる</button>
    </div>

    <p v-if="s.hooks.v.isEmpty" class="muted">読み込み中…</p>
    <ul v-else class="list">
      <li v-if="s.hooks.v.exists(_.isEmpty) && !s.adding.v" class="muted">まだ設定がありません。</li>
      <li v-for="h in s.hooks.v.getOrElse(Nil)" :key="h.id" :class='List("hook", "off" -> !h.enabled)' data-testid="webhook-item">
        <div class="h-head">
          <b>{{ h.name }}</b><span class="fmt">{{ Format.getOrElse(h.format, h.format) }}</span>
          <label class="en"><input type="checkbox" :checked="h.enabled" data-testid="webhook-enabled" @change='update(s, props, h, "enabled" -> Dlg.checked(event).asJson)'> 有効</label>
        </div>
        <div class="url">{{ h.url }}</div>
        <div class="events">
          <label v-for="ev in DialogsApi.WebhookEvents" :key="ev._1"><input type="checkbox" :checked="h.events.contains(ev._1)" @change="toggleEvent(s, props, h, ev._1, Dlg.checked(event))"> {{ ev._2 }}</label>
        </div>
        <div class="h-foot">
          <span class="muted small">{{ last(h) }}</span>
          <span class="acts">
            <button data-testid="webhook-test" @click="test(s, props, h)">テスト送信</button>
            <button class="danger" data-testid="webhook-delete" @click="remove(s, props, h)">削除</button>
          </span>
        </div>
        <div v-if="s.testResult.v.contains(h.id)" class="small" data-testid="webhook-test-result">{{ s.testResult.v.getOrElse(h.id, "") }}</div>
      </li>
    </ul>

    <form v-if="s.adding.v" class="form" data-testid="webhook-form" @submit.prevent="create(s, props)">
      <div class="formats">
        <label v-for="f in Formats" :key="f._1"><input v-model="s.fFormat" type="radio" :value="f._1" :data-testid='s"webhook-format-${f._1}"'> {{ f._2 }}</label>
      </div>
      <p class="hint">{{ Hint.getOrElse(s.fFormat.v, "") }}</p>
      <input v-model="s.fUrl" type="url" placeholder="https://…" required data-testid="webhook-url">
      <input v-model="s.fName" placeholder="名前（例: #skiwi-更新 チャンネル）" maxlength="100" data-testid="webhook-name">
      <div class="events">
        <label v-for="ev in DialogsApi.WebhookEvents" :key="ev._1"><input v-model="s.fEvents" type="checkbox" :value="ev._1"> {{ ev._2 }}</label>
      </div>
      <div class="f-acts">
        <button type="button" @click="s.adding := false">やめる</button>
        <button type="submit" class="primary" :disabled="s.busy.v || s.fUrl.v.trim.isEmpty" data-testid="webhook-save">追加</button>
      </div>
    </form>
    <button v-else class="add" data-testid="webhook-add" @click="s.adding := true">＋ 送り先を追加</button>
  </Modal>
</template>

<style scoped>
.note, .hint { font-size: .78rem; color: var(--muted); line-height: 1.55; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.hook { border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin-bottom: .5rem; font-size: .85rem; }
.hook.off { opacity: .6; }
.h-head { display: flex; align-items: center; gap: .5rem; }
.fmt { font-size: .7rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; }
.en { margin-left: auto; font-size: .8rem; display: flex; align-items: center; gap: .25rem; }
.url { font-size: .75rem; color: var(--muted); word-break: break-all; margin: .15rem 0 .3rem; }
.events { display: flex; flex-wrap: wrap; gap: .2rem .8rem; font-size: .8rem; }
.events label, .formats label { display: flex; align-items: center; gap: .25rem; }
.h-foot { display: flex; justify-content: space-between; align-items: center; gap: .5rem; margin-top: .4rem; flex-wrap: wrap; }
.acts { display: flex; gap: .3rem; }
.acts button { font-size: .78rem; padding: 1px 8px; }
.danger:hover { color: var(--danger); border-color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .45rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; }
.formats { display: flex; gap: 1rem; font-size: .85rem; flex-wrap: wrap; }
.form input:not([type=checkbox]):not([type=radio]) { font: inherit; padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.f-acts { display: flex; justify-content: flex-end; gap: .4rem; }
.add { margin-top: .3rem; }
.secret { font-size: .8rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin: .4rem 0; }
.secret code { display: block; word-break: break-all; margin: .3rem 0; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .78rem; }
.error { font-size: .85rem; }
</style>
