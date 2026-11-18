import { Node, mergeAttributes } from '@tiptap/core'
import { domNodeView } from './nodeView'
import { guestContext } from '../lib'
import { host } from '../ui/host'
import { h, setChildren } from '../ui/dom'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    smohuaForm: { insertSmohuaForm: (formId: string) => ReturnType }
  }
}

const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i

/** smohua のフォームの URL（…/forms/{ID}・…/f/{ID}）か ID から、フォームの ID（小文字）を取り出す */
export function smohuaFormId(input: string): string | null {
  const m = UUID.exec(input.trim())
  return m ? m[0].toLowerCase() : null
}

/**
 * smohua（フォーム）で集めた回答を、ページの中に表示するブロック（docs/smohua-integration.md）。
 * 表示は Scalactive の部品（host().mountView('smohuaForm')）が行い、見ている本人の smohua の権限で読む。本文に回答は保存しない
 */
const SmohuaFormView = domNodeView((c) => {
  const dom = h('div', { class: 'smohua-block', 'data-testid': 'smohua-form-block' })
  const grip = h('span', { class: 'db-grip', 'data-drag-handle': '', title: 'ドラッグで移動' }, '⋮⋮')
  const mountEl = h('div', { class: 'smohua-mount' })
  const inner = h('div', { contenteditable: 'false', class: 'smohua-inner' }, mountEl)
  const guestBox = h('div', { class: 'db-guest' }, '📋 フォームの回答（公開ページでは表示されません）')

  let mounted: { key: string; unmount: () => void } | null = null
  const unmount = () => { mounted?.unmount(); mounted = null }

  const render = () => {
    if (guestContext()) {
      unmount()
      setChildren(dom, guestBox)
      return
    }
    if (dom.firstChild !== inner) setChildren(dom, inner)
    if (c.editor.isEditable) { if (grip.parentNode !== inner) inner.prepend(grip) } else grip.remove()
    const key = (c.node().attrs.formId as string | null) ?? ''
    if (mounted && mounted.key === key) return
    unmount()
    if (!key) return
    mounted = { key, unmount: host().mountView('smohuaForm', mountEl, { formId: key }) }
  }
  return { dom, render, destroy: unmount }
})

export const SmohuaForm = Node.create({
  name: 'smohuaForm',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() {
    return {
      formId: { default: null, parseHTML: (el) => el.getAttribute('data-form-id'), renderHTML: (a) => ({ 'data-form-id': a.formId }) },
    }
  },
  parseHTML() { return [{ tag: 'div[data-smohua-form]' }] },
  renderHTML({ HTMLAttributes }) { return ['div', mergeAttributes(HTMLAttributes, { 'data-smohua-form': '' }), '（フォームの回答）'] },
  renderText() { return '（フォームの回答）' },
  addNodeView() { return SmohuaFormView },
  addCommands() {
    return { insertSmohuaForm: (formId) => ({ commands }) => commands.insertContent({ type: this.name, attrs: { formId } }) }
  },
})
