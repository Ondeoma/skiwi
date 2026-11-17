import type { Editor } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import { COLORS, onDismiss } from '../lib'
import { alertDialog, alertError, confirmDialog } from '../ui/dialog'
import { host } from '../ui/host'
import { appendToPage } from '../ui/moveBlock'
import { BLOCK_TYPES } from './BlockAttrs'
import { h, setChildren } from '../ui/dom'

type Chain = ReturnType<Editor['chain']>
const TURN: { label: string; icon: string; run: (c: Chain) => Chain }[] = [
  { label: 'テキスト', icon: '¶', run: (c) => c.clearNodes().setParagraph() },
  { label: '見出し1', icon: 'H1', run: (c) => c.clearNodes().setHeading({ level: 1 }) },
  { label: '見出し2', icon: 'H2', run: (c) => c.clearNodes().setHeading({ level: 2 }) },
  { label: '見出し3', icon: 'H3', run: (c) => c.clearNodes().setHeading({ level: 3 }) },
  { label: '箇条書きリスト', icon: '•', run: (c) => c.clearNodes().toggleBulletList() },
  { label: '番号付きリスト', icon: '1.', run: (c) => c.clearNodes().toggleOrderedList() },
  { label: 'ToDoリスト', icon: '☑', run: (c) => c.clearNodes().toggleTaskList() },
  { label: '引用', icon: '❝', run: (c) => c.clearNodes().toggleBlockquote() },
  { label: 'コード', icon: '</>', run: (c) => c.clearNodes().setCodeBlock() },
  { label: '開閉ブロック', icon: '▸', run: (c) => c.clearNodes().setDetails() },
]

/**
 * ブロックのつまみ（⋮⋮）のメニュー: 変換・色・複製・削除・ブロックへのリンク・別のページへ移動。
 * parent に置き、閉じたら onClose を呼ぶ。戻り値で閉じる
 */
