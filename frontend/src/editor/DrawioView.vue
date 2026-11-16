<script setup lang="ts">
import { nodeViewProps, NodeViewWrapper } from '@tiptap/vue-3'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { alertDialog, alertError } from '../ui/dialog'
import { useEditable } from './useEditable'
import { fetchAttachment } from '../util/url'
import { dataUrlToFile, drawioFileName, drawioUrl, editorSrc, labelsOf, urlToDataUrl } from './drawioUtil'

/** draw.io の図: ふだんは PNG を表示し、編集は diagrams.net のエディタ（埋め込みモード）を全画面で開いて行う */
const props = defineProps(nodeViewProps)
const editable = useEditable(props.editor)
const open = ref(false)
const src = ref('')
const frame = ref<HTMLIFrameElement | null>(null)
const status = ref('')
const stuck = ref(false)
let origin = ''
let lastXml = ''
let exitAfter = false
let stuckTimer: ReturnType<typeof setTimeout> | undefined

const post = (msg: object) => frame.value?.contentWindow?.postMessage(JSON.stringify(msg), origin || '*')

async function startEdit() {
  if (!editable.value || open.value) return
  const upload = props.extension.options.uploadFile as ((f: File) => Promise<string>) | undefined
  const base = await drawioUrl()
  if (!base) { await alertDialog('draw.io のエディタが設定されていません（管理者が DRAWIO_URL を空にしています）。'); return }
  if (!upload) { await alertDialog('ここでは図を編集できません。'); return }
  src.value = editorSrc(base)
  origin = new URL(src.value).origin
  status.value = '読み込み中…'
  stuck.value = false
  open.value = true
  clearTimeout(stuckTimer)
  stuckTimer = setTimeout(() => { if (status.value === '読み込み中…') stuck.value = true }, 15000)
}

async function load() {
  status.value = ''
  stuck.value = false
  const a = props.node.attrs
  try {
    // 取り込んだ図は XML を正とする（表示用の PNG に図が埋め込まれていないことがある）
    if (a.xmlSrc) post({ action: 'load', xml: await (await fetchAttachment(a.xmlSrc)).text(), autosave: 0, title: a.title ?? '' })
    else if (a.src) post({ action: 'load', xmlpng: await urlToDataUrl(a.src), autosave: 0, title: a.title ?? '' })
    else post({ action: 'load', xml: '', autosave: 0 })
  } catch {
    post({ action: 'load', xml: '', autosave: 0 })
    status.value = '元の図を読み込めなかったため、空の図を開きました'
  }
}

/** 図のページ名（既定の「ページ-1」以外）を題名にする */
function titleOf(xml: string): string | null {
  const m = /<diagram[^>]*\bname="([^"]*)"/.exec(xml)
  const n = m?.[1]?.replace(/&amp;/g, '&').replace(/&quot;/g, '"').trim()
  return n && !/^(Page|ページ)-\d+$/.test(n) ? n : null
}

async function saved(dataUrl: string) {
  const upload = props.extension.options.uploadFile as (f: File) => Promise<string>
  try {
    status.value = '保存中…'
    const title = titleOf(lastXml) ?? props.node.attrs.title
    const url = await upload(dataUrlToFile(dataUrl, drawioFileName(title)))
    const text = await labelsOf(lastXml)
    props.updateAttributes({ src: url, xmlSrc: null, title, text })
    status.value = ''
    if (exitAfter) close()
    else post({ action: 'status', message: '保存しました', modified: false })
  } catch (e) {
    status.value = ''
    post({ action: 'spinner', show: false })
    await alertError('図を保存できませんでした', e)
  }
}

