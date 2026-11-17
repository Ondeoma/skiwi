import { InputRule, Node, mergeAttributes } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { PageLinkView } from './PageLinkView'
import { pageHref, parsePageUrl } from '../lib'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    pageLink: { insertPageLink: (attrs: { pageId: string; workspaceId: string; title?: string }) => ReturnType }
  }
}

/** このシステムのページへのリンク。表示時に最新のページタイトル・アイコンを出す。
  * title 属性は挿入時点のタイトル（検索用の平文や、取得できないときの予備）。 */
export const PageLink = Node.create({
  name: 'pageLink',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  // URLの貼り付けを Link（自動リンク）より先に処理する
  priority: 1100,

  addAttributes() {
    return {
      pageId: { default: null, parseHTML: (el) => el.getAttribute('data-page-id'), renderHTML: () => ({}) },
      workspaceId: { default: null, parseHTML: (el) => el.getAttribute('data-workspace-id'), renderHTML: () => ({}) },
      title: { default: '', parseHTML: (el) => el.textContent ?? '', renderHTML: () => ({}) },
      // ページの中のブロックへのリンク（#block-ID）
      anchor: { default: null, parseHTML: (el) => el.getAttribute('data-anchor'), renderHTML: () => ({}) },
    }
  },
  parseHTML() { return [{ tag: 'a[data-page-link]' }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['a', mergeAttributes({
      'data-page-link': '', 'data-page-id': node.attrs.pageId, 'data-workspace-id': node.attrs.workspaceId,
      href: pageHref(node.attrs.workspaceId, node.attrs.pageId) + (node.attrs.anchor ? `#block-${node.attrs.anchor}` : ''), class: 'page-link',
      ...(node.attrs.anchor ? { 'data-anchor': node.attrs.anchor } : {}),
    }, HTMLAttributes), node.attrs.title || '無題']
  },
  renderText({ node }) { return node.attrs.title || '無題' },

  addNodeView() { return PageLinkView },

  addCommands() {
    return {
      insertPageLink: (attrs) => ({ chain }) =>
        chain().insertContent([{ type: this.name, attrs: { title: '', ...attrs } }, { type: 'text', text: ' ' }]).run(),
    }
  },

  // 入力: ページURLの後に空白を打つとページリンクにする
  addInputRules() {
    return [
      new InputRule({
        find: /(\S+)\s$/,
        handler: ({ state, range, match }) => {
          const p = parsePageUrl(match[1])
          if (!p) return null
          const node = this.type.create({ pageId: p.pageId, workspaceId: p.workspaceId, title: '', anchor: p.anchor ?? null })
          state.tr.replaceWith(range.from, range.to, [node, state.schema.text(' ')])
        },
      }),
    ]
  },

  // 貼り付け: ページURLだけを貼ったら（文字を選択していなければ）ページリンクにする
  addProseMirrorPlugins() {
    const type = this.type
    return [
      new Plugin({
        key: new PluginKey('pageLinkPaste'),
        props: {
          handlePaste(view, event) {
            const text = event.clipboardData?.getData('text/plain') ?? ''
            const p = parsePageUrl(text)
            if (!p || !view.state.selection.empty) return false
            const { tr, schema } = view.state
            // 同期ブロックのリンクは埋め込みにする
            if (p.blockId && schema.nodes.syncedRef) {
              view.dispatch(tr.replaceSelectionWith(schema.nodes.syncedRef.create({ pageId: p.pageId, workspaceId: p.workspaceId, blockId: p.blockId })).scrollIntoView())
              return true
            }
            view.dispatch(tr.replaceSelectionWith(type.create({ pageId: p.pageId, workspaceId: p.workspaceId, title: '', anchor: p.anchor ?? null }), false).insertText(' ').scrollIntoView())
            return true
          },
        },
      }),
    ]
  },
})
