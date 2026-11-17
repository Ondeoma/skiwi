import { TableCell, TableHeader } from '@tiptap/extension-table'
import { isColor } from '../lib'

/** セル背景色。色名（palette.ts）を data-bg に保存し、実際の色はCSSで決める（ダークモード対応）。 */
const backgroundColor = {
  backgroundColor: {
    default: null,
    parseHTML: (el: HTMLElement) => { const v = el.getAttribute('data-bg'); return isColor(v) ? v : null },
    renderHTML: (a: Record<string, unknown>) => (a.backgroundColor ? { 'data-bg': a.backgroundColor } : {}),
  },
}

export const ColoredTableCell = TableCell.extend({
  addAttributes() { return { ...this.parent?.(), ...backgroundColor } },
})
export const ColoredTableHeader = TableHeader.extend({
  addAttributes() { return { ...this.parent?.(), ...backgroundColor } },
})
