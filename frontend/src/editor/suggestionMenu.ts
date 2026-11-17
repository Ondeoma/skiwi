import type { SuggestionOptions, SuggestionProps } from '@tiptap/suggestion'
import { computePosition, flip, offset, shift } from '@floating-ui/dom'
import { h } from '../ui/dom'

/** 候補メニュー（素の DOM）。update で候補を描き直し、onKeyDown で上下・Enter などを受け取る */
export interface SuggestMenu<I> {
  el: HTMLElement
  update(p: { items: I[]; query: string; command: (item: I) => void }): void
  onKeyDown(e: KeyboardEvent): boolean
}

/**
 * 入力中の文字（/・:・@ など）の下に候補メニューを出す Suggestion の render。
 * make: メニューを作る関数。Esc で閉じる。
 */
export function menuRenderer<I>(make: () => SuggestMenu<I>): SuggestionOptions<I, I>['render'] {
  return () => {
    let menu: SuggestMenu<I> | null = null
    const place = (rect?: (() => DOMRect | null) | null) => {
      const el = menu?.el
      const r = rect?.()
      if (!el || !r) return
      computePosition({ getBoundingClientRect: () => r }, el, { placement: 'bottom-start', strategy: 'absolute', middleware: [offset(6), flip(), shift({ padding: 8 })] })
        .then(({ x, y }) => Object.assign(el.style, { left: `${x}px`, top: `${y}px` }))
    }
    const close = () => { menu?.el.remove(); menu = null }
    const update = (p: SuggestionProps<I, I>) => menu?.update({ items: p.items, query: p.query, command: (it) => p.command(it) })
    return {
      onStart: (p) => {
        menu = make()
        update(p)
        document.body.appendChild(menu.el)
        place(p.clientRect)
      },
      onUpdate: (p) => { update(p); place(p.clientRect) },
      onKeyDown: ({ event }) => {
        if (event.key === 'Escape') { close(); return true }
        return menu?.onKeyDown(event) ?? false
      },
      onExit: close,
    }
  }
}

/**
 * 上下の矢印・Enter で選ぶ一覧の共通部分。
 * draw(items, selected): 一覧を描く / pick(i): i 番目を選んだ / keys: 前・次に動くキー
 */
export function listMenu<I>(el: HTMLElement, draw: (items: I[], query: string, selected: number, hover: (i: number) => void, pick: (i: number) => void) => void,
  opts: { prev?: string[]; next?: string[]; pickKeys?: string[]; ignoreEmpty?: boolean; afterMove?: (selected: number) => void } = {}): SuggestMenu<I> {
  let items: I[] = []
  let query = ''
  let selected = 0
  let command: (item: I) => void = () => {}
  const prev = opts.prev ?? ['ArrowUp']
  const next = opts.next ?? ['ArrowDown']
  const pickKeys = opts.pickKeys ?? ['Enter', 'Tab']
  const redraw = () => draw(items, query, selected, (i) => { if (i !== selected) { selected = i; redraw() } }, (i) => { const it = items[i]; if (it) command(it) })
  return {
    el,
    update(p) {
      if (p.items !== items) selected = 0
      items = p.items
      query = p.query
      command = p.command
      redraw()
    },
    onKeyDown(e) {
      if (e.isComposing) return false // IME変換中は奪わない
      const n = items.length
      if (!n && opts.ignoreEmpty) return false
      if (next.includes(e.key)) { selected = (selected + 1) % Math.max(n, 1); redraw(); opts.afterMove?.(selected); return true }
      if (prev.includes(e.key)) { selected = (selected - 1 + n) % Math.max(n, 1); redraw(); opts.afterMove?.(selected); return true }
      if (pickKeys.includes(e.key)) { const it = items[selected]; if (it) command(it); return true }
      return false
    },
  }
}

/** ボタンの mousedown で選ぶ（エディタのフォーカスを外さない） */
export const pickOnMouseDown = (pick: () => void) => (e: MouseEvent) => { e.preventDefault(); pick() }
