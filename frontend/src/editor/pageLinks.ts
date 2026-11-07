import { reactive, shallowRef } from 'vue'
import { api, ApiError, type GuestPage } from '../api'

/** このシステムのページURL（/w/{ワークスペース}/p/{ページ}）。同じホストのものだけを対象にする。 */
const UUID = '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
const PATH = new RegExp(`^/w/(${UUID})/p/(${UUID})/?(?:\\?[^#]*)?(?:#(.*))?$`, 'i')

/** blockId: 同期ブロックへのリンク（#sync-ID）のときのID */
/** anchor: ブロックへのリンク（#block-ID）のときのID */
export function parsePageUrl(raw: string): { workspaceId: string; pageId: string; blockId?: string; anchor?: string } | null {
  const s = raw.trim()
  if (!s || /\s/.test(s)) return null
  let path = s
  if (/^https?:\/\//i.test(s)) {
    try {
      const u = new URL(s)
      if (u.host !== location.host) return null
      path = u.pathname + u.search + u.hash
    } catch { return null }
  }
  const m = PATH.exec(path)
  if (!m) return null
  const sync = /^sync-([0-9a-z]{4,40})$/i.exec(m[3] ?? '')
  const block = /^block-([0-9a-z]{4,40})$/i.exec(m[3] ?? '')
  return { workspaceId: m[1].toLowerCase(), pageId: m[2].toLowerCase(), ...(sync ? { blockId: sync[1] } : {}), ...(block ? { anchor: block[1] } : {}) }
}

export const pageHref = (workspaceId: string, pageId: string) => `/w/${workspaceId}/p/${pageId}`
/** ゲスト向け公開ページのURL（URL名があればそれ、なければID） */
export const guestHref = (token: string, p: { id: string; slug?: string | null }) => `/s/${token}/${p.slug || p.id}`

// --- 表示用のページ情報 -------------------------------------------------------------------
export type LinkInfo =
  | { state: 'loading' }
  | { state: 'ok'; title: string; icon: string | null; workspaceId: string }
  | { state: 'denied' } // 削除済み・権限なし

/** 取得済みのページ情報（ページリンクの表示で共有。名前の変更は patchPageInfo で反映する）。 */
const cache = reactive(new Map<string, LinkInfo>())

export function pageInfo(pageId: string): LinkInfo {
  const hit = cache.get(pageId)
  if (hit) return hit
  cache.set(pageId, { state: 'loading' })
  api.getPage(pageId, { redirectOn401: false })
    .then((p) => { cache.set(pageId, { state: 'ok', title: p.title, icon: p.icon, workspaceId: p.workspaceId }) })
    .catch((e) => {
      if (e instanceof ApiError) cache.set(pageId, { state: 'denied' })
      else cache.delete(pageId) // 通信エラーは次回やり直す
    })
  return cache.get(pageId)!
}

// --- ゲスト表示 ---------------------------------------------------------------------------
/** ゲスト表示中は、公開範囲のページだけをリンクとして出す（ログインが必要なAPIは呼ばない）。 */
export const guestContext = shallowRef<{ token: string; pages: Map<string, GuestPage> } | null>(null)
