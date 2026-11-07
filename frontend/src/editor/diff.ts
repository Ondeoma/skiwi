/** 版の差分（ブロック単位の差分と、変更されたブロック内の文字単位の差分）。 */

type Piece = { kind: 'same' | 'add' | 'del'; text: string }
export type Row =
  | { kind: 'same'; text: string }
  | { kind: 'add'; text: string }
  | { kind: 'del'; text: string }
  | { kind: 'change'; pieces: Piece[] }
  | { kind: 'fold'; count: number }

/** 最長共通部分列による差分（a → b）。 */
function lcs<T>(a: T[], b: T[], eq: (x: T, y: T) => boolean): { kind: 'same' | 'add' | 'del'; a?: T; b?: T }[] {
  // 前後の一致を先に取り除く（大きな文書でも表を小さくする）
  let start = 0
  while (start < a.length && start < b.length && eq(a[start], b[start])) start++
  let endA = a.length, endB = b.length
  while (endA > start && endB > start && eq(a[endA - 1], b[endB - 1])) { endA--; endB-- }
  const A = a.slice(start, endA), B = b.slice(start, endB)
  const n = A.length, m = B.length
  const out: { kind: 'same' | 'add' | 'del'; a?: T; b?: T }[] = a.slice(0, start).map((x, i) => ({ kind: 'same', a: x, b: b[i] }))
  if (n * m > 4_000_000) {
    // 大きすぎる場合は全削除＋全追加として扱う
    A.forEach((x) => out.push({ kind: 'del', a: x }))
    B.forEach((y) => out.push({ kind: 'add', b: y }))
  } else {
    const t = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
    for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) t[i][j] = eq(A[i], B[j]) ? t[i + 1][j + 1] + 1 : Math.max(t[i + 1][j], t[i][j + 1])
    let i = 0, j = 0
    while (i < n && j < m) {
      if (eq(A[i], B[j])) { out.push({ kind: 'same', a: A[i], b: B[j] }); i++; j++ }
      else if (t[i + 1][j] >= t[i][j + 1]) out.push({ kind: 'del', a: A[i++] })
      else out.push({ kind: 'add', b: B[j++] })
    }
    while (i < n) out.push({ kind: 'del', a: A[i++] })
    while (j < m) out.push({ kind: 'add', b: B[j++] })
  }
  a.slice(endA).forEach((x, k) => out.push({ kind: 'same', a: x, b: b[endB + k] }))
  return out
}

/** 文字単位の差分（コードポイント単位）。 */
function charDiff(a: string, b: string): Piece[] {
  const ops = lcs([...a], [...b], (x, y) => x === y)
  const out: Piece[] = []
  for (const o of ops) {
    const text = (o.kind === 'add' ? o.b : o.a) as string
    const last = out[out.length - 1]
    if (last && last.kind === o.kind) last.text += text
    else out.push({ kind: o.kind, text })
  }
  return out
}

/** ブロック列の差分。変更のない部分は前後 context ブロックを残して折りたたむ。 */
export function diffBlocks(before: string[], after: string[], context = 2): Row[] {
  const ops = lcs(before, after, (x, y) => x === y)
  const rows: Row[] = []
  for (let k = 0; k < ops.length; k++) {
    const o = ops[k]
    // 削除の直後の追加は「変更」として文字単位で比べる（似ている場合のみ）
    if (o.kind === 'del' && ops[k + 1]?.kind === 'add') {
      const a = o.a as string, b = ops[k + 1].b as string
      const pieces = charDiff(a, b)
      const same = pieces.filter((p) => p.kind === 'same').reduce((n, p) => n + p.text.length, 0)
      if (same >= Math.min(a.length, b.length) * 0.4) { rows.push({ kind: 'change', pieces }); k++; continue }
    }
    rows.push(o.kind === 'same' ? { kind: 'same', text: o.a as string } : o.kind === 'add' ? { kind: 'add', text: o.b as string } : { kind: 'del', text: o.a as string })
  }
  // 折りたたみ
  const near = rows.map((r, i) => r.kind !== 'same' || rows.slice(Math.max(0, i - context), i + context + 1).some((x) => x.kind !== 'same'))
  const out: Row[] = []
  let folded = 0
  rows.forEach((r, i) => {
    if (near[i]) { if (folded) { out.push({ kind: 'fold', count: folded }); folded = 0 } out.push(r) }
    else folded++
  })
  if (folded) out.push({ kind: 'fold', count: folded })
  return out
}

export const changeCount = (rows: Row[]) => rows.filter((r) => r.kind !== 'same' && r.kind !== 'fold').length
