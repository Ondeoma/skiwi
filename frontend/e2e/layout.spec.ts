import { expect, test, type Browser, type Page } from '@playwright/test'
import { api, cleanup, editorRun, makeUser, open as openAs, waitContent, type TestUser } from './helpers'

/** 段組みブロックと、ゲスト向けの表示（ヘッダーを出さない・本文の上下のHTML） */
test.describe.configure({ mode: 'serial' })

let u: TestUser
let ws: { id: string }
let pg: { id: string }

const open = (browser: Browser, path: string, viewport?: { width: number; height: number }) => openAs(browser, u, path, { context: viewport ? { viewport } : {} })
const boxes = (page: Page) => page.locator('.ProseMirror .column').evaluateAll((els) => els.map((e) => { const r = e.getBoundingClientRect(); return { x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width) } }))

test.beforeAll(async () => {
  u = makeUser('レイアウト 担当')
  ws = await api(u, 'POST', '/api/workspaces', { name: 'レイアウトの確認' })
  pg = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '段組みのページ' })
})
test.afterAll(() => cleanup())

test('段組み: 列が横に並び、列数・幅の比を変えられ、列を減らしても中身は残る', async ({ browser }) => {
  const page = await open(browser, `/w/${ws.id}/p/${pg.id}?edit=1`)
  await editorRun(page, `editor.chain().focus().insertColumns(3).run()`)
  await expect(page.locator('.ProseMirror .column')).toHaveCount(3)
  // 3列目に文字を入れる
  await editorRun(page, `let pos = -1; let i = 0; editor.state.doc.descendants((n, p) => { if (n.type.name === 'column') { if (i === 2) pos = p + 2; i++ } }); editor.chain().setTextSelection(pos).insertContent('3列目の内容').run()`)
  let b = await boxes(page)
  expect(new Set(b.map((x) => x.y)).size).toBe(1) // 横に並ぶ
  expect(b[0].x < b[1].x && b[1].x < b[2].x).toBe(true)

  // 2列に減らす → 3列目の内容は2列目の末尾へ
  await page.getByTestId('columns-count').selectOption('2')
  await expect(page.locator('.ProseMirror .column')).toHaveCount(2)
  await expect(page.locator('.ProseMirror .column').nth(1)).toContainText('3列目の内容')

  // 1 : 2 の比
  await page.getByTestId('columns').getByRole('button', { name: '1 : 2' }).click()
  b = await boxes(page)
  expect(b[1].w / b[0].w).toBeGreaterThan(1.8)
  expect(b[1].w / b[0].w).toBeLessThan(2.2)

  // 12列
  await page.getByTestId('columns-count').selectOption('12')
  await expect(page.locator('.ProseMirror .column')).toHaveCount(12)
  b = await boxes(page)
  expect(new Set(b.map((x) => x.y)).size).toBe(1)
  await waitContent(u, pg.id, (_, json) => (JSON.stringify(json).match(/"type":"column"/g) ?? []).length === 12)
  await page.context().close()

  // スマートフォンの幅では縦に積む
  const phone = await open(browser, `/w/${ws.id}/p/${pg.id}`, { width: 375, height: 800 })
  await expect(phone.locator('.ProseMirror .column')).toHaveCount(12)
  const pb = await boxes(phone)
  expect(new Set(pb.map((x) => x.x)).size).toBe(1)
  await phone.context().close()
})

test('ゲスト向け: ヘッダーを出さず、本文の上下にHTMLを隔離して表示する', async ({ browser }) => {
  const page = await open(browser, `/w/${ws.id}/p/${pg.id}`)
  // ワークスペース: 本文の上
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-guest-html').click()
  await page.getByTestId('guest-html-top').fill(
    `<div id="notice" style="padding:8px;background:#eef2ff">ワークスペースのお知らせ</div>` +
    `<script>try { parent.document.title; document.body.dataset.leak = 'yes' } catch (e) { document.body.dataset.leak = 'no' }` +
    `try { document.cookie; document.body.dataset.cookie = String(document.cookie.length) } catch (e) { document.body.dataset.cookie = 'blocked' }</script>`)
  await page.getByTestId('guest-html-save').click()
  await expect(page.getByTestId('guest-html-dialog')).toHaveCount(0)
  // ページ: 公開して、ヘッダーを出さない・本文の下
  await page.getByTestId('share').click()
  await page.getByTestId('public-toggle').check()
  await page.getByTestId('public-hide-header').check()
  await page.getByTestId('guest-html-open').click()
  await page.getByTestId('guest-html-bottom').fill('<p id="footer">© レイアウト確認 <a href="https://example.com">リンク</a></p>')
  await page.getByTestId('guest-html-save').click()
  const url = await page.getByTestId('public-url').inputValue()
  // ログインしている人のページ表示には出ない
  await page.keyboard.press('Escape')
  await page.goto(`/w/${ws.id}/p/${pg.id}`)
  await expect(page.locator('[data-testid=guest-html]')).toHaveCount(0)
  await page.context().close()

  const ctx = await browser.newContext() // ログインなし
  const guest = await ctx.newPage()
  await guest.goto(url)
  await expect(guest.getByTestId('guest-title')).toHaveText('段組みのページ')
  await expect(guest.getByTestId('guest-header')).toHaveCount(0)
  await expect(guest.getByTestId('guest-html')).toHaveCount(2)
  const top = guest.frameLocator('[data-testid=guest-html] >> nth=0')
  await expect(top.locator('#notice')).toHaveText('ワークスペースのお知らせ')
  // 隔離: 親の画面に触れない・Cookie も読めない（別オリジン扱い）
  await expect(top.locator('body')).toHaveAttribute('data-leak', 'no')
  await expect(top.locator('body')).toHaveAttribute('data-cookie', 'blocked')
  await expect(guest.frameLocator('[data-testid=guest-html] >> nth=1').locator('#footer')).toContainText('© レイアウト確認')
  // 高さが中身に合わせて付く
  await expect.poll(() => guest.getByTestId('guest-html').first().evaluate((el) => el.getBoundingClientRect().height)).toBeGreaterThan(20)
  await ctx.close()
})
