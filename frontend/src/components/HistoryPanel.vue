<script setup lang="ts">
import HelpLink from '../ui/HelpLink.vue'
import { confirmDialog, promptDialog } from '../ui/dialog'
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { Editor, EditorContent } from '@tiptap/vue-3'
import { api4, formatDate, type DocState, type Version } from '../api'
import { contentExtensions } from '../editor/extensions'
import { blockUnits } from '../editor/markdown'
import { changeCount, diffBlocks, type Row } from '../editor/diff'

/** 版履歴パネル。版の一覧・プレビュー・手動保存・復元。
  * 復元は、現在の状態をサーバーに退避してから、版の本文をエディタに適用する（Yjs経由で全員に同期）。 */
const props = defineProps<{
  pageId: string
  canEdit: boolean
  getState: () => DocState
  applyContent: (json: unknown) => void
}>()
const emit = defineEmits<{ close: [] }>()

const versions = ref<Version[]>([])
const selected = ref<Version | null>(null)
const busy = ref(false)
const error = ref('')
const preview = shallowRef<Editor | null>(null)

async function load() {
  versions.value = await api4.versions(props.pageId)
}

onMounted(async () => {
  preview.value = new Editor({ editable: false, extensions: contentExtensions(), content: '' })
  try { await load() } catch (e) { error.value = (e as Error).message }
})
onBeforeUnmount(() => preview.value?.destroy())

// --- 表示: 内容 / 差分（現在と比較・ひとつ前の版と比較） ---------------------------------
const view = ref<'content' | 'current' | 'prev'>('content')
const selectedContent = shallowRef<unknown>(null)
const diffRows = ref<Row[] | null>(null)
const diffNote = ref('')

async function select(v: Version) {
  selected.value = v
  const c = await api4.version(props.pageId, v.id)
  selectedContent.value = c.content
  preview.value?.commands.setContent(c.content as Record<string, unknown>)
  await refreshDiff()
}

async function refreshDiff() {
  diffRows.value = null
  diffNote.value = ''
  const v = selected.value
  if (!v || view.value === 'content') return
  if (view.value === 'current') {
    diffRows.value = diffBlocks(blockUnits(selectedContent.value), blockUnits(props.getState().content))
    diffNote.value = `この版（${formatDate(v.createdAt)}）→ 現在`
  } else {
    const i = versions.value.findIndex((x) => x.id === v.id)
    const prev = versions.value[i + 1]
    if (!prev) { diffRows.value = diffBlocks([], blockUnits(selectedContent.value)); diffNote.value = '最初の版です（すべて追加として表示）'; return }
    const p = await api4.version(props.pageId, prev.id)
    diffRows.value = diffBlocks(blockUnits(p.content), blockUnits(selectedContent.value))
    diffNote.value = `ひとつ前の版（${formatDate(prev.createdAt)}）→ この版`
  }
}
function setView(v: 'content' | 'current' | 'prev') { view.value = v; void refreshDiff() }

