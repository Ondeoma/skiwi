<script lang="scala">
import skiwi.shared.*
import skiwi.web.{Api, Fmt}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 監査ログ（管理者のみ）. 新しい順. 閲覧操作は記録していない.
case class State(entries: RV[List[AuditDto]] = RV(Nil),
                 action: RV[String] = RV(""),
                 done: RV[Boolean] = RV(false),
                 error: RV[String] = RV(""))

def initState() = State()

val PageSize = 100

val labels: Map[String, String] = Map(
  "auth.login" -> "ログイン", "auth.logout" -> "ログアウト", "auth.login.denied" -> "ログイン拒否",
  "workspace.create" -> "ワークスペース作成", "workspace.acl" -> "ワークスペース共有設定",
  "page.create" -> "ページ作成", "page.update" -> "ページ名変更", "page.move" -> "ページ移動", "page.delete" -> "ページ削除（ゴミ箱へ）",
  "page.acl" -> "ページ共有設定", "page.version.create" -> "版の保存", "page.restore" -> "版の復元",
  "attachment.upload" -> "添付アップロード", "token.create" -> "APIトークン発行", "token.revoke" -> "APIトークン失効",
  "scim.user.create" -> "SCIM: ユーザー作成", "scim.user.update" -> "SCIM: ユーザー更新", "scim.user.deactivate" -> "SCIM: ユーザー無効化",
  "scim.user.delete" -> "SCIM: ユーザー削除", "scim.group.create" -> "SCIM: グループ作成", "scim.group.replace" -> "SCIM: グループ更新",
  "scim.group.patch" -> "SCIM: グループ変更", "scim.group.delete" -> "SCIM: グループ削除",
  "workspace.rename" -> "ワークスペース名変更", "workspace.delete" -> "ワークスペース削除（ゴミ箱へ）", "workspace.restore" -> "ワークスペース復元",
  "workspace.purge" -> "ワークスペース完全削除", "page.restore_from_trash" -> "ページ復元（ゴミ箱から）", "page.purge" -> "ページ完全削除",
  "page.labels" -> "ラベル変更", "page.public.enable" -> "ゲスト公開", "page.public.update" -> "ゲスト公開の設定変更", "page.public.disable" -> "ゲスト公開停止"
)

val viaLabels: Map[String, String] = Map("session" -> "画面", "token" -> "API", "scim" -> "SCIM", "system" -> "システム")

def load(s: State, reset: Boolean): Unit = {
  s.error := ""
  val before = if (reset) None else s.entries.v.lastOption.map(_.id)
  Api.audit(before, Some(s.action.v).filter(_.nonEmpty), PageSize).onComplete {
    case Success(page) =>
      s.entries := (if (reset) page else s.entries.v ++ page)
      s.done := page.size < PageSize
    case Failure(e) => s.error := e.getMessage
  }
}

def mounted(s: State): Unit = load(s, reset = true)

def target(e: AuditDto): String = e.targetType.getOrElse("") + " " + e.targetId.map(_.take(8)).getOrElse("")

def detail(e: AuditDto): String = if (e.detail.asObject.exists(_.nonEmpty)) e.detail.noSpaces else ""
</script>

<template>
  <div class="wrap">
    <p><Link to="/">← 戻る</Link></p>
    <h1>監査ログ</h1>
    <div class="filters">
      <select v-model="s.action" @change="load(s, reset = true)">
        <option value="">すべて</option>
        <option value="auth.">認証</option>
        <option value="workspace.">ワークスペース</option>
        <option value="page.">ページ</option>
        <option value="attachment.">添付</option>
        <option value="token.">APIトークン</option>
        <option value="scim.">SCIM</option>
      </select>
    </div>
    <p v-if="s.error.v.nonEmpty" class="error">{{ s.error }}</p>
    <table>
      <thead><tr><th>日時</th><th>操作者</th><th>経路</th><th>操作</th><th>対象</th><th>詳細</th></tr></thead>
      <tbody>
        <tr v-for="e in s.entries" :key="e.id">
          <td class="nowrap">{{ Fmt.date(e.at) }}</td>
          <td>{{ e.actorName.getOrElse("—") }}</td>
          <td>{{ viaLabels.getOrElse(e.via, e.via) }}</td>
          <td>{{ labels.getOrElse(e.action, e.action) }}</td>
          <td class="mono">{{ target(e) }}</td>
          <td class="mono detail">{{ detail(e) }}</td>
        </tr>
      </tbody>
    </table>
    <button v-if="!s.done.v && s.entries.v.nonEmpty" class="more" @click="load(s, reset = false)">さらに読み込む</button>
  </div>
</template>

<style scoped>
.wrap { max-width: 1200px; margin: 0 auto; padding: 2rem; }
h1 { font-size: 1.6rem; }
.filters { margin: .5rem 0 1rem; }
select { font: inherit; background: var(--bg); color: inherit; border: 1px solid var(--line); border-radius: 6px; padding: .2rem .4rem; }
table { width: 100%; border-collapse: collapse; font-size: .85rem; }
th, td { text-align: left; padding: .35rem .5rem; border-bottom: 1px solid var(--line); vertical-align: top; }
.nowrap { white-space: nowrap; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .78rem; }
.detail { max-width: 420px; word-break: break-all; color: var(--muted); }
.more { margin: 1rem 0; }
.error { color: #dc2626; }
</style>
