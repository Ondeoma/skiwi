import { Extension } from '@tiptap/core'
import { NodeRange, NodeRangeSelection, isNodeRangeSelection } from '@tiptap/extension-node-range'
import { TextSelection } from '@tiptap/pm/state'

/**
 * ブロックの範囲選択（ドラッグのつまみで使う NodeRange）. キー操作は SelectionKeys が受け持つので、拡張の既定のキー割り当ては外す.
 */
export const BlockRange = NodeRange.extend({ addKeyboardShortcuts: () => ({}) })

/**
 * 選択のキー操作.
 *  - Shift+↑/↓: 段落の途中の行では、ふつうの文字の選択として1行ずつ広げる（ブラウザに任せる）.
 *    段落の端まで来たら、ブロック単位の選択に切り替え、続けて押すとブロックを1つずつ広げる.
 *  - ⌘A / Ctrl+A: コードブロックの中では、コードブロックの中身だけを選ぶ（もう一度押すと本文全体）. ほかでは本文全体（ブロック単位）.
 */
export const SelectionKeys = Extension.create({
  name: 'selectionKeys',
  // NodeRange・コードブロックなどの既定のキー割り当てより先に処理する
  priority: 1000,

  addKeyboardShortcuts() {
    const extend = (dir: 'up' | 'down') => () => {
      const { view } = this.editor
      // 直前のキーでブラウザが動かした選択を、エディタの状態に取り込んでから判断する（取り込みは選択の変化の通知を待つため遅れる）
      ;(view as unknown as { domObserver: { flush(): void } }).domObserver.flush()
      const { state } = view
      const { selection, doc } = state
      if (isNodeRangeSelection(selection)) {
        view.dispatch(state.tr.setSelection(dir === 'up' ? selection.extendBackwards() : selection.extendForwards()))
        return true
      }
      if (!view.endOfTextblock(dir)) return false
      view.dispatch(state.tr.setSelection(NodeRangeSelection.create(doc, selection.anchor, selection.head, undefined, dir === 'up' ? -1 : 1)))
      return true
    }
    return {
      'Shift-ArrowUp': extend('up'),
      'Shift-ArrowDown': extend('down'),
      'Mod-a': () => {
        const { view, state } = this.editor
        const { selection, doc } = state
        const { $from, $to } = selection
        if (!isNodeRangeSelection(selection) && $from.parent.type.spec.code && $from.sameParent($to)) {
          const start = $from.start()
          const end = $from.end()
          if (end > start && !(selection.from === start && selection.to === end)) {
            view.dispatch(state.tr.setSelection(TextSelection.create(doc, start, end)))
            return true
          }
        }
        view.dispatch(state.tr.setSelection(NodeRangeSelection.create(doc, 0, doc.content.size)))
        return true
      },
    }
  },
})
