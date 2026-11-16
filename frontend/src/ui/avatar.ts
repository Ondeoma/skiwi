/** ユーザーのアイコンまわりの共通処理。 */

const COLORS = ['#2563eb', '#16a34a', '#d97706', '#dc2626', '#7c3aed', '#0891b2', '#db2777', '#4b5563']

/** 画像がないときの丸の色（名前から決まる） */
export function avatarColor(name: string): string {
  let h = 0
  for (const ch of name) h = (h * 31 + ch.codePointAt(0)!) >>> 0
  return COLORS[h % COLORS.length]
}

/** サーバーが発行するアイコンの URL の形（同時編集で他の人から届いた値は、この形のものだけを使う） */
const AVATAR_URL = /^\/api\/users\/[0-9a-f-]{36}\/avatar\?v=\d+$/
export const safeAvatarUrl = (u: unknown): string | null => (typeof u === 'string' && AVATAR_URL.test(u) ? u : null)

export const AVATAR_SIZE = 256
const MAX_BYTES = 200 * 1024

/** 画像ファイルを、真ん中を正方形に切り抜いて AVATAR_SIZE に縮める（WebP、使えなければ JPEG。200KB に収まるまで画質を下げる） */
export async function squareImage(file: File): Promise<Blob> {
  const bmp = await createImageBitmap(file).catch(() => { throw new Error('画像として読めませんでした（PNG・JPEG・WebP・GIF などを選んでください）') })
  const side = Math.min(bmp.width, bmp.height)
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = AVATAR_SIZE
  const g = canvas.getContext('2d')!
  g.imageSmoothingQuality = 'high'
  g.drawImage(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, 0, 0, AVATAR_SIZE, AVATAR_SIZE)
  bmp.close()
  const encode = (type: string, q: number) => new Promise<Blob | null>((ok) => canvas.toBlob(ok, type, q))
  for (const q of [0.9, 0.8, 0.65, 0.5]) {
    let b = await encode('image/webp', q)
    if (!b || b.type !== 'image/webp') b = await encode('image/jpeg', q)
    if (b && b.size <= MAX_BYTES) return b
  }
  throw new Error('画像を小さくできませんでした')
}

/** 同じページを開いている人（同時編集の awareness から） */
export interface Person { name: string; color: string; editing: boolean; avatar: string | null }
