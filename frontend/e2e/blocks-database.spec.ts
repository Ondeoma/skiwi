import { expect, test, type Browser } from '@playwright/test'
import { strToU8, zipSync } from 'fflate'
import { api, cleanup, editorRun, makeUser, nodes, open as openAs, waitContent, type TestUser } from './helpers'

/** ブロックのつまみとメニュー・文字色・トグル見出し・全幅・パンくず・キャプション・絵文字・ブロックへのリンク・リンクの展開・
 *  データベース（ビュー・数式・自動の列・集計・グループ・サブ項目・ページ内・フォーム）・Notion／Confluence／Word の取り込み */
test.describe.configure({ mode: 'serial' })

let owner: TestUser
test.beforeAll(() => { owner = makeUser('ノート 担当') })
test.afterAll(() => cleanup())

const open = (browser: Browser, u: TestUser, path: string) => openAs(browser, u, path, { context: { permissions: ['clipboard-read', 'clipboard-write'] } })
const contentOf = async (id: string) => (await api(owner, 'GET', `/api/pages/${id}/content`)).content as any

test('ブロックのつまみ: 変換・色・複製・リンク・別のページへ移動', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'ブロックの確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'ブロック' })
  const target = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '移動先のページ' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}?edit=1`)
  await editorRun(page, `editor.chain().focus().setContent('<p>一つ目の段落</p><p>二つ目の段落</p><p>三つ目の段落</p>').run()`)
  const para = (t: string) => page.locator('.ProseMirror p', { hasText: t })

  // 変換: 段落 → 見出し2
  await para('一つ目').hover()
  await page.getByTestId('block-handle').click()
  await page.getByTestId('block-turn').click()
  await page.getByTestId('block-turn-item').filter({ hasText: '見出し2' }).click()
  await expect(page.locator('.ProseMirror h2')).toHaveText('一つ目の段落')
  // 色: 文字を赤に
  await para('二つ目').hover()
  await page.getByTestId('block-handle').click()
  await page.getByTestId('block-color').click()
  await page.getByTestId('block-color-red').click()
  await expect(para('二つ目').locator('[data-text-color="red"]')).toHaveText('二つ目の段落')
  // 複製
  await para('二つ目').hover()
  await page.getByTestId('block-handle').click()
  await page.getByTestId('block-duplicate').click()
  await expect(para('二つ目')).toHaveCount(2)
  // ブロックへのリンク
  await para('三つ目').hover()
  await page.getByTestId('block-handle').click()
  await page.getByTestId('block-link').click()
  const msg = await page.getByTestId('app-dialog').textContent()
  const url = /http\S+#block-[0-9a-z]+/.exec(msg ?? '')![0]
  await page.getByTestId('dialog-ok').click()
  await expect(page.locator('.ProseMirror p[data-block-id]', { hasText: '三つ目' })).toHaveCount(1)
  // 別のページへ移動
  await para('三つ目').hover()
  await page.getByTestId('block-handle').click()
  await page.getByTestId('block-move').click()
  await page.getByTestId('page-picker-search').fill('移動先')
  await page.getByTestId('page-picker').getByText('移動先のページ').first().click()
  await page.getByTestId('dialog-ok').click() // 確認
  await expect(page.getByTestId('app-dialog')).toContainText('移動しました')
  await page.getByTestId('dialog-ok').click()
  await expect(para('三つ目')).toHaveCount(0)
  await waitContent(owner, target.id, (t) => t.includes('三つ目の段落'))
  // ブロックへのリンクを開くと、そのブロックへ（移動先でも同じ ID）
  const page2 = await open(browser, owner, url.replace(p.id, target.id))
  await expect(page2.locator('.ProseMirror [data-block-id].block-flash')).toHaveCount(1)
  await page2.context().close()
  await page.context().close()
})

test('文字色・トグル見出し・絵文字・キャプション・全幅・パンくず', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '書式の確認' })
  const parent = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '親の章' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '書式', parentId: parent.id })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}?edit=1`)
  // パンくず
  await expect(page.getByTestId('breadcrumbs')).toContainText('書式の確認')
  await expect(page.getByTestId('breadcrumbs')).toContainText('親の章')
  // 文字色（選んだ文字のメニュー）
  await editorRun(page, `editor.chain().focus().setContent('<p>色を付ける文字</p>').setTextSelection({ from: 1, to: 4 }).run()`)
  await page.getByTestId('text-color').click()
  await page.getByTestId('text-bg-yellow').click()
  await expect(page.locator('.ProseMirror [data-text-bg="yellow"]')).toHaveText('色を付')
  // トグル見出し
  await editorRun(page, `editor.chain().focus('end').run()`)
  await page.keyboard.press('Enter')
  await page.keyboard.type('/開閉見出し2')
  await page.keyboard.press('Enter')
  await expect(page.locator('.ProseMirror .details[data-level="2"]')).toHaveCount(1)
  await page.keyboard.type('トグルの見出し')
  // 絵文字
  await editorRun(page, `editor.chain().focus('end').insertContent('<p>了解 </p>').focus('end').run()`)
  await page.keyboard.type(':いいね')
  await expect(page.getByTestId('emoji-menu')).toBeVisible()
  await page.keyboard.press('Enter')
  await expect(page.locator('.ProseMirror')).toContainText('了解 👍')
  // 画像のキャプション
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64')
  await page.route('**/e2e-caption.png', (r) => r.fulfill({ contentType: 'image/png', body: png }))
  await editorRun(page, `editor.chain().focus('end').insertContent({ type: 'image', attrs: { src: '/e2e-caption.png', alt: 'icon' } }).run()`)
  // 画像の読み込みが終わると表示が作り直されることがあるので、待ってから入力する
  await expect.poll(() => page.locator('.img-figure img').evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth > 0)).toBe(true)
  await page.getByTestId('image-caption').click()
  await expect(page.getByTestId('image-caption')).toBeFocused()
  await page.getByTestId('image-caption').fill('図1 構成')
  await page.getByTestId('image-caption').press('Enter')
  const c = await waitContent(owner, p.id, (_t, json) => JSON.stringify(json).includes('図1 構成'))
  expect(nodes(c.content, 'image')[0].attrs.caption).toBe('図1 構成')
  expect(nodes(c.content, 'details')[0].attrs.level).toBe(2)
  // 全幅
  await page.getByTestId('full-width').click()
  await expect(page.locator('article.page')).toHaveClass(/full-width/)
  await expect.poll(async () => (await api(owner, 'GET', `/api/pages/${p.id}`)).fullWidth).toBe(true)
  await page.context().close()
})

