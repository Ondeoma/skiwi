import type { SlashItem } from './commands'
import { listMenu, pickOnMouseDown } from './suggestionMenu'
import { h, setChildren } from '../ui/dom'

/** 「/」で開くブロックの候補 */
export function slashMenu() {
  const el = h('div', { class: 'slash-menu', role: 'listbox' })
  return listMenu<SlashItem>(el, (items, _q, selected, hover, pick) => {
    setChildren(el,
      items.length === 1 && h('div', { class: 'empty' }, '該当なし'),
      items.map((it, i) => h('button', {
        class: `item${i === selected ? ' active' : ''}${it.special === 'all' ? ' all' : ''}`,
        role: 'option', 'aria-selected': String(i === selected),
        onmousedown: pickOnMouseDown(() => pick(i)), onmouseenter: () => hover(i),
      }, h('span', { class: 'icon' }, it.iconClass ? h('span', { class: it.iconClass, 'aria-hidden': 'true' }) : it.icon), h('span', { class: 'text' }, h('b', null, it.title), h('small', null, it.description)))))
  }, {
    pickKeys: ['Enter'],
    afterMove: (i) => el.querySelectorAll('.item')[i]?.scrollIntoView({ block: 'nearest' }),
  })
}
