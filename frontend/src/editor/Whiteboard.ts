import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import type * as Y from 'yjs'
import WhiteboardView from './WhiteboardView.vue'
import { snapshotText, type Awareness, type Snapshot } from './whiteboard/shared'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    whiteboard: { insertWhiteboard: () => ReturnType }
  }
}

export interface WhiteboardOptions {
  /** 同時編集用（ページの Y.Doc）。版のプレビュー・ゲスト表示では null（スナップショットを読み取り専用で表示） */
  ydoc: Y.Doc | null
  awareness: Awareness | null
  user: { name: string; color: string } | null
  uploadFile: ((file: File) => Promise<string>) | null
}

const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`)

export function parseSnapshot(raw: unknown): Snapshot | null {
  if (!raw) return null
  try { return typeof raw === 'string' ? JSON.parse(raw) : (raw as Snapshot) } catch { return null }
}

/** ホワイトボード（Excalidraw）。中身は Y.Doc で同時編集し、表示用のスナップショットを snapshot 属性に保存する。 */
export const Whiteboard = Node.create<WhiteboardOptions>({
  name: 'whiteboard',
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,

  addOptions() { return { ydoc: null, awareness: null, user: null, uploadFile: null } },

  addAttributes() {
    return {
      // 貼り付けで複製した場合は別のボードにする（同じIDだと中身が連動してしまう）
      id: { default: null, parseHTML: () => newId(), renderHTML: (a) => ({ 'data-id': a.id }) },
      height: { default: 520, parseHTML: (el) => Number(el.getAttribute('data-height')) || 520, renderHTML: (a) => ({ 'data-height': a.height }) },
      snapshot: { default: null, parseHTML: (el) => el.getAttribute('data-snapshot'), renderHTML: (a) => (a.snapshot ? { 'data-snapshot': a.snapshot } : {}) },
    }
  },
  parseHTML() { return [{ tag: 'div[data-whiteboard]' }] },
  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-whiteboard': '', class: 'whiteboard-static' }), '🧩 ホワイトボード']
  },
  renderText({ node }) { return snapshotText(parseSnapshot(node.attrs.snapshot)) },

  addNodeView() {
    // ボード内の操作（キー入力・貼り付け・ドラッグなど）はエディタに渡さない
    return VueNodeViewRenderer(WhiteboardView, { stopEvent: ({ event }) => !(event.target as HTMLElement)?.closest?.('[data-drag-handle]') })
  },

  addCommands() {
    return {
      insertWhiteboard: () => ({ commands }) => commands.insertContent({ type: this.name, attrs: { id: newId() } }),
    }
  },
})
