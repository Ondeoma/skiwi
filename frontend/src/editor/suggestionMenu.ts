import type { Component } from 'vue'
import type { SuggestionOptions } from '@tiptap/suggestion'
import { VueRenderer } from '@tiptap/vue-3'
import { computePosition, flip, offset, shift } from '@floating-ui/dom'

/**
 * 入力中の文字（/・:・@ など）の下に候補メニューを出す Suggestion の render。
 * メニューのコンポーネントは onKeyDown(e) を expose すると、上下・Enter などを受け取れる。Esc で閉じる。
 */
export function menuRenderer<I, P = I>(component: Component): SuggestionOptions<I, P>['render'] {
  return () => {
    let comp: VueRenderer | null = null
    const place = (rect?: (() => DOMRect | null) | null) => {
      const el = comp?.element as HTMLElement | undefined
      const r = rect?.()
      if (!el || !r) return
      computePosition({ getBoundingClientRect: () => r }, el, { placement: 'bottom-start', strategy: 'absolute', middleware: [offset(6), flip(), shift({ padding: 8 })] })
        .then(({ x, y }) => Object.assign(el.style, { left: `${x}px`, top: `${y}px` }))
    }
    const close = () => { (comp?.element as HTMLElement | undefined)?.remove(); comp?.destroy(); comp = null }
    return {
      onStart: (props) => {
        comp = new VueRenderer(component, { props, editor: props.editor })
        document.body.appendChild(comp.element as HTMLElement)
        place(props.clientRect)
      },
      onUpdate: (props) => { comp?.updateProps(props); place(props.clientRect) },
      onKeyDown: ({ event }) => {
        if (event.key === 'Escape') { close(); return true }
        return (comp?.ref as { onKeyDown?: (e: KeyboardEvent) => boolean } | undefined)?.onKeyDown?.(event) ?? false
      },
      onExit: close,
    }
  }
}
