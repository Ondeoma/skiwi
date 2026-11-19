<script lang="scala">
import skiwi.web.AppNames
import skiwi.shared.*
import skiwi.web.{Dialog, Fmt, Nav}

import java.util.UUID
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 管理者: エージェント専用アカウント（skakapo などの AI エージェントが API キーで使う、ログインしないアカウント）の作成とキーの発行・失効。
case class State(rows: RV[Option[List[AgentDto]]] = RV(None),
                 error: RV[String] = RV(""),
                 newName: RV[String] = RV(""),
                 // キーを発行するフォームを開いているアカウント
                 keyFor: RV[Option[UUID]] = RV(None),
                 keyName: RV[String] = RV("skakapo"),
                 scope: RV[String] = RV("write"),
                 days: RV[String] = RV(""),
                 keyError: RV[String] = RV(""),
                 // 発行したキー（アカウント名, 平文のキー）。この画面でだけ表示する
                 created: RV[Option[(String, String)]] = RV(None))

def initState() = State()

def load(s: State): Unit = AdminApi.agents().onComplete {
  case Success(r) => s.rows := Some(r)
  case Failure(e) => s.error := e.getMessage
}

def mounted(s: State): Unit = load(s)

def rows(s: State): List[AgentDto] = s.rows.v.getOrElse(Nil)

def create(s: State): Unit = {
  s.error := ""
  val name = s.newName.v.trim
  if (name.isEmpty) s.error := "名前を入力してください"
  else AdminApi.createAgent(name).onComplete {
    case Success(_) =>
      s.newName := ""
      load(s)
    case Failure(e) => s.error := e.getMessage
  }
}

def rename(s: State, a: AgentDto): Unit =
  Dialog.prompt("アカウントの名前", a.name, okLabel = "変更").foreach {
    case Some(n) if n.trim.nonEmpty && n.trim != a.name =>
      AdminApi.updateAgent(a.id, UpdateAgentReq(name = Some(n.trim))).onComplete {
        case Success(_) => load(s)
        case Failure(e) => Dialog.alertError("変更できませんでした", e)
      }
    case _ => ()
  }

def toggle(s: State, a: AgentDto): Unit = {
  val ask =
    if (a.disabled) Dialog.confirm(s"「${a.name}」を有効に戻しますか？\n失効していないキーがまた使えるようになります。", okLabel = "有効にする")
    else Dialog.confirm(s"「${a.name}」を無効にしますか？\nこのアカウントのキーはすべて使えなくなります（有効に戻すとまた使えます）。", okLabel = "無効にする", danger = true)
  ask.foreach { ok =>
    if (ok) AdminApi.updateAgent(a.id, UpdateAgentReq(disabled = Some(!a.disabled))).onComplete {
      case Success(_) => load(s)
      case Failure(e) => Dialog.alertError("変更できませんでした", e)
    }
  }
}

def openKey(s: State, a: AgentDto): Unit = {
  s.keyError := ""
  s.keyFor := (if (s.keyFor.v.contains(a.id)) None else Some(a.id))
}

def issue(s: State, a: AgentDto): Unit = {
  s.keyError := ""
  val name = s.keyName.v.trim
  if (name.isEmpty) s.keyError := "キーの用途を入力してください"
  else AdminApi.createAgentKey(a.id, CreateTokenReq(name, s.scope.v, s.days.v.trim.toIntOption)).onComplete {
    case Success(r) =>
      s.created := Some((a.name, r.token))
      s.keyFor := None
      load(s)
    case Failure(e) => s.keyError := e.getMessage
  }
}

def revoke(s: State, a: AgentDto, k: AgentKeyDto): Unit =
  Dialog.confirm(s"「${a.name}」のキー「${k.name}」を失効させますか？\nこのキーを使っているエージェントは使えなくなります。", okLabel = "失効させる", danger = true)
    .foreach { ok =>
      if (ok) AdminApi.revokeAgentKey(a.id, k.id).onComplete {
        case Success(_) => load(s)
        case Failure(e) => Dialog.alertError("失効できませんでした", e)
      }
    }

def copy(s: State): Unit = s.created.v.foreach(c => org.scalajs.dom.window.navigator.clipboard.writeText(c._2))

def createdName(s: State): String = s.created.v.fold("")(_._1)
def createdKey(s: State): String = s.created.v.fold("")(_._2)

def scopeLabel(scope: String): String = if (scope == "read") "閲覧のみ" else "閲覧・編集"

def keyOpen(s: State, a: AgentDto): Boolean = s.keyFor.v.contains(a.id)
</script>