test('リンクの展開: URL を貼るとカード・埋め込みに変えられる', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'リンクの確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'リンク' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}?edit=1`)
  const paste = (text: string) => page.evaluate((t) => {
    const dt = new DataTransfer()
    dt.setData('text/plain', t)
    document.querySelector('.ProseMirror')!.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true }))
  }, text)
  await page.locator('.ProseMirror').click()
  await paste('https://example.com/docs/guide')
  await expect(page.getByTestId('url-menu')).toBeVisible()
  await expect(page.getByTestId('url-embed')).toHaveCount(0) // 対応サービスではない
  await page.getByTestId('url-card').click()
  await expect(page.getByTestId('link-card')).toHaveCount(1)
  await expect(page.getByTestId('link-card')).toContainText('example.com')
  // Figma は埋め込める
  await editorRun(page, `editor.chain().focus('end').insertContent('<p></p>').focus('end').run()`)
  await paste('https://www.figma.com/design/AbC123/Sample')
  await page.getByTestId('url-embed').click()
  await expect(page.locator('.ProseMirror iframe[src^="https://www.figma.com/embed"]')).toHaveCount(1)
  // 社内のアドレスにはサーバーから接続しない（タイトルなし・ドメインだけ）
  const u = await api(owner, 'GET', `/api/unfurl?url=${encodeURIComponent('http://127.0.0.1:8080/healthz')}`)
  expect(u.title ?? null).toBeNull()
  await page.context().close()
})