function onMessage(e: MessageEvent) {
  if (!open.value || e.source !== frame.value?.contentWindow || typeof e.data !== 'string') return
  let msg: { event?: string; xml?: string; exit?: boolean; data?: string; modified?: boolean }
  try { msg = JSON.parse(e.data) } catch { return }
  if (msg.event === 'init') void load()
  else if (msg.event === 'save') {
    lastXml = msg.xml ?? ''
    exitAfter = !!msg.exit
    post({ action: 'export', format: 'xmlpng', xml: lastXml, spinKey: 'saving', scale: 1, border: 10 })
  } else if (msg.event === 'export' && msg.data) void saved(msg.data)
  else if (msg.event === 'exit') close()
}

function close() {
  open.value = false
  src.value = ''
  status.value = ''
  clearTimeout(stuckTimer)
  props.editor.commands.focus()
}

onMounted(() => {
  window.addEventListener('message', onMessage)
  // 「/draw.io」で入れた直後は、すぐにエディタを開く
  const st = props.editor.storage as unknown as { drawio?: { openNext: boolean } }
  if (st.drawio?.openNext && !props.node.attrs.src && !props.node.attrs.xmlSrc) {
    st.drawio.openNext = false
    void startEdit()
  }
})
onBeforeUnmount(() => { window.removeEventListener('message', onMessage); clearTimeout(stuckTimer) })
</script>

<template>
  <NodeViewWrapper class="drawio-block" :class="{ selected: props.selected }" data-testid="drawio-block">
    <div class="pic" :title="editable ? 'ダブルクリックで編集' : ''" @dblclick="startEdit">
      <img v-if="props.node.attrs.src" :src="props.node.attrs.src" :alt="props.node.attrs.title || 'draw.io の図'" :style="props.node.attrs.width ? { width: `${props.node.attrs.width}px` } : {}" draggable="false" />
      <div v-else class="empty">
        <span class="big">⧉</span>
        <span>{{ props.node.attrs.xmlSrc ? 'draw.io の図（開くと表示されます）' : '空の draw.io の図' }}</span>
      </div>
    </div>
    <div v-if="editable" class="bar" contenteditable="false">
      <button type="button" data-testid="drawio-edit" @click="startEdit">✏️ 図を編集</button>
    </div>
    <Teleport to="body">
      <div v-if="open" class="drawio-overlay" data-testid="drawio-overlay" @keydown.stop>
        <div class="head">
          <span>draw.io で編集中（「保存して終了」で反映）</span>
          <span v-if="status" class="st">{{ status }}</span>
          <span v-if="stuck" class="warn">エディタを読み込めません。インターネットにつながらない環境では、管理者が社内に draw.io を置いて DRAWIO_URL を設定してください。</span>
          <button type="button" data-testid="drawio-close" @click="close">閉じる（保存しない）</button>
        </div>
        <iframe ref="frame" :src="src" title="draw.io" data-testid="drawio-frame" />
      </div>
    </Teleport>
  </NodeViewWrapper>
</template>

<style scoped>
.drawio-block { margin: .8em 0; border: 1px solid transparent; border-radius: 6px; padding: .4em; position: relative; }
.drawio-block.selected, .drawio-block:hover { border-color: var(--line); }
.pic { text-align: center; overflow-x: auto; }
.pic img { max-width: 100%; height: auto; }
.empty { display: flex; flex-direction: column; align-items: center; gap: .3rem; padding: 1.6rem; color: var(--muted); background: var(--side); border-radius: 6px; font-size: .9em; }
.big { font-size: 1.6rem; }
.bar { position: absolute; top: .3rem; right: .3rem; opacity: 0; transition: opacity .15s; }
.drawio-block:hover .bar, .drawio-block.selected .bar { opacity: 1; }
.bar button { font-size: .78rem; padding: .15rem .5rem; }
.drawio-overlay { position: fixed; inset: 0; z-index: 200; background: var(--bg); display: flex; flex-direction: column; }
.head { display: flex; align-items: center; gap: .8rem; padding: .35rem .8rem; border-bottom: 1px solid var(--line); font-size: .85rem; }
.head button { margin-left: auto; }
.st { color: var(--muted); }
.warn { color: #b45309; }
iframe { flex: 1; border: none; width: 100%; }
</style>
