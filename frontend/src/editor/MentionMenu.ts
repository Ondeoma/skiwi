import { listMenu, pickOnMouseDown } from './suggestionMenu'
import { h, setChildren } from '../ui/dom'

export interface MentionUser { id: string; name: string; detail: string | null }

/** 「@」で開くユーザーの候補 */
export function mentionMenu() {
  const el = h('div', { class: 'mention-menu', role: 'listbox', 'data-testid': 'mention-menu' })
  return listMenu<MentionUser>(el, (items, query, selected, hover, pick) => {
    setChildren(el,
      !query ? h('div', { class: 'hint' }, '名前・メールを入力してメンション')
        : !items.length && h('div', { class: 'hint' }, '該当するユーザーがいません'),
      items.map((u, i) => h('button', {
        class: `item${i === selected ? ' active' : ''}`, role: 'option',
        onmousedown: pickOnMouseDown(() => pick(i)), onmouseenter: () => hover(i),
      }, `👤 ${u.name} `, h('small', null, u.detail ?? ''))))
  }, { ignoreEmpty: true })
}
