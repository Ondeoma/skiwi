import { Node, mergeAttributes } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import ColumnsView from './ColumnsView.vue'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    columns: { insertColumns: (count: number) => ReturnType }
  }
}

export const MIN_COLUMNS = 2
export const MAX_COLUMNS = 12

/** 列の幅の比（例: "1,2"）。数が合わない・不正なときは均等 */
export function parseLayout(layout: unknown, count: number): number[] {
  const xs = String(layout ?? '').split(',').map((x) => Number(x.trim()))
  return xs.length === count && xs.every((x) => Number.isFinite(x) && x > 0 && x <= 12) ? xs : Array(count).fill(1)
}

/** 段組みの1列 */
export const Column = Node.create({
  name: 'column',
  content: 'block+',
  isolating: true,
  defining: true,
  parseHTML() { return [{ tag: 'div[data-column]' }] },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-column': '', class: 'column' }), 0] },
})

/** 段組み（2〜12列を横に並べる）。layout は列の幅の比（"1,2" など） */
export const Columns = Node.create({
  name: 'columns',
  group: 'block',
  content: `column{${MIN_COLUMNS},${MAX_COLUMNS}}`,
  isolating: true,
  defining: true,
  draggable: true,
  addAttributes() {
    return {
      layout: { default: null, parseHTML: (el) => el.getAttribute('data-layout'), renderHTML: (a) => (a.layout ? { 'data-layout': a.layout } : {}) },
    }
  },
  parseHTML() { return [{ tag: 'div[data-columns]' }] },
  renderHTML({ node, HTMLAttributes }) {
    const spans = parseLayout(node.attrs.layout, node.childCount)
    return ['div', mergeAttributes(HTMLAttributes, { 'data-columns': '', class: 'columns', style: `grid-template-columns: ${spans.map((s) => `minmax(0, ${s}fr)`).join(' ')}` }), 0]
  },
  addNodeView() { return VueNodeViewRenderer(ColumnsView) },
  addCommands() {
    return {
      insertColumns: (count) => ({ commands }) => {
        const n = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, Math.round(count)))
        return commands.insertContent({
          type: this.name,
          content: Array.from({ length: n }, () => ({ type: 'column', content: [{ type: 'paragraph' }] })),
        })
      },
    }
  },
})

/** 列の数を変えた段組みを作る。減らすときは、消える列の中身を残る最後の列の末尾へ移す（内容を失わない） */
export function resizeColumns(node: PMNode, count: number): PMNode {
  const schema = node.type.schema
  const cols: PMNode[] = []
  node.forEach((c) => cols.push(c))
  const n = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, count))
  if (n === cols.length) return node
  let next: PMNode[]
  if (n > cols.length) {
    next = [...cols, ...Array.from({ length: n - cols.length }, () => schema.nodes.column.create(null, schema.nodes.paragraph.create()))]
  } else {
    const keep = cols.slice(0, n)
    const moved: PMNode[] = []
    cols.slice(n).forEach((c) => c.forEach((b) => { if (!(b.type.name === 'paragraph' && b.content.size === 0)) moved.push(b) }))
    const last = keep[n - 1]
    const blocks: PMNode[] = []
    last.forEach((b) => blocks.push(b))
    keep[n - 1] = schema.nodes.column.create(last.attrs, [...blocks, ...moved])
    next = keep
  }
  return node.type.create({ ...node.attrs, layout: null }, next)
}
