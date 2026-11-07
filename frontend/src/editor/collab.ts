import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import { WebsocketProvider } from 'y-websocket'
import type { Editor } from '@tiptap/core'
import { BOARDS_ROOT_NAME } from './whiteboard/shared'

/** 同時編集の WebSocket のメッセージ番号（0 は y-protocols の同期、100 以降は skiwi 独自） */
export const MSG = {
  SYNC: 0,
  /** サーバー → クライアント: スナップショット（圧縮）の要求 */
  COMPACT_REQ: 100,
  /** クライアント → サーバー: 要求への応答（全状態＋表示用JSON＋平文） */
  COMPACT_RESP: 101,
  /** サーバー → クライアント: 権限の通知（接続時・共有設定の変更時） */
  ROLE: 102,
} as const

/** ページの本文（Yjs）に同時編集の接続をする。 */
export function connectPage(pageId: string): { ydoc: Y.Doc; provider: WebsocketProvider } {
  const ydoc = new Y.Doc()
  // ホワイトボードの共有データのルート。同期より前に型を登録しておく（後から登録すると型の付け替えが起きるため）
  ydoc.getMap(BOARDS_ROOT_NAME)
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  const provider = new WebsocketProvider(`${proto}://${location.host}/ws/pages`, pageId, ydoc, {
    disableBc: true, // 同一ブラウザ内のタブ間直接同期は使わず、サーバー(認可・永続化)を唯一の経路にする
  })
  return { ydoc, provider }
}

/**
 * サーバーからのスナップショット要求（圧縮用）に答える。
 * editorIfReady() が null を返すときは答えない。答えたら onAnswered を呼ぶ。
 */
export function answerCompactRequests(provider: WebsocketProvider, ydoc: Y.Doc, editorIfReady: () => Editor | null, onAnswered?: () => void) {
  provider.messageHandlers[MSG.COMPACT_REQ] = (encoder, decoder) => {
    const token = decoding.readVarString(decoder)
    const editor = editorIfReady()
    if (!editor) return
    encoding.writeVarUint(encoder, MSG.COMPACT_RESP)
    encoding.writeVarString(encoder, token)
    encoding.writeVarUint8Array(encoder, Y.encodeStateAsUpdate(ydoc))
    encoding.writeVarString(encoder, JSON.stringify(editor.getJSON()))
    encoding.writeVarString(encoder, editor.getText({ blockSeparator: '\n' }))
    onAnswered?.()
  }
}

/** 権限の通知（MSG.ROLE）を受け取る */
export function onRole(provider: WebsocketProvider, cb: (role: string) => void) {
  provider.messageHandlers[MSG.ROLE] = (_encoder, decoder) => { cb(decoding.readVarString(decoder)) }
}