export function openBlockMenu(parent: HTMLElement, p: { editor: Editor; node: PMNode; pos: number; at: { x: number; y: number }; pageId: string; onClose: () => void }): () => void {
  const { editor, node, pos } = p
  let tab: 'main' | 'turn' | 'color' = 'main'
  let busy = false
  const isText = ['paragraph', 'heading', 'bulletList', 'orderedList', 'taskList', 'blockquote', 'details'].includes(node.type.name)
  /** 中身の範囲（変換・色はこの範囲の文字に対して行う） */
  const inner = () => ({ from: pos + 1, to: pos + node.nodeSize - 1 })
  const el = h('div', { class: 'block-menu', role: 'menu', 'data-testid': 'block-menu' })
  el.style.left = `${Math.min(p.at.x, window.innerWidth - 250)}px`
  el.style.top = `${Math.min(p.at.y, window.innerHeight - 380)}px`
  let stop = () => {}
  let closed = false
  const close = () => {
    if (closed) return
    closed = true
    stop()
    el.remove()
    p.onClose()
  }
  const done = close

  function turn(t: (typeof TURN)[number]) {
    t.run(editor.chain().focus().setTextSelection(inner())).run()
    done()
  }
  function color(c: { color?: string | null; bg?: string | null }) {
    const ch = editor.chain().focus().setTextSelection(inner())
    if (c.color === null && c.bg === null) ch.unsetTextColor().run()
    else ch.setTextColor(c).run()
    done()
  }
  /** ブロックの JSON（ブロックへのリンクの ID は外す） */
  const copyJson = () => {
    const j = node.toJSON()
    if (j.attrs?.blockId) j.attrs = { ...j.attrs, blockId: null }
    return j
  }
  function duplicate() {
    editor.chain().focus().insertContentAt(pos + node.nodeSize, copyJson()).run()
    done()
  }
  function remove() {
    editor.chain().focus().deleteRange({ from: pos, to: pos + node.nodeSize }).run()
    done()
  }
  async function copyLink() {
    if (!BLOCK_TYPES.includes(node.type.name)) { done(); await alertDialog('このブロックにはリンクを付けられません'); return }
    let id = node.attrs.blockId as string | null
    if (!id) {
      id = Math.random().toString(36).slice(2, 10)
      editor.view.dispatch(editor.state.tr.setNodeAttribute(pos, 'blockId', id))
    }
    const url = `${location.origin}${location.pathname}#block-${id}`
    await navigator.clipboard.writeText(url).catch(() => {})
    done()
    await alertDialog(`ブロックへのリンクをコピーしました。\nほかのページに貼り付けると、このブロックへのリンクになります。\n\n${url}`)
  }
  async function moveTo() {
    if (node.type.name === 'whiteboard') { done(); await alertDialog('ホワイトボードは別のページへ移動できません（中身はこのページに保存されているため）'); return }
    const target = await host().pickPage()
    if (!target) return
    if (target.id === p.pageId) { await alertDialog('同じページです'); return }
    if (!(await confirmDialog(`このブロックを「${target.title || '無題'}」の末尾へ移動しますか？`, { okLabel: '移動' }))) return
    busy = true
    draw()
    const cur0 = editor.state.doc.nodeAt(pos) ?? node
    const json = cur0.toJSON()
    if (json.attrs?.blockId === undefined) delete json.attrs?.blockId
    try {
      await appendToPage(target.id, [json])
      // 移動先に書き込めたら、こちらから消す（そのあいだに位置が変わっていないか確かめる）
      const cur = editor.state.doc.nodeAt(pos)
      if (cur && cur.eq(cur0)) editor.chain().deleteRange({ from: pos, to: pos + cur0.nodeSize }).run()
      done()
      await alertDialog(`「${target.title || '無題'}」の末尾へ移動しました`)
    } catch (e) {
      await alertError('移動できませんでした', e)
    } finally { busy = false; if (!closed) draw() }
  }

  const item = (label: (string | HTMLElement)[], testid: string | undefined, onclick: () => void, extra: Record<string, unknown> = {}) =>
    h('button', { role: 'menuitem', 'data-testid': testid, onclick, ...extra }, label)
  const back = () => h('button', { class: 'back', onclick: () => { tab = 'main'; draw() } }, '‹ 戻る')

  function draw() {
    if (tab === 'main') {
      setChildren(el,
        isText && item(['🔁 変換', h('span', { class: 'arrow' }, '›')], 'block-turn', () => { tab = 'turn'; draw() }),
        isText && item(['🎨 色', h('span', { class: 'arrow' }, '›')], 'block-color', () => { tab = 'color'; draw() }),
        item(['⧉ 複製'], 'block-duplicate', duplicate),
        item(['🔗 ブロックへのリンクをコピー'], 'block-link', () => void copyLink()),
        item(['↪ 別のページへ移動…'], 'block-move', () => void moveTo(), { disabled: busy }),
        h('hr'),
        item(['🗑 削除'], 'block-delete', remove, { class: 'danger' }))
    } else if (tab === 'turn') {
      setChildren(el, back(), TURN.map((t) => item([h('span', { class: 'ic' }, t.icon), t.label], 'block-turn-item', () => turn(t))))
    } else {
      setChildren(el, back(),
        h('p', { class: 'sub' }, '文字の色'),
        h('div', { class: 'swatches' },
          h('button', { class: 'sw', title: '標準', onclick: () => color({ color: null }) }, 'A'),
          COLORS.map((c) => h('button', { class: 'sw', 'data-text-color': c.id, title: c.label, 'data-testid': `block-color-${c.id}`, onclick: () => color({ color: c.id }) }, 'A'))),
        h('p', { class: 'sub' }, '背景の色'),
        h('div', { class: 'swatches' },
          h('button', { class: 'sw', title: 'なし', onclick: () => color({ bg: null }) }, '∅'),
          COLORS.map((c) => h('button', { class: 'sw', 'data-text-bg': c.id, title: c.label, 'data-testid': `block-bg-${c.id}`, onclick: () => color({ bg: c.id }) }, ' '))),
        h('button', { class: 'clear', onclick: () => color({ color: null, bg: null }) }, '色をすべて外す'))
    }
  }
  draw()
  parent.append(el)
  stop = onDismiss((t) => el.contains(t), close, { escape: close, defer: true })
  return close
}
