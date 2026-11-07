import { ymd } from '../ui/date'

/**
 * データベースの「数式」の列。表計算ソフトに近い書き方の小さな式の言語。
 *
 *   prop("金額") * 1.1
 *   if(prop("状態") == "完了", "✅", "")
 *   dateBetween(prop("期限"), today(), "days")
 *   concat(prop("顧客"), " / ", prop("担当"))
 *
 * 値: 数値・文字列・真偽・日付（Date）・一覧（配列）・空（null）
 */
export type Val = number | string | boolean | Date | Val[] | null

type Tok = { t: 'num'; v: number } | { t: 'str'; v: string } | { t: 'id'; v: string } | { t: 'op'; v: string }

class FormulaError extends Error {}

function tokenize(src: string): Tok[] {
  const out: Tok[] = []
  let i = 0
  while (i < src.length) {
    const c = src[i]
    if (/\s/.test(c)) { i++; continue }
    if (/[0-9.]/.test(c)) {
      const m = /^\d*\.?\d+(e[+-]?\d+)?/i.exec(src.slice(i))
      if (!m) throw new FormulaError(`数値の書き方が正しくありません（${i + 1}文字目）`)
      out.push({ t: 'num', v: Number(m[0]) }); i += m[0].length; continue
    }
    if (c === '"' || c === '「' || c === "'") {
      const close = c === '「' ? '」' : c
      let j = i + 1, s = ''
      while (j < src.length && src[j] !== close) { if (src[j] === '\\' && j + 1 < src.length) { s += src[j + 1]; j += 2 } else s += src[j++] }
      if (j >= src.length) throw new FormulaError('文字列の終わり（"）がありません')
      out.push({ t: 'str', v: s }); i = j + 1; continue
    }
    const two = src.slice(i, i + 2)
    if (['==', '!=', '>=', '<=', '&&', '||'].includes(two)) { out.push({ t: 'op', v: two }); i += 2; continue }
    if ('+-*/%(),<>!'.includes(c)) { out.push({ t: 'op', v: c }); i++; continue }
    const m = /^[\p{L}_][\p{L}\p{N}_]*/u.exec(src.slice(i))
    if (m) { out.push({ t: 'id', v: m[0] }); i += m[0].length; continue }
    throw new FormulaError(`使えない文字があります: 「${c}」`)
  }
  return out
}

type Node =
  | { k: 'lit'; v: Val }
  | { k: 'call'; name: string; args: Node[] }
  | { k: 'un'; op: string; a: Node }
  | { k: 'bin'; op: string; a: Node; b: Node }

const PREC: Record<string, number> = { '||': 1, '&&': 2, '==': 3, '!=': 3, '<': 4, '>': 4, '<=': 4, '>=': 4, '+': 5, '-': 5, '*': 6, '/': 6, '%': 6 }

export function parse(src: string): Node {
  const toks = tokenize(src)
  let p = 0
  const peek = () => toks[p]
  const isOp = (v: string) => peek()?.t === 'op' && peek()!.v === v
  const expect = (v: string) => { if (!isOp(v)) throw new FormulaError(`「${v}」が必要です`); p++ }
  function primary(): Node {
    const t = toks[p++]
    if (!t) throw new FormulaError('式が途中で終わっています')
    if (t.t === 'num' || t.t === 'str') return { k: 'lit', v: t.v }
    if (t.t === 'op' && t.v === '(') { const e = expr(0); expect(')'); return e }
    if (t.t === 'op' && (t.v === '-' || t.v === '!')) return { k: 'un', op: t.v, a: primary() }
    if (t.t === 'id') {
      if (t.v === 'true') return { k: 'lit', v: true }
      if (t.v === 'false') return { k: 'lit', v: false }
      if (!isOp('(')) throw new FormulaError(`「${t.v}」のあとに ( が必要です（列の値は prop("列名") と書きます）`)
      p++
      const args: Node[] = []
      if (!isOp(')')) { do { args.push(expr(0)) } while (isOp(',') && ++p) }
      expect(')')
      return { k: 'call', name: t.v, args }
    }
    throw new FormulaError(`「${t.v}」の位置が正しくありません`)
  }
  function expr(min: number): Node {
    let a = primary()
    for (;;) {
      const t = peek()
      if (!t || t.t !== 'op' || !(t.v in PREC) || PREC[t.v] < min) return a
      p++
      a = { k: 'bin', op: t.v, a, b: expr(PREC[t.v] + 1) }
    }
  }
  const e = expr(0)
  if (p < toks.length) throw new FormulaError('式の終わりに余分なものがあります')
  return e
}

// ---------------------------------------------------------------- 評価

