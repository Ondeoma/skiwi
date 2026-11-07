<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Page } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { duplicatePage } from '../ui/duplicate'
import { progress } from '../ui/progress'
import { alertDialog, alertError } from '../ui/dialog'

/** ページの複製（子ページ・添付ファイル・ホワイトボードごと。別のワークスペースへも） */
const props = defineProps<{ page: Page }>()
const emit = defineEmits<{ close: [] }>()
const store = useWorkspaceStore()
const router = useRouter()

const title = ref(`${props.page.title || '無題'} のコピー`)
const targets = computed(() => store.workspaces.filter((w) => w.role === 'edit'))
const workspaceId = ref(targets.value.some((w) => w.id === props.page.workspaceId) ? props.page.workspaceId : (targets.value[0]?.id ?? ''))
const sameWs = computed(() => workspaceId.value === props.page.workspaceId)
/** sibling: 元のページのすぐ下（同じ階層）/ root: ワークスペースの直下 */
const place = ref<'sibling' | 'root'>('sibling')
const descendants = computed(() => {
  let n = 0
  const walk = (id: string) => { for (const p of store.pages) if (p.parentId === id) { n++; walk(p.id) } }
  walk(props.page.id)
  return n
})
const includeChildren = ref(true)
const busy = ref(false)
const titleEl = ref<HTMLInputElement | null>(null)
onMounted(() => { titleEl.value?.focus(); titleEl.value?.select() })

async function run() {
  if (!workspaceId.value || busy.value) return
  busy.value = true
  try {
    const parentId = sameWs.value && place.value === 'sibling' ? undefined : null
    progress.value = { label: '複製中', done: 0, total: 1 }
    const r = await duplicatePage(props.page, { workspaceId: workspaceId.value, parentId, includeChildren: includeChildren.value, title: title.value.trim() },
      (done, total) => { progress.value = { label: '複製中', done, total } })
    if (store.currentId === r.workspaceId) await store.reloadPages()
    emit('close')
    await router.push(`/w/${r.workspaceId}/p/${r.rootId}`)
    if (r.failed) await alertDialog(`${r.failed} ページの本文を複製できませんでした（ページは作成済みです）。`)
  } catch (e) {
    await alertError('複製できませんでした', e)
  } finally {
    busy.value = false
    progress.value = null
  }
}
</script>

<template>
  <div class="backdrop" @click.self="emit('close')">
    <form class="dialog" role="dialog" aria-label="ページを複製" data-testid="duplicate-dialog" @submit.prevent="run">
      <h2>⧉ ページを複製</h2>
      <label class="field">
        <span>新しいページの名前</span>
        <input ref="titleEl" v-model="title" data-testid="duplicate-title" />
      </label>
      <label class="field">
        <span>複製先のワークスペース</span>
        <select v-model="workspaceId" data-testid="duplicate-ws">
          <option v-for="w in targets" :key="w.id" :value="w.id">{{ w.name }}{{ w.id === page.workspaceId ? '（このワークスペース）' : '' }}</option>
        </select>
      </label>
      <div v-if="sameWs" class="field">
        <span>置く場所</span>
        <label class="row"><input v-model="place" type="radio" value="sibling" /> 元のページのすぐ下（同じ階層）</label>
        <label class="row"><input v-model="place" type="radio" value="root" data-testid="duplicate-root" /> ワークスペースの直下（末尾）</label>
      </div>
      <p v-else class="note">複製先のワークスペースの直下（末尾）に置きます。</p>
      <label v-if="descendants" class="row">
        <input v-model="includeChildren" type="checkbox" data-testid="duplicate-children" />
        子ページも複製する（{{ descendants }}ページ）
      </label>
      <p class="note">
        本文・添付ファイル・ホワイトボード・ラベル・スタイル・個別の共有設定を複製します。一緒に複製したページへのリンクは複製したページに付け替えます。
        コメント・版の履歴・公開リンクは複製しません。
      </p>
      <p v-if="!targets.length" class="error">複製先にできるワークスペース（編集権限のあるもの）がありません。</p>
      <div class="actions">
        <button type="button" @click="emit('close')">キャンセル</button>
        <button type="submit" class="primary" :disabled="busy || !workspaceId" data-testid="duplicate-run">{{ busy ? '複製中…' : '複製' }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 8vh; z-index: 50; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; width: min(480px, 92vw); max-height: 84vh; overflow: auto; padding: 1rem 1.2rem; box-shadow: 0 20px 50px rgba(0,0,0,.25); }
h2 { font-size: 1.05rem; margin: 0 0 .8rem; }
.field { display: flex; flex-direction: column; gap: .25rem; margin: .6rem 0; font-size: .88rem; }
.field > span { font-weight: 600; font-size: .82rem; color: var(--muted); }
.field input:not([type]), .field select { font: inherit; padding: .35rem .55rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.row { display: flex; gap: .45rem; align-items: center; margin: .2rem 0; font-size: .88rem; }
.note { margin: .5rem 0; }
.error { font-size: .85rem; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.primary:disabled { opacity: .6; }
</style>
