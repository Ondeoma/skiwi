<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { useEditable } from './useEditable'
import { useHandleOnlyDrag } from './dragHandle'
import { parseSnapshot, type WhiteboardOptions } from './Whiteboard'
import { guestContext } from './pageLinks'
import { download } from './markdown'
import { confirmDialog } from '../ui/dialog'
import type { BoardHandle, Snapshot } from './whiteboard/board'

const props = defineProps(nodeViewProps)
const editable = useEditable(props.editor)
const opts = computed(() => props.extension.options as WhiteboardOptions)

const host = ref<HTMLElement | null>(null)
useHandleOnlyDrag(host, '.wb')
const state = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const full = ref(false)
let board: BoardHandle | null = null

const media = window.matchMedia('(prefers-color-scheme: dark)')
const dark = ref(media.matches)
const onScheme = () => { dark.value = media.matches; board?.setDark(media.matches) }

const height = computed(() => Number(props.node.attrs.height) || 520)

// スナップショットの保存（自分の変更から少し待って。ドラッグ中に毎回文書を書き換えない）
let saveTimer: ReturnType<typeof setTimeout> | undefined
function saveSnapshot(snapshot: () => Snapshot) {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    if (props.editor.isDestroyed || !props.editor.isEditable) return
    const json = JSON.stringify(snapshot())
    if (json !== props.node.attrs.snapshot) props.updateAttributes({ snapshot: json })
  }, 1500)
}

async function load() {
  if (state.value !== 'idle' || !host.value) return
  state.value = 'loading'
  try {
    const { mountBoard } = await import('./whiteboard/board')
    const g = guestContext.value
    board = await mountBoard(host.value, {
      id: String(props.node.attrs.id),
      snapshot: parseSnapshot(props.node.attrs.snapshot),
      editable: editable.value,
      dark: dark.value,
      ydoc: opts.value.ydoc,
      awareness: opts.value.awareness,
      user: opts.value.user ?? undefined,
      uploadFile: opts.value.uploadFile ?? undefined,
      mapUrl: g ? (u) => u.replace(/^\/api\/attachments\//, `/api/public/${g.token}/attachments/`) : undefined,
      onLocalChange: saveSnapshot,
    })
    state.value = 'ready'
  } catch (e) {
    console.error('[whiteboard] failed to load', e)
    state.value = 'error'
  }
}

// 画面に入ってから読み込む（ページに多数あっても重くならないように）
let io: IntersectionObserver | undefined
onMounted(() => {
  media.addEventListener('change', onScheme)
  io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) { io?.disconnect(); void load() } }, { rootMargin: '200px' })
  if (host.value) io.observe(host.value)
})
onBeforeUnmount(() => {
  io?.disconnect()
  media.removeEventListener('change', onScheme)
  clearTimeout(saveTimer)
  board?.destroy()
  document.removeEventListener('keydown', onEsc, true)
})

watch(editable, (v) => board?.setEditable(v))
// Y.Doc なしの表示（版のプレビューなど）で内容が差し替わったとき
watch(() => props.node.attrs.snapshot, (s) => { if (!opts.value.ydoc) board?.setSnapshot(parseSnapshot(s)) })

// --- 全画面 ------------------------------------------------------------------------
function onEsc(e: KeyboardEvent) {
  // 入力中の文字や選択の解除にも Esc を使うため、図形が何も選ばれていないときだけ閉じる
  if (e.key === 'Escape' && full.value && !document.activeElement?.closest('.excalidraw textarea')) {
    const selected = host.value?.querySelector('.excalidraw .selected-shape-actions, .excalidraw .App-menu__left .panelColumn')
    if (!selected) { e.stopPropagation(); toggleFull() }
  }
}
function toggleFull() {
  full.value = !full.value
  document.body.style.overflow = full.value ? 'hidden' : ''
  if (full.value) document.addEventListener('keydown', onEsc, true)
  else document.removeEventListener('keydown', onEsc, true)
  relayout()
}
/** 大きさが変わったことを Excalidraw に伝える（ウィンドウの resize でキャンバスを作り直す） */
function relayout() {
  requestAnimationFrame(() => { window.dispatchEvent(new Event('resize')); board?.refresh() })
}

// --- 高さの変更（下端をドラッグ） ---------------------------------------------------------
function startResize(e: PointerEvent) {
  e.preventDefault()
  const y0 = e.clientY
  const h0 = height.value
  const el = e.currentTarget as HTMLElement
  el.setPointerCapture(e.pointerId)
  let h = h0
  const move = (ev: PointerEvent) => {
    h = Math.max(240, Math.min(2000, Math.round(h0 + ev.clientY - y0)))
    if (host.value) host.value.style.height = `${h}px`
  }
  const up = () => {
    el.removeEventListener('pointermove', move)
    el.removeEventListener('pointerup', up)
    props.updateAttributes({ height: h })
    relayout()
  }
  el.addEventListener('pointermove', move)
  el.addEventListener('pointerup', up)
}

async function exportPng() {
  const b = await board?.exportPng()
  if (b) download('whiteboard.png', '', 'image/png', b)
}
const isGuest = computed(() => !!guestContext.value)
function comment() {
  if (full.value) toggleFull()
  window.dispatchEvent(new CustomEvent('skiwi:wb-comment', { detail: { id: String(props.node.attrs.id) } }))
}
async function remove() {
  if (!(await confirmDialog('このホワイトボードを削除しますか？', { okLabel: '削除', danger: true }))) return
  props.deleteNode()
}
</script>

<template>
  <NodeViewWrapper class="wb" :class="{ full, selected }" contenteditable="false" data-testid="whiteboard" :data-whiteboard-id="node.attrs.id">
    <div class="wb-bar">
      <span class="wb-title" data-drag-handle :draggable="editable && !full" :title="editable ? 'ここをドラッグするとブロックを移動できます' : undefined">{{ editable && !full ? '⠿ ' : '' }}🧩 ホワイトボード</span>
      <span class="wb-hint">{{ editable ? 'ドラッグで描画・Space+ドラッグで移動・Ctrl/⌘+ホイールで拡大縮小' : '閲覧のみ（ホイール・ドラッグで移動）' }}</span>
      <button v-if="!isGuest" title="このホワイトボードにコメント" data-testid="wb-comment" @click="comment">💬</button>
      <button v-if="state === 'ready'" title="PNG画像として保存" @click="exportPng">⤓ PNG</button>
      <button :title="full ? '元に戻す（Esc）' : '全画面で開く'" data-testid="wb-full" @click="toggleFull">{{ full ? '✕ 閉じる' : '⤢ 全画面' }}</button>
      <button v-if="editable && !full" class="danger" title="ホワイトボードを削除" @click="remove">🗑</button>
    </div>
    <div ref="host" class="wb-host" :style="full ? undefined : { height: `${height}px` }">
      <div v-if="state !== 'ready'" class="wb-loading">
        <span v-if="state === 'error'">ホワイトボードを読み込めませんでした</span>
        <span v-else>ホワイトボードを読み込み中…</span>
      </div>
    </div>
    <div v-if="editable && !full" class="wb-resize" title="ドラッグで高さを変更" @pointerdown="startResize" />
  </NodeViewWrapper>
</template>
