import { domNodeView } from './nodeView'
import { download, guestContext, isDark, onThemeChange } from '../lib'
import { handleOnlyDrag } from './dragHandle'
import { parseSnapshot, type WhiteboardOptions } from './Whiteboard'
import { confirmDialog } from '../ui/dialog'
import { h, setChildren } from '../ui/dom'
import type { BoardHandle, Snapshot } from './whiteboard/board'

/** 添付ファイルの URL をゲスト向けの公開リンク経由に読み替える（Scala の CustomCss.guestAttachmentUrl と同じ） */
const guestAttachmentUrl = (token: string) => (u: string) => u.replace(/^\/api\/attachments\//, `/api/public/${token}/attachments/`)

/** ホワイトボード（Excalidraw）。画面に入ってから読み込む。全画面・高さの変更・PNG で保存ができる */
export const WhiteboardView = domNodeView<WhiteboardOptions>((c) => {
  const opts = c.extension.options
  let state: 'idle' | 'loading' | 'ready' | 'error' = 'idle'
  let full = false
  let board: BoardHandle | null = null
  let lastEditable = c.editor.isEditable
  let lastSnapshot = c.node().attrs.snapshot
  const height = () => Number(c.node().attrs.height) || 520

  const title = h('span', { class: 'wb-title', 'data-drag-handle': '' })
  const hint = h('span', { class: 'wb-hint' })
  const bar = h('div', { class: 'wb-bar' })
  const loadingText = h('span')
  const loading = h('div', { class: 'wb-loading' }, loadingText)
  const host = h('div', { class: 'wb-host' }, loading)
  const resize = h('div', { class: 'wb-resize', title: 'ドラッグで高さを変更', onpointerdown: startResize })
  const dom = h('div', { class: 'wb', contenteditable: 'false', 'data-testid': 'whiteboard' }, bar, host)
  const fixDrag = handleOnlyDrag(dom)

  // スナップショットの保存（自分の変更から少し待って。ドラッグ中に毎回文書を書き換えない）
  let saveTimer: ReturnType<typeof setTimeout> | undefined
  function saveSnapshot(snapshot: () => Snapshot) {
    clearTimeout(saveTimer)
    saveTimer = setTimeout(() => {
      if (c.editor.isDestroyed || !c.editor.isEditable) return
      const json = JSON.stringify(snapshot())
      if (json !== c.node().attrs.snapshot) c.updateAttributes({ snapshot: json })
    }, 1500)
  }

  async function load() {
    if (state !== 'idle') return
    state = 'loading'
    render()
    try {
      const { mountBoard } = await import('./whiteboard/board')
      const g = guestContext()
      board = await mountBoard(host, {
        id: String(c.node().attrs.id),
        snapshot: parseSnapshot(c.node().attrs.snapshot),
        editable: c.editor.isEditable,
        dark: isDark(),
        ydoc: opts.ydoc,
        awareness: opts.awareness,
        user: opts.user ?? undefined,
        uploadFile: opts.uploadFile ?? undefined,
        mapUrl: g ? guestAttachmentUrl(g.token) : undefined,
        onLocalChange: saveSnapshot,
      })
      if (!alive) { board.destroy(); board = null; return }
      state = 'ready'
    } catch (e) {
      console.error('[whiteboard] failed to load', e)
      state = 'error'
    }
    render()
  }

  // 画面に入ってから読み込む（ページに多数あっても重くならないように）
  let alive = true
  const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) { io.disconnect(); void load() } }, { rootMargin: '200px' })
  io.observe(host)
  const stopTheme = onThemeChange((d) => board?.setDark(d))

  // --- 全画面 ------------------------------------------------------------------------
  function onEsc(e: KeyboardEvent) {
    // 入力中の文字や選択の解除にも Esc を使うため、図形が何も選ばれていないときだけ閉じる
    if (e.key === 'Escape' && full && !document.activeElement?.closest('.excalidraw textarea')) {
      const selected = host.querySelector('.excalidraw .selected-shape-actions, .excalidraw .App-menu__left .panelColumn')
      if (!selected) { e.stopPropagation(); toggleFull() }
    }
  }
  function toggleFull() {
    full = !full
    document.body.style.overflow = full ? 'hidden' : ''
    if (full) document.addEventListener('keydown', onEsc, true)
    else document.removeEventListener('keydown', onEsc, true)
    render()
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
    const h0 = height()
    const el = e.currentTarget as HTMLElement
    el.setPointerCapture(e.pointerId)
    let hh = h0
    const move = (ev: PointerEvent) => {
      hh = Math.max(240, Math.min(2000, Math.round(h0 + ev.clientY - y0)))
      host.style.height = `${hh}px`
    }
    const up = () => {
      el.removeEventListener('pointermove', move)
      el.removeEventListener('pointerup', up)
      c.updateAttributes({ height: hh })
      relayout()
    }
    el.addEventListener('pointermove', move)
    el.addEventListener('pointerup', up)
  }

  async function exportPng() {
    const b = await board?.exportPng()
    if (b) download('whiteboard.png', b)
  }
  function comment() {
    if (full) toggleFull()
    window.dispatchEvent(new CustomEvent('skiwi:wb-comment', { detail: { id: String(c.node().attrs.id) } }))
  }
  async function remove() {
    if (!(await confirmDialog('このホワイトボードを削除しますか？', { okLabel: '削除', danger: true }))) return
    c.deleteNode()
  }

  function render() {
    const editable = c.editor.isEditable
    const node = c.node()
    dom.classList.toggle('full', full)
    dom.classList.toggle('selected', c.selected())
    dom.setAttribute('data-whiteboard-id', String(node.attrs.id ?? ''))
    title.draggable = editable && !full
    title.textContent = `${editable && !full ? '⠿ ' : ''}🧩 ホワイトボード`
    if (editable) title.title = 'ここをドラッグするとブロックを移動できます'
    else title.removeAttribute('title')
    hint.textContent = editable ? 'ドラッグで描画・Space+ドラッグで移動・Ctrl/⌘+ホイールで拡大縮小' : '閲覧のみ（ホイール・ドラッグで移動）'
    setChildren(bar, title, hint,
      !guestContext() && h('button', { title: 'このホワイトボードにコメント', 'data-testid': 'wb-comment', onclick: comment }, '💬'),
      state === 'ready' && h('button', { title: 'PNG画像として保存', onclick: () => void exportPng() }, '⤓ PNG'),
      h('button', { title: full ? '元に戻す（Esc）' : '全画面で開く', 'data-testid': 'wb-full', onclick: toggleFull }, full ? '✕ 閉じる' : '⤢ 全画面'),
      editable && !full && h('button', { class: 'danger', title: 'ホワイトボードを削除', onclick: () => void remove() }, '🗑'))
    host.style.height = full ? '' : `${height()}px`
    if (state === 'ready') loading.remove()
    else loadingText.textContent = state === 'error' ? 'ホワイトボードを読み込めませんでした' : 'ホワイトボードを読み込み中…'
    if (editable && !full) { if (!resize.parentNode) dom.append(resize) } else resize.remove()
    if (editable !== lastEditable) { lastEditable = editable; board?.setEditable(editable) }
    // Y.Doc なしの表示（版のプレビューなど）で内容が差し替わったとき
    if (node.attrs.snapshot !== lastSnapshot) {
      lastSnapshot = node.attrs.snapshot
      if (!opts.ydoc) board?.setSnapshot(parseSnapshot(lastSnapshot))
    }
    fixDrag()
  }

  return {
    dom, render,
    destroy: () => {
      alive = false
      io.disconnect()
      clearTimeout(saveTimer)
      stopTheme()
      board?.destroy()
      document.removeEventListener('keydown', onEsc, true)
      if (full) document.body.style.overflow = ''
    },
  }
}, {
  // ボード内の操作（キー入力・貼り付け・ドラッグなど）はエディタに渡さない
  stopEvent: ({ event }) => !(event.target as HTMLElement)?.closest?.('[data-drag-handle]'),
})
