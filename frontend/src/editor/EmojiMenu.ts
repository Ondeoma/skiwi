import type { EmojiItem } from '../lib'
import { listMenu, pickOnMouseDown } from './suggestionMenu'
import { h, setChildren } from '../ui/dom'

/** 「:」で開く絵文字の候補 */
export function emojiMenu() {
  const el = h('div', { class: 'emoji-menu', 'data-testid': 'emoji-menu' })
  return listMenu<EmojiItem>(el, (items, _q, selected, hover, pick) => {
    el.style.display = items.length ? '' : 'none'
    setChildren(el, items.map((it, i) => h('button', {
      class: i === selected ? 'on' : undefined, title: it.name, 'data-testid': 'emoji-item',
      onmousedown: pickOnMouseDown(() => pick(i)), onmouseenter: () => hover(i),
    }, h('span', { class: 'e' }, it.emoji), h('span', { class: 'n' }, it.name))))
  }, { prev: ['ArrowUp', 'ArrowLeft'], next: ['ArrowDown', 'ArrowRight'], ignoreEmpty: true })
}
