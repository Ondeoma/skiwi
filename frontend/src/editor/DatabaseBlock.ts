import { Node, mergeAttributes } from '@tiptap/core'
import { DatabaseBlockView } from './DatabaseBlockView'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    databaseBlock: { insertDatabaseBlock: (databaseId: string) => ReturnType }
  }
}

/** ページの中に置いたデータベース（リンクビュー）。項目と列は元のデータベースのもの、ビューはこの場所だけのもの（views に JSON で保存） */
export const DatabaseBlock = Node.create({
  name: 'databaseBlock',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() {
    return {
      databaseId: { default: null, parseHTML: (el) => el.getAttribute('data-database-id'), renderHTML: (a) => ({ 'data-database-id': a.databaseId }) },
      views: { default: null, parseHTML: (el) => el.getAttribute('data-views'), renderHTML: (a) => (a.views ? { 'data-views': a.views } : {}) },
    }
  },
  parseHTML() { return [{ tag: 'div[data-database-block]' }] },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-database-block': '' }), '（データベース）'] },
  renderText() { return '（データベース）' },
  addNodeView() { return DatabaseBlockView },
  addCommands() {
    return { insertDatabaseBlock: (databaseId) => ({ commands }) => commands.insertContent({ type: this.name, attrs: { databaseId } }) }
  },
})
