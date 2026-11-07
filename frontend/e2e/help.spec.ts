import { expect, test } from '@playwright/test'
import { api, cleanup, login, makeUser } from './helpers'

/** ユーザーマニュアル */
test.afterAll(() => cleanup())

test('マニュアル: 目次から章を開き、検索から節へ移動できる。各画面の「？ 使い方」からも開ける', async ({ browser }) => {
  const u = makeUser('マニュアル 読者')
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto('/help')
  await expect(page.getByTestId('help-card')).toHaveCount(16)
  await page.getByTestId('help-card').filter({ has: page.locator('.ct', { hasText: /^データベース$/ }) }).click()
  await expect(page.getByTestId('help-doc').locator('h1')).toHaveText(/データベース/)
  await expect(page.getByTestId('help-toc')).toContainText('関連と集計')
  // 検索 → 節へ
  await page.getByTestId('help-search').fill('期限付き')
  const hit = page.getByTestId('help-hit').filter({ hasText: '共有と権限 › 期限付きの権限' })
  await expect(hit).toBeVisible()
  await hit.click()
  await expect(page).toHaveURL(/\/help\/sharing#/)
  await expect(page.locator('h2#期限付きの権限')).toBeInViewport()
  // 本文のリンクで別の章へ
  await page.getByTestId('help-doc').getByRole('link', { name: 'ゲスト向けの公開' }).first().click()
  await expect(page).toHaveURL(/\/help\/public$/)

  // ワークスペースから
  const ws = await api(u, 'POST', '/api/workspaces', { name: 'マニュアルの確認' })
  const p = await api(u, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'ページ' })
  await page.goto(`/w/${ws.id}/p/${p.id}`)
  await page.getByTestId('lock').click()
  const link = page.getByTestId('lock-dialog').getByTestId('help-link')
  expect(await link.getAttribute('href')).toBe(`/help/sharing#${encodeURIComponent('ページの保護')}`)
  const [tab] = await Promise.all([ctx.waitForEvent('page'), link.click()])
  await expect(tab.locator('h2#ページの保護')).toBeInViewport()
  await ctx.close()
})
