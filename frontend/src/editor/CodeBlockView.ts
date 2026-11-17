import { domNodeView } from './nodeView'
import { LANGUAGES, languageLabel } from './CodeBlock'
import { copyWithFlash, h, setChildren } from '../ui/dom'

/** コードブロック: 言語の選択（編集中）・言語名（表示中）とコピーのボタン */
export const CodeBlockView = domNodeView((c) => {
  const lang = () => (c.node().attrs.language as string | null) ?? ''
  const select = h('select', { title: '言語', 'data-testid': 'code-language', onchange: () => c.updateAttributes({ language: select.value || null }) },
    h('option', { value: '' }, '自動判定'),
    LANGUAGES.map((l) => h('option', { value: l.id }, l.label)))
  const label = h('span', { class: 'lang' })
  const copy = h('button', { class: 'copy', title: 'コードをコピー', onclick: () => copyWithFlash(copy, c.node().textContent, 'コピー') }, 'コピー')
  const head = h('div', { class: 'code-head', contenteditable: 'false' })
  const code = h('code')
  const dom = h('div', { class: 'code-block' }, head, h('pre', null, code))
  const render = () => {
    const l = lang()
    if (l) dom.dataset.language = l
    else delete dom.dataset.language
    code.className = l ? `language-${l}` : ''
    select.value = l
    label.textContent = l ? languageLabel(l) : ''
    setChildren(head, c.editor.isEditable ? select : label, copy)
  }
  return { dom, contentDOM: code, render }
})
