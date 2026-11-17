/**
 * 素の DOM を短く書くための小さな関数（エディタのノードビュー・メニューの DOM を作る。ProseMirror に同期で渡すため JS のまま）。
 *   h('button', { class: 'x', title: '…', onclick: () => … }, '文字', 子要素)
 * props: on〜 は addEventListener、value・checked・disabled などはプロパティ、
 *        style は文字列かオブジェクト、false・null・undefined の属性は付けない、それ以外は属性
 */
type Child = Node | string | number | null | undefined | false
type Props = Record<string, unknown> | null | undefined

const PROPS = new Set(['value', 'checked', 'disabled', 'selected', 'textContent', 'innerHTML', 'tabIndex'])

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, props?: Props, ...children: (Child | Child[])[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag)
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v === undefined || v === null || v === false) continue
      if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2), v as EventListener)
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v)
      else if (k === 'class') el.className = String(v)
      else if (PROPS.has(k)) (el as unknown as Record<string, unknown>)[k] = v
      else el.setAttribute(k, v === true ? '' : String(v))
    }
  }
  append(el, children)
  return el
}

function append(el: Element, children: (Child | Child[])[]) {
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue
    el.append(typeof c === 'number' ? String(c) : c)
  }
}

/** 子要素をすべて置き換える */
export function setChildren(el: Element, ...children: (Child | Child[])[]) {
  el.replaceChildren()
  append(el, children)
}
