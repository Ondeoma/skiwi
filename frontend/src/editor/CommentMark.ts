import { Mark, mergeAttributes } from '@tiptap/core'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    commentMark: { setCommentMark: (threadId: string) => ReturnType; unsetCommentMark: (threadId: string) => ReturnType }
  }
}

/** コメントを付けた本文の範囲（data-comment にスレッドのアンカーID）。重ねて付けられる。 */
export const CommentMark = Mark.create({
  name: 'comment',
  inclusive: false,
  excludes: '',
  addAttributes() {
    return { anchorId: { default: null, parseHTML: (el) => el.getAttribute('data-comment'), renderHTML: (a) => ({ 'data-comment': a.anchorId }) } }
  },
  parseHTML() { return [{ tag: 'span[data-comment]' }] },
  renderHTML({ HTMLAttributes }) { return ['span', mergeAttributes(HTMLAttributes, { class: 'comment-mark' }), 0] },
  addCommands() {
    return {
      setCommentMark: (anchorId) => ({ commands }) => commands.setMark(this.name, { anchorId }),
      // 文書全体から、そのアンカーの印を外す
      unsetCommentMark: (anchorId) => ({ tr, state, dispatch }) => {
        state.doc.descendants((node, pos) => {
          for (const m of node.marks) if (m.type.name === this.name && m.attrs.anchorId === anchorId) tr.removeMark(pos, pos + node.nodeSize, m)
        })
        if (dispatch) dispatch(tr)
        return true
      },
    }
  },
})
