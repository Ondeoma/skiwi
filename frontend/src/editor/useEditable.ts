import type { Editor } from '@tiptap/core'
import { readonly, ref, type Ref } from 'vue'

/** エディタごとに1つ（ノードビューの数だけリスナーを増やさない） */
const shared = new WeakMap<Editor, Readonly<Ref<boolean>>>()

/**
 * ノードビュー内で editor.isEditable をリアクティブに扱う（表示/編集モードの切替に追従する）。
 * setEditable は 'update' を出すので、それだけを見る。リスナーはエディタと一緒に破棄される。
 */
export function useEditable(editor: Editor): Readonly<Ref<boolean>> {
  let r = shared.get(editor)
  if (!r) {
    const editable = ref(editor.isEditable)
    editor.on('update', () => { editable.value = editor.isEditable })
    r = readonly(editable)
    shared.set(editor, r)
  }
  return r
}
