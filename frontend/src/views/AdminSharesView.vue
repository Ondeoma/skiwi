<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { shareApi, shareUrl, formatDate, type PublicShare } from '../api'
import { alertError, confirmDialog } from '../ui/dialog'

/** 管理者: ゲストに公開中のページの一覧（出しっぱなしの確認・停止）。 */
type Row = { pageId: string; title: string; icon: string | null; workspaceId: string; workspaceName: string; share: PublicShare }
const rows = ref<Row[] | null>(null)
const error = ref('')

async function load() {
  try { rows.value = await shareApi.adminList() } catch (e) { error.value = (e as Error).message }
}
onMounted(load)

async function stop(r: Row) {
  if (!(await confirmDialog(`「${r.title || '無題'}」の公開を停止しますか？\nこれまでのリンクは使えなくなります。`, { okLabel: '公開を停止', danger: true }))) return
  try { await shareApi.disable(r.pageId); await load() } catch (e) { await alertError('停止できませんでした', e) }
}
</script>

<template>
  <main class="wrap">
    <p><router-link to="/">← 戻る</router-link></p>
    <h1>🌐 ゲストに公開中のページ</h1>
    <p class="muted">リンクを知っていればログインなしで閲覧できるページです（子ページを含む設定のものは、その下も公開されています）。</p>
    <p v-if="error" class="error">{{ error }}</p>
    <p v-else-if="rows === null" class="muted">読み込み中…</p>
    <p v-else-if="!rows.length" class="muted">公開中のページはありません。</p>
    <table v-else data-testid="admin-shares">
      <thead><tr><th>ページ</th><th>ワークスペース</th><th>公開した人・日時</th><th>子ページ</th><th>公開期間</th><th>パスワード</th><th></th></tr></thead>
      <tr v-for="r in rows" :key="r.pageId" :class="{ expired: r.share.expired }">
        <td><router-link :to="`/w/${r.workspaceId}/p/${r.pageId}`">{{ r.icon ?? '📄' }} {{ r.title || '無題' }}</router-link></td>
        <td>{{ r.workspaceName }}</td>
        <td class="small">{{ r.share.createdBy ?? '（不明）' }}<br />{{ formatDate(r.share.createdAt) }}</td>
        <td>{{ r.share.includeChildren ? '含む' : '—' }}</td>
        <td class="small">
          <template v-if="r.share.startsAt">{{ formatDate(r.share.startsAt) }} から<br /></template>{{ r.share.expiresAt ? `${formatDate(r.share.expiresAt)} まで` : '無期限' }}
          <span v-if="r.share.expired" class="badge">期限切れ</span><span v-else-if="r.share.notStarted" class="badge">公開前</span>
          <span v-if="r.share.mode === 'pinned'" class="badge pin">版を固定</span><span v-if="r.share.indexable" class="badge pin">検索エンジン</span>
        </td>
        <td>{{ r.share.hasPassword ? '🔑 あり' : 'なし' }}</td>
        <td>
          <div class="acts">
            <a :href="shareUrl(r.share.slug ?? r.share.token)" target="_blank" rel="noopener">開く</a>
            <button @click="stop(r)">公開を停止</button>
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
