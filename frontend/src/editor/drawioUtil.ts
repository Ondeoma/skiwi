/** draw.io（diagrams.net）の図のための補助: エディタの URL、図の文字（検索用）、PNG の扱い */
import { base64ToBytes, blobToDataUrl } from '../util/bytes'

const DEFAULT_URL = 'https://embed.diagrams.net'
let configP: Promise<{ drawioUrl?: string }> | null = null
/** サーバーの設定（DRAWIO_URL）。空ならエディタを使えない */
export function drawioUrl(): Promise<string> {
  configP ??= fetch('/api/client-config', { credentials: 'same-origin' })
    .then((r) => (r.ok ? r.json() : { drawioUrl: DEFAULT_URL }))
    .catch(() => ({ drawioUrl: DEFAULT_URL }))
  return configP.then((c) => c.drawioUrl ?? DEFAULT_URL)
}

/** 埋め込みモードのエディタの URL（日本語・「保存して終了」ボタン付き） */
export function editorSrc(base: string): string {
  const u = new URL(base, location.origin)
  for (const [k, v] of Object.entries({ embed: '1', proto: 'json', spin: '1', lang: 'ja', saveAndExit: '1', noSaveBtn: '1', noExitBtn: '0', libraries: '1' })) u.searchParams.set(k, v)
  return u.toString()
}

/** 圧縮された図（<diagram> の中が base64 + deflate）を XML に戻す */
async function inflate(b64: string): Promise<string | null> {
  try {
    const bin = base64ToBytes(b64.trim())
    const text = await new Response(new Blob([bin]).stream().pipeThrough(new DecompressionStream('deflate-raw'))).text()
    return decodeURIComponent(text)
  } catch { return null }
}

const stripTags = (s: string) => {
  const d = new DOMParser().parseFromString(`<body>${s.replace(/<br\s*\/?>/gi, ' ')}</body>`, 'text/html')
  return (d.body.textContent ?? '').replace(/\s+/g, ' ').trim()
}

/** 図の中の文字（図形・矢印のラベル、ページ名）を取り出す。ページの検索・本文の文字に使う */
export async function labelsOf(xml: string | null | undefined): Promise<string> {
  if (!xml) return ''
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.querySelector('parsererror')) return ''
  const out: string[] = []
  const models: Document[] = [doc]
  for (const d of [...doc.getElementsByTagName('diagram')]) {
    const name = d.getAttribute('name')
    if (name && !/^(Page|ページ)-\d+$/.test(name)) out.push(name)
    if (d.getElementsByTagName('mxGraphModel').length) continue // 圧縮されていない（doc にそのまま入っている）
    const inner = await inflate(d.textContent ?? '')
    if (!inner) continue
    const m = new DOMParser().parseFromString(inner, 'application/xml')
    if (!m.querySelector('parsererror')) models.push(m)
  }
  for (const m of models) {
    for (const el of [...m.querySelectorAll('mxCell[value], object[label], UserObject[label]')]) {
      const v = stripTags(el.getAttribute('value') ?? el.getAttribute('label') ?? '')
      if (v) out.push(v)
    }
  }
  return [...new Set(out)].join('\n').slice(0, 20000)
}

export function dataUrlToFile(dataUrl: string, name: string): File {
  const [head, b64] = dataUrl.split(',', 2)
  const type = /data:([^;]+)/.exec(head)?.[1] ?? 'image/png'
  const bin = base64ToBytes(b64)
  return new File([bin], name, { type })
}

export async function urlToDataUrl(url: string): Promise<string> {
  return blobToDataUrl(await (await fetch(url, { credentials: 'same-origin' })).blob())
}

/** 添付ファイルの名前（「図.drawio.png」の形） */
export const drawioFileName = (title: string | null | undefined) =>
  `${(title || '図').replace(/[\\/:*?"<>|\n\r]+/g, ' ').trim().slice(0, 80) || '図'}.drawio.png`

/** draw.io の図のファイルか（.drawio.png は図の XML を埋め込んだ PNG、.drawio / .dio は XML） */
export const isDrawioPng = (name: string) => /\.(drawio|dio)\.png$/i.test(name)
export const isDrawioXml = (name: string) => /\.(drawio|dio)$/i.test(name)

/** PNG に埋め込まれた図の XML（tEXt / zTXt / iTXt の "mxfile"）を取り出す。なければ null */
export async function xmlFromPng(buf: ArrayBuffer): Promise<string | null> {
  const b = new Uint8Array(buf)
  const dv = new DataView(buf)
  if (b.length < 8 || b[1] !== 0x50 || b[2] !== 0x4e || b[3] !== 0x47) return null
  let i = 8
  const latin1 = (a: Uint8Array) => Array.from(a, (c) => String.fromCharCode(c)).join('')
  while (i + 8 <= b.length) {
    const len = dv.getUint32(i)
    const type = latin1(b.subarray(i + 4, i + 8))
    const data = b.subarray(i + 8, i + 8 + len)
    if (type === 'tEXt' || type === 'zTXt' || type === 'iTXt') {
      const z = data.indexOf(0)
      const key = latin1(data.subarray(0, z))
      if (key === 'mxfile' || key === 'mxGraphModel') {
        let raw: string
        if (type === 'tEXt') raw = latin1(data.subarray(z + 1))
        else if (type === 'zTXt') raw = await new Response(new Blob([data.subarray(z + 2)]).stream().pipeThrough(new DecompressionStream('deflate'))).text()
        else {
          // iTXt: key\0 圧縮フラグ 圧縮方式 言語\0 訳語\0 本文
          const comp = data[z + 1]
          let p = z + 3
          p = data.indexOf(0, p) + 1
          p = data.indexOf(0, p) + 1
          const body = data.subarray(p)
          raw = comp ? await new Response(new Blob([body]).stream().pipeThrough(new DecompressionStream('deflate'))).text() : new TextDecoder().decode(body)
        }
        try { return decodeURIComponent(raw) } catch { return raw }
      }
    }
    if (type === 'IEND') break
    i += 12 + len
  }
  return null
}
