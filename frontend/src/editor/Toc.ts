import { Node, mergeAttributes } from '@tiptap/core'
import type { Node as PMNode } from '@tiptap/pm/model'
import { TocView } from './TocView'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    toc: { insertToc: () => ReturnType }
  }
}

export interface Heading { level: number; text: string; pos: number }

/** 文書内の見出し（表やパネルの中も含む）。 */
export function collectHeadings(doc: PMNode, maxLevel = 3): Heading[] {
  const out: Heading[] = []
  doc.descendants((n, pos) => {
    if (n.type.name === 'heading') {
      const level = n.attrs.level as number
      if (level <= maxLevel && n.textContent.trim()) out.push({ level, text: n.textContent, pos })
      return false
    }
    return true
  })
  return out
}

/** 見出しの位置までスクロールする（表示モード・編集モード共通）。 */
export function scrollToHeading(view: { nodeDOM: (pos: number) => globalThis.Node | null }, pos: number) {
  const el = view.nodeDOM(pos) as HTMLElement | null
  el?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

/** 目次マクロ。本文の見出しから自動で作られ、見出しを編集すると即座に反映される。 */
export const Toc = Node.create({
  name: 'toc',
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,
  addAttributes() {
    return {
      maxLevel: {
        default: 3,
        parseHTML: (el) => Number(el.getAttribute('data-max-level')) || 3,
        renderHTML: (a) => ({ 'data-max-level': a.maxLevel }),
      },
    }
  },
  parseHTML() { return [{ tag: 'nav[data-toc]' }] },
  renderHTML({ HTMLAttributes }) { return ['nav', mergeAttributes(HTMLAttributes, { 'data-toc': '' })] },
  renderText() { return '' }, // 検索用の本文には含めない
  addNodeView() { return TocView },
  addCommands() {
    return { insertToc: () => ({ commands }) => commands.insertContent({ type: this.name }) }
  },
})
