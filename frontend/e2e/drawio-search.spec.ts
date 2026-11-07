import { expect, test, type Browser } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'
import { api, cleanup, editorRun, makeUser, nodes, open as openAs, waitContent, type TestUser } from './helpers'

/** draw.io の図（編集・貼り付け・取り込み）と、添付ファイルの中身の検索 */
test.describe.configure({ mode: 'serial' })

let owner: TestUser
test.beforeAll(() => { owner = makeUser('図 担当') })
test.afterAll(() => cleanup())

const XML = '<mxfile><diagram name="システム構成" id="d1"><mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>'
  + '<mxCell id="2" value="Webサーバー" vertex="1" parent="1"/><mxCell id="3" value="&lt;b&gt;データベース&lt;/b&gt;" vertex="1" parent="1"/></root></mxGraphModel></diagram></mxfile>'

// ---------------------------------------------------------------- 図を埋め込んだ PNG を作る
const BASE_PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0 } return t })()
const crc32 = (b: Buffer) => { let c = 0xffffffff; for (const x of b) c = CRC[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0 }
function pngWithXml(xml: string): Buffer {
  const data = Buffer.concat([Buffer.from('mxfile\0', 'latin1'), Buffer.from(encodeURIComponent(xml), 'latin1')])
  const type = Buffer.from('tEXt', 'latin1')
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([type, data])))
  const iend = BASE_PNG.length - 12
  return Buffer.concat([BASE_PNG.subarray(0, iend), len, type, data, crc, BASE_PNG.subarray(iend)])
}
const DRAWIO_PNG = pngWithXml(XML)

/** diagrams.net の代わり（埋め込みモードのやり取りだけをまねる） */
const FAKE_DRAWIO = `<!doctype html><html><body><p id="state">init</p><button id="save">保存して終了</button><script>
  const send = (m) => parent.postMessage(JSON.stringify(m), '*')
  addEventListener('message', (e) => {
    const m = JSON.parse(e.data)
    if (m.action === 'load') { document.body.dataset.loaded = m.xmlpng ? 'xmlpng:' + m.xmlpng.slice(0, 22) : m.xml ? 'xml' : 'empty'; document.getElementById('state').textContent = 'loaded' }
    if (m.action === 'export') send({ event: 'export', format: m.format, data: 'data:image/png;base64,${DRAWIO_PNG.toString('base64')}', xml: m.xml })
  })
  document.getElementById('save').onclick = () => send({ event: 'save', xml: ${JSON.stringify(XML)}, exit: true })
  send({ event: 'init' })
</script></body></html>`

// draw.io のエディタは差し替える
const open = (browser: Browser, u: TestUser, path: string) => openAs(browser, u, path, {
  setup: (ctx) => ctx.route('https://embed.diagrams.net/**', (r) => r.fulfill({ contentType: 'text/html; charset=utf-8', body: FAKE_DRAWIO })),
})
const contentOf = async (id: string) => (await api(owner, 'GET', `/api/pages/${id}/content`)).content as any

test('draw.io: 図を描いて保存すると PNG と図の文字が入り、開き直すと図を読み込む。.drawio.png を落とすと図になる', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '図の確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '構成' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}?edit=1`)
  await page.locator('.ProseMirror').click()
  // 「/draw.io 図」で入れると、すぐにエディタが開く
  await page.keyboard.type('/drawio')
  await expect(page.locator('.slash-menu')).toContainText('draw.io 図')
  await page.keyboard.press('Enter')
  await expect(page.getByTestId('drawio-overlay')).toBeVisible()
  const frame = page.frameLocator('[data-testid=drawio-frame]')
  await expect(frame.locator('#state')).toHaveText('loaded')
  expect(await frame.locator('body').getAttribute('data-loaded')).toBe('empty')
  await frame.locator('#save').click()
  await expect(page.getByTestId('drawio-overlay')).toBeHidden()
  await expect(page.locator('[data-testid=drawio-block] img')).toHaveAttribute('src', /^\/api\/attachments\//)
  const c = await waitContent(owner, p.id, (t) => t.includes('Webサーバー'))
  const d = nodes(c.content, 'drawio')[0]
  expect(d.attrs.title).toBe('システム構成')
  expect(d.attrs.text).toBe('システム構成\nWebサーバー\nデータベース')
  expect(c.text).toContain('データベース') // 図の中の文字もページの検索の対象になる

  // 開き直すと、保存した PNG（図入り）を読み込む
  await page.getByTestId('drawio-edit').click()
  await expect(frame.locator('#state')).toHaveText('loaded')
  expect(await frame.locator('body').getAttribute('data-loaded')).toBe('xmlpng:data:image/png;base64,')
  await page.getByTestId('drawio-close').click()
  await expect(page.getByTestId('drawio-overlay')).toBeHidden()

  // .drawio.png を落とすと、画像ではなく図のブロックになる（ふつうの PNG は画像のまま）
  await editorRun(page, `editor.commands.focus('end')`)
  await page.evaluate(([b64, plain]) => {
    const dt = new DataTransfer()
    dt.items.add(new File([Uint8Array.from(atob(b64), (x) => x.charCodeAt(0))], 'ネットワーク.drawio.png', { type: 'image/png' }))
    dt.items.add(new File([Uint8Array.from(atob(plain), (x) => x.charCodeAt(0))], '写真.png', { type: 'image/png' }))
    document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  }, [DRAWIO_PNG.toString('base64'), BASE_PNG.toString('base64')] as const)
  const c2 = await waitContent(owner, p.id, (_t, j) => nodes(j, 'drawio').length === 2 && nodes(j, 'image').length === 1)
  const dropped = nodes(c2.content, 'drawio')[1]
  expect(dropped.attrs.title).toBe('ネットワーク')
  expect(dropped.attrs.text).toContain('Webサーバー')

})

