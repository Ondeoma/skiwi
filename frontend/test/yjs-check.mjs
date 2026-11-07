// サーバー側の Yjs の実装（modules/api の skiwi.ydoc）を、本物の Yjs と突き合わせるための補助（YdocSpec から呼ぶ）。
// 標準入力に要求の配列（JSON）を受け、結果の配列（JSON）を標準出力に返す。
//   { mode: "gen", seed, editors, steps } → 複数の人が同時に編集した文書を作る
//       { full: 全状態の update, parts: 各編集の update の配列, ids: 最上位の（削除されていない）ブロックの ID, doc: 本文の JSON }
//   { mode: "apply", updates: [base64...] } → { doc: 本文の JSON（y-prosemirror と同じ読み方） }
import * as Y from 'yjs'

const b64 = (u) => Buffer.from(u).toString('base64')
const unb64 = (s) => new Uint8Array(Buffer.from(s, 'base64'))

/** y-prosemirror と同じ読み方で、XmlElement を ProseMirror の JSON にする。 */
function toPM(el) {
  const out = { type: el.nodeName }
  const attrs = el.getAttributes()
  if (Object.keys(attrs).length) out.attrs = attrs
  const content = []
  for (const ch of el.toArray()) {
    if (ch instanceof Y.XmlText) {
      for (const d of ch.toDelta()) {
        const t = { type: 'text', text: d.insert }
        if (d.attributes) {
          t.marks = Object.entries(d.attributes)
            .map(([k, v]) => (v && typeof v === 'object' && Object.keys(v).length ? { type: k, attrs: v } : { type: k }))
            .sort((a, b) => a.type.localeCompare(b.type))
        }
        content.push(t)
      }
    } else content.push(toPM(ch))
  }
  if (content.length) out.content = content
  return out
}
const docJSON = (doc) => ({ type: 'doc', content: doc.getXmlFragment('default').toArray().map(toPM) })

function rng(seed) {
  let s = seed >>> 0 || 1
  return () => { s = (Math.imul(s, 1664525) + 1013904223) >>> 0; return s / 4294967296 }
}

function gen({ seed, editors = 3, steps = 60 }) {
  const rand = rng(seed)
  const pick = (n) => Math.floor(rand() * n)
  const words = ['予定', '議事録', 'alpha', 'β', '😀絵文字', '日本語の文', 'x', '']
  const docs = Array.from({ length: editors }, (_, i) => { const d = new Y.Doc(); d.clientID = 1000 + i * 7 + seed % 5; return d })
  const parts = []
  docs.forEach((d) => d.on('update', (u) => parts.push(u)))
  const sync = (a, b) => Y.applyUpdate(b, Y.encodeStateAsUpdate(a, Y.encodeStateVector(b)))
  for (let i = 0; i < steps; i++) {
    const d = docs[pick(docs.length)]
    const frag = d.getXmlFragment('default')
    const r = rand()
    if (r < 0.35 || frag.length === 0) {
      // ブロックを入れる
      const kind = ['paragraph', 'heading', 'paragraph', 'blockquote'][pick(4)]
      const el = new Y.XmlElement(kind)
      frag.insert(pick(frag.length + 1), [el])
      if (kind === 'heading') el.setAttribute('level', 1 + pick(3))
      const t = new Y.XmlText()
      el.insert(0, [t])
      t.insert(0, words[pick(words.length)] + i)
      if (rand() < 0.3) t.format(0, 1, { bold: {} })
    } else if (r < 0.45) {
      const at = pick(frag.length)
      frag.delete(at, Math.min(1 + pick(2), frag.length - at))
    } else if (r < 0.8) {
      // ブロックの中の文字を編集する
      const el = frag.get(pick(frag.length))
      const t = el.get(0)
      if (t instanceof Y.XmlText) {
        if (rand() < 0.6) t.insert(pick(t.length + 1), words[pick(words.length)])
        else if (t.length > 0) t.delete(pick(t.length), 1)
      }
    } else if (r < 0.9) {
      const el = frag.get(pick(frag.length))
      if (el.nodeName === 'heading') el.setAttribute('level', 1 + pick(3))
    } else {
      // ほかの人と同期する（同時編集の重なりを作る）
      const o = docs[pick(docs.length)]
      if (o !== d) { sync(d, o); sync(o, d) }
    }
  }
  docs.forEach((a) => docs.forEach((b) => a !== b && sync(a, b)))
  const d0 = docs[0]
  const ids = []
  for (let it = d0.getXmlFragment('default')._start; it; it = it.right) if (!it.deleted) ids.push([it.id.client, it.id.clock])
  return { full: b64(Y.encodeStateAsUpdate(d0)), parts: parts.map(b64), ids, doc: docJSON(d0) }
}

function apply({ updates }) {
  const d = new Y.Doc()
  for (const u of updates) Y.applyUpdate(d, unb64(u))
  return { doc: docJSON(d) }
}

let input = ''
process.stdin.on('data', (c) => { input += c })
process.stdin.on('end', () => {
  const reqs = JSON.parse(input)
  const out = reqs.map((r) => {
    try { return r.mode === 'gen' ? gen(r) : apply(r) } catch (e) { return { error: String(e && e.stack || e) } }
  })
  process.stdout.write(JSON.stringify(out))
})
