/**
 * セル背景・パネルで使う色。文書には色名だけを保存し、実際の色はCSS変数（ライト/ダーク別）で決める。
 */
export const COLORS = [
  { id: 'gray', label: 'グレー' },
  { id: 'brown', label: 'ブラウン' },
  { id: 'orange', label: 'オレンジ' },
  { id: 'yellow', label: 'イエロー' },
  { id: 'green', label: 'グリーン' },
  { id: 'blue', label: 'ブルー' },
  { id: 'purple', label: 'パープル' },
  { id: 'pink', label: 'ピンク' },
  { id: 'red', label: 'レッド' },
] as const

export type ColorId = (typeof COLORS)[number]['id']
export const isColor = (v: unknown): v is ColorId => typeof v === 'string' && COLORS.some((c) => c.id === v)

export const EMOJIS = [
  '📄', '📝', '📘', '📗', '📕', '📙', '📒', '📚', '📌', '📍', '📎', '🔖',
  '💡', 'ℹ️', '⚠️', '✅', '❌', '⛔', '❓', '❗', '🔥', '⭐', '✨', '🎯',
  '🚀', '🛠️', '🔧', '⚙️', '🔒', '🔑', '🧪', '🧭', '🗂️', '📁', '🗃️', '📦',
  '📊', '📈', '📉', '🗓️', '⏰', '⏳', '🔔', '📣', '💬', '👥', '👤', '🤝',
  '🎉', '🏢', '🏠', '🌐', '💻', '🖥️', '📱', '☁️', '🐛', '🧩', '🚚', '💰',
  '🧾', '📜', '✏️', '📐', '🎨', '🧠', '❤️', '💙', '💚', '💛', '💜', '🟢',
]

/** 文字列の先頭の絵文字（書記素）1つ。入力欄に任意の絵文字を入れたとき用。 */
export function firstGrapheme(s: string): string {
  const t = s.trim()
  if (!t) return ''
  const seg = new Intl.Segmenter('ja', { granularity: 'grapheme' })
  for (const { segment } of seg.segment(t)) return segment
  return ''
}
