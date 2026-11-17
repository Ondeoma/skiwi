import { domNodeView } from './nodeView'
import { COLORS, type ColorId } from '../lib'
import { openIconPicker } from '../lib'
import { h } from '../ui/dom'

/** パネル（アイコンと背景色のある囲み）。編集中はアイコンのボタンでアイコン・色を変える */
export const PanelView = domNodeView((c) => {
  let closePicker: (() => void) | null = null
  const close = () => { closePicker?.(); closePicker = null }
  const setIcon = (icon: string | null) => { c.updateAttributes({ icon }); close() }
  const setColor = (color: ColorId) => c.updateAttributes({ color })
  /** パネルを外して中身だけ残す */
  function unwrap() {
    close()
    const pos = c.getPos()
    if (typeof pos !== 'number') return
    const node = c.node()
    c.editor.chain().focus().command(({ tr }) => {
      tr.replaceWith(pos, pos + node.nodeSize, node.content)
      return true
    }).run()
  }
  function toggle() {
    if (!c.editor.isEditable) return
    if (closePicker) { close(); return }
    const color = c.node().attrs.color
    const head = h('div', { class: 'panel-colors' },
      COLORS.map((x) => h('button', { class: `swatch${color === x.id ? ' on' : ''}`, 'data-color': x.id, title: x.label, onclick: () => setColor(x.id) })),
      h('button', { class: 'unwrap', title: 'パネルを解除して中身だけ残す', onclick: unwrap }, '解除'))
    closePicker = openIconPicker(iconBox, { head, onSelect: setIcon, onClose: close })
  }

  const btn = h('button', { class: 'panel-icon-btn', 'data-testid': 'panel-icon', onclick: toggle })
  const iconBox = h('div', { class: 'panel-icon', contenteditable: 'false' })
  const body = h('div', { class: 'panel-body' })
  const dom = h('div', { class: 'panel', 'data-panel': '' }, iconBox, body)

  const render = () => {
    const { icon, color } = c.node().attrs
    const editable = c.editor.isEditable
    dom.setAttribute('data-color', String(color))
    if (icon || editable) {
      btn.textContent = icon || '＋'
      btn.className = `panel-icon-btn${icon ? '' : ' empty'}${editable ? '' : ' ro'}`
      if (editable) btn.title = 'アイコンと色を変更'
      else btn.removeAttribute('title')
      if (btn.parentNode !== iconBox) iconBox.prepend(btn)
    } else btn.remove()
    if (!editable) close()
    // 開いている色の選択の「選択中」を合わせる
    iconBox.querySelectorAll<HTMLElement>('.panel-colors .swatch').forEach((s) => s.classList.toggle('on', s.dataset.color === color))
  }
  return { dom, contentDOM: body, render, destroy: close }
})
