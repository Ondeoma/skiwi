import { Extension, Mark, mergeAttributes } from '@tiptap/core'
import { COLORS } from '../ui/palette'

/** ブロックへのリンク用の ID（リンクをコピーしたときに付ける）。data-block-id として描画する */
export const BLOCK_TYPES = ['paragraph', 'heading', 'blockquote', 'codeBlock', 'bulletList', 'orderedList', 'taskList', 'details', 'table',
  'image', 'panel', 'fileBlock', 'embed', 'mermaid', 'whiteboard', 'columns', 'toc', 'syncedBlock', 'syncedRef', 'linkCard', 'databaseBlock', 'horizontalRule']

export const BlockId = Extension.create({
  name: 'blockId',
  addGlobalAttributes() {
    return [{
      types: BLOCK_TYPES,
      attributes: {
        blockId: {
          default: null,
          keepOnSplit: false,
          parseHTML: (el) => el.getAttribute('data-block-id'),
          renderHTML: (a) => (a.blockId ? { 'data-block-id': a.blockId } : {}),
        },
      },
    }]
  },
})

const isColor = (v: unknown) => typeof v === 'string' && COLORS.some((c) => c.id === v)

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    textColor: {
      /** 文字色（color）・背景色（bg）。色名で保存し、実際の色はCSS変数（ライト/ダーク別） */
      setTextColor: (a: { color?: string | null; bg?: string | null }) => ReturnType
      unsetTextColor: () => ReturnType
    }
  }
}

/** 文字の色・背景色 */
export const TextColor = Mark.create({
  name: 'textColor',
  addAttributes() {
    return {
      color: { default: null, parseHTML: (el) => (isColor(el.getAttribute('data-text-color')) ? el.getAttribute('data-text-color') : null), renderHTML: (a) => (a.color ? { 'data-text-color': a.color } : {}) },
      bg: { default: null, parseHTML: (el) => (isColor(el.getAttribute('data-text-bg')) ? el.getAttribute('data-text-bg') : null), renderHTML: (a) => (a.bg ? { 'data-text-bg': a.bg } : {}) },
    }
  },
  parseHTML() { return [{ tag: 'span[data-text-color]' }, { tag: 'span[data-text-bg]' }] },
  renderHTML({ HTMLAttributes }) { return ['span', mergeAttributes(HTMLAttributes), 0] },
  addCommands() {
    return {
      setTextColor: (a) => ({ editor, chain }) => {
        const cur = editor.getAttributes(this.name)
        const next = { color: a.color === undefined ? cur.color ?? null : a.color, bg: a.bg === undefined ? cur.bg ?? null : a.bg }
        return next.color || next.bg ? chain().setMark(this.name, next).run() : chain().unsetMark(this.name).run()
      },
      unsetTextColor: () => ({ chain }) => chain().unsetMark(this.name).run(),
    }
  },
})
