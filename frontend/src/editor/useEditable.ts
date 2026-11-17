import type { Editor } from '@tiptap/core'

/** エディタごとに1つ（ノードビューの数だけリスナーを増やさない） */
const shared = new WeakMap<Editor, Set<() => void>>()

/**
 * 表示/編集モードの切替（editor.isEditable の変化）で f を呼ぶ。戻り値で解除する。
 * setEditable は 'update' を出すので、それだけを見る。リスナーはエディタと一緒に破棄される。
 */
export function onEditableChange(editor: Editor, f: () => void): () => void {
  let fs = shared.get(editor)
  if (!fs) {
    const set = new Set<() => void>()
    fs = set
    shared.set(editor, set)
    let last = editor.isEditable
    editor.on('update', () => {
      if (editor.isEditable === last) return
      last = editor.isEditable
      set.forEach((g) => g())
    })
  }
  const s = fs
  s.add(f)
  return () => { s.delete(f) }
}