const DAY = 86400000
const toDate = (v: Val): Date | null => (v instanceof Date ? v : typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v) ? new Date(v.length === 10 ? `${v}T00:00:00` : v) : null)
const num = (v: Val): number => (typeof v === 'number' ? v : typeof v === 'boolean' ? (v ? 1 : 0) : typeof v === 'string' && v.trim() !== '' && !isNaN(Number(v)) ? Number(v) : v instanceof Date ? v.getTime() : NaN)
export const show = (v: Val): string => {
  if (v === null || v === undefined) return ''
  if (v instanceof Date) return ymd(v)
  if (Array.isArray(v)) return v.map(show).join(', ')
  if (typeof v === 'number') return Number.isFinite(v) ? String(Math.round(v * 1e10) / 1e10) : ''
  if (typeof v === 'boolean') return v ? '✓' : ''
  return v
}
const empty = (v: Val) => v === null || v === '' || (Array.isArray(v) && !v.length) || (typeof v === 'number' && isNaN(v))
const eq = (a: Val, b: Val): boolean => {
  if (a instanceof Date || b instanceof Date) return toDate(a)?.getTime() === toDate(b)?.getTime()
  if (typeof a === 'number' || typeof b === 'number') return num(a) === num(b)
  if (Array.isArray(a)) return a.some((x) => eq(x, b))
  return show(a) === show(b)
}
function addUnit(d: Date, n: number, unit: string): Date {
  const r = new Date(d)
  if (/^year/.test(unit)) r.setFullYear(r.getFullYear() + n)
  else if (/^month/.test(unit)) r.setMonth(r.getMonth() + n)
  else if (/^week/.test(unit)) r.setDate(r.getDate() + n * 7)
  else if (/^hour/.test(unit)) r.setHours(r.getHours() + n)
  else r.setDate(r.getDate() + n)
  return r
}

export type PropGetter = (name: string) => Val

/** 式を計算する。prop: 列の名前から値を取る */
function evaluate(n: Node, prop: PropGetter): Val {
  const ev = (x: Node) => evaluate(x, prop)
  switch (n.k) {
    case 'lit': return n.v
    case 'un': { const a = ev(n.a); return n.op === '-' ? -num(a) : !truthy(a) }
    case 'bin': {
      if (n.op === '&&') return truthy(ev(n.a)) && truthy(ev(n.b))
      if (n.op === '||') return truthy(ev(n.a)) || truthy(ev(n.b))
      const a = ev(n.a), b = ev(n.b)
      switch (n.op) {
        case '+':
          if (a instanceof Date && typeof b === 'number') return new Date(a.getTime() + b * DAY)
          return typeof a === 'string' || typeof b === 'string' || Array.isArray(a) ? show(a) + show(b) : num(a) + num(b)
        case '-':
          if (a instanceof Date && b instanceof Date) return Math.round((a.getTime() - b.getTime()) / DAY)
          if (a instanceof Date && typeof b === 'number') return new Date(a.getTime() - b * DAY)
          return num(a) - num(b)
        case '*': return num(a) * num(b)
        case '/': return num(b) === 0 ? null : num(a) / num(b)
        case '%': return num(a) % num(b)
        case '==': return eq(a, b)
        case '!=': return !eq(a, b)
        default: {
          const x = a instanceof Date || b instanceof Date ? (toDate(a)?.getTime() ?? NaN) : typeof a === 'string' && typeof b === 'string' ? a : num(a)
          const y = a instanceof Date || b instanceof Date ? (toDate(b)?.getTime() ?? NaN) : typeof a === 'string' && typeof b === 'string' ? b : num(b)
          return n.op === '<' ? x < y : n.op === '>' ? x > y : n.op === '<=' ? x <= y : x >= y
        }
      }
    }
    case 'call': {
      const f = FUNCS[n.name] ?? FUNCS[n.name.toLowerCase()]
      if (!f) throw new FormulaError(`「${n.name}」という関数はありません`)
      if (n.name === 'if') { const [c, a, b] = n.args; return truthy(ev(c)) ? (a ? ev(a) : null) : (b ? ev(b) : null) }
      if (n.name === 'prop') { const a = ev(n.args[0]); return prop(String(a)) }
      return f(n.args.map(ev))
    }
  }
}
const truthy = (v: Val) => !empty(v) && v !== false && v !== 0

