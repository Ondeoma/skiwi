import * as Y from 'yjs'
import { BOARDS_ROOT_NAME, type BoardElement, type FileMeta } from './shared'

/**
 * 版の復元で、ホワイトボードの中身もその版のスナップショットに戻す。
 * ほかの人の画面にも反映されるよう、図形の version を今より大きくして書き込み、
 * その版になかった図形は消すのではなく isDeleted にする（Excalidraw の同期の規則に合わせる）。
 */
export function restoreBoards(ydoc: Y.Doc, content: unknown): number {
  const boards: { id: string; snapshot: string | null }[] = []
  const walk = (n: any) => {
    if (!n || typeof n !== 'object') return
    if (n.type === 'whiteboard' && n.attrs?.id) boards.push({ id: n.attrs.id, snapshot: n.attrs.snapshot ?? null })
    ;(n.content ?? []).forEach(walk)
  }
  walk(content)
  if (!boards.length) return 0
  const root = ydoc.getMap<Y.Map<any>>(BOARDS_ROOT_NAME)
  let restored = 0
  ydoc.transact(() => {
    for (const b of boards) {
      let snap: { elements?: BoardElement[]; files?: Record<string, FileMeta> } = {}
      try { snap = b.snapshot ? JSON.parse(b.snapshot) : {} } catch { continue }
      let board = root.get(b.id)
      if (!(board instanceof Y.Map)) {
        // ボードの共有データがまだない（表示時にスナップショットから作られる）
        continue
      }
      const e = board.get('e') as Y.Map<BoardElement>
      const f = board.get('f') as Y.Map<FileMeta>
      const want = new Map((snap.elements ?? []).map((x) => [x.id, x]))
      let maxVersion = 0
      e.forEach((x) => { maxVersion = Math.max(maxVersion, x.version ?? 0) })
      const bump = maxVersion + 1
      const nonce = () => Math.floor(Math.random() * 2 ** 31)
      for (const [id, x] of want) e.set(id, { ...x, isDeleted: false, version: bump + (x.version ?? 0), versionNonce: nonce() })
      e.forEach((x, id) => {
        if (!want.has(id) && !x.isDeleted) e.set(id, { ...x, isDeleted: true, version: bump + (x.version ?? 0), versionNonce: nonce() })
      })
      for (const [k, v] of Object.entries(snap.files ?? {})) if (!f.has(k)) f.set(k, v)
      restored++
    }
  }, 'version-restore')
  return restored
}
