import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import MermaidView from './MermaidView.vue'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    mermaid: { insertMermaid: (code?: string) => ReturnType }
  }
}

const DEFAULT_MERMAID = 'flowchart LR\n  A[開始] --> B{条件}\n  B -->|はい| C[処理]\n  B -->|いいえ| D[終了]'

/** Mermaid図。コードを属性に持ち、表示時にSVGへ描画する（同時編集では属性単位で同期）。 */
export const Mermaid = Node.create({
  name: 'mermaid',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() { return { code: { default: DEFAULT_MERMAID } } },
  parseHTML() { return [{ tag: 'div[data-mermaid]', getAttrs: (el) => ({ code: (el as HTMLElement).textContent ?? '' }) }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-mermaid': '' }), ['pre', {}, node.attrs.code]]
  },
  renderText({ node }) { return node.attrs.code },
  addNodeView() { return VueNodeViewRenderer(MermaidView) },
  addCommands() {
    return { insertMermaid: (code) => ({ commands }) => commands.insertContent({ type: this.name, attrs: { code: code ?? DEFAULT_MERMAID } }) }
  },
})
