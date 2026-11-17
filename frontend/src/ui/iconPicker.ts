import { EMOJIS, firstGrapheme } from './palette'
import { h, onDismiss } from './dom'

/**
 * 絵文字アイコンの選択（ui/IconPicker.vue と同じ見た目の素の DOM 版。エディタのパネルで使う）。
 * parent（position: relative の要素）の下に開く。外側クリック・Escで閉じる。戻り値で閉じる。
 * head: 一覧の上に出す要素（パネルの色など） / at: 画面座標に固定表示（スクロール領域の外へはみ出せるように）
 */
export function openIconPicker(parent: HTMLElement, opts: {
  onSelect: (icon: string | null) => void
  onClose: () => void
  head?: HTMLElement
  allowNone?: boolean
  noneLabel?: string
  at?: { x: number; y: number } | null
}): () => void {
  const custom = h('input', { placeholder: '他の絵文字を入力', maxlength: '16' })
  const ok = h('button', { disabled: true, onclick: () => applyCustom() }, '決定')
  custom.addEventListener('input', () => { ok.disabled = !custom.value.trim() })
  custom.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); if (!e.isComposing) applyCustom() } })
  function applyCustom() {
    const g = firstGrapheme(custom.value)
    if (g) opts.onSelect(g)
  }
  const root = h('div', { class: 'icon-picker', 'data-testid': 'icon-picker', onmousedown: (e: Event) => e.stopPropagation() },
    opts.head,
    h('div', { class: 'grid' }, EMOJIS.map((e) => h('button', { class: 'emoji', title: e, onclick: () => opts.onSelect(e) }, e))),
    h('div', { class: 'foot' }, custom, ok,
      opts.allowNone !== false && h('button', { class: 'none', onclick: () => opts.onSelect(null) }, opts.noneLabel ?? 'アイコンなし')))
  if (opts.at) {
    Object.assign(root.style, {
      position: 'fixed',
      left: `${Math.max(8, Math.min(opts.at.x, window.innerWidth - 344))}px`,
      top: `${Math.max(8, Math.min(opts.at.y, window.innerHeight - 260))}px`,
    })
  }
  parent.append(root)
  // 開いたクリック自体で閉じないよう、次のタスクで登録する
  const stop = onDismiss((t) => root.contains(t), opts.onClose, { escape: opts.onClose, defer: true })
  return () => { stop(); root.remove() }
}