const FUNCS: Record<string, (a: Val[]) => Val> = {
  if: () => null, prop: () => null,
  and: (a) => a.every(truthy), or: (a) => a.some(truthy), not: (a) => !truthy(a[0]),
  empty: (a) => empty(a[0]), concat: (a) => a.map(show).join(''), join: (a) => (Array.isArray(a[0]) ? a[0].map(show).join(show(a[1] ?? ', ')) : show(a[0])),
  length: (a) => (Array.isArray(a[0]) ? a[0].length : show(a[0]).length), contains: (a) => (Array.isArray(a[0]) ? a[0].some((x) => eq(x, a[1])) : show(a[0]).includes(show(a[1]))),
  replace: (a) => show(a[0]).split(show(a[1])).join(show(a[2])), upper: (a) => show(a[0]).toUpperCase(), lower: (a) => show(a[0]).toLowerCase(),
  format: (a) => show(a[0]), toNumber: (a) => num(a[0]),
  round: (a) => { const d = num(a[1] ?? 0) || 0; const m = 10 ** d; return Math.round(num(a[0]) * m) / m },
  floor: (a) => Math.floor(num(a[0])), ceil: (a) => Math.ceil(num(a[0])), abs: (a) => Math.abs(num(a[0])),
  min: (a) => Math.min(...a.flat().map(num)), max: (a) => Math.max(...a.flat().map(num)), sum: (a) => a.flat().map(num).filter((x) => !isNaN(x)).reduce((s, x) => s + x, 0),
  now: () => new Date(), today: () => { const d = new Date(); d.setHours(0, 0, 0, 0); return d },
  dateBetween: (a) => {
    const x = toDate(a[0]), y = toDate(a[1]); const unit = show(a[2] ?? 'days')
    if (!x || !y) return null
    if (/^year/.test(unit)) return x.getFullYear() - y.getFullYear()
    if (/^month/.test(unit)) return (x.getFullYear() - y.getFullYear()) * 12 + x.getMonth() - y.getMonth()
    if (/^week/.test(unit)) return Math.trunc((x.getTime() - y.getTime()) / (7 * DAY))
    if (/^hour/.test(unit)) return Math.trunc((x.getTime() - y.getTime()) / 3600000)
    return Math.round((x.getTime() - y.getTime()) / DAY)
  },
  dateAdd: (a) => { const d = toDate(a[0]); return d ? addUnit(d, num(a[1]), show(a[2] ?? 'days')) : null },
  dateSubtract: (a) => { const d = toDate(a[0]); return d ? addUnit(d, -num(a[1]), show(a[2] ?? 'days')) : null },
  formatDate: (a) => {
    const d = toDate(a[0]); if (!d) return null
    const p = (n: number) => String(n).padStart(2, '0')
    return show(a[1] ?? 'YYYY/MM/DD').replace('YYYY', String(d.getFullYear())).replace('MM', p(d.getMonth() + 1)).replace('DD', p(d.getDate()))
      .replace('HH', p(d.getHours())).replace('mm', p(d.getMinutes()))
  },
  year: (a) => toDate(a[0])?.getFullYear() ?? null, month: (a) => { const d = toDate(a[0]); return d ? d.getMonth() + 1 : null }, day: (a) => toDate(a[0])?.getDate() ?? null,
  weekday: (a) => { const d = toDate(a[0]); return d ? '日月火水木金土'[d.getDay()] : null },
}

/** 使える関数（数式の入力画面の説明用） */
export const FORMULA_HELP = `列の値: prop("列名")　演算: + - * / %　比較: == != > < >= <=　論理: && || !
関数: if(条件, 真, 偽)・and・or・not・empty(値)・concat(…)・length・contains(文字, 部分)・replace・upper・lower・round(数, 桁)・floor・ceil・abs・min・max・sum・toNumber・format
日付: today()・now()・dateBetween(日付1, 日付2, "days"|"weeks"|"months"|"years"|"hours")・dateAdd(日付, 数, 単位)・dateSubtract・formatDate(日付, "YYYY/MM/DD")・year・month・day・weekday
例: prop("金額") * 1.1　／　if(prop("状態") == "完了", "✅", "")　／　dateBetween(prop("期限"), today(), "days")`

const cache = new Map<string, Node | FormulaError>()
/** 式を計算する（解析した結果は覚えておく）。エラーは「⚠ …」の文字にする */
export function run(expr: string, prop: PropGetter): Val {
  let ast = cache.get(expr)
  if (!ast) { try { ast = parse(expr) } catch (e) { ast = e instanceof FormulaError ? e : new FormulaError(String(e)) } cache.set(expr, ast) }
  if (ast instanceof FormulaError) return `⚠ ${ast.message}`
  try { return evaluate(ast, prop) } catch (e) { return `⚠ ${(e as Error).message}` }
}
export function check(expr: string): string | null {
  try { parse(expr); return null } catch (e) { return (e as Error).message }
}
