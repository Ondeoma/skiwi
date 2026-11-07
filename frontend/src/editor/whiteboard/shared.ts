/** ホワイトボードの共有データの型と、Y.Doc のルート名（Editor.vue が接続前に登録する）。Excalidraw を読み込まずに使える部分 */
export const BOARDS_ROOT_NAME = 'whiteboards'
export interface BoardElement { id: string; version: number; versionNonce: number; isDeleted?: boolean; type: string; text?: string; fileId?: string | null }
export interface FileMeta { mimeType: string; url: string }
export interface Snapshot { elements: BoardElement[]; background?: string; files?: Record<string, FileMeta> }

/* eslint-disable @typescript-eslint/no-explicit-any */
export interface Awareness {
  clientID: number
  getStates(): Map<number, Record<string, any>>
  setLocalStateField(field: string, value: unknown): void
  on(ev: 'change', f: () => void): void
  off(ev: 'change', f: () => void): void
}

/** 検索・テキスト出力用: ボード内の文字。 */
export function snapshotText(s: Snapshot | null): string {
  return (s?.elements ?? []).filter((e) => !e.isDeleted && e.type === 'text' && e.text).map((e) => e.text).join(' ')
}
