import { domNodeView } from './nodeView'
import { MAX_COLUMNS, MIN_COLUMNS, parseLayout, resizeColumns } from './Columns'
import { promptDialog } from '../ui/dialog'
import { handleOnlyDrag } from './dragHandle'
import { h, setChildren } from '../ui/dom'

/** よく使う比（12等分の目盛りで考えたもの） */
const PRESETS: Record<number, { label: string; layout: string | null }[]> = {
  2: [{ label: '1 : 1', layout: null }, { label: '1 : 2', layout: '1,2' }, { label: '2 : 1', layout: '2,1' }, { label: '1 : 3', layout: '1,3' }, { label: '3 : 1', layout: '3,1' }],
  3: [{ label: '1 : 1 : 1', layout: null }, { label: '1 : 2 : 1', layout: '1,2,1' }, { label: '2 : 1 : 1', layout: '2,1,1' }, { label: '1 : 1 : 2', layout: '1,1,2' }],
  4: [{ label: '均等', layout: null }, { label: '1 : 2 : 2 : 1', layout: '1,2,2,1' }],
}
const COUNTS = Array.from({ length: MAX_COLUMNS - MIN_COLUMNS + 1 }, (_, i) => i + MIN_COLUMNS)

/** 段組み: 編集中は上に列数・幅の比の操作を出す（移動は「⠿ 段組み」のつまみでだけ） */
export const ColumnsView = domNodeView((c) => {
  const count = () => c.node().childCount
  const spans = () => parseLayout(c.node().attrs.layout, count())

  function setCount(n: number) {
    const pos = c.getPos()
    if (typeof pos !== 'number') return
    const node = c.editor.state.doc.nodeAt(pos) ?? c.node()
    c.editor.view.dispatch(c.editor.state.tr.replaceWith(pos, pos + node.nodeSize, resizeColumns(node, n)))
  }
  const setLayout = (layout: string | null) => c.updateAttributes({ layout })
  async function customLayout() {
    const n = count()
    const v = await promptDialog(`${n}列の幅の比をカンマ区切りで（例: ${Array.from({ length: n }, (_, i) => (i === 0 ? 2 : 1)).join(',')}）。合計12にすると12等分の目盛りで指定できます`, spans().join(','), { okLabel: '設定' })
    if (v === null) return
    const xs = v.split(/[,、\s:]+/).filter(Boolean).map(Number)
    if (xs.length === n && xs.every((x) => Number.isFinite(x) && x > 0 && x <= 12)) setLayout(xs.join(','))
  }

  const content = h('div', { class: 'columns' })
  const anchor = h('span', { class: 'cb-anchor' })
  const bar = h('div', { class: 'columns-bar', contenteditable: 'false' })
  const dom = h('div', { class: 'columns-wrap', 'data-testid': 'columns' }, anchor, content)
  const fixDrag = handleOnlyDrag(dom)

  const render = () => {
    const editable = c.editor.isEditable
    dom.classList.toggle('editing', editable)
    content.style.gridTemplateColumns = spans().map((s) => `minmax(0, ${s}fr)`).join(' ')
    if (editable) {
      const n = count()
      const current = (c.node().attrs.layout as string | null) ?? null
      const select = h('select', { 'data-testid': 'columns-count', onchange: () => setCount(Number(select.value)) }, COUNTS.map((k) => h('option', { value: String(k) }, String(k))))
      select.value = String(n)
      setChildren(bar,
        h('span', { class: 'cb-handle', 'data-drag-handle': '', draggable: 'true', title: 'ここをドラッグするとブロックを移動できます' }, '⠿ 段組み'),
        h('label', null, '列数', select),
        h('span', { class: 'cb-presets' },
          (PRESETS[n] ?? [{ label: '均等', layout: null }]).map((p) => h('button', { class: current === p.layout ? 'on' : undefined, onclick: () => setLayout(p.layout) }, p.label)),
          h('button', { title: '幅の比を数字で指定', onclick: () => void customLayout() }, '比を指定…')),
        h('button', { class: 'cb-del', title: '段組みを削除（中身も消えます）', onclick: () => c.deleteNode() }, '🗑'))
    }
    const first = editable ? bar : anchor
    if (dom.firstChild !== first) dom.replaceChild(first, dom.firstChild!)
    fixDrag()
  }
  return { dom, contentDOM: content, render }
})
