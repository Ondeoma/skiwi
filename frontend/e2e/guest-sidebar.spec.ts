import { expect, test } from '@playwright/test'
import { api, cleanup, login, makeUser, type TestUser } from './helpers'

/** ゲスト閲覧ページのサイドメニュー（公開範囲のページ一覧）と、その設定 */
test.describe.configure({ mode: 'serial' })

let u: TestUser
let ws: { id: string }
let root: { id: string }
let chapter: { id: string }
let deep: { id: string }
let token = ''

test.beforeAll(async () => {
  u = makeUser('一覧 担当')
  ws = await api(u, 'POST', '/api/workspaces', { name: 'ゲストの一覧' })
  root = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'マニュアル' })
  chapter = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '第1章 はじめに', parentId: root.id })
  deep = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'インストール手順', parentId: chapter.id })
  for (let i = 2; i <= 9; i++) await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: `第${i}章`, parentId: root.id })
  token = (await api<{ token: string }>(u, 'PUT', `/api/pages/${root.id}/share`, { includeChildren: true })).token
})
test.afterAll(() => cleanup())

test('自動: 公開範囲のページを階層で一覧し、畳む・絞り込む・閉じることができる', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 860 } })
  const page = await ctx.newPage()
  await page.goto(`/s/${token}/${deep.id}`)
  const side = page.getByTestId('guest-side')
  await expect(side).toBeVisible()
  await expect(side).toContainText('ページ一覧（11）')
  // 開いているページは強調され、その祖先は開いている
  await expect(side.locator('.item.active')).toHaveText(/インストール手順/)
  await expect(page.getByTestId('guest-side-item')).toHaveCount(11)
  // 「マニュアル」を畳む → 子は隠れる（開いているページの祖先なので、別のページに移ってから）
  await side.getByText('第5章').click()
  await expect(page.getByTestId('guest-title')).toHaveText('第5章')
  await side.locator('.row').filter({ hasText: '第1章 はじめに' }).locator('.twisty').click()
  await expect(page.getByTestId('guest-side-item')).toHaveCount(10) // インストール手順が隠れる
  // 絞り込み（ひらがなでもカタカナに一致）
  await page.getByTestId('guest-side-filter').fill('いんすとーる')
  await expect(page.getByTestId('guest-side-item')).toHaveCount(3) // 祖先も出す
  await expect(side.locator('.item.hit')).toHaveText(/インストール手順/)
  await page.getByTestId('guest-side-filter').fill('')
  // 閉じる・開く（このブラウザに保存）
  await page.getByTestId('guest-side-close').click()
  await expect(side).toHaveCount(0)
  await page.reload()
  await expect(page.getByTestId('guest-side')).toHaveCount(0)
  await page.getByTestId('guest-side-open').click()
  await expect(page.getByTestId('guest-side')).toBeVisible()
  await ctx.close()
})

test('スマートフォンの幅では本文に重ねて開き、ページを選ぶと閉じる', async ({ browser }) => {
  const ctx = await browser.newContext({ viewport: { width: 375, height: 800 } })
  const page = await ctx.newPage()
  await page.goto(`/s/${token}`)
  await expect(page.getByTestId('guest-side')).toHaveCount(0)
  await page.getByTestId('guest-side-open').click()
  await expect(page.getByTestId('guest-side')).toBeVisible()
  await page.getByTestId('guest-side').getByText('第3章').click()
  await expect(page.getByTestId('guest-title')).toHaveText('第3章')
  await expect(page.getByTestId('guest-side')).toHaveCount(0)
  await ctx.close()
})

test('設定: 表示しない／常に表示（1ページだけの公開でも）', async ({ browser }) => {
  const editor = await browser.newContext()
  await login(editor, u)
  const ep = await editor.newPage()
  await ep.goto(`/w/${ws.id}/p/${root.id}`)
  await ep.getByTestId('share').click()
  await ep.getByTestId('public-sidebar').selectOption('hide')
  await expect(ep.getByTestId('public-sidebar')).toHaveValue('hide')
  await editor.close()

  const ctx = await browser.newContext()
  const page = await ctx.newPage()
  await page.goto(`/s/${token}`)
  await expect(page.getByTestId('guest-title')).toHaveText('マニュアル')
  await expect(page.getByTestId('guest-side')).toHaveCount(0)
  await expect(page.getByTestId('guest-side-open')).toHaveCount(0)

  // 子ページを含めない1ページだけの公開で「常に表示」
  await api(u, 'PUT', `/api/pages/${root.id}/share`, { includeChildren: false, sidebar: 'show' })
  await page.reload()
  await expect(page.getByTestId('guest-side')).toBeVisible()
  await expect(page.getByTestId('guest-side-item')).toHaveCount(1)
  // 「自動」なら1ページのときは出さない
  await api(u, 'PUT', `/api/pages/${root.id}/share`, { sidebar: 'auto' })
  await page.reload()
  await expect(page.getByTestId('guest-title')).toHaveText('マニュアル')
  await expect(page.getByTestId('guest-side')).toHaveCount(0)
  await ctx.close()
})
