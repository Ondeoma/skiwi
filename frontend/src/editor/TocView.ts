import { domNodeView } from './nodeView'
import { collectHeadings, scrollToHeading, type Heading } from './Toc'
import { h, setChildren } from '../ui/dom'

/** 目次: 本文の見出しから作る（見出しを編集すると即座に反映される） */
export const TocView = domNodeView((c) => {
  const maxLevel = () => Number(c.node().attrs.maxLevel) || 3
  const select = h('select', { title: '表示する見出しの深さ', onchange: () => c.updateAttributes({ maxLevel: Number(select.value) }) },
    h('option', { value: '1' }, '見出し1まで'), h('option', { value: '2' }, '見出し2まで'), h('option', { value: '3' }, '見出し3まで'))
  const head = h('div', { class: 'toc-head', contenteditable: 'false' }, h('span', null, '目次'))
  const list = h('ul', { contenteditable: 'false' })
  const empty = h('p', { class: 'toc-empty', contenteditable: 'false' }, '見出しを追加すると、ここに目次が表示されます。')
  const dom = h('div', { class: 'toc', 'data-testid': 'toc' }, head)
  let headings: Heading[] | null = null

  const drawList = (next: Heading[]) => {
    const cur = headings
    if (cur && next.length === cur.length && next.every((x, i) => x.text === cur[i].text && x.level === cur[i].level && x.pos === cur[i].pos)) return
    headings = next
    if (!next.length) { list.remove(); dom.append(empty); return }
    empty.remove()
    // 一番浅い見出しを基準に字下げする（H2から始まる文書でも左詰め）
    const min = Math.min(...next.map((x) => x.level), 6)
    setChildren(list, next.map((x) => h('li', { 'data-level': String(x.level - min), style: { paddingLeft: `${(x.level - min) * 1.2}em` } },
      h('a', { href: '#', onclick: (e: MouseEvent) => { e.preventDefault(); scrollToHeading(c.editor.view, x.pos) } }, x.text))))
    dom.append(list)
  }
  const refresh = () => drawList(collectHeadings(c.editor.state.doc, maxLevel()))
  const render = () => {
    dom.classList.toggle('selected', c.selected())
    select.value = String(maxLevel())
    if (c.editor.isEditable) { if (!select.parentNode) head.append(select) } else select.remove()
    headings = null
    refresh()
  }
  c.editor.on('update', refresh) // 本文の変更時のみ（他の人の編集も含む）
  return { dom, render, destroy: () => { c.editor.off('update', refresh) } }
})