test('データベース: 数式・自動の列・集計・グループ・サブ項目・リスト／ギャラリー／タイムライン', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'DB拡張の確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'タスク' })
  await api(owner, 'PUT', `/api/pages/${p.id}/database`, {
    props: [
      { id: 'st', name: '状態', type: 'select', options: [{ id: 'a', name: '未着手', color: 'gray' }, { id: 'b', name: '完了', color: 'green' }] },
      { id: 'pt', name: '見積', type: 'number' },
      { id: 's', name: '開始', type: 'date' }, { id: 'e', name: '終了', type: 'date' },
      { id: 'f', name: '税込', type: 'formula', expr: 'prop("見積") * 1.1' },
      { id: 'id', name: 'ID', type: 'unique_id', prefix: 'TASK' },
      { id: 'cb', name: '作成者', type: 'created_by' },
    ],
    views: [{ id: 'v1', name: '表', type: 'table' }],
  })
  const today = new Date().toISOString().slice(0, 10)
  const a = await api(owner, 'POST', `/api/pages/${p.id}/database/rows`, { title: '設計', values: { st: 'a', pt: 100, s: today, e: today } })
  await api(owner, 'POST', `/api/pages/${p.id}/database/rows`, { title: '実装', values: { st: 'b', pt: 300 } })
  await api(owner, 'POST', `/api/pages/${p.id}/database/rows`, { title: '画面の設計', values: { pt: 50 }, parentId: a.page.id })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  const rowA = page.getByTestId('db-row').filter({ hasText: '設計' }).first()
  await expect(rowA.getByTestId('db-computed').first()).toHaveText('110')
  await expect(rowA).toContainText('TASK-1')
  await expect(rowA).toContainText('ノート 担当')
  // サブ項目: 開くと親の下に出る
  await expect(page.getByTestId('db-row')).toHaveCount(2)
  await rowA.getByTestId('db-sub-toggle').click()
  await expect(page.getByTestId('db-row')).toHaveCount(3)
  // 集計: 見積の合計（最上位の項目）
  await page.getByTestId('db-calc').nth(1).selectOption('sum')
  await expect(page.getByTestId('db-calc-value').nth(1)).toHaveText('400')
  // グループ化
  await page.getByTestId('db-filter-toggle').click()
  await page.getByTestId('db-group').selectOption({ label: '状態' })
  await expect(page.getByTestId('db-group-head')).toHaveCount(2)
  // ビューの追加: ギャラリー・タイムライン・リスト
  for (const [label, testid] of [['ギャラリー', 'db-gallery'], ['タイムライン', 'db-timeline'], ['リスト', 'db-list']] as const) {
    await page.getByTestId('db-view-add').click()
    await page.getByTestId('dialog-choice').filter({ hasText: label }).click()
    await page.getByTestId('dialog-ok').click()
    await expect(page.getByTestId(testid)).toBeVisible()
  }
  // ビューはデータベースに保存される
  await expect.poll(async () => (await api(owner, 'GET', `/api/pages/${p.id}/database`)).views.map((v: any) => v.type).sort().join()).toBe('gallery,list,table,timeline')
  await page.getByTestId('db-view-tab').filter({ hasText: 'タイムライン' }).click()
  await expect(page.getByTestId('db-tl-bar')).toHaveCount(1)
  await page.getByTestId('db-view-tab').filter({ hasText: 'ギャラリー' }).click()
  await expect(page.getByTestId('db-gallery-card')).toHaveCount(3)
  await page.context().close()
})

