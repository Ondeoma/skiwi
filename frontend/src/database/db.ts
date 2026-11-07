import type { CalcFn, Database, DbFilter, DbOption, DbProp, DbPropType, DbRow, DbValues, DbView, RelatedDb, RollupFn, RowMeta } from '../api'
import { run as runFormula, show as showVal, type Val } from './formula'
import { decodeText, normalizeForSearch } from '../util/text'

/** データベースの列・値の扱い（表示・並べ替え・絞り込み） */

export const TYPE_LABEL: Record<DbPropType, string> = {
  text: 'テキスト', number: '数値', select: '選択', multi_select: '複数選択', date: '日付', person: 'ユーザー', checkbox: 'チェック', url: 'URL',
  relation: '関連（別のデータベース）', rollup: '集計（関連先の値）',
  formula: '数式', created_time: '作成日時', edited_time: '最終更新日時', created_by: '作成者', edited_by: '最終更新者', unique_id: 'ID（連番）',
}
/** 値を入れない（計算・自動で決まる）列 */
export const COMPUTED: DbPropType[] = ['rollup', 'formula', 'created_time', 'edited_time', 'created_by', 'edited_by', 'unique_id']
export const TYPE_ICON: Record<DbPropType, string> = {
  text: '≡', number: '#', select: '◉', multi_select: '☰', date: '📅', person: '👤', checkbox: '☑', url: '🔗', relation: '↗', rollup: 'Σ',
  formula: 'ƒ', created_time: '🕐', edited_time: '🕑', created_by: '👤', edited_by: '✍', unique_id: '№',
}
export const FN_LABEL: Record<RollupFn, string> = {
  count: '件数', count_values: '値のある件数', sum: '合計', avg: '平均', min: '最小', max: '最大', checked: 'チェック済みの割合',
}

/** 値の計算に使う情報（関連先のデータベース・ユーザー名） */
export interface Ctx { props: DbProp[]; targets: Record<string, RelatedDb>; users: Record<string, string> }

/** 値を計算するのに使う項目の情報 */
interface RowLike { values: DbValues; meta?: RowMeta | null; title?: string }
export const like = (r: DbRow): RowLike => ({ values: r.values, meta: r.meta, title: r.page.title })
const fmtTime = (iso: string) => { const d = new Date(iso); const p = (n: number) => String(n).padStart(2, '0'); return `${d.getFullYear()}/${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}` }
let depth = 0

/** 数式の中の prop("列名") の値 */
function formulaValue(name: string, row: RowLike, ctx: Ctx): Val {
  if (name === '名前' || name === 'title') return row.title ?? ''
  const p = ctx.props.find((x) => x.name === name)
  if (!p) throw new Error(`「${name}」という列はありません`)
  const v = cellValue(p, row, ctx)
  if (v === null || v === undefined) return null
  switch (p.type) {
    case 'select': return option(p, v)?.name ?? null
    case 'multi_select': return arr(v).map((id) => option(p, id)?.name ?? '').filter(Boolean)
    case 'person': return arr(v).map((id) => ctx.users[id] ?? '').filter(Boolean)
    case 'relation': return relatedTitles(p, v, ctx)
    case 'date': return typeof v === 'string' ? new Date(`${v}T00:00:00`) : null
    case 'created_time': case 'edited_time': return typeof v === 'string' ? new Date(v) : null
    case 'number': return Number(v)
    case 'checkbox': return !!v
    default: return v as Val
  }
}

