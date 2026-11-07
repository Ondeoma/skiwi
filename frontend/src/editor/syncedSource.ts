import { api, guestApi, okOrNull } from '../api'

/**
 * 同期ブロックの埋め込み（SyncedRefView）が読む元ページ。
 * 同じページを埋め込んだブロックがいくつあっても、同時の取得は1回にまとめ、
 * 定期更新のタイマーと「画面に戻ったとき」の listener も全ブロックで1つにする。
 */
export interface SyncedSource { title: string; content: unknown }

const TTL = 1000
const recent = new Map<string, { at: number; p: Promise<SyncedSource | null> }>()

async function load(pid: string, guestToken: string | null): Promise<SyncedSource | null> {
  if (guestToken) {
    const r = await guestApi.page(guestToken, pid)
    return { title: r.page.title, content: r.content }
  }
  // 取れなければ「権限なし」として表示する（ログイン画面には飛ばさない）
  const [meta, content] = await Promise.all([
    okOrNull(api.getPage(pid, { redirectOn401: false })),
    okOrNull(api.pageContent(pid)),
  ])
  if (!meta || !content) return null
  return { title: meta.title, content: content.content }
}

/** 元ページのタイトルと保存済みの本文（見られなければ null） */
export function fetchSyncedSource(pid: string, guestToken: string | null): Promise<SyncedSource | null> {
  const now = Date.now()
  for (const [k, v] of recent) if (now - v.at >= TTL) recent.delete(k)
  const key = `${guestToken ?? ''}:${pid}`
  const hit = recent.get(key)
  if (hit) return hit.p
  const p = load(pid, guestToken)
  recent.set(key, { at: now, p })
  p.catch(() => recent.delete(key))
  return p
}

const subscribers = new Set<() => void>()
let timer: ReturnType<typeof setInterval> | undefined
const onVisible = () => { if (document.visibilityState === 'visible') for (const f of subscribers) f() }

/** 30秒ごと・画面に戻ったときに refresh を呼ぶ。戻り値で止める */
export function onSyncedRefresh(refresh: () => void): () => void {
  subscribers.add(refresh)
  if (subscribers.size === 1) {
    timer = setInterval(onVisible, 30_000)
    document.addEventListener('visibilitychange', onVisible)
  }
  return () => {
    if (!subscribers.delete(refresh) || subscribers.size) return
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
  }
}
