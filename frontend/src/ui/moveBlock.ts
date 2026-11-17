import { Editor } from '@tiptap/core'
import Collaboration from '@tiptap/extension-collaboration'
import type { JSONContent } from '@tiptap/core'
import { contentExtensions } from '../editor/extensions'
import { answerCompactRequests, connectPage, onRole } from '../editor/collab'

/**
 * 別のページの末尾にブロックを足す（ブロックの「別のページへ移動」用）。
 * サーバーは本文（Yjs）を解釈しないので、そのページに同時編集の接続をして、エディタとして書き込む。
 * 書き込める（編集権限がある）ことを確かめてから足し、送り終えたら接続を閉じる。
 */
export async function appendToPage(pageId: string, content: JSONContent[]): Promise<void> {
  const { ydoc, provider } = connectPage(pageId)
  let role: string | null = null
  onRole(provider, (r) => { role = r })
  let editor: Editor | null = null
  // サーバーからの保存（圧縮）の要求に答える。答えると、検索・公開ページなどに使う本文も新しくなる
  let compacted: () => void = () => {}
  const compactedP = new Promise<void>((r) => { compacted = r })
  answerCompactRequests(provider, ydoc, () => (role === 'edit' ? editor : null), () => setTimeout(compacted, 300))
  try {
    await new Promise<void>((resolve, reject) => {
      const t = setTimeout(() => reject(new Error('移動先のページに接続できませんでした')), 15000)
      const check = () => { if (provider.synced && role !== null) { clearTimeout(t); resolve() } }
      provider.on('sync', check)
      const iv = setInterval(() => { check(); if (provider.synced && role !== null) clearInterval(iv) }, 100)
    })
    if (role !== 'edit') throw new Error('移動先のページを編集する権限がありません')
    editor = new Editor({ extensions: [...contentExtensions(), Collaboration.configure({ document: ydoc })] })
    // 空のページは空の段落だけなので、その後ろに足す
    editor.commands.insertContentAt(editor.state.doc.content.size, content)
    // 送り終え、サーバーが保存（圧縮）するまで待つ（最長15秒。間に合わなくても内容は失われず、次に開いたときに保存される）
    await new Promise<void>((resolve) => {
      const started = Date.now()
      const iv = setInterval(() => {
        const ws = provider.ws
        if ((ws && ws.bufferedAmount === 0 && Date.now() - started > 300) || Date.now() - started > 5000) { clearInterval(iv); resolve() }
      }, 50)
    })
    await Promise.race([compactedP, new Promise((r) => setTimeout(r, 15000))])
  } finally {
    editor?.destroy()
    provider.destroy()
    ydoc.destroy()
  }
}