<template>
  <main class="wrap">
    <p><a href="/" @click='Nav.link(event, "/")'>← 戻る</a></p>
    <h1>🤖 エージェント専用アカウント</h1>
    <div class="about muted">
      <p>
        {{ AppNames.of("skakapo") }} などの外部の AI エージェントが {{ AppNames.self }} の MCP（<code>/mcp</code>）と REST API を使うための、ログインしないアカウントです。
        エージェントはここで発行した API キーで、<strong>このアカウント自身の権限</strong>で動きます（管理者の権限は持ちません）。
      </p>
      <p>
        作っただけでは何も見られません。読ませたい・書かせたいワークスペースやページの「共有」で、ふつうのユーザーと同じようにこのアカウントの名前を検索して権限（閲覧・編集）を与えてください。
        エージェントがこのアカウントで行った書き込み（ページの作成・編集・コメントなど）は、このアカウントの操作として記録されます。
      </p>
    </div>

    <section class="new">
      <input v-model="s.newName" maxlength="60" placeholder="アカウントの名前（例: skakapo エージェント）" data-testid="agent-name">
      <button class="primary" @click="create(s)">アカウントを作る</button>
    </section>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>

    <div v-if="s.created.v.isDefined" class="created" data-testid="new-agent-key">
      <p><strong>「{{ createdName(s) }}」の API キーを発行しました。</strong></p>
      <code>{{ createdKey(s) }}</code>
      <button @click="copy(s)">コピー</button>
      <button @click="s.created := None">閉じる</button>
      <p class="note">この画面を閉じると二度と表示されません。{{ AppNames.of("skakapo") }} の管理画面の「サービス」→ このサービス →「エージェント専用アカウント」に貼り付けてください。</p>
    </div>

    <p v-if="s.rows.v.isEmpty" class="muted">読み込み中…</p>
    <p v-else-if="rows(s).isEmpty" class="muted">エージェント専用アカウントはまだありません。</p>
    <section v-for="a in rows(s)" :key="a.id" class="agent" :class='if (a.disabled) "disabled" else ""'>
      <div class="head">
        <strong>🤖 {{ a.name }}</strong>
        <span v-if="a.disabled" class="badge">無効</span>
        <span class="muted small">作成 {{ Fmt.date(a.createdAt) }}</span>
        <span class="acts">
          <button @click="rename(s, a)">名前を変更</button>
          <button @click="toggle(s, a)">{{ if (a.disabled) "有効にする" else "無効にする" }}</button>
          <button class="primary" @click="openKey(s, a)">キーを発行</button>
        </span>
      </div>

      <div v-if="keyOpen(s, a)" class="keyform">
        <input v-model="s.keyName" maxlength="100" placeholder="キーの用途（例: skakapo）">
        <select v-model="s.scope">
          <option value="write">閲覧・編集</option>
          <option value="read">閲覧のみ</option>
        </select>
        <select v-model="s.days">
          <option value="">無期限</option>
          <option value="30">30日</option>
          <option value="90">90日</option>
          <option value="365">1年</option>
        </select>
        <button class="primary" @click="issue(s, a)">発行</button>
        <span v-if="s.keyError.v.nonEmpty" class="error">{{ s.keyError }}</span>
      </div>

      <table>
        <thead><tr><th>キーの用途</th><th>権限</th><th>発行日</th><th>有効期限</th><th>最終使用</th><th>状態</th><th></th></tr></thead>
        <tbody>
          <tr v-for="k in a.keys" :key="k.id" :class='if (k.revoked) "revoked" else ""'>
            <td>{{ k.name }}</td>
            <td>{{ scopeLabel(k.scope) }}</td>
            <td>{{ Fmt.date(k.createdAt) }}</td>
            <td>{{ k.expiresAt.fold("無期限")(Fmt.date) }}</td>
            <td>{{ k.lastUsedAt.fold("未使用")(Fmt.date) }}</td>
            <td>{{ if (k.revoked) "失効" else "有効" }}</td>
            <td><button v-if="!k.revoked" @click="revoke(s, a, k)">失効</button></td>
          </tr>
          <tr v-if="a.keys.isEmpty"><td colspan="7" class="muted">発行済みのキーはありません</td></tr>
        </tbody>
      </table>
    </section>
  </main>
</template>

<style scoped>
.wrap { max-width: 1000px; margin: 0 auto; padding: 1.5rem; }
h1 { font-size: 1.4rem; }
.about p { margin: .4rem 0; line-height: 1.6; }
.new, .keyform { display: flex; gap: .5rem; margin: 1rem 0; flex-wrap: wrap; align-items: center; }
.new input, .keyform input { flex: 1; min-width: 220px; padding: .3rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
select { font: inherit; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 6px; padding: .2rem .4rem; }
.primary { background: var(--accent); color: #fff; border-color: var(--accent); }
.created { border: 1px solid #16a34a; border-radius: 8px; padding: .8rem 1rem; margin: 1rem 0; background: rgba(22,163,74,.06); }
.created code { display: inline-block; padding: .2rem .5rem; background: var(--side); border-radius: 4px; word-break: break-all; margin-right: .5rem; }
.created .note { margin: .6rem 0 0; font-size: .85rem; }
.agent { border: 1px solid var(--line); border-radius: 8px; padding: .8rem 1rem; margin: 1rem 0; }
.agent.disabled { opacity: .7; }
.head { display: flex; gap: .6rem; align-items: center; flex-wrap: wrap; }
.acts { margin-left: auto; display: flex; gap: .4rem; flex-wrap: wrap; }
table { width: 100%; border-collapse: collapse; margin-top: .6rem; font-size: .88rem; }
th { text-align: left; font-size: .78rem; color: var(--muted); border-bottom: 1px solid var(--line); padding: .35rem .4rem; }
td { border-bottom: 1px solid var(--line); padding: .4rem; }
tr.revoked td { opacity: .55; }
.small { font-size: .78rem; }
.badge { font-size: .7rem; color: var(--danger); border: 1px solid var(--danger); border-radius: 4px; padding: 0 4px; }
.muted { color: var(--muted); }
</style>
