import { onBeforeUnmount, watchEffect, type Ref } from 'vue'

/**
 * カスタムCSSを安全に適用する。
 *  - すべてのルールを表示エリア（scope）の中だけに効くよう書き換える（:root / html / body は scope に置き換え）
 *  - 外部の読み込み（@import、http(s) などの url()）、画面全体を覆える position: fixed は取り除く
 *  - url() は添付ファイル（/api/attachments/…）と data:image のみ許す
 * 解析はブラウザ自身のCSSパーサ（CSSStyleSheet）で行う。
 */
const PAGE_CSS_SCOPE = '.skiwi-page-css'

export interface ScopedCss { css: string; warnings: string[] }

const URL_RE = /url\(\s*(['"]?)(.*?)\1\s*\)/gi

function urlAllowed(u: string): boolean {
  const s = u.trim()
  return /^\/api\/attachments\/[0-9a-f-]{36}$/i.test(s) || /^\/api\/public\/[\w-]+\/attachments\/[0-9a-f-]{36}$/i.test(s) || /^data:image\//i.test(s)
}

/** 括弧の中の , では区切らない */
function splitSelectors(sel: string): string[] {
  const out: string[] = []
  let depth = 0, cur = ''
  for (const ch of sel) {
    if (ch === '(' || ch === '[') depth++
    else if (ch === ')' || ch === ']') depth--
    if (ch === ',' && depth === 0) { out.push(cur); cur = '' } else cur += ch
  }
  out.push(cur)
  return out.map((s) => s.trim()).filter(Boolean)
}

/** 範囲の外に効くセレクタ（「body ~ *」のような兄弟の指定）は取り除く。全部取り除いたら null */
function scopeSelector(sel: string, scope: string, warn: (w: string) => void): string | null {
  const out = splitSelectors(sel).flatMap((s) => {
    const m = /^(:root|html|body)(?![\w-])/i.exec(s)
    if (!m) return [`${scope} ${s}`]
    const rest = s.slice(m[0].length)
    if (/^\s*[~+]/.test(rest)) { warn(`${s}: 表示エリアの外に効く指定は使えません`); return [] }
    return [(scope + rest).trim()]
  })
  return out.length ? out.join(', ') : null
}

function sanitizeStyle(style: CSSStyleDeclaration, warn: (w: string) => void, mapUrl?: (u: string) => string) {
  for (const prop of Array.from(style)) {
    const value = style.getPropertyValue(prop)
    // var() で値を後から入れられると検査をすり抜けるので、position には使わせない
    if (prop === 'position' && /fixed|var\(/i.test(value)) { style.removeProperty(prop); warn('position: fixed は使えません（画面全体を覆えるため）'); continue }
    // image-set("https://…") や src("…") は url() を書かずに外部を読み込めるので使わせない
    if (/image-set\(|(^|[^\w-])src\(/i.test(value)) { style.removeProperty(prop); warn(`${prop}: image-set() と src() は使えません（url() で添付ファイルを指定してください）`); continue }
    if (/url\(/i.test(value)) {
      const urls = [...value.matchAll(URL_RE)].map((m) => m[2])
      if (urls.some((u) => !urlAllowed(u))) { style.removeProperty(prop); warn(`${prop}: 外部の url() は使えません（添付ファイルと data:image のみ）`); continue }
      if (mapUrl) style.setProperty(prop, value.replace(URL_RE, (_, q: string, u: string) => `url(${q}${mapUrl(u)}${q})`), style.getPropertyPriority(prop))
    }
  }
}

function processRules(rules: CSSRuleList, scope: string, out: string[], warn: (w: string) => void, mapUrl?: (u: string) => string, nested = false) {
  for (const rule of Array.from(rules)) {
    if (rule instanceof CSSStyleRule) {
      sanitizeStyle(rule.style, warn, mapUrl)
      if (rule.cssRules?.length) processRules(rule.cssRules, scope, [], warn, mapUrl, true) // 入れ子のルールは中身だけ安全にする
      if (!nested) {
        const sel = scopeSelector(rule.selectorText, scope, warn)
        if (sel === null) continue
        rule.selectorText = sel
        out.push(rule.cssText)
      }
    } else if (typeof CSSGroupingRule !== 'undefined' && rule instanceof CSSGroupingRule && !(rule instanceof CSSStyleRule)) {
      // @media / @supports / @container / @layer { … }
      processRules(rule.cssRules, scope, [], warn, mapUrl, nested)
      out.push(rule.cssText)
    } else if (rule instanceof CSSKeyframesRule) {
      for (const kf of Array.from(rule.cssRules)) sanitizeStyle((kf as CSSKeyframeRule).style, warn, mapUrl)
      out.push(rule.cssText)
    } else if (rule instanceof CSSFontFaceRule) {
      const src = rule.style.getPropertyValue('src')
      if ([...src.matchAll(URL_RE)].some((m) => !urlAllowed(m[2]))) warn('@font-face: 外部のフォントは使えません（添付ファイルのみ）')
      else out.push(mapUrl ? rule.cssText.replace(URL_RE, (_, q: string, u: string) => `url(${q}${mapUrl(u)}${q})`) : rule.cssText)
    } else {
      warn(`${rule.cssText.split(/[\s{]/)[0]} は使えません`)
    }
  }
}

/** CSS を範囲限定・安全化する。解析できない部分はブラウザの規則どおり読み飛ばされる */
export function scopeCss(raw: string, scope = PAGE_CSS_SCOPE, mapUrl?: (u: string) => string): ScopedCss {
  const warnings = new Set<string>()
  const warn = (w: string) => warnings.add(w)
  if (/@import\b/i.test(raw)) warn('@import は使えません（外部の読み込み）')
  if (!raw.trim()) return { css: '', warnings: [] }
  const sheet = new CSSStyleSheet()
  try { sheet.replaceSync(raw) } catch { return { css: '', warnings: ['CSS を解析できませんでした'] } }
  const out: string[] = []
  processRules(sheet.cssRules, scope, out, warn, mapUrl)
  return { css: out.join('\n'), warnings: [...warnings] }
}

/** 重ねたCSSを <head> に入れる（画面を離れたら取り除く） */
export function useCustomCss(layers: Ref<string[]>, mapUrl?: () => ((u: string) => string) | undefined) {
  const el = document.createElement('style')
  el.dataset.customCss = ''
  document.head.appendChild(el)
  watchEffect(() => {
    el.textContent = layers.value.map((c) => scopeCss(c, PAGE_CSS_SCOPE, mapUrl?.()).css).join('\n')
  })
  onBeforeUnmount(() => el.remove())
}
