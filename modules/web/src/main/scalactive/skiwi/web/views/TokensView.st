<script lang="scala">
import skiwi.shared.*
import skiwi.web.{Api, Dialog, Fmt}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// APIトークンの発行・失効（本人のログインセッションでのみ使える）
case class State(tokens: RV[List[TokenDto]] = RV(Nil),
                 name: RV[String] = RV(""),
                 scope: RV[String] = RV("read"),
                 days: RV[String] = RV("90"),
                 created: RV[Option[String]] = RV(None),
                 error: RV[String] = RV(""))

def initState() = State()

def load(s: State): Unit = Api.tokens().foreach(s.tokens := _)

def mounted(s: State): Unit = load(s)

def create(s: State): Unit = {
  s.error := ""
  val name = s.name.v.trim
  if (name.isEmpty) s.error := "名前を入力してください"
  else Api.createToken(CreateTokenReq(name, s.scope.v, s.days.v.toIntOption)).onComplete {
    case Success(r) =>
      s.created := Some(r.token)
      s.name := ""
      load(s)
    case Failure(e) => s.error := e.getMessage
  }
}

def revoke(s: State, t: TokenDto): Unit = {
  Dialog.confirm(s"トークン「${t.name}」を失効させますか？\nこのトークンを使っているプログラムは使えなくなります。", okLabel = "失効させる", danger = true)
    .foreach(ok => if (ok) Api.revokeToken(t.id).foreach(_ => load(s)))
}

def copy(s: State): Unit = s.created.v.foreach(org.scalajs.dom.window.navigator.clipboard.writeText(_))

def scopeLabel(scope: String): String = if (scope == "read") "閲覧のみ" else "閲覧・編集"
</script>

<template>
  <div class="wrap">
    <p><RouterLink to="/">← 戻る</RouterLink></p>
    <h1>APIトークン</h1>
    <p class="muted">使い方は <RouterLink to="/settings/api">API の説明書</RouterLink> を見てください（その場で試せます）。</p>
    <p class="muted">
      プログラムから skiwi の APIを使うためのトークンです。<code>Authorization: Bearer &lt;トークン&gt;</code> で送ります。
      権限はあなた自身の権限に準じます。「閲覧のみ」は読み取り（GET）だけができます。
    </p>

    <section class="new">
      <input v-model="s.name" placeholder="用途（例: バックアップスクリプト）">
      <select v-model="s.scope">
        <option value="read">閲覧のみ</option>
        <option value="write">閲覧・編集</option>
      </select>
      <select v-model="s.days">
        <option value="30">30日</option>
        <option value="90">90日</option>
        <option value="365">1年</option>
        <option value="">無期限</option>
      </select>
      <button class="primary" @click="create(s)">発行</button>
    </section>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>

    <div v-if="s.created.v.isDefined" class="created" data-testid="new-token">
      <p><strong>トークンを発行しました。</strong>この画面を離れると二度と表示できません。安全な場所に保存してください。</p>
      <code>{{ s.created.v.getOrElse("") }}</code>
      <button @click="copy(s)">コピー</button>
      <button @click="s.created := None">閉じる</button>
    </div>

    <table>
      <thead><tr><th>用途</th><th>権限</th><th>発行日</th><th>有効期限</th><th>最終使用</th><th></th></tr></thead>
      <tbody>
        <tr v-for="t in s.tokens" :key="t.id">
          <td>{{ t.name }}</td>
          <td>{{ scopeLabel(t.scope) }}</td>
          <td>{{ Fmt.date(t.createdAt) }}</td>
          <td>{{ t.expiresAt.fold("無期限")(Fmt.date) }}</td>
          <td>{{ t.lastUsedAt.fold("未使用")(Fmt.date) }}</td>
          <td><button @click="revoke(s, t)">失効</button></td>
        </tr>
        <tr v-if="s.tokens.v.isEmpty"><td colspan="6" class="muted">発行済みのトークンはありません</td></tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.wrap { max-width: 900px; margin: 0 auto; padding: 2rem; }
h1 { font-size: 1.6rem; }
.new { display: flex; gap: .5rem; margin: 1rem 0; flex-wrap: wrap; }
.new input { flex: 1; min-width: 220px; padding: .3rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); }
select { font: inherit; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 6px; padding: .2rem .4rem; }
.primary { background: var(--accent); color: #fff; border-color: var(--accent); }
.created { border: 1px solid #16a34a; border-radius: 8px; padding: .8rem 1rem; margin: 1rem 0; background: rgba(22,163,74,.06); }
.created code { display: inline-block; padding: .2rem .5rem; background: var(--side); border-radius: 4px; word-break: break-all; margin-right: .5rem; }
table { width: 100%; border-collapse: collapse; margin-top: 1rem; font-size: .9rem; }
th, td { text-align: left; padding: .4rem .5rem; border-bottom: 1px solid var(--line); }
.muted { color: var(--muted); }
</style>
