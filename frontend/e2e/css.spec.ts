import { expect, test, type Browser, type Page } from '@playwright/test'
import { api, cleanup, editorRun, makeUser, open as openAs, waitContent, type TestUser } from './helpers'

/** カスタムCSS（ワークスペース・ページ・子孫への継承・範囲の限定） */
test.describe.configure({ mode: 'serial' })

let u: TestUser
let ws: { id: string }
let parent: { id: string }, child: { id: string }, grandchild: { id: string }

const BLUE = 'rgb(0, 0, 255)', GREEN = 'rgb(0, 128, 0)'

const open = (browser: Browser, path: string) => openAs(browser, u, path)
const paraColor = (page: Page) => page.locator('.ProseMirror p').first().evaluate((el) => getComputedStyle(el).color)
const paraStyle = (page: Page) => page.locator('.ProseMirror p').first().evaluate((el) => getComputedStyle(el).fontStyle)

test.beforeAll(async () => {
  u = makeUser('スタイル 担当')
  ws = await api(u, 'POST', '/api/workspaces', { name: 'スタイルの確認' })
  parent = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '親' })
  child = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '子', parentId: parent.id })
  grandchild = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '孫', parentId: child.id })
})
test.afterAll(() => cleanup())

test('ページのCSS: 危険な記述は除かれ、表示エリアの中だけに効き、子ページに継承される', async ({ browser }) => {
  // 各ページに段落を入れておく
  for (const p of [parent, child, grandchild]) {
    const pg = await open(browser, `/w/${ws.id}/p/${p.id}?edit=1`)
    await editorRun(pg, `editor.chain().focus().insertContent('<p>本文の段落</p>').run()`)
    await waitContent(u, p.id, (t) => t.includes('本文の段落'))
    await pg.context().close()
  }
  const page = await open(browser, `/w/${ws.id}/p/${parent.id}`)
  const before = await paraColor(page)
  await page.getByTestId('page-css').click()
  await page.getByTestId('css-input').fill([
    'p { color: rgb(0, 0, 255); }',
    'body { background: rgb(1, 2, 3); }',
    '.side { display: none; }',
    '.x { background: url(https://evil.example/a.png); position: fixed; }',
    '@import url(https://evil.example/x.css);',
  ].join('\n'))
  // 入力中にその場で反映される
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  const warnings = page.getByTestId('css-warnings')
  await expect(warnings).toContainText('外部の url()')
  await expect(warnings).toContainText('position: fixed')
  await expect(warnings).toContainText('@import')
  await page.getByTestId('css-save').click()
  await expect(page.getByTestId('css-dialog')).toHaveCount(0)
  expect(before).not.toBe(BLUE)
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  // サイドバー（表示エリアの外）には効かない。body は表示エリアに置き換わる
  await expect(page.getByTestId('side')).toBeVisible()
  expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).not.toBe('rgb(1, 2, 3)')
  expect(await page.locator('.skiwi-page-css').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe('rgb(1, 2, 3)')
  // 子ページ・孫ページにも継承される
  await page.goto(`/w/${ws.id}/p/${grandchild.id}`)
  await expect.poll(() => paraColor(page)).toBe(BLUE)
  await page.context().close()
})

test('子ページの「このページだけ」のCSSは、孫には継承されない。ワークスペースのCSSは全ページに効く', async ({ browser }) => {
  const page = await open(browser, `/w/${ws.id}/p/${child.id}`)
  await page.getByTestId('page-css').click()
  await expect(page.getByTestId('css-dialog')).toContainText('上位から適用されているスタイル（1件）')
  await page.getByTestId('css-input').fill('p { color: rgb(0, 128, 0); }')
  await page.getByTestId('css-inherit').uncheck()
  await page.getByTestId('css-save').click()
  await expect.poll(() => paraColor(page)).toBe(GREEN)

  // ワークスペースのCSS
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-css').click()
  await page.getByTestId('css-input').fill('p { font-style: italic; color: rgb(9, 9, 9); }')
  await page.getByTestId('css-save').click()
  await expect.poll(() => paraStyle(page)).toBe('italic')
  await expect.poll(() => paraColor(page)).toBe(GREEN) // ページのCSSが後から重なる

  await page.goto(`/w/${ws.id}/p/${grandchild.id}`)
  await expect.poll(() => paraColor(page)).toBe(BLUE) // 子の「このページだけ」は効かず、親のものが効く
  await expect.poll(() => paraStyle(page)).toBe('italic')
  await page.context().close()
})

test('ゲスト向けの公開ページにもCSSが効く', async ({ browser }) => {
  const sh = await api<{ token: string }>(u, 'PUT', `/api/pages/${parent.id}/share`, { includeChildren: true })
  const ctx = await browser.newContext() // ログインなし
  const page = await ctx.newPage()
  await page.goto(`/s/${sh.token}/${child.id}`)
  await expect(page.getByTestId('guest-title')).toHaveText('子')
  await expect.poll(() => paraColor(page)).toBe(GREEN)
  await expect.poll(() => paraStyle(page)).toBe('italic')
  await ctx.close()
})