/** 列の値（集計・数式・自動の列は計算する） */
export function cellValue(p: DbProp, row: RowLike, ctx: Ctx): unknown {
  const values = row.values
  switch (p.type) {
    case 'created_time': return row.meta?.createdAt ?? null
    case 'edited_time': return row.meta?.editedAt ?? null
    case 'created_by': return row.meta?.createdBy ?? null
    case 'edited_by': return row.meta?.editedBy ?? null
    case 'unique_id': return typeof values._seq === 'number' ? `${p.prefix ? `${p.prefix}-` : ''}${values._seq}` : null
    case 'formula': {
      if (!p.expr || depth > 8) return null
      depth++
      try { return runFormula(p.expr, (name) => formulaValue(name, row, ctx)) } finally { depth-- }
    }
  }
  if (p.type !== 'rollup') return values[p.id]
  const rel = ctx.props.find((x) => x.id === p.relation && x.type === 'relation')
  const db = rel?.target ? ctx.targets[rel.target] : undefined
  if (!rel || !db) return null
  const ids = arr(values[rel.id])
  const rows = db.rows.filter((r) => ids.includes(r.id))
  const tp = db.props.find((x) => x.id === p.prop)
  const vals = tp ? rows.map((r) => r.values[tp.id]) : []
  switch (p.fn) {
    case 'count': return rows.length
    case 'count_values': return tp ? vals.filter((v) => !isEmpty(tp, v)).length : rows.length
    case 'checked': return rows.length ? Math.round((vals.filter(Boolean).length / rows.length) * 100) + '%' : null
    default: {
      const ns = vals.map(Number).filter((n, i) => vals[i] !== null && vals[i] !== undefined && vals[i] !== '' && Number.isFinite(n))
      if (!ns.length) return null
      const r = p.fn === 'sum' ? ns.reduce((a, b) => a + b, 0) : p.fn === 'avg' ? ns.reduce((a, b) => a + b, 0) / ns.length : p.fn === 'min' ? Math.min(...ns) : Math.max(...ns)
      return Math.round(r * 100) / 100
    }
  }
}
/** 関連先の項目の名前 */
const relatedTitles = (p: DbProp, v: unknown, ctx: Ctx) =>
  arr(v).map((id) => ctx.targets[p.target ?? '']?.rows.find((r) => r.id === id)).filter((r) => !!r).map((r) => r!.title || '無題')
export const COLORS = ['gray', 'red', 'orange', 'yellow', 'green', 'blue', 'purple', 'pink'] as const
export const COLOR_LABEL: Record<string, string> = { gray: 'グレー', red: '赤', orange: 'オレンジ', yellow: '黄', green: '緑', blue: '青', purple: '紫', pink: 'ピンク' }

/** カレンダーの最初の月（今月の1日） */
export const thisMonth = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth(), 1) }
/** タイムラインの左端（1週間前） */
export const weekAgo = () => { const d = new Date(); d.setDate(d.getDate() - 7); d.setHours(0, 0, 0, 0); return d }

export const newId = () => Math.random().toString(36).slice(2, 10)

export function defaultDatabase(): { props: DbProp[]; views: DbView[] } {
  const status = newId()
  const due = newId()
  return {
    props: [
      { id: status, name: '状態', type: 'select', options: [
        { id: newId(), name: '未着手', color: 'gray' }, { id: newId(), name: '進行中', color: 'blue' }, { id: newId(), name: '完了', color: 'green' },
      ] },
      { id: newId(), name: '担当者', type: 'person' },
      { id: due, name: '期限', type: 'date' },
    ],
    views: [
      { id: newId(), name: '表', type: 'table' },
      { id: newId(), name: 'ボード', type: 'board', groupBy: status },
      { id: newId(), name: 'カレンダー', type: 'calendar', dateProp: due },
    ],
  }
}

export const option = (p: DbProp, id: unknown): DbOption | undefined => p.options?.find((o) => o.id === id)
const arr = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])

/** 値の文字表現（検索・並べ替え・CSVなど） */
export function text(p: DbProp, v: unknown, users: Record<string, string>, ctx?: Ctx): string {
  if (v === null || v === undefined) return ''
  switch (p.type) {
    case 'relation': return ctx ? relatedTitles(p, v, ctx).join(', ') : ''
    case 'rollup': return String(v)
    case 'formula': return showVal(v as Val)
    case 'created_time': case 'edited_time': return typeof v === 'string' ? fmtTime(v) : ''
    case 'select': return option(p, v)?.name ?? ''
    case 'multi_select': return arr(v).map((id) => option(p, id)?.name).filter(Boolean).join(', ')
    case 'person': return arr(v).map((id) => users[id] ?? '').filter(Boolean).join(', ')
    case 'checkbox': return v ? '✓' : ''
    case 'date': return typeof v === 'string' ? v : ''
    default: return String(v)
  }
}
const isEmpty = (p: DbProp, v: unknown) =>
  v === null || v === undefined || v === '' || (Array.isArray(v) && !v.length) || (p.type === 'checkbox' && !v) ||
  (p.type === 'select' && !option(p, v))

