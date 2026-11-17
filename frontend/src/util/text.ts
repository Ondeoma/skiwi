/** 検索用の正規化: 全角/半角・大文字/小文字・カタカナ/ひらがなを区別しない */
export const normalizeForSearch = (s: string) =>
  s.normalize('NFKC').toLowerCase().replace(/[ァ-ヶ]/g, (c) => String.fromCharCode(c.charCodeAt(0) - 0x60))
