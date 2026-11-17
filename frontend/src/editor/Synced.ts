import { Node, mergeAttributes } from '@tiptap/core'
import { SyncedBlockView } from './SyncedBlockView'
import { SyncedRefView } from './SyncedRefView'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    synced: {
      setSyncedBlock: () => ReturnType
      insertSyncedRef: (attrs: { pageId: string; workspaceId: string; blockId: string | null }) => ReturnType
    }
  }
}

const newId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}${Math.random()}`).replace(/-/g, '').slice(0, 16)

/** 同期ブロック（元）。ほかのページに埋め込める範囲。中身は普通に編集する。 */
export const SyncedBlock = Node.create({
  name: 'syncedBlock',
  group: 'block',
  content: 'block+',
  defining: true,
  addAttributes() {
    // 貼り付けで複製したときは別のブロックにする
    return { id: { default: null, parseHTML: () => newId(), renderHTML: (a) => ({ 'data-synced-id': a.id }) } }
  },
  parseHTML() { return [{ tag: 'div[data-synced-block]' }] },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-synced-block': '', class: 'synced-block' }), 0] },
  addNodeView() { return SyncedBlockView },
  addCommands() {
    return { setSyncedBlock: () => ({ commands }) => commands.wrapIn(this.name, { id: newId() }) }
  },
})

/** 同期ブロックの埋め込み（読み取り専用。元のページを直すと、ここにも反映される）。blockId が null ならページ全体。 */
export const SyncedRef = Node.create<{ depth: number }>({
  name: 'syncedRef',
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,
  addOptions() { return { depth: 0 } },
  addAttributes() {
    return {
      pageId: { default: null, parseHTML: (el) => el.getAttribute('data-page-id'), renderHTML: (a) => ({ 'data-page-id': a.pageId }) },
      workspaceId: { default: null, parseHTML: (el) => el.getAttribute('data-workspace-id'), renderHTML: (a) => ({ 'data-workspace-id': a.workspaceId }) },
      blockId: { default: null, parseHTML: (el) => el.getAttribute('data-block-id'), renderHTML: (a) => (a.blockId ? { 'data-block-id': a.blockId } : {}) },
    }
  },
  parseHTML() { return [{ tag: 'div[data-synced-ref]' }] },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-synced-ref': '', class: 'synced-ref-static' }), '🔗 同期ブロック'] },
  // 検索の本文には含めない（元のページで見つかる）
  renderText() { return '' },
  addNodeView() { return SyncedRefView },
  addCommands() {
    return { insertSyncedRef: (attrs) => ({ commands }) => commands.insertContent({ type: this.name, attrs }) }
  },
})

/** 文書の中の同期ブロックを探す（埋め込み先の選択用） */
export function findSyncedBlocks(doc: unknown): { id: string; text: string; node: Record<string, unknown> }[] {
  const out: { id: string; text: string; node: Record<string, unknown> }[] = []
  const textOf = (n: any): string => (n.text ?? '') + (n.content ?? []).map(textOf).join(' ')
  const walk = (n: any) => {
    if (!n || typeof n !== 'object') return
    if (n.type === 'syncedBlock' && n.attrs?.id) out.push({ id: n.attrs.id, text: textOf(n).replace(/\s+/g, ' ').trim(), node: n })
    ;(n.content ?? []).forEach(walk)
  }
  walk(doc)
  return out
}