/** 並べ替え用の値 */
function key(p: DbProp | 'title' | 'updated', r: DbRow, users: Record<string, string>, ctx: Ctx): string | number | null {
  if (p === 'title') return r.page.title || ''
  if (p === 'updated') return r.page.updatedAt
  const v = cellValue(p, like(r), ctx)
  if (p.type === 'rollup') return v === null || v === undefined ? null : typeof v === 'number' ? v : parseFloat(String(v))
  if (p.type === 'formula') return v === null || v === undefined || v === '' ? null : typeof v === 'number' ? v : v instanceof Date ? v.getTime() : showVal(v as Val).toLowerCase()
  if (p.type === 'created_time' || p.type === 'edited_time') return typeof v === 'string' ? v : null
  if (p.type === 'unique_id') return typeof r.values._seq === 'number' ? r.values._seq : null
  if (isEmpty(p, v)) return null
  if (p.type === 'number') return Number(v)
  if (p.type === 'checkbox') return v ? 1 : 0
  if (p.type === 'select') return p.options?.findIndex((o) => o.id === v) ?? 0
  return text(p, v, users, ctx).toLowerCase()
}


export function matches(f: DbFilter, props: DbProp[], r: DbRow, users: Record<string, string>, me: string | undefined, ctx?: Ctx): boolean {
  if (f.prop === 'title') {
    const t = normalizeForSearch(r.page.title || '')
    if (f.op === 'empty') return !t
    if (f.op === 'notEmpty') return !!t
    return t.includes(normalizeForSearch(f.value ?? ''))
  }
  const p = props.find((x) => x.id === f.prop)
  if (!p) return true
  const c = ctx ?? { props, targets: {}, users }
  const v = cellValue(p, like(r), c)
  if (p.type === 'formula' || p.type === 'created_by' || p.type === 'edited_by' || p.type === 'unique_id' || p.type === 'created_time' || p.type === 'edited_time') {
    const t = text(p, v, users, c)
    if (f.op === 'empty') return !t
    if (f.op === 'notEmpty') return !!t
    if (f.op === 'contains') return normalizeForSearch(t).includes(normalizeForSearch(f.value ?? ''))
    const n = typeof v === 'number' ? v : parseFloat(t), b = Number(f.value)
    if (f.op === 'is') return Number.isFinite(n) && Number.isFinite(b) ? n === b : t === (f.value ?? '')
    if (f.op === 'isNot') return t !== (f.value ?? '')
    const x = (p.type === 'created_time' || p.type === 'edited_time') ? t.slice(0, 10).replace(/\//g, '-') : n
    const y = (p.type === 'created_time' || p.type === 'edited_time') ? (f.value ?? '') : b
    return f.op === 'gt' ? x > y : f.op === 'lt' ? x < y : true
  }
  if (p.type === 'relation' && (f.op === 'contains' || f.op === 'is')) return arr(v).includes(f.value ?? '') || normalizeForSearch(text(p, v, users, c)).includes(normalizeForSearch(f.value ?? ''))
  if (p.type === 'rollup' && (f.op === 'gt' || f.op === 'lt' || f.op === 'is')) {
    const n = parseFloat(String(v ?? '')), b = Number(f.value)
    if (!Number.isFinite(n) || !f.value) return false
    return f.op === 'gt' ? n > b : f.op === 'lt' ? n < b : n === b
  }
  switch (f.op) {
    case 'empty': return isEmpty(p, v)
    case 'notEmpty': return !isEmpty(p, v)
    case 'contains':
      if (p.type === 'person' && f.value === '@me') return arr(v).includes(me ?? '')
      if (p.type === 'multi_select') return arr(v).includes(f.value ?? '')
      return normalizeForSearch(text(p, v, users, c)).includes(normalizeForSearch(f.value ?? ''))
    case 'is':
    case 'isNot': {
      let eq: boolean
      if (p.type === 'checkbox') eq = !!v === (f.value === 'true')
      else if (p.type === 'person') eq = arr(v).includes(f.value === '@me' ? me ?? '' : f.value ?? '')
      else if (p.type === 'multi_select') eq = arr(v).includes(f.value ?? '')
      else if (p.type === 'number') eq = v !== null && v !== undefined && Number(v) === Number(f.value)
      else eq = (v ?? '') === (f.value ?? '')
      return f.op === 'is' ? eq : !eq
    }
    case 'gt':
    case 'lt': {
      if (isEmpty(p, v) || !f.value) return false
      const a = p.type === 'number' ? Number(v) : String(v)
      const b = p.type === 'number' ? Number(f.value) : f.value
      return f.op === 'gt' ? a > b : a < b
    }
  }
  return true
}

/** ビューの絞り込み・並べ替えを当てた項目 */
export function applyView(v: DbView, props: DbProp[], rows: DbRow[], users: Record<string, string>, me: string | undefined, q: string, targets: Record<string, RelatedDb> = {}): DbRow[] {
  const ctx: Ctx = { props, targets, users }
  const nq = normalizeForSearch(q.trim())
  let out = rows.filter((r) => (v.filters ?? []).every((f) => matches(f, props, r, users, me, ctx)))
  if (nq) out = out.filter((r) => normalizeForSearch([r.page.title, ...props.map((p) => text(p, cellValue(p, like(r), ctx), users, ctx))].join(' ')).includes(nq))
  const s = v.sort
  if (s) {
    const p = s.prop === 'title' || s.prop === 'updated' ? s.prop : props.find((x) => x.id === s.prop)
    if (p) {
      const dir = s.dir === 'desc' ? -1 : 1
      // 並べ替えのキーは先に1回ずつ計算しておく（数式・集計は比較のたびに計算すると重い）
      const keys = new Map(out.map((r) => [r, key(p, r, users, ctx)]))
      out = [...out].sort((a, b) => {
        const x = keys.get(a)!, y = keys.get(b)!
        if (x === null && y === null) return 0
        if (x === null) return 1 // 空は常に後ろ
        if (y === null) return -1
        return (x < y ? -1 : x > y ? 1 : 0) * dir
      })
    }
  }
  return out
}

/** 絞り込みの条件として選べるもの */
export function opsFor(t: DbPropType | 'title'): { value: DbFilter['op']; label: string }[] {
  const empty = [{ value: 'empty' as const, label: 'が空' }, { value: 'notEmpty' as const, label: 'が空でない' }]
  switch (t) {
    case 'title': case 'text': case 'url': return [{ value: 'contains', label: 'に含む' }, ...empty]
    case 'number': return [{ value: 'is', label: '＝' }, { value: 'gt', label: 'より大きい' }, { value: 'lt', label: 'より小さい' }, ...empty]
    case 'date': return [{ value: 'is', label: 'が' }, { value: 'gt', label: 'より後' }, { value: 'lt', label: 'より前' }, ...empty]
    case 'select': return [{ value: 'is', label: 'が' }, { value: 'isNot', label: 'が次でない' }, ...empty]
    case 'multi_select': return [{ value: 'contains', label: 'に含む' }, ...empty]
    case 'person': return [{ value: 'contains', label: 'に含む' }, ...empty]
    case 'checkbox': return [{ value: 'is', label: 'が' }]
    case 'relation': return [{ value: 'contains', label: 'に含む' }, ...empty]
    case 'rollup': return [{ value: 'is', label: '＝' }, { value: 'gt', label: 'より大きい' }, { value: 'lt', label: 'より小さい' }]
    case 'formula': case 'unique_id': return [{ value: 'contains', label: 'に含む' }, { value: 'is', label: '＝' }, { value: 'gt', label: 'より大きい' }, { value: 'lt', label: 'より小さい' }, ...empty]
    case 'created_by': case 'edited_by': return [{ value: 'contains', label: 'に含む' }, ...empty]
    case 'created_time': case 'edited_time': return [{ value: 'gt', label: 'より後' }, { value: 'lt', label: 'より前' }]
  }
}
export const needsValue = (op: DbFilter['op']) => op !== 'empty' && op !== 'notEmpty'

// ---------------------------------------------------------------- CSV

/** CSV の1つの値 */
const csvCell = (s: string) => (/[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s)

/** 表示している列で CSV を作る（Excel で開けるよう先頭に BOM を付ける） */
export function toCsv(props: DbProp[], rows: DbRow[], ctx: Ctx): string {
  const head = ['名前', ...props.map((p) => p.name)]
  const lines = rows.map((r) => [r.page.title, ...props.map((p) => {
    const v = cellValue(p, like(r), ctx)
    if (p.type === 'checkbox') return v ? 'TRUE' : 'FALSE'
    if (p.type === 'multi_select' || p.type === 'person' || p.type === 'relation') return text(p, v, ctx.users, ctx).split(', ').filter(Boolean).join('; ')
    return text(p, v, ctx.users, ctx)
  })])
  return '\ufeff' + [head, ...lines].map((l) => l.map((c) => csvCell(String(c ?? ''))).join(',')).join('\r\n') + '\r\n'
}

/** CSV を読む（引用符・改行を含む値に対応） */
export function parseCsv(src: string): string[][] {
  const s = src.replace(/^\ufeff/, '')
  const rows: string[][] = []
  let row: string[] = [], cell = '', q = false
  for (let i = 0; i < s.length; i++) {
    const c = s[i]
    if (q) {
      if (c === '"' && s[i + 1] === '"') { cell += '"'; i++ }
      else if (c === '"') q = false
      else cell += c
    } else if (c === '"') q = true
    else if (c === ',') { row.push(cell); cell = '' }
    else if (c === '\n' || c === '\r') {
      if (c === '\r' && s[i + 1] === '\n') i++
      row.push(cell); rows.push(row); row = []; cell = ''
    } else cell += c
  }
  if (cell !== '' || row.length) { row.push(cell); rows.push(row) }
  return rows.filter((r) => r.some((c) => c.trim() !== ''))
}

/** CSV ファイルを読む（UTF-8 として読めなければ Excel の CSV＝Shift_JIS として） */
export const readCsv = async (f: File): Promise<string[][]> => parseCsv(decodeText(await f.arrayBuffer()))

/**
 * CSV（1行目は列名）から取り込む項目を作る。「名前」（なければ1列目）を項目名にし、同じ名前の列に値を入れる
 * （ない列はテキストの列として追加。選択肢は足りない分を追加）。props: 追加・変更後の列 / added: 追加した列の名前
 */
export function planCsvImport([head, ...body]: string[][], db: Database): { props: DbProp[]; added: string[]; items: { title: string; values: Record<string, unknown> }[] } {
  const titleCol = Math.max(0, head.findIndex((h) => ['名前', 'タイトル', 'name', 'title'].includes(h.trim().toLowerCase())))
  const ps = [...db.props]
  const added: string[] = []
  const colProp = head.map((h, i) => {
    if (i === titleCol || !h.trim()) return null
    let p = ps.find((x) => x.name === h.trim())
    if (!p) { p = { id: newId(), name: h.trim(), type: 'text' }; ps.push(p); added.push(p.name) }
    return COMPUTED.includes(p.type) ? null : p
  })
  // 選択肢: 足りないものを足す
  const optId = (p: DbProp, name: string) => {
    const n = name.trim()
    if (!n) return null
    let o = p.options?.find((x) => x.name === n)
    if (!o) { o = { id: newId(), name: n, color: COLORS[(p.options?.length ?? 0) % COLORS.length] }; p.options = [...(p.options ?? []), o] }
    return o.id
  }
  const userByName = new Map(Object.entries(db.users).map(([id, n]) => [n, id]))
  const items = body.map((r) => {
    const values: Record<string, unknown> = {}
    colProp.forEach((p, i) => {
      const c = (r[i] ?? '').trim()
      if (!p || !c) return
      switch (p.type) {
        case 'number': { const n = Number(c.replace(/[,，¥$]/g, '')); if (Number.isFinite(n)) values[p.id] = n; break }
        case 'checkbox': values[p.id] = TRUTHY.has(c.toLowerCase()); break
        case 'date': { const d = parseDate(c); if (d) values[p.id] = d; break }
        case 'select': values[p.id] = optId(p, c); break
        case 'multi_select': values[p.id] = c.split(/[;；,、]/).map((x) => optId(p, x)).filter(Boolean); break
        case 'person': values[p.id] = c.split(/[;；,、]/).map((x) => userByName.get(x.trim())).filter(Boolean); break
        case 'relation': {
          const t = db.targets[p.target ?? '']
          values[p.id] = c.split(/[;；]/).map((x) => t?.rows.find((rr) => rr.title === x.trim())?.id).filter(Boolean)
          break
        }
        case 'url': if (/^(https?:\/\/|mailto:)/i.test(c)) values[p.id] = c; break
        default: values[p.id] = c
      }
    })
    return { title: (r[titleCol] ?? '').trim(), values }
  })
  return { props: ps, added, items }
}

/** 日付の表記（2026/1/5、2026-01-05、2026年1月5日）を YYYY-MM-DD に */
export function parseDate(s: string): string | null {
  const m = /^(\d{4})[-/年.](\d{1,2})[-/月.](\d{1,2})日?/.exec(s.trim())
  if (!m) return null
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])]
  if (mo < 1 || mo > 12 || d < 1 || d > 31) return null
  return `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}
export const TRUTHY = new Set(['true', '1', 'yes', 'y', '✓', '✔', 'はい', '済', '完了', 'on', 'x'])

// ---------------------------------------------------------------- 集計行（表の列の下）

export const CALC_LABEL: Record<CalcFn, string> = {
  count: '件数', count_values: '値のある件数', count_empty: '空の件数', sum: '合計', avg: '平均', min: '最小', max: '最大', checked: 'チェックあり', unchecked: 'チェックなし',
}
/** 列の種類で使える集計 */
export function calcsFor(t: DbPropType | 'title'): CalcFn[] {
  if (t === 'number' || t === 'rollup' || t === 'formula' || t === 'unique_id') return ['count', 'count_values', 'count_empty', 'sum', 'avg', 'min', 'max']
  if (t === 'checkbox') return ['count', 'checked', 'unchecked']
  if (t === 'date' || t === 'created_time' || t === 'edited_time') return ['count', 'count_values', 'count_empty', 'min', 'max']
  return ['count', 'count_values', 'count_empty']
}
export function calc(fn: CalcFn, p: DbProp | null, rows: DbRow[], ctx: Ctx): string {
  if (fn === 'count') return String(rows.length)
  if (!p) return ''
  const vals = rows.map((r) => cellValue(p, like(r), ctx))
  const filled = vals.filter((v) => !(v === null || v === undefined || v === '' || (Array.isArray(v) && !v.length) || (p.type === 'checkbox' && !v)))
  switch (fn) {
    case 'count_values': return String(filled.length)
    case 'count_empty': return String(vals.length - filled.length)
    case 'checked': return `${vals.filter(Boolean).length}（${vals.length ? Math.round((vals.filter(Boolean).length / vals.length) * 100) : 0}%）`
    case 'unchecked': return String(vals.filter((v) => !v).length)
    case 'min': case 'max': {
      if (p.type === 'date' || p.type === 'created_time' || p.type === 'edited_time') {
        const ds = filled.map(String).sort()
        return ds.length ? text(p, fn === 'min' ? ds[0] : ds[ds.length - 1], ctx.users, ctx) : ''
      }
    }
  }
  const ns = vals.map((v) => (typeof v === 'number' ? v : typeof v === 'string' ? parseFloat(v.replace(/^[^0-9-]*/, '')) : NaN)).filter((n) => Number.isFinite(n))
  if (!ns.length) return ''
  const r = fn === 'sum' ? ns.reduce((a, b) => a + b, 0) : fn === 'avg' ? ns.reduce((a, b) => a + b, 0) / ns.length : fn === 'min' ? Math.min(...ns) : Math.max(...ns)
  return String(Math.round(r * 100) / 100)
}
