<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api5, formatDate, type TrashedPage, type TrashedWorkspace } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { alertError, confirmDialog, guarded } from '../ui/dialog'

/** ゴミ箱。削除したページ（このワークスペース）と、削除したワークスペースを戻す・完全に削除する。 */
const props = defineProps<{ wid: string | null }>()
const emit = defineEmits<{ close: [] }>()
const store = useWorkspaceStore()
const router = useRouter()

const tab = ref<'pages' | 'workspaces'>(props.wid ? 'pages' : 'workspaces')
const pages = ref<TrashedPage[]>([])
const workspaces = ref<TrashedWorkspace[]>([])
const loading = ref(true)
const busy = ref(false)

async function load() {
  loading.value = true
  try {
    ;[pages.value, workspaces.value] = await Promise.all([props.wid ? api5.trash(props.wid) : Promise.resolve([]), api5.trashedWorkspaces()])
  } catch (e) { await alertError('ゴミ箱を読み込めませんでした', e) }
  finally { loading.value = false }
}
onMounted(load)

const guard = (f: () => Promise<unknown>) => guarded(f, busy)

const restorePage = (t: TrashedPage) => guard(async () => {
  const r = await api5.restorePage(t.id)
  await store.reloadPages()
  emit('close')
  router.push(`/w/${r.workspaceId}/p/${r.id}`)
})
async function purgePage(t: TrashedPage) {
  const extra = t.count > 1 ? `（子ページ ${t.count - 1} 件を含む）` : ''
  if (!(await confirmDialog(`「${t.title || '無題'}」${extra}を完全に削除しますか？\n元に戻せません。添付ファイルも削除されます。`, { okLabel: '完全に削除', danger: true }))) return
  await guard(async () => { await api5.purgePage(t.id); await load() })
}
const restoreWs = (w: TrashedWorkspace) => guard(async () => {
  await api5.restoreWorkspace(w.id)
  await store.reloadWorkspaces()
  emit('close')
  router.push(`/w/${w.id}`)
})
async function purgeWs(w: TrashedWorkspace) {
  if (!(await confirmDialog(`ワークスペース「${w.name}」と中のすべてのページを完全に削除しますか？\n元に戻せません。`, { okLabel: '完全に削除', danger: true }))) return
  await guard(async () => { await api5.purgeWorkspace(w.id); await load() })
}
const day = (iso: string) => new Date(iso).toLocaleDateString('ja-JP')
</script>

<template>
  <div class="backdrop" @mousedown.self="emit('close')" @keydown.esc="emit('close')">
    <div class="dialog" role="dialog" aria-label="ゴミ箱" data-testid="trash">
      <header>
        <h2>🗑 ゴミ箱</h2>
        <div class="tabs" role="tablist">
          <button v-if="wid" role="tab" :class="{ on: tab === 'pages' }" @click="tab = 'pages'">このワークスペースのページ（{{ pages.length }}）</button>
          <button role="tab" :class="{ on: tab === 'workspaces' }" data-testid="trash-ws-tab" @click="tab = 'workspaces'">削除したワークスペース（{{ workspaces.length }}）</button>
        </div>
        <button class="close" title="閉じる" @click="emit('close')">✕</button>
      </header>
      <p class="note">削除したものは、ここに一定期間残ってから自動で完全に削除されます。</p>
      <div class="body">
        <p v-if="loading" class="muted">読み込み中…</p>
        <template v-else-if="tab === 'pages'">
          <p v-if="!pages.length" class="muted">ゴミ箱は空です。</p>
          <table v-else>
            <tr v-for="t in pages" :key="t.id" data-testid="trash-item">
              <td class="name">{{ t.icon ?? '📄' }} {{ t.title || '無題' }}<span v-if="t.count > 1" class="muted">（子ページ {{ t.count - 1 }} 件）</span></td>
              <td class="muted small">{{ formatDate(t.deletedAt) }} {{ t.deletedBy ?? '' }} が削除<br />{{ day(t.purgeAt) }} に完全に削除</td>
              <td class="acts">
                <button :disabled="busy" data-testid="trash-restore" @click="restorePage(t)">↩ 戻す</button>
                <button :disabled="busy" class="danger" @click="purgePage(t)">完全に削除</button>
              </td>
            </tr>
          </table>
        </template>
        <template v-else>
          <p v-if="!workspaces.length" class="muted">削除したワークスペースはありません。</p>
          <table v-else>
            <tr v-for="w in workspaces" :key="w.id" data-testid="trash-ws-item">
              <td class="name">🗂 {{ w.name }}</td>
              <td class="muted small">{{ formatDate(w.deletedAt) }} {{ w.deletedBy ?? '' }} が削除<br />{{ day(w.purgeAt) }} に完全に削除</td>
              <td class="acts">
                <button :disabled="busy" data-testid="trash-ws-restore" @click="restoreWs(w)">↩ 戻す</button>
                <button :disabled="busy" class="danger" @click="purgeWs(w)">完全に削除</button>
              </td>
            </tr>
          </table>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: flex-start; justify-content: center; padding: 10vh 16px 16px; z-index: 60; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; width: min(760px, 100%); max-height: 75vh; display: flex; flex-direction: column; box-shadow: 0 16px 48px rgba(0,0,0,.3); }
header { display: flex; align-items: center; gap: .8rem; padding: .8rem 1rem; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
h2 { font-size: 1.05rem; margin: 0; }
.tabs { display: flex; gap: 2px; flex: 1; }
.tabs button { font-size: .8rem; border: 1px solid var(--line); color: var(--muted); }
.tabs button.on { color: var(--fg); border-color: var(--accent); background: var(--hover); }
.close { border: none; color: var(--muted); }
.note { margin: .5rem 1rem 0; }
.body { overflow: auto; padding: .5rem 1rem 1rem; }
table { width: 100%; border-collapse: collapse; }
td { padding: .45rem .3rem; border-bottom: 1px solid var(--line); vertical-align: middle; }
.name { font-weight: 600; }
.small { white-space: nowrap; }
.acts { white-space: nowrap; text-align: right; }
.acts button { font-size: .8rem; margin-left: .3rem; }
.danger:hover { color: var(--danger); border-color: var(--danger); }
.muted { font-weight: 400; }
</style>
