import { ref, watch, type Ref } from 'vue'
import { integrationApi, type IntegrationStatus, type ServiceName, type SkawauFile, type SkeaTask } from '../api'

/**
 * 外部サービス（skea・skawau）との連携の状態と、項目（タスク・ファイル）のキャッシュ（ログイン中の画面で共有する）。
 * ゲスト向けの公開ページでは使わない（API を呼ばない）。
 */
export interface Integration<T> {
  name: ServiceName
  api: ReturnType<typeof integrationApi>
  status: Ref<IntegrationStatus | null>
  /** 状態を読む（1回だけ。force で読み直す）。読めなければ null（連携なしとして扱う） */
  load(force?: boolean): Promise<IntegrationStatus | null>
  /** 状態や項目が変わったときに呼ぶ（エディタの表示の更新）。戻り値で解除 */
  onChange(f: () => void): () => void
  /** 分かっている項目（undefined はまだ分からない・取れなかった、null は見つからない・見る権限がない） */
  item(key: string): T | null | undefined
  /** まだ知らない（または古い）項目だけ、まとめて問い合わせる。結果が届いたら onChange に知らせる */
  request(keys: string[]): void
  /** 連携・解除のあとに状態を読み直し、キャッシュを捨てる */
  reload(): Promise<void>
  /** 連携の結果（コールバックで戻り先に付く ?{サービス名}=connected|denied|error）を取り出し、URL から消す */
  takeResult(): 'connected' | 'denied' | 'error' | null
}

const TTL = 60_000
const BATCH = 100

function createIntegration<T extends { key: string }>(name: ServiceName): Integration<T> {
  const api = integrationApi(name)
  const status = ref<IntegrationStatus | null>(null)
  let loading: Promise<IntegrationStatus | null> | null = null
  const listeners = new Set<() => void>()
  const notify = () => listeners.forEach((f) => f())
  watch(status, notify)
  const cache = new Map<string, { at: number; item: T | null | undefined }>()
  const inflight = new Set<string>()

  const load = (force = false) => {
    if (!loading || force) {
      loading = api.status()
        .then((s) => { status.value = s; return s })
        .catch(() => { loading = null; return null })
    }
    return loading
  }

  return {
    name, api, status, load,
    onChange(f) { listeners.add(f); return () => listeners.delete(f) },
    item: (key) => cache.get(key)?.item,
    request(keys) {
      const s = status.value
      if (!s?.enabled || !s.connected) return
      const now = Date.now()
      const need = [...new Set(keys)].filter((k) => !inflight.has(k) && !((cache.get(k)?.at ?? -Infinity) + TTL > now))
      for (let i = 0; i < need.length; i += BATCH) {
        const chunk = need.slice(i, i + BATCH)
        chunk.forEach((k) => inflight.add(k))
        api.lookup<T>(chunk)
          .then((r) => {
            if (!r.connected) {
              // 相手側で連携が切れた
              cache.clear()
              if (status.value) status.value = { ...status.value, connected: false }
              return
            }
            const got = new Map(r.items.map((t) => [t.key, t]))
            chunk.forEach((k) => cache.set(k, { at: Date.now(), item: got.get(k) ?? null }))
          })
          .catch(() => chunk.forEach((k) => cache.set(k, { at: Date.now(), item: undefined })))
          .finally(() => { chunk.forEach((k) => inflight.delete(k)); notify() })
      }
    },
    async reload() {
      cache.clear()
      await load(true)
    },
    takeResult() {
      const u = new URL(location.href)
      const v = u.searchParams.get(name)
      if (v !== 'connected' && v !== 'denied' && v !== 'error') return null
      u.searchParams.delete(name)
      history.replaceState(history.state, '', u.pathname + u.search + u.hash)
      return v
    },
  }
}

export const skea = createIntegration<SkeaTask>('skea')
export const skawau = createIntegration<SkawauFile>('skawau')
