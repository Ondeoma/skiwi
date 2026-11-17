import { domNodeView } from './nodeView'
import { copyWithFlash, pageHref } from '../lib'
import { h } from '../ui/dom'

/** 同期ブロック（元）: 上に説明と「埋め込み用リンクをコピー」「解除」のボタン */
export const SyncedBlockView = domNodeView((c) => {
  const COPY = '埋め込み用リンクをコピー'
  /** 埋め込み用のリンク（このページのURL＋#sync-ID）。貼り付けると埋め込みになる */
  const copyBtn = h('button', {
    'data-testid': 'synced-copy',
    onclick: () => {
      const m = /^\/w\/([0-9a-f-]{36})\/p\/([0-9a-f-]{36})/.exec(location.pathname)
      if (m) void copyWithFlash(copyBtn, `${location.origin}${pageHref(m[1], m[2])}#sync-${c.node().attrs.id}`, COPY)
    },
  }, COPY)
  const unwrap = h('button', {
    title: '同期ブロックを解除（中身は残ります）',
    onclick: () => {
      const pos = c.getPos()
      if (typeof pos === 'number') c.editor.chain().focus().setTextSelection(pos + 2).lift('syncedBlock').run()
    },
  }, '解除')
  const bar = h('div', { class: 'sb-bar', contenteditable: 'false' },
    h('span', null, '🔗 同期ブロック（元）'),
    h('span', { class: 'sb-hint' }, 'ほかのページに埋め込めます。ここを直すと埋め込み先にも反映されます'),
    copyBtn)
  const content = h('div', { class: 'sb-content' })
  const dom = h('div', { class: 'synced-block', 'data-testid': 'synced-block' }, bar, content)
  const render = () => {
    dom.setAttribute('data-synced-id', String(c.node().attrs.id ?? ''))
    if (c.editor.isEditable) { if (!unwrap.parentNode) bar.append(unwrap) } else unwrap.remove()
  }
  return { dom, contentDOM: content, render }
})
