<script lang="scala">
import io.circe.Json
import io.circe.syntax.*
import org.scalajs.dom
import skiwi.web.*
import skiwi.web.parts.*

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 受信用の Webhook: URL に POST すると、データベースの項目・子ページ・コメントを作る（作った人の権限で）
case class Props(wid: String, name: String, onClose: Unit => Unit)

/** 受け取り先のページ */
case class Target(id: String, title: String)

case class State(hooks: RV[Option[List[InboundHook]]] = RV(None),
                 error: RV[String] = RV(""),
                 shown: RV[Option[(String, String)]] = RV(None),
                 copied: RV[Boolean] = RV(false),
                 adding: RV[Boolean] = RV(false),
                 fName: RV[String] = RV(""),
                 fMode: RV[String] = RV("row"),
                 fTarget: RV[Option[Target]] = RV(None))

def initState() = State()

def load(s: State, p: Props): Unit = DialogsApi.inboundHooks(p.wid).onComplete {
  case Success(h) => s.hooks := Some(h)
  case Failure(e) => s.error := e.getMessage
}

def mounted(s: State, props: Props): Unit = load(s, props)

val Modes: List[(String, String)] = List("row" -> "データベースに項目を追加", "child" -> "子ページを作る", "comment" -> "コメントを付ける")

val Mode: Map[String, String] = Modes.toMap

def targetIsDb(s: State): Boolean = s.fTarget.v.exists(t => Store.byId(t.id).exists(_.isDatabase))

def pickTarget(s: State, p: Props): Unit = Host.pickPage().foreach(_.foreach { pg =>
  if (pg.workspaceId != p.wid) s.error := "このワークスペースのページを選んでください"
  else s.fTarget := Some(Target(pg.id, pg.title))
})

def message(e: Throwable): String = e.getMessage match {
  case "対象がデータベースではありません" => "「データベースに項目を追加」には、データベースのページを選んでください"
  case "権限がありません" => "受け取り先のページの編集権限がありません"
  case m => m
}

def create(s: State, p: Props): Unit = s.fTarget.v match {
  case None => s.error := "受け取り先のページを選んでください"
  case Some(t) =>
    s.error := ""
    val name = Dlg.nonBlank(s.fName.v).getOrElse(Mode(s.fMode.v))
    DialogsApi.createInbound(p.wid, name, t.id, s.fMode.v).onComplete {
      case Success(h) =>
        h.url.foreach(u => s.shown := Some((h.id, u)))
        s.adding := false
        s.fName := ""
        s.fMode := "row"
        s.fTarget := None
        load(s, p)
      case Failure(e) => s.error := message(e)
    }
}

def rotate(s: State, h: InboundHook): Unit =
  Dialog.confirm(s"「${h.name}」のURLを作り直しますか？\n今のURLは使えなくなります。", okLabel = "作り直す", danger = true).foreach { ok =>
    if (ok) DialogsApi.rotateInbound(h.id).onComplete {
      case Success(r) => r.url.foreach(u => s.shown := Some((h.id, u)))
      case Failure(e) => s.error := message(e)
    }
  }

def toggle(s: State, p: Props, h: InboundHook): Unit =
  DialogsApi.updateInbound(h.id, !h.enabled).onComplete {
    case Success(_) => load(s, p)
    case Failure(e) => s.error := message(e)
  }

def remove(s: State, p: Props, h: InboundHook): Unit =
  Dialog.confirm(s"「${h.name}」を削除しますか？\nこのURLへの送信は受け付けなくなります。", okLabel = "削除", danger = true).foreach { ok =>
    if (ok) DialogsApi.removeInbound(h.id).onComplete {
      case Success(_) => load(s, p)
      case Failure(e) => s.error := message(e)
    }
  }

/** 送り方の例（curl） */
def sample(s: State, id: String, url: String): String = {
  val mode = s.hooks.v.getOrElse(Nil).find(_.id == id).map(_.mode)
  val body =
    if (mode.contains("comment")) Json.obj("text" -> "お問い合わせがありました".asJson)
    else if (mode.contains("row")) Json.obj("title" -> "A社 問い合わせ".asJson, "text" -> "本文（改行で段落）".asJson,
      "values" -> Json.obj("状態" -> "未着手".asJson, "期限" -> "2026-12-01".asJson))
    else Json.obj("title" -> "新しいページ".asJson, "text" -> "本文（改行で段落）".asJson)
  s"curl -X POST '$url' \\\n  -H 'Content-Type: application/json' \\\n  -d '${body.noSpaces}'"
}

/** 受け取り先のページを開く（ダイアログは閉じる） */
def open(p: Props, e: dom.Event, h: InboundHook): Unit = {
  val m = e.asInstanceOf[dom.MouseEvent]
  if (!(m.ctrlKey || m.metaKey || m.shiftKey || m.button != 0)) {
    e.preventDefault()
    p.onClose(())
    Nav.go(Nav.pageHref(p.wid, h.targetPageId))
  }
}

def shownList(s: State): List[(String, String)] = s.shown.v.toList

def title(p: Props): String = s"📥 受信用の Webhook — ${p.name}"
</script>

