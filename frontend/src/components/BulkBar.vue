<script setup lang="ts">
import HelpLink from '../ui/HelpLink.vue'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { bulkApi, STATUS_LABEL, type BulkAction, type PageStatus } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { alertDialog, chooseDialog, confirmDialog, pickUser, promptDialog } from '../ui/dialog'
import { openPagePicker } from '../ui/pagePicker'
import { clearSelection, selectedPages } from '../ui/selection'

/** ページツリーで選んだページの一括操作（サイドメニューの下に出る） */
const props = defineProps<{ wid: string }>()
const store = useWorkspaceStore()
const route = useRoute()
const router = useRouter()
const busy = ref(false)

const pages = computed(() => selectedPages.value.map((id) => store.byId.get(id)).filter((p) => !!p))
const editable = computed(() => pages.value.filter((p) => p.role === 'edit'))
const titles = (n = 3) => pages.value.slice(0, n).map((p) => `「${p.title || '無題'}」`).join('') + (pages.value.length > n ? ` ほか${pages.value.length - n}件` : '')

/** 開いているページ（またはその祖先）が移動・削除された */
function openAffected(ids: string[]): boolean {
  return store.ancestors(String(route.params.pid), true).some((p) => ids.includes(p.id))
}

async function run(a: BulkAction, label: string) {
  busy.value = true
  const ids = [...selectedPages.value]
  const affected = (a.action === 'move' || a.action === 'delete') && openAffected(ids)
  try {
    const r = await bulkApi.run(ids, a)
    await store.reloadPages()
    if (r.failed.length) {
      await alertDialog(`${label}: ${r.done.length + r.skipped.length} 件を処理しました。次の ${r.failed.length} 件はできませんでした。\n\n` +
        r.failed.slice(0, 10).map((f) => `・${f.title ? `「${f.title || '無題'}」` : '（見つからないページ）'}: ${reason(f.error)}`).join('\n'))
      selectedPages.value = r.failed.map((f) => f.id).filter((id) => store.byId.has(id))
    } else if (a.action === 'move' || a.action === 'delete') clearSelection()
    // ラベル・所有者・状態のあとは、続けて操作できるよう選択を残す
    if (affected && a.action === 'delete') router.push(`/w/${props.wid}`)
    else if (affected && a.action === 'move' && a.workspaceId && a.workspaceId !== props.wid) router.push(`/w/${a.workspaceId}/p/${route.params.pid}`)
  } catch (e) {
    await alertDialog(`${label}できませんでした: ${(e as Error).message}`)
  } finally {
    busy.value = false
  }
}
const reason = (e: string) =>
  e === 'insufficient permission' ? '編集権限がありません'
    : e.includes('under itself') ? '自分自身や子ページの下には移動できません'
    : e === 'page not found' ? 'ページが見つかりません' : e

async function move() {
  const where = await chooseDialog(`${titles()} をどこへ移動しますか？（子ページも一緒に移動します）`, [
    { label: '📄 ページの下へ…', value: 'page', detail: 'ページを選んで、その子ページにする' },
    { label: '🗂 このワークスペースの直下へ', value: 'root' },
    ...store.workspaces.filter((w) => w.role === 'edit' && w.id !== props.wid).map((w) => ({ label: `↪ 「${w.name}」の直下へ`, value: `ws:${w.id}`, detail: '別のワークスペースへ移す（移動先の権限が適用されます）' })),
  ])
  if (!where) return
  if (where === 'root') return run({ action: 'move', parentId: null }, '移動')
  if (where.startsWith('ws:')) return run({ action: 'move', parentId: null, workspaceId: where.slice(3) }, '移動')
  const p = await openPagePicker()
  if (!p) return
  await run({ action: 'move', parentId: p.id, workspaceId: p.workspaceId !== props.wid ? p.workspaceId : undefined }, '移動')
}
async function remove() {
  const ok = await confirmDialog(`${titles()}（${pages.value.length}件）とその子ページをゴミ箱に移しますか？\nゴミ箱から元に戻せます。`, { okLabel: 'ゴミ箱に移す', danger: true })
  if (ok) await run({ action: 'delete' }, '削除')
}
async function labels() {
  const mode = await chooseDialog(`${pages.value.length}件のページのラベル`, [
    { label: '🏷 ラベルを付ける', value: 'add' },
    { label: '✂ ラベルを外す', value: 'remove' },
  ])
  if (!mode) return
  const existing = [...new Set(pages.value.flatMap((p) => p.labels ?? []))]
  const v = await promptDialog(mode === 'add' ? '付けるラベル（「,」区切りで複数可）' : `外すラベル（「,」区切りで複数可）${existing.length ? `\n付いているラベル: ${existing.join(', ')}` : ''}`, '', { okLabel: mode === 'add' ? '付ける' : '外す' })
  const ls = v?.split(/[,、，]/).map((x) => x.trim()).filter(Boolean) ?? []
  if (!ls.length) return
  await run(mode === 'add' ? { action: 'labels', addLabels: ls } : { action: 'labels', removeLabels: ls }, 'ラベルの変更')
}
async function owner() {
  const u = await pickUser('所有者（名前・メールで検索。空欄で外す）', { chooseMessage: '所有者を選んでください', allowEmpty: true })
  if (u !== undefined) await run({ action: 'owner', ownerId: u?.id ?? null }, '所有者の変更')
}
async function status() {
  const v = await chooseDialog(`${pages.value.length}件のページの状態`, [
    ...(Object.keys(STATUS_LABEL) as PageStatus[]).map((k) => ({ label: STATUS_LABEL[k], value: k })),
    { label: '（状態なし）', value: '' },
  ])
  if (v !== null) await run({ action: 'status', status: (v || null) as PageStatus | null }, '状態の変更')
}
</script>

<template>
  <div v-if="selectedPages.length" class="bulk" data-testid="bulk-bar">
    <div class="head">
      <span><b data-testid="bulk-count">{{ selectedPages.length }}</b> ページを選択中</span>
      <span><HelpLink to="pages#まとめて操作する" /> <button class="link" data-testid="bulk-clear" @click="clearSelection">選択を解除</button></span>
    </div>
    <p v-if="editable.length < pages.length" class="note">編集できないページ {{ pages.length - editable.length }} 件は変更されません</p>
    <div class="acts">
      <button :disabled="busy" data-testid="bulk-move" @click="move">↪ 移動</button>
      <button :disabled="busy" data-testid="bulk-labels" @click="labels">🏷 ラベル</button>
      <button :disabled="busy" data-testid="bulk-owner" @click="owner">👤 所有者</button>
      <button :disabled="busy" data-testid="bulk-status" @click="status">📌 状態</button>
      <button :disabled="busy" class="danger" data-testid="bulk-delete" @click="remove">🗑 削除</button>
    </div>
    <p class="note">⌘/Ctrl＋クリックで選択を追加・解除、Shift＋クリックで範囲を選べます</p>
  </div>
</template>

<style scoped>
.bulk { border-top: 1px solid var(--line); padding: .5rem .6rem; background: var(--bg); font-size: .82rem; display: flex; flex-direction: column; gap: .35rem; }
.head { display: flex; justify-content: space-between; align-items: center; }
.link { border: none; color: var(--accent); padding: 0; font-size: .78rem; }
.acts { display: flex; flex-wrap: wrap; gap: 4px; }
.acts button { font-size: .78rem; padding: 2px 8px; }
.acts .danger:hover { color: var(--danger); border-color: var(--danger); }
.note { margin: 0; font-size: .7rem; }
</style>
