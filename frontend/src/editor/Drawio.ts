import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import DrawioView from './DrawioView.vue'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    drawio: { insertDrawio: (attrs?: { src?: string | null; xmlSrc?: string | null; title?: string | null }) => ReturnType }
  }
}

export interface DrawioOptions {
  /** 添付ファイルとしてアップロードして URL を返す（編集した図の保存に使う。ないと編集できない） */
  uploadFile?: (file: File) => Promise<string>
}

/**
 * draw.io（diagrams.net）の図。
 * src: 図の XML を埋め込んだ PNG（添付ファイル）。表示はふつうの画像として行い、編集するときはこの PNG から図を読み込む。
 * xmlSrc: 図の XML だけのファイル（Confluence などから取り込んだもの。最初に保存したときに PNG にする）。
 * text: 図の中の文字（検索・本文の文字に使う）。
 */
export const Drawio = Node.create<DrawioOptions>({
  name: 'drawio',
  group: 'block',
  atom: true,
  draggable: true,
  addOptions() { return { uploadFile: undefined } },
  addStorage() { return { openNext: false } },
  addAttributes() {
    return {
      src: { default: null },
      xmlSrc: { default: null },
      title: { default: null },
      text: { default: '' },
      width: { default: null },
    }
  },
  parseHTML() {
    return [{
      tag: 'figure[data-drawio]',
      getAttrs: (el) => {
        const e = el as HTMLElement
        const w = Number(e.getAttribute('data-width'))
        return {
          src: e.querySelector('img')?.getAttribute('src') ?? null, xmlSrc: e.getAttribute('data-xml-src'),
          title: e.getAttribute('data-title'), text: e.getAttribute('data-text') ?? '', width: w > 0 ? w : null,
        }
      },
    }]
  },
  renderHTML({ node, HTMLAttributes }) {
    const a = node.attrs
    return ['figure', mergeAttributes(
      { 'data-drawio': '', 'data-title': a.title ?? '', 'data-text': a.text ?? '', ...(a.xmlSrc ? { 'data-xml-src': a.xmlSrc } : {}), ...(a.width ? { 'data-width': String(a.width) } : {}) },
      { ...HTMLAttributes, src: undefined, xmlSrc: undefined, title: undefined, text: undefined, width: undefined },
    ), ['img', { src: a.src ?? '', alt: a.title || 'draw.io の図' }]]
  },
  renderText({ node }) { return [node.attrs.title, node.attrs.text].filter(Boolean).join('\n') },
  addNodeView() { return VueNodeViewRenderer(DrawioView) },
  addCommands() {
    return {
      insertDrawio: (attrs) => ({ commands }) => {
        // 空の図を入れたら、すぐにエディタを開く
        if (!attrs?.src && !attrs?.xmlSrc) this.storage.openNext = true
        return commands.insertContent({ type: this.name, attrs: { src: null, xmlSrc: null, title: null, ...attrs } })
      },
    }
  },
})