<template>
  <Modal label="受信用の Webhook" :title="title(props)" testid="inbound" :width="660" @close="props.onClose(())">
    <template #head><HelpLink to="automation#受信用の-webhook外からページを作る" /></template>
    <p class="note">URL に JSON を POST すると、データベースに項目を追加したり、子ページ・コメントを作ったりします。フォーム・監視ツール・問い合わせ窓口の通知を {{ AppNames.self }} に集められます。
      作った人の権限で動きます（その人が受け取り先を編集できなくなると止まります）。1分に60件まで。</p>
    <p v-if="s.error.v.nonEmpty" class="error" data-testid="inbound-error">{{ s.error }}</p>

    <div v-for="x in shownList(s)" :key="x._1" class="url" data-testid="inbound-url">
      <b>URL（この画面でしか表示しません。知っている人は誰でも送れるので、大切に扱ってください）</b>
      <code>{{ x._2 }}</code>
      <button @click="Dlg.copy(x._2, s.copied, flashOnError = true)">{{ if (s.copied.v) "コピーしました" else "URLをコピー" }}</button>
      <details><summary>送り方の例（curl）</summary><pre>{{ sample(s, x._1, x._2) }}</pre></details>
      <button class="link" @click="s.shown := None">閉じる</button>
    </div>

    <ul v-if="s.hooks.v.isDefined" class="list">
      <li v-if="s.hooks.v.exists(_.isEmpty) && !s.adding.v" class="muted">まだ設定がありません。</li>
      <li v-for="h in s.hooks.v.getOrElse(Nil)" :key="h.id" :class='List("hook", "off" -> !h.enabled)' data-testid="inbound-item">
        <div class="h-head">
          <label class="en"><input type="checkbox" :checked="h.enabled" @change="toggle(s, props, h)"></label>
          <b>{{ h.name }}</b><span class="tag">{{ Mode.getOrElse(h.mode, h.mode) }}</span>
          <span class="spacer"></span>
          <button class="link" data-testid="inbound-rotate" @click="rotate(s, h)">URLを作り直す</button>
          <button class="link danger" @click="remove(s, props, h)">削除</button>
        </div>
        <div class="muted small">
          受け取り先: <a :href="Nav.pageHref(props.wid, h.targetPageId)" @click="open(props, event, h)">{{ h.targetTitle.filter(_.nonEmpty).getOrElse("（見られないページ）") }}</a>
          ・ 作成: {{ h.createdBy.getOrElse("—") }}
          ・ {{ h.lastAt.fold("まだ受信していません")(at => s"最後の受信 ${Ui.date(at)}（${h.useCount}件）") }}
        </div>
      </li>
    </ul>

    <form v-if="s.adding.v" class="form" data-testid="inbound-form" @submit.prevent="create(s, props)">
      <div class="modes">
        <label v-for="m in Modes" :key="m._1"><input v-model="s.fMode" type="radio" :value="m._1" :data-testid='s"inbound-mode-${m._1}"'> {{ m._2 }}</label>
      </div>
      <div class="row">
        受け取り先: <b>{{ s.fTarget.v.fold("（未選択）")(t => Dlg.untitled(t.title)) }}</b>
        <button type="button" data-testid="inbound-target" @click="pickTarget(s, props)">ページを選ぶ</button>
        <span v-if='s.fMode.v == "row" && s.fTarget.v.isDefined && !targetIsDb(s)' class="error">データベースのページではありません</span>
      </div>
      <input v-model="s.fName" maxlength="100" placeholder="名前（例: 問い合わせフォーム）" data-testid="inbound-name">
      <p v-if='s.fMode.v == "row"' class="hint">送る内容: <code>{ "title": "…", "text": "本文", "values": { "列名": 値 } }</code>（選択肢は名前、日付は 2026-12-01、ユーザーはメールアドレスでも可）</p>
      <p v-else-if='s.fMode.v == "child"' class="hint">送る内容: <code>{ "title": "…", "text": "本文" }</code></p>
      <p v-else class="hint">送る内容: <code>{ "text": "コメント" }</code>（Slack 形式の通知もそのまま受け付けます）</p>
      <div class="f-acts">
        <button type="button" @click="s.adding := false">やめる</button>
        <button type="submit" class="primary" data-testid="inbound-save">作成</button>
      </div>
    </form>
    <button v-else class="add" data-testid="inbound-add" @click="s.adding := true">＋ 受信用の URL を作る</button>
  </Modal>
</template>

<style scoped>
.x { padding: 0 4px; }
.note, .hint { font-size: .78rem; color: var(--muted); line-height: 1.55; }
.url { font-size: .82rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin: .4rem 0; display: flex; flex-direction: column; gap: .35rem; align-items: flex-start; }
.url code { word-break: break-all; }
pre { font-size: .75rem; white-space: pre-wrap; word-break: break-all; background: var(--bg); padding: .4rem; border-radius: 6px; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.hook { border: 1px solid var(--line); border-radius: 8px; padding: .45rem .7rem; margin-bottom: .45rem; font-size: .85rem; }
.hook.off { opacity: .55; }
.h-head { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
.tag { font-size: .7rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; }
.spacer { flex: 1; }
.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .5rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; font-size: .85rem; }
.modes { display: flex; gap: 1rem; flex-wrap: wrap; }
.modes label { display: flex; align-items: center; gap: .25rem; }
.row { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
input:not([type=checkbox]):not([type=radio]) { font: inherit; padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.f-acts { display: flex; justify-content: flex-end; gap: .4rem; }
.add { margin-top: .3rem; }
.error { font-size: .82rem; }
</style>
