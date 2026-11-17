import { domNodeView } from './nodeView'
import { today } from './DueDate'
import { h } from '../ui/dom'

/** 期限の印（📅 11/20（金））。クリックで日付を選ぶ。過ぎた・近い期限は色を変える */
export const DueDateView = domNodeView((c) => {
  const input = h('input', { type: 'date', class: 'due-input', tabindex: '-1', onchange: () => c.updateAttributes({ date: input.value || null }) })
  const text = document.createTextNode('')
  const dom = h('span', { class: 'due-date', contenteditable: 'false', 'data-testid': 'due-date', onclick: () => pick() }, text, input)

  const date = () => String(c.node().attrs.date ?? '')
  /** 項目が完了済みか（親の ToDo 項目の checked） */
  const done = () => {
    const pos = c.getPos()
    if (typeof pos !== 'number') return false
    const $pos = c.editor.state.doc.resolve(pos)
    for (let d = $pos.depth; d > 0; d--) { const n = $pos.node(d); if (n.type.name === 'taskItem') return !!n.attrs.checked }
    return false
  }
  function pick() {
    if (!c.editor.isEditable) return
    if (typeof input.showPicker === 'function') input.showPicker()
    else input.click()
  }
  const render = () => {
    const d = date()
    const isDone = done()
    const tone = !d || isDone ? 'normal' : d < today() ? 'overdue' : d <= today(1) ? 'soon' : 'normal'
    dom.className = `due-date ${tone}${isDone ? ' done' : ''}`
    dom.title = c.editor.isEditable ? 'クリックで期限を変更' : `期限 ${d}`
    let label = '期限なし'
    if (d) {
      const [y, m, day] = d.split('-').map(Number)
      const wd = '日月火水木金土'[new Date(y, m - 1, day).getDay()]
      label = `${y === new Date().getFullYear() ? '' : `${y}/`}${m}/${day}（${wd}）`
    }
    text.data = `📅 ${label} `
    if (document.activeElement !== input) input.value = d
  }
  // 親の ToDo 項目の完了（checked）が変わっても、このノード自体は変わらないので、本文の変更のたびに見直す
  c.editor.on('update', render)
  return { dom, render, destroy: () => { c.editor.off('update', render) } }
})