test('ページ内のデータベースとフォーム', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'フォームの確認' })
  const host = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '問い合わせ窓口' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${host.id}?edit=1`)
  await page.locator('.ProseMirror').click()
  await page.keyboard.type('/データベース（ページ内')
  await page.keyboard.press('Enter')
  await page.getByTestId('dialog-choice').filter({ hasText: '新しいデータベースを作る' }).click()
  await page.getByTestId('app-dialog').locator('input').fill('問い合わせ一覧')
  await page.getByTestId('dialog-ok').click()
  await expect(page.getByTestId('database-block').getByTestId('db-table')).toBeVisible()
  await expect(page.getByTestId('db-embed-title')).toContainText('問い合わせ一覧')
  const dbId = (await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)).find((x: any) => x.title === '問い合わせ一覧').id
  // ページ内のビューの変更は、ブロックに保存される
  await page.getByTestId('database-block').getByTestId('db-view-tab').filter({ hasText: 'ボード' }).click()
  await page.getByTestId('database-block').getByTestId('db-view-menu').click()
  await page.getByTestId('dialog-choice').filter({ hasText: '名前を変更' }).click()
  await page.getByTestId('app-dialog').locator('input').fill('担当別')
  await page.getByTestId('dialog-ok').click()
  await waitContent(owner, host.id, (_t, json) => JSON.stringify(json).includes('担当別'))
  expect((await api(owner, 'GET', `/api/pages/${dbId}/database`)).views.some((v: any) => v.name === '担当別')).toBe(false)

  // フォーム（データベースのページで作る）
  await page.goto(`/w/${ws.id}/p/${dbId}`)
  await page.getByTestId('db-view-menu').click()
  await page.getByTestId('dialog-choice').filter({ hasText: 'フォーム' }).click()
  await page.getByTestId('form-add').click()
  await page.getByTestId('form-title').fill('お問い合わせフォーム')
  await page.getByTestId('form-save').click()
  const url = await page.getByTestId('form-url').getAttribute('href')
  await page.context().close()

  // ログインしていない人が回答する
  const gctx = await browser.newContext()
  const guest = await gctx.newPage()
  await guest.goto(url!)
  await expect(guest.getByTestId('form-page')).toContainText('お問い合わせフォーム')
  await guest.getByTestId('form-submit').click()
  await expect(guest.getByTestId('form-page')).toContainText('入力してください')
  await guest.getByTestId('form-input-件名').fill('ログインできません')
  await guest.getByTestId('form-input-状態').selectOption({ label: '未着手' })
  await guest.getByTestId('form-submit').click()
  await expect(guest.getByTestId('form-done')).toBeVisible()
  await gctx.close()
  const rows = (await api(owner, 'GET', `/api/pages/${dbId}/database`)).rows
  expect(rows.map((r: any) => r.page.title)).toContain('ログインできません')
})

test('取り込み: Notion・Confluence・Word', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '取り込みの確認' })
  const page = await open(browser, owner, `/w/${ws.id}`)
  const importZip = async (name: string, files: Record<string, Uint8Array>) => {
    await page.getByTestId('ws-menu').click()
    const chooser = page.waitForEvent('filechooser')
    await page.getByTestId('ws-import').click()
    await (await chooser).setFiles({ name, mimeType: name.endsWith('.zip') ? 'application/zip' : 'application/octet-stream', buffer: Buffer.from(name.endsWith('.zip') ? zipSync(files) : Object.values(files)[0]) })
    // 1ページだけのときは知らせずに、そのページを開く
    if (name.endsWith('.zip')) {
      await expect(page.getByTestId('app-dialog')).toContainText('取り込みました', { timeout: 30_000 })
      await page.getByTestId('dialog-ok').click()
    } else await expect(page.locator('#page-title')).toHaveValue('議事録', { timeout: 30_000 })
  }
  const id = 'a'.repeat(32), id2 = 'b'.repeat(32), id3 = 'c'.repeat(32), id4 = 'd'.repeat(32)
  // Notion: ページ・子ページ・データベース（CSV と項目のページ）
  await importZip('Export-notion.zip', {
    [`手順書 ${id}.md`]: strToU8(`# 手順書\n\n準備は[こちら](%E6%BA%96%E5%82%99%20${id2}.md)を見てください。\n`),
    [`手順書 ${id}/準備 ${id2}.md`]: strToU8('# 準備\n\nアカウントを作ります。\n'),
    [`案件 ${id3}.csv`]: strToU8('Name,Status,Amount,Due\nA社,Done,1200,"November 7, 2026"\nB社,Doing,300,\n'),
    [`案件 ${id3}/A社 ${id4}.md`]: strToU8('# A社\n\nStatus: Done\n\n導入の記録です。\n'),
  })
  const pages = await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)
  const titles = pages.map((x: any) => x.title)
  expect(titles).toEqual(expect.arrayContaining(['手順書', '準備', '案件', 'A社', 'B社']))
  expect(titles.some((t: string) => /[0-9a-f]{32}/.test(t))).toBe(false)
  const deals = pages.find((x: any) => x.title === '案件')
  expect(deals.database).toBe(true)
  const db = await api(owner, 'GET', `/api/pages/${deals.id}/database`)
  expect(db.props.map((p: any) => `${p.name}:${p.type}`)).toEqual(['Status:select', 'Amount:number', 'Due:date'])
  const aRow = db.rows.find((r: any) => r.page.title === 'A社')
  expect(aRow.values[db.props[2].id]).toBe('2026-11-07')
  const manual = pages.find((x: any) => x.title === '手順書')
  const prep = pages.find((x: any) => x.title === '準備')
  expect(JSON.stringify(await contentOf(manual.id))).toContain(prep.id) // 中のリンクはページへのリンクに

  // Confluence: index.html の階層で並べ、本文だけを取り込む
  const cpage = (title: string, body: string) => strToU8(`<html><head><title>SPACE : ${title}</title></head><body><div id="breadcrumb-section">パンくず</div><h1 id="title-heading"><span id="title-text">SPACE : ${title}</span></h1><div id="main-content" class="skiwi-content">${body}</div><div id="footer">フッター</div></body></html>`)
  await importZip('confluence.zip', {
    'SPACE/index.html': strToU8('<html><body><div id="main-content"><div class="pageSection"><ul><li><a href="Home_100.html">ホーム</a><ul><li><a href="Child_200.html">子ページ</a></li></ul></li></ul></div></div><p>Confluence</p></body></html>'),
    'SPACE/Home_100.html': cpage('ホーム', '<p>ようこそ</p><p><a href="Child_200.html">子ページへ</a></p>'),
    'SPACE/Child_200.html': cpage('子ページ', '<p>子の本文</p>'),
  })
  const after = await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)
  const home = after.find((x: any) => x.title === 'ホーム'), child = after.find((x: any) => x.title === '子ページ')
  expect(child.parentId).toBe(home.id)
  const homeText = (await api(owner, 'GET', `/api/pages/${home.id}/content`)).text
  expect(homeText).toContain('ようこそ')
  expect(homeText).not.toContain('フッター')

  // Word（Google ドキュメントの .docx 書き出し）
  const docx = zipSync({
    '[Content_Types].xml': strToU8('<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>'),
    '_rels/.rels': strToU8('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'),
    'word/document.xml': strToU8('<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>議事録</w:t></w:r></w:p><w:p><w:r><w:t>決定事項はありません。</w:t></w:r></w:p></w:body></w:document>'),
  })
  await importZip('minutes.docx', { 'minutes.docx': docx })
  const last = await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)
  const minutes = last.find((x: any) => x.title === '議事録')
  expect(minutes).toBeTruthy()
  expect((await api(owner, 'GET', `/api/pages/${minutes.id}/content`)).text).toContain('決定事項はありません')
  await page.context().close()
})
