import { Node, mergeAttributes } from '@tiptap/core'
import { isAttachmentPath } from '../lib'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    fileBlock: { insertFile: (attrs: { id: string; url: string; filename: string; size: number; contentType: string }) => ReturnType }
  }
}

function formatSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}

/** 添付ファイル（画像以外）のブロック。実体は /api/attachments/{id}。 */
export const FileBlock = Node.create({
  name: 'fileBlock',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() {
    return {
      id: { default: null }, url: { default: null }, filename: { default: 'file' },
      size: { default: 0 }, contentType: { default: 'application/octet-stream' },
    }
  },
  parseHTML() { return [{ tag: 'div[data-file-block]' }] },
  renderHTML({ node, HTMLAttributes }) {
    return ['div', mergeAttributes(HTMLAttributes, { 'data-file-block': '', class: 'file-block' }),
      ['a', { href: isAttachmentPath(node.attrs.url) ? node.attrs.url : null, target: '_blank', rel: 'noopener' }, `📎 ${node.attrs.filename}`],
      ['span', { class: 'file-size' }, formatSize(Number(node.attrs.size))]]
  },
  renderText({ node }) { return `[file: ${node.attrs.filename}]` },
  addCommands() {
    return { insertFile: (attrs) => ({ commands }) => commands.insertContent({ type: this.name, attrs }) }
  },
})
