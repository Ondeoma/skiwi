/** ページの親子・並び順の共通処理（ログイン中の一覧・ゲスト・印刷・書き出しで使う） */
export interface TreeItem { id: string; parentId: string | null; position: string }

/** 並び順（position）で比べる。同じなら 0（sort は安定なので元の並びのまま） */
const comparePosition = (a: { position: string }, b: { position: string }) =>
  a.position < b.position ? -1 : a.position > b.position ? 1 : 0

/** 並び順で比べ、同じなら ID で決める */
export const comparePositionThenId = (a: TreeItem, b: TreeItem) =>
  comparePosition(a, b) || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)

/**
 * 親 → 子の一覧（それぞれ cmp で並べる。ルートは null）。
 * orphansToRoot: 一覧にない親（見られない・公開範囲の外）の子をルート扱いにする
 */
export function groupByParent<T extends TreeItem>(
  items: readonly T[],
  { cmp = comparePosition, orphansToRoot = false }: { cmp?: (a: T, b: T) => number; orphansToRoot?: boolean } = {},
): Map<string | null, T[]> {
  const ids = orphansToRoot ? new Set(items.map((p) => p.id)) : null
  const kids = new Map<string | null, T[]>()
  for (const p of items) {
    const parent = p.parentId && (!ids || ids.has(p.parentId)) ? p.parentId : null
    const xs = kids.get(parent)
    if (xs) xs.push(p)
    else kids.set(parent, [p])
  }
  for (const xs of kids.values()) xs.sort(cmp)
  return kids
}
