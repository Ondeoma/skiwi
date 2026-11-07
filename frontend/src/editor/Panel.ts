import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import PanelView from './PanelView.vue'
import { isColor, type ColorId } from '../ui/palette'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    panel: { setPanel: (attrs?: { icon?: string | null; color?: ColorId }) => ReturnType }
  }
}

/** よく使うパネルの組み合わせ（情報・注意・成功・エラー・メモ）。 */
export const PANEL_PRESETS = [
  { title: '情報パネル', icon: 'ℹ️', color: 'blue', keywords: ['info', 'panel', 'じょうほう', 'ぱねる', 'callout'] },
  { title: '注意パネル', icon: '⚠️', color: 'yellow', keywords: ['warning', 'caution', 'note', 'ちゅうい', 'けいこく'] },
  { title: '成功パネル', icon: '✅', color: 'green', keywords: ['success', 'tip', 'せいこう', 'ok'] },
  { title: 'エラーパネル', icon: '⛔', color: 'red', keywords: ['error', 'danger', 'えらー', 'きけん'] },
  { title: 'メモパネル', icon: '📝', color: 'purple', keywords: ['memo', 'note', 'めも'] },
] as const

/** アイコンと背景色を持つ囲みブロック。中には任意のブロック（段落・リスト・表など）を入れられる。 */
export const Panel = Node.create({
  name: 'panel',
  group: 'block',
  content: 'block+',
  defining: true,
  draggable: true,
  addAttributes() {
    return {
      icon: {
        default: 'ℹ️',
        parseHTML: (el) => el.getAttribute('data-icon') || null,
        renderHTML: (a) => (a.icon ? { 'data-icon': a.icon } : {}),
      },
      color: {
        default: 'blue',
        parseHTML: (el) => { const c = el.getAttribute('data-color'); return isColor(c) ? c : 'blue' },
        renderHTML: (a) => ({ 'data-color': a.color }),
      },
    }
  },
  parseHTML() { return [{ tag: 'div[data-panel]' }] },
  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-panel': '', class: 'panel' }), 0]
  },
  addNodeView() { return VueNodeViewRenderer(PanelView) },
  addCommands() {
    return {
      setPanel: (attrs) => ({ commands }) => commands.wrapIn(this.name, attrs),
    }
  },
})
