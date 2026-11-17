import { CATEGORIES, filterItems, type SlashItem } from '../editor/commands'
import { h, setChildren } from './dom'

/**
 * ブロックの一覧（分類ごとの表）のモーダル。検索・矢印キー・Enter で選んで挿入する。
 * 選んだブロック（取り消しは null）を返す
 */
export function openBlockPicker(items: SlashItem[], query = ''): Promise<SlashItem | null> {
  return new Promise((resolve) => {
    let q = query
    let selected = 0
    let flat: SlashItem[] = []

    const input = h('input', { type: 'search', placeholder: '名前・説明・キーワードで絞り込み', 'data-testid': 'block-picker-search', value: q })
    const body = h('div', { class: 'body' })
    const modal = h('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'ブロックを挿入', 'data-testid': 'block-picker' },
      h('header', null,
        h('h2', null, 'ブロックを挿入'),
        input,
        h('button', { class: 'close', title: '閉じる（Esc）', onclick: () => done(null) }, '✕')),
      body,
      h('footer', null, '↑↓ で選択 ・ Enter で挿入 ・ Esc で閉じる ・ クリックでも挿入できます'))
    const backdrop = h('div', { class: 'block-picker-backdrop' }, modal)
    backdrop.addEventListener('mousedown', (e) => { if (e.target === backdrop) done(null) })
    backdrop.addEventListener('keydown', onKey)
    // 日本語の変換中も即座に絞り込めるよう、input イベントで受ける
    input.addEventListener('input', () => { q = input.value; selected = 0; draw() })

    function done(it: SlashItem | null) {
      backdrop.remove()
      resolve(it)
    }
    /** 「/」のあとに入力して呼び出せる語（日本語・英語を数個） */
    const hints = (it: SlashItem) => it.keywords.slice(0, 4).join('、')

    function draw() {
      const filtered = filterItems(items, q)
      /** 分類ごとの表示と、矢印キーで移動する順序（表示順）。 */
      const groups = CATEGORIES.map((c) => ({ category: c, items: filtered.filter((i) => i.category === c) })).filter((g) => g.items.length)
      flat = groups.flatMap((g) => g.items)
      if (!flat.length) { setChildren(body, h('p', { class: 'empty' }, `「${q}」に一致するブロックはありません。`)); return }
      setChildren(body, h('table', null,
        h('thead', null, h('tr', null, h('th', { class: 'c-icon' }), h('th', null, 'ブロック'), h('th', null, '説明'), h('th', { class: 'c-kw' }, '「/」のあとに入力して呼び出せる語'))),
        groups.map((g) => h('tbody', null,
          h('tr', { class: 'cat' }, h('th', { colspan: '4' }, g.category)),
          g.items.map((it) => {
            const i = flat.indexOf(it)
            return h('tr', {
              class: i === selected ? 'active' : undefined,
              onmouseenter: () => { if (selected !== i) { selected = i; mark() } },
              onclick: () => done(it),
            },
            h('td', { class: 'c-icon' }, h('span', { class: 'icon' }, it.iconClass ? h('span', { class: it.iconClass, 'aria-hidden': 'true' }) : it.icon)),
            h('td', { class: 'name' }, it.title),
            h('td', { class: 'desc' }, it.description),
            h('td', { class: 'c-kw' }, hints(it)))
          })))))
    }
    /** 選択中の行の印だけを付け替える */
    function mark() {
      body.querySelectorAll('tbody tr:not(.cat)').forEach((tr, i) => tr.classList.toggle('active', i === selected))
    }
    function move(d: number) {
      const n = flat.length
      if (!n) return
      selected = (selected + d + n) % n
      mark()
      body.querySelector('tr.active')?.scrollIntoView({ block: 'nearest' })
    }
    function onKey(e: KeyboardEvent) {
      if (e.isComposing) return
      if (e.key === 'ArrowDown') { e.preventDefault(); move(1) }
      else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1) }
      else if (e.key === 'Enter') { e.preventDefault(); const it = flat[selected]; if (it) done(it) }
      else if (e.key === 'Escape') { e.preventDefault(); done(null) }
    }

    draw()
    document.body.append(backdrop)
    input.focus()
    input.select()
  })
}
