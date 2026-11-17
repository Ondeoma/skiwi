import { Node, mergeAttributes } from '@tiptap/core'
import { safeEmbedUrl, toEmbedUrl } from '../lib'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    embed: { insertEmbed: (url: string) => ReturnType }
  }
}

/** 外部コンテンツの埋め込み(iframe)。sandbox で動作を制限する。 */
export const Embed = Node.create({
  name: 'embed',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() { return { src: { default: null }, height: { default: 400 } } },
  parseHTML() { return [{ tag: 'div[data-embed]', getAttrs: (el) => ({ src: safeEmbedUrl((el as HTMLElement).querySelector('iframe')?.getAttribute('src')) }) }] },
  renderHTML({ node, HTMLAttributes }) {
    // 本文は同時編集で好きな値にできるので、描画の直前にも https の外部 URL だけに限る（javascript: などは出さない）
    const src = safeEmbedUrl(node.attrs.src)
    if (!src) return ['div', mergeAttributes(HTMLAttributes, { 'data-embed': '', class: 'embed-block embed-invalid' }), '埋め込めない URL です']
    return ['div', mergeAttributes(HTMLAttributes, { 'data-embed': '', class: 'embed-block' }),
      ['iframe', {
        src, height: Number(node.attrs.height) || 400, loading: 'lazy', allowfullscreen: 'true',
        referrerpolicy: 'strict-origin-when-cross-origin',
        sandbox: 'allow-scripts allow-same-origin allow-popups allow-presentation allow-forms',
      }]]
  },
  renderText({ node }) { return `[embed: ${node.attrs.src}]` },
  addCommands() {
    return {
      insertEmbed: (url) => ({ commands }) => {
        const src = toEmbedUrl(url)
        return src ? commands.insertContent({ type: this.name, attrs: { src } }) : false
      },
    }
  },
})
