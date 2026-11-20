<script lang="scala">
import skiwi.shared.Titles
import skiwi.web.Nav
import skiwi.web.{Dialog, Ui}

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.{Failure, Success}

// 管理者: ゲストに公開中のページの一覧（出しっぱなしの確認・停止）。
case class State(rows: AsyncState[List[AdminShare]])

def initState() = State(useAsync(AdminApi.shares()))

def title(r: AdminShare): String = Titles.display(r.title)

def stop(s: State, r: AdminShare): Unit =
  Dialog.confirm(s"「${title(r)}」の公開を停止しますか？\nこれまでのリンクは使えなくなります。", okLabel = "公開を停止", danger = true).foreach { ok =>
    if (ok) skiwi.web.dialogs.DialogsApi.disableShare(r.pageId).onComplete {
      case Success(_) => s.rows.reload()
      case Failure(e) => Dialog.alertError("停止できませんでした", e)
    }
  }

def period(r: AdminShare): String = r.share.expiresAt.fold("無期限")(e => s"${Ui.date(e)} まで")

def rows(s: State): List[AdminShare] = s.rows.getOrElse(Nil)
</script>

<template>
  <main class="wrap">
    <p><a href="/" @click='Nav.link(event, "/")'>← 戻る</a></p>
    <h1>🌐 ゲストに公開中のページ</h1>
    <p class="muted">リンクを知っていればログインなしで閲覧できるページです（子ページを含む設定のものは、その下も公開されています）。</p>
    <p v-if="s.rows.failed" class="error">{{ s.rows.errorMessage }}</p>
    <p v-else-if="!s.rows.ready.v" class="muted">読み込み中…</p>
    <p v-else-if="rows(s).isEmpty" class="muted">公開中のページはありません。</p>
    <table v-else data-testid="admin-shares">
      <thead><tr><th>ページ</th><th>ワークスペース</th><th>公開した人・日時</th><th>子ページ</th><th>公開期間</th><th>パスワード</th><th></th></tr></thead>
      <tr v-for="r in rows(s)" :key="r.pageId" :class='if (r.share.expired) "expired" else ""'>
        <td><a :href='"/w/" + r.workspaceId + "/p/" + r.pageId' @click='Nav.link(event, "/w/" + r.workspaceId + "/p/" + r.pageId)'>{{ r.icon.getOrElse("📄") }} {{ title(r) }}</a></td>
        <td>{{ r.workspaceName }}</td>
        <td class="small">{{ r.share.createdBy.getOrElse("（不明）") }}<br>{{ Ui.date(r.share.createdAt) }}</td>
        <td>{{ if (r.share.includeChildren) "含む" else "—" }}</td>
        <td class="small">
          <template v-if="r.share.startsAt.isDefined">{{ r.share.startsAt.fold("")(Ui.date) }} から<br></template>{{ period(r) }}
          <span v-if="r.share.expired" class="badge">期限切れ</span><span v-else-if="r.share.notStarted" class="badge">公開前</span>
          <span v-if='r.share.mode == "pinned"' class="badge pin">版を固定</span><span v-if="r.share.indexable" class="badge pin">検索エンジン</span>
        </td>
        <td>{{ if (r.share.hasPassword) "🔑 あり" else "なし" }}</td>
        <td>
          <div class="acts">
            <a :href="AdminApi.shareUrl(r.share.slug.getOrElse(r.share.token))" target="_blank" rel="noopener">開く</a>
            <button @click="stop(s, r)">公開を停止</button>
          </div>
        </td>
      </tr>
    </table>
  </main>
</template>

<style scoped>
.wrap { max-width: 1100px; margin: 0 auto; padding: 1.5rem; }
h1 { font-size: 1.4rem; }
table { width: 100%; border-collapse: collapse; font-size: .9rem; }
th { text-align: left; font-size: .78rem; color: var(--muted); border-bottom: 1px solid var(--line); padding: .4rem; }
td { border-bottom: 1px solid var(--line); padding: .45rem .4rem; vertical-align: middle; }
td a { color: var(--accent); text-decoration: none; }
.small { font-size: .78rem; }
.acts { white-space: nowrap; display: flex; gap: .6rem; align-items: center; }
tr.expired td { opacity: .6; }
.badge { margin-left: .3rem; font-size: .7rem; color: var(--danger); border: 1px solid var(--danger); border-radius: 4px; padding: 0 4px; }
.badge.pin { color: var(--muted); border-color: var(--line); }
</style>
