import type { Editor } from '@tiptap/core'
import { CellSelection } from '@tiptap/pm/tables'
import { confirmDialog } from '../ui/dialog'
import { COLORS, onDismiss, type ColorId } from '../lib'
import { h } from '../ui/dom'

type Chain = ReturnType<Editor['chain']>

/**
 * 表の操作。カーソルが表の中にあるとき、表の左側の余白（本文と重ならない位置）に小さなボタンを出し、
 * クリックで操作メニューを開く。container は位置計算の基準（position: relative の要素）で、ボタンとメニューもここに置く。
 * 戻り値で片付ける
 */
export function tableControls(editor: Editor, container: HTMLElement): () => void {
  let open = false
  let cellColor: string | null = null
  let headerRow = false
  let pos: { left: number; top: number } | null = null

  const btn = h('button', {
    class: 'table-handle', title: '表の操作', 'data-testid': 'table-handle',
    onmousedown: (e: Event) => e.preventDefault(),
    onclick: () => { open = !open; draw() },
  }, '⋮')
  const menu = h('div', { class: 'table-menu', 'data-testid': 'table-menu', onmousedown: (e: Event) => e.preventDefault() })

  /** 選択位置のセル（td/th）と表のDOM。表の外なら null。 */
  function current(): { cell: HTMLElement; table: HTMLElement; attrs: Record<string, unknown> } | null {
    const { state, view } = editor
    const $from = state.selection.$from
    for (let d = $from.depth; d > 0; d--) {
      const n = $from.node(d)
      if (n.type.name === 'tableCell' || n.type.name === 'tableHeader') {
        const cell = view.nodeDOM($from.before(d)) as HTMLElement | null
        const table = (cell?.closest('.tableWrapper') ?? cell?.closest('table')) as HTMLElement | null
        return cell && table ? { cell, table, attrs: n.attrs } : null
      }
    }
    return null
  }

  function update() {
    if (editor.isDestroyed) return
    const c = editor.isEditable ? current() : null
    if (!c) { pos = null; open = false; draw(); return }
    const base = container.getBoundingClientRect()
    const t = c.table.getBoundingClientRect()
    const r = c.cell.getBoundingClientRect()
    // 表の左外側・現在の行の高さに置く
    pos = { left: t.left - base.left - 28, top: r.top - base.top + Math.min(6, Math.max(0, r.height / 2 - 11)) }
    cellColor = (c.attrs.backgroundColor as string | null) ?? null
    headerRow = editor.isActive('tableHeader')
    draw()
  }

  function run(f: (c: Chain) => Chain) { f(editor.chain().focus()).run() }
  function setColor(c: ColorId | null) {
    run((ch) => ch.setCellAttribute('backgroundColor', c))
    cellColor = c
    draw()
  }
  async function deleteTable() {
    open = false
    draw()
    if (await confirmDialog('表を削除しますか？', { okLabel: '削除', danger: true })) editor.chain().focus().deleteTable().run()
  }

  const grp = (label: string | null, ...children: (HTMLElement | HTMLElement[])[]) => h('div', { class: 'grp' }, label && h('span', { class: 'lbl' }, label), ...children)
  /** 前に描いたメニューの状態（変わったときだけ描き直す。描き直すとクリック中のボタンが消えるため） */
  let drawn = ''
  function drawMenu() {
    const multi = editor.state.selection instanceof CellSelection
    const sig = `${cellColor}|${headerRow}|${multi}`
    if (sig === drawn && menu.childElementCount) return
    drawn = sig
    menu.replaceChildren(
      grp('行',
        h('button', { title: '上に行を追加', onclick: () => run((c) => c.addRowBefore()) }, '↑ 上に追加'),
        h('button', { title: '下に行を追加', onclick: () => run((c) => c.addRowAfter()) }, '↓ 下に追加'),
        h('button', { class: 'danger', title: '行を削除', onclick: () => run((c) => c.deleteRow()) }, '削除')),
      grp('列',
        h('button', { title: '左に列を追加', onclick: () => run((c) => c.addColumnBefore()) }, '← 左に追加'),
        h('button', { title: '右に列を追加', onclick: () => run((c) => c.addColumnAfter()) }, '→ 右に追加'),
        h('button', { class: 'danger', title: '列を削除', onclick: () => run((c) => c.deleteColumn()) }, '削除')),
      h('div', { class: 'grp colors' },
        h('span', { class: 'lbl' }, '背景'),
        h('button', { class: `swatch none${!cellColor ? ' on' : ''}`, title: 'なし', 'data-testid': 'cell-color-none', onclick: () => setColor(null) }, '∅'),
        COLORS.map((c) => h('button', { class: `swatch${cellColor === c.id ? ' on' : ''}`, 'data-color': c.id, title: c.label, 'data-testid': `cell-color-${c.id}`, onclick: () => setColor(c.id) }))),
      grp('表',
        h('button', { class: headerRow ? 'on' : undefined, title: '先頭行を見出しにする／戻す', onclick: () => run((c) => c.toggleHeaderRow()) }, '見出し行'),
        h('button', { title: '先頭列を見出しにする／戻す', onclick: () => run((c) => c.toggleHeaderColumn()) }, '見出し列'),
        h('button', { title: multi ? '選択したセルを結合' : '結合を解除（複数セルをドラッグで選ぶと結合）', onclick: () => run((c) => c.mergeOrSplit()) }, '結合/分割')),
      grp(null, h('button', { class: 'danger wide', title: '表を削除', onclick: () => void deleteTable() }, '🗑 表を削除')))
  }

  function draw() {
    if (!pos) { btn.remove(); menu.remove(); return }
    btn.style.left = `${pos.left}px`
    btn.style.top = `${pos.top}px`
    btn.classList.toggle('on', open)
    if (!btn.parentNode) container.append(btn)
    if (open) {
      menu.style.left = `${pos.left}px`
      menu.style.top = `${pos.top + 26}px`
      drawMenu()
      if (!menu.parentNode) container.append(menu)
    } else menu.remove()
  }

  const stopDismiss = onDismiss((t) => !open || menu.contains(t) || btn.contains(t), () => { open = false; draw() }, {
    escape: () => { if (open) { open = false; draw(); editor.commands.focus() } },
  })
  editor.on('selectionUpdate', update)
  editor.on('update', update)
  editor.on('transaction', update)
  window.addEventListener('resize', update)
  update()
  return () => {
    stopDismiss()
    editor.off('selectionUpdate', update)
    editor.off('update', update)
    editor.off('transaction', update)
    window.removeEventListener('resize', update)
    btn.remove()
    menu.remove()
  }
}