async function saveNow() {
  const label = await promptDialog('版の名前（任意）', '', { okLabel: '保存' })
  if (label === null) return
  busy.value = true
  try {
    await api4.saveVersion(props.pageId, { ...props.getState(), label })
    await load()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}

async function restore() {
  const v = selected.value
  if (!v) return
  if (!(await confirmDialog(`${formatDate(v.createdAt)} の版に戻しますか？\n現在の内容は「復元前の状態」として履歴に残ります。`, { okLabel: '復元' }))) return
  busy.value = true
  try {
    const c = await api4.restoreVersion(props.pageId, v.id, props.getState())
    props.applyContent(c.content)
    await load()
    emit('close')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <aside class="history" aria-label="版履歴">
    <header>
      <strong>版履歴</strong> <HelpLink to="manage#版の履歴" />
      <span class="grow" />
      <button v-if="canEdit" :disabled="busy" title="今の内容を版として保存" @click="saveNow">現在の版を保存</button>
      <button title="閉じる" @click="emit('close')">✕</button>
    </header>
    <p class="note">編集が続くと約10分ごとに自動で版が残ります。</p>
    <div class="body">
      <ul class="list">
        <li v-for="v in versions" :key="v.id" :class="{ on: selected?.id === v.id }" @click="select(v)">
          <div class="when">{{ formatDate(v.createdAt) }}</div>
          <div v-if="v.label" class="label">{{ v.label }}</div>
          <div class="who">{{ v.authors.length ? v.authors.join('、') : v.createdBy ?? '' }}</div>
        </li>
        <li v-if="versions.length === 0" class="muted">まだ版がありません</li>
      </ul>
      <div class="preview">
        <template v-if="selected">
          <div class="preview-head">
            <span>{{ formatDate(selected.createdAt) }}</span>
            <div class="seg" role="tablist">
              <button :class="{ on: view === 'content' }" data-testid="hist-content" @click="setView('content')">内容</button>
              <button :class="{ on: view === 'current' }" data-testid="hist-diff-current" @click="setView('current')">現在との差分</button>
              <button :class="{ on: view === 'prev' }" data-testid="hist-diff-prev" @click="setView('prev')">前の版との差分</button>
            </div>
            <button v-if="canEdit" class="primary" :disabled="busy" @click="restore">この版に戻す</button>
          </div>
          <EditorContent v-show="view === 'content'" v-if="preview" :editor="preview" class="preview-doc" />
          <div v-if="view !== 'content'" class="diff" data-testid="diff">
            <p class="diff-note">{{ diffNote }}<template v-if="diffRows">（変更 {{ changeCount(diffRows) }} か所）</template></p>
            <p v-if="diffRows && !changeCount(diffRows)" class="muted">違いはありません。</p>
            <template v-for="(r, i) in diffRows ?? []" :key="i">
              <div v-if="r.kind === 'fold'" class="d-fold">⋯ 変更のない {{ r.count }} ブロック</div>
              <pre v-else-if="r.kind === 'change'" class="d-row d-change"><template v-for="(p, j) in r.pieces" :key="j"><del v-if="p.kind === 'del'">{{ p.text }}</del><ins v-else-if="p.kind === 'add'">{{ p.text }}</ins><span v-else>{{ p.text }}</span></template></pre>
              <pre v-else class="d-row" :class="`d-${r.kind}`"><span class="d-mark">{{ r.kind === 'add' ? '+' : r.kind === 'del' ? '−' : ' ' }}</span>{{ r.text }}</pre>
            </template>
          </div>
        </template>
        <p v-else class="muted">左の一覧から版を選ぶと内容を表示します。</p>
      </div>
    </div>
    <p v-if="error" class="error">{{ error }}</p>
  </aside>
</template>

<style scoped>
.history { position: fixed; top: 0; right: 0; bottom: 0; width: min(860px, 96vw); background: var(--bg); border-left: 1px solid var(--line); box-shadow: -10px 0 30px rgba(0,0,0,.15); z-index: 40; display: flex; flex-direction: column; }
header { display: flex; gap: .5rem; align-items: center; padding: .7rem 1rem; border-bottom: 1px solid var(--line); }
.grow { flex: 1; }
.note { margin: .4rem 1rem; }
.body { flex: 1; display: flex; min-height: 0; }
.list { width: 240px; flex: none; overflow: auto; list-style: none; margin: 0; padding: .3rem; border-right: 1px solid var(--line); }
.list li { padding: .45rem .6rem; border-radius: 6px; cursor: pointer; }
.list li:hover { background: var(--hover); }
.list li.on { background: var(--hover); outline: 1px solid var(--accent); }
.when { font-weight: 600; font-size: .9rem; }
.label { font-size: .8rem; color: var(--accent); }
.who { font-size: .78rem; color: var(--muted); }
.preview { flex: 1; overflow: auto; padding: .6rem 1.2rem; }
.preview-head { display: flex; flex-wrap: wrap; gap: .5rem; justify-content: space-between; align-items: center; color: var(--muted); font-size: .85rem; margin-bottom: .5rem; position: sticky; top: 0; background: var(--bg); padding: .3rem 0; }
.seg { display: flex; gap: 2px; }
.seg button { font-size: .75rem; padding: 1px 8px; color: var(--muted); }
.seg button.on { color: var(--fg); border-color: var(--accent); background: var(--hover); }
.diff-note { font-size: .78rem; color: var(--muted); margin: 0 0 .5rem; }
.d-row { margin: 0 0 4px; padding: .3rem .6rem; border-radius: 6px; white-space: pre-wrap; word-break: break-word; font: inherit; font-size: .88rem; line-height: 1.6; }
.d-mark { display: inline-block; width: 1.2em; color: var(--muted); font-family: monospace; }
.d-same { color: var(--muted); }
.d-add { background: rgba(22,163,74,.14); }
.d-add .d-mark { color: #16a34a; }
.d-del { background: rgba(220,38,38,.12); text-decoration: line-through; text-decoration-color: rgba(220,38,38,.5); }
.d-del .d-mark { color: var(--danger); }
.d-change { background: rgba(234,179,8,.08); }
.d-change ins { background: rgba(22,163,74,.28); text-decoration: none; border-radius: 2px; }
.d-change del { background: rgba(220,38,38,.25); border-radius: 2px; }
.d-fold { font-size: .75rem; color: var(--muted); text-align: center; padding: .2rem; border-top: 1px dashed var(--line); border-bottom: 1px dashed var(--line); margin: 4px 0; }
.preview-doc :deep(.ProseMirror) { min-height: 0; padding-bottom: 2rem; }
.muted { font-size: .85em; }
.error { padding: 0 1rem; }
@media (max-width: 768px) {
  .history { width: 100vw; }
  .body { flex-direction: column; }
  .list { width: auto; max-height: 30vh; border-right: none; border-bottom: 1px solid var(--line); }
  .preview { padding: .6rem; }
}
</style>
