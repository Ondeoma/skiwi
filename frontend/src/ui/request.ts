import { shallowRef } from 'vue'

/**
 * 1つずつ開くモーダルの状態（App.vue のコンポーネントが current を見て表示し、resolve で閉じる）。
 * 新しく開くと、開いていたものは cancelValue(新しい要求) で閉じる。
 */
export function createRequest<Req extends object, R>(cancelValue: (next: Req) => R) {
  const current = shallowRef<(Req & { resolve: (v: R) => void }) | null>(null)
  function open(req: Req): Promise<R> {
    current.value?.resolve(cancelValue(req))
    return new Promise((resolve) => {
      current.value = { ...req, resolve: (v: R) => { current.value = null; resolve(v) } }
    })
  }
  return { current, open }
}
