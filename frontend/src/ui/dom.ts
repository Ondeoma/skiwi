/**
 * 素の DOM を短く書くための小さな関数（Vue のテンプレートの代わり）。
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

/**
 * メニューなどの外をクリック（mousedown）したら閉じる。escape を渡すと Esc キーでも呼ぶ。
 * inside(target): 閉じないクリックか / defer: 開いたクリック自体で閉じないよう、次のタスクで登録する。
 * 戻り値で解除する
 */
export function onDismiss(inside: (target: Node) => boolean, close: () => void, opts: { escape?: () => void; defer?: boolean } = {}): () => void {
  const onDoc = (e: MouseEvent) => { if (!inside(e.target as Node)) close() }
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') opts.escape?.() }
  let alive = true
  if (opts.defer) setTimeout(() => { if (alive) document.addEventListener('mousedown', onDoc) })
  else document.addEventListener('mousedown', onDoc)
  if (opts.escape) document.addEventListener('keydown', onKey)
  return () => {
    alive = false
    document.removeEventListener('mousedown', onDoc)
    document.removeEventListener('keydown', onKey)
  }
}

/**
 * クリップボードへコピーし、ボタンの文字を1.5秒「コピーしました」にする。
 * label: ふだんの文字（戻すときに使う）
 */
export async function copyWithFlash(btn: HTMLElement, text: string, label: string, done = 'コピーしました') {
  const ok = await navigator.clipboard.writeText(text).then(() => true, () => false)
  if (!ok) return
  btn.textContent = done
  setTimeout(() => { btn.textContent = label }, 1500)
}
