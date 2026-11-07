<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import HelpLink from '../ui/HelpLink.vue'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { backupApi, formatDate, type Backup, type BackupConfig } from '../api'
import { confirmDialog } from '../ui/dialog'

/** ワークスペースの定期バックアップ（管理者のみ）。Markdown＋添付の ZIP をオブジェクトストレージに保存する */
const props = defineProps<{ wid: string; name: string }>()
const emit = defineEmits<{ close: [] }>()
const cfg = ref<BackupConfig>({ enabled: false, hour: 3, keep: 7 })
const configured = ref(false)
const list = ref<Backup[] | null>(null)
const storage = ref(true)
const error = ref('')
const saved = ref(false)

async function load() {
  try {
    const r = await backupApi.list(props.wid)
    if (r.config) { cfg.value = { ...r.config }; configured.value = true }
    list.value = r.backups
    storage.value = r.storage
  } catch (e) { error.value = (e as Error).message }
}
// 実行中があれば数秒ごとに読み直す
let timer: ReturnType<typeof setInterval> | undefined
onMounted(async () => {
  await load()
  timer = setInterval(() => { if (list.value?.some((b) => b.status === 'running')) void load() }, 3000)
})
onBeforeUnmount(() => clearInterval(timer))

async function save() {
  error.value = ''
  try {
    cfg.value = await backupApi.setConfig(props.wid, cfg.value)
    configured.value = true
    saved.value = true
    setTimeout(() => (saved.value = false), 1500)
  } catch (e) { error.value = (e as Error).message }
}
async function runNow() {
  error.value = ''
  try { await backupApi.run(props.wid); await load() } catch (e) {
    const m = (e as Error).message
    error.value = m === 'a backup is already running' ? '実行中のバックアップがあります' : m
  }
}
async function remove(b: Backup) {
  if (await confirmDialog(`${formatDate(b.createdAt)} のバックアップを削除しますか？`, { okLabel: '削除', danger: true })) {
    try { await backupApi.remove(b.id); await load() } catch (e) { error.value = (e as Error).message }
  }
}
const size = (n: number | null) => (n === null ? '' : n > 1048576 ? `${(n / 1048576).toFixed(1)} MB` : `${Math.ceil(n / 1024)} KB`)
</script>

<template>
  <ModalDialog label="定期バックアップ" testid="backups" @close="emit('close')">
    <template #title>💾 定期バックアップ — {{ name }} <HelpLink to="admin#定期バックアップ" /></template>
    <p class="note">毎日決まった時刻に、このワークスペースのすべてのページ（権限を絞ったページも）を ZIP にしてオブジェクトストレージに保存します。
      中身は「⤓ 書き出し」→ ZIP と同じ形（Markdown・添付ファイル・ホワイトボード）で、そのまま「⤒ 取り込む」で戻せます。
      ページの親子・本文の JSON・ラベル・データベースの定義と値は <code>skiwi-backup.json</code> に入ります。管理者だけが扱えます。</p>
    <p v-if="!storage" class="error">オブジェクトストレージ（S3）が設定されていないため、バックアップできません。</p>
    <p v-if="error" class="error" data-testid="backup-error">{{ error }}</p>

    <form class="cfg" @submit.prevent="save">
      <label class="row"><input v-model="cfg.enabled" type="checkbox" data-testid="backup-enabled" /> 毎日バックアップする</label>
      <label class="row">時刻
        <select v-model.number="cfg.hour" data-testid="backup-hour"><option v-for="h in 24" :key="h - 1" :value="h - 1">{{ h - 1 }}:00</option></select>
        ごろ（10分ごとに確認）
      </label>
      <label class="row">残す数 <input v-model.number="cfg.keep" type="number" min="1" max="90" data-testid="backup-keep" /> 世代（古いものから消します）</label>
      <div class="row">
        <button type="submit" class="primary" data-testid="backup-save">{{ saved ? '保存しました' : '保存' }}</button>
        <span class="spacer" />
        <button type="button" :disabled="!storage" data-testid="backup-run" @click="runNow">今すぐバックアップ</button>
      </div>
    </form>

    <h3>バックアップ</h3>
    <p v-if="list && !list.length" class="muted">まだありません。</p>
    <table v-else-if="list" class="tbl" data-testid="backup-list">
      <tr v-for="b in list" :key="b.id" data-testid="backup-item">
        <td>{{ formatDate(b.createdAt) }}<span v-if="b.manual" class="tag">手動</span></td>
        <td>
          <span v-if="b.status === 'running'" class="muted">作成中…</span>
          <span v-else-if="b.status === 'failed'" class="error" :title="b.error ?? ''">失敗: {{ b.error }}</span>
          <span v-else>{{ b.pages }}ページ・添付{{ b.attachments }}件・{{ size(b.size) }}</span>
        </td>
        <td class="acts">
          <a v-if="b.status === 'done'" :href="backupApi.downloadUrl(b.id)" data-testid="backup-download">ダウンロード</a>
          <button v-if="b.status !== 'running'" class="link danger" @click="remove(b)">削除</button>
        </td>
      </tr>
    </table>
  </ModalDialog>
</template>

<style scoped>
:deep(.x) { padding: 0 4px; }
h3 { font-size: .9rem; margin: 1rem 0 .4rem; }
.x { border: none; color: var(--muted); padding: 0 4px; }
.note { line-height: 1.55; }
.cfg { display: flex; flex-direction: column; gap: .45rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; font-size: .85rem; }
.row { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
.spacer { flex: 1; }
select, input[type=number] { font: inherit; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; width: auto; }
input[type=number] { width: 4.5rem; }
.tbl { width: 100%; border-collapse: collapse; font-size: .82rem; }
.tbl td { padding: .35rem .3rem; border-bottom: 1px solid var(--line); vertical-align: top; }
.acts { white-space: nowrap; text-align: right; }
.acts a { color: var(--accent); margin-right: .5rem; }
.tag { font-size: .68rem; border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; margin-left: .3rem; color: var(--muted); }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.error { font-size: .82rem; }
</style>