test('draw.io の取り込み: 図入りの PNG・図の XML（.drawio）・Confluence 形式（図名.png と図名）', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '図の取り込み' })
  const page = await open(browser, owner, `/w/${ws.id}`)
  await page.getByTestId('ws-menu').click()
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId('ws-import').click()
  const zip = zipSync({
    '設計.md': strToU8('# 設計\n\n![構成](構成.drawio.png)\n\n[流れ](流れ.drawio)\n\n![全体](attachments/全体.png)\n\n![写真](写真.png)\n'),
    '構成.drawio.png': new Uint8Array(DRAWIO_PNG),
    '流れ.drawio': strToU8(XML.replace('Webサーバー', '申請')),
    'attachments/全体.png': new Uint8Array(BASE_PNG),
    'attachments/全体': strToU8(XML.replace('Webサーバー', 'ロードバランサー')),
    '写真.png': new Uint8Array(BASE_PNG),
  })
  await (await chooser).setFiles({ name: 'design.zip', mimeType: 'application/zip', buffer: Buffer.from(zip) })
  await expect(page.locator('#page-title')).toHaveValue('設計', { timeout: 30_000 })
  const pages = await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)
  const design = pages.find((x: any) => x.title === '設計')
  const c = await contentOf(design.id)
  const ds = nodes(c, 'drawio')
  expect(ds.map((d) => d.attrs.title)).toEqual(['構成', '流れ', '全体'])
  expect(ds[0].attrs.src).toMatch(/^\/api\/attachments\//)
  expect(ds[0].attrs.xmlSrc).toBeNull()
  expect(ds[1].attrs.src).toBeFalsy()
  expect(ds[1].attrs.xmlSrc).toMatch(/^\/api\/attachments\//)
  expect(ds[1].attrs.text).toContain('申請')
  expect(ds[2].attrs.src).toMatch(/^\/api\/attachments\//)
  expect(ds[2].attrs.xmlSrc).toMatch(/^\/api\/attachments\//)
  expect(ds[2].attrs.text).toContain('ロードバランサー')
  expect(nodes(c, 'image')).toHaveLength(1)
  // XML だけの図は「開くと表示されます」と出る
  await expect(page.locator('[data-testid=drawio-block]').nth(1)).toContainText('開くと表示されます')
})

test('添付ファイルの中身で検索でき、結果に 📎 ファイル名が出る', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '添付の検索' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '規程の置き場' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}?edit=1`)
  const docx = zipSync({
    'word/document.xml': strToU8('<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>在宅勤務の申請は前日までに行います。</w:t></w:r></w:p></w:body></w:document>'),
  })
  await editorRun(page, `editor.commands.focus('end')`)
  await page.evaluate((b64) => {
    const dt = new DataTransfer()
    dt.items.add(new File([Uint8Array.from(atob(b64), (x) => x.charCodeAt(0))], '勤務規程.docx', { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }))
    document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  }, Buffer.from(docx).toString('base64'))
  await waitContent(owner, p.id, (_t, j) => nodes(j, 'fileBlock').length === 1)
  // アップロードの直後に裏で文字を取り出す
  await expect.poll(async () => (await api(owner, 'GET', `/api/search?workspaceId=${ws.id}&q=${encodeURIComponent('在宅勤務')}`)).length, { timeout: 20_000 }).toBe(1)
  await page.getByTestId('search').fill('在宅勤務 前日')
  const hit = page.locator('.result', { hasText: '規程の置き場' })
  await expect(hit).toBeVisible()
  await expect(hit.getByTestId('search-attachment')).toContainText('勤務規程.docx')
  await expect(hit.locator('.snippet')).toContainText('在宅勤務')
})
