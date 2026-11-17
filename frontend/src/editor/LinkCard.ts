import { Extension, Node, mergeAttributes } from '@tiptap/core'
import { Plugin, PluginKey } from '@tiptap/pm/state'
import { LinkCardView } from './LinkCardView'
import { parsePageUrl } from '../lib'
import { safeLinkUrl } from '../lib'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    linkCard: { insertLinkCard: (url: string) => ReturnType }
  }
}

/** URL のカード（タイトル・説明・画像・サイト名）。情報は表示したときに取りに行き、ノードに保存する */
export const LinkCard = Node.create({
  name: 'linkCard',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() {
    const a = (k: string) => ({ default: null, parseHTML: (el: HTMLElement) => el.getAttribute(`data-${k}`), renderHTML: (v: Record<string, unknown>) => (v[k] ? { [`data-${k}`]: v[k] } : {}) })
    return { url: a('url'), title: a('title'), description: a('description'), image: a('image'), siteName: a('site'), icon: a('icon') }
  },
  parseHTML() { return [{ tag: 'div[data-link-card]' }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-link-card': '' }), ['a', { href: safeLinkUrl(node.attrs.url), target: '_blank', rel: 'noopener' }, node.attrs.title || node.attrs.url]]
  },
  renderText({ node }) { return `${node.attrs.title ?? ''} ${node.attrs.url}`.trim() },
  addNodeView() { return LinkCardView },
  addCommands() {
    return {
      insertLinkCard: (url) => ({ commands }) => /^https?:\/\//i.test(url.trim()) && !!safeLinkUrl(url) && commands.insertContent({ type: this.name, attrs: { url: url.trim() } }),
    }
  },
})

/** URL だけを貼り付けたとき: リンクとして入れ、「カード／埋め込み」に変えるメニューを出す（Editor が受け取る） */
export const UrlPaste = Extension.create({
  name: 'urlPaste',
  priority: 1050,
  addProseMirrorPlugins() {
    return [
      new Plugin({
        key: new PluginKey('urlPaste'),
        props: {
          handlePaste(view, event) {
            const text = (event.clipboardData?.getData('text/plain') ?? '').trim()
            if (!/^https?:\/\/\S+$/i.test(text) || text.length > 2000 || parsePageUrl(text)) return false
            if (event.clipboardData?.files.length) return false
            const { state } = view
            if (!state.selection.empty || state.selection.$from.parent.type.spec.code) return false
            const link = state.schema.marks.link
            if (!link) return false
            const from = state.selection.from
            const tr = state.tr.insertText(text, from).addMark(from, from + text.length, link.create({ href: text }))
            view.dispatch(tr.scrollIntoView())
            window.dispatchEvent(new CustomEvent('skiwi:url-pasted', { detail: { url: text, from, to: from + text.length } }))
            return true
          },
        },
      }),
    ]
  },
})
