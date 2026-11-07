/** 検索用の正規化: 全角/半角・大文字/小文字・カタカナ/ひらがなを区別しない */
export const normalizeForSearch = (s: string) =>
  s.normalize('NFKC').toLowerCase().replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60))

/** Mac・iPhone・iPad（ショートカットの修飾キーが ⌘） */
export const isMac = /Mac|iPhone|iPad/.test(navigator.platform)

/** ファイルの中身を文字列に: UTF-8（読めなければ Excel などの Shift_JIS）。先頭の BOM は除く */
export function decodeText(data: Uint8Array | ArrayBuffer): string {
  try { return new TextDecoder('utf-8', { fatal: true }).decode(data).replace(/^\ufeff/, '') } catch { return new TextDecoder('shift_jis').decode(data) }
}
