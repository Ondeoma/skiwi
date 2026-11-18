import { expect, test } from '@playwright/test'
import { cleanup, login, makeAdmin, makeUser } from './helpers'

/** 設定・管理の画面（Scalactive で書いた画面） */
test.afterAll(() => cleanup())

test('APIトークン: 発行すると一度だけ表示され、一覧から失効できる', async ({ browser }) => {
  const u = makeUser('トークン 利用者')
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto('/settings/tokens')
  await expect(page.getByText('発行済みのトークンはありません')).toBeVisible()
  // 名前がないと発行できない
  await page.getByRole('button', { name: '発行' }).click()
  await expect(page.locator('.error')).toHaveText('名前を入力してください')
  await page.getByPlaceholder('用途（例: バックアップスクリプト）').fill('バックアップ')
  await page.locator('.new select').first().selectOption('write')
  await page.locator('.new select').nth(1).selectOption('')
  await page.getByRole('button', { name: '発行' }).click()
  await expect(page.getByTestId('new-token').locator('code')).toHaveText(/^skiwi_/)
  await expect(page.locator('.error')).toHaveCount(0)
  const row = page.locator('tbody tr', { hasText: 'バックアップ' })
  await expect(row).toContainText('閲覧・編集')
  await expect(row).toContainText('無期限')
  await expect(row).toContainText('未使用')
  // 失効（確認ダイアログは Vue の画面内ダイアログ）
  await row.getByRole('button', { name: '失効' }).click()
  await page.getByRole('button', { name: '失効させる' }).click()
  await expect(page.getByText('発行済みのトークンはありません')).toBeVisible()
  await page.getByTestId('new-token').getByRole('button', { name: '閉じる' }).click()
  await expect(page.getByTestId('new-token')).toHaveCount(0)
  // リンクは画面を読み込み直さずに移動する
  await page.evaluate(() => { (window as any).__kept = true })
  await page.getByRole('link', { name: 'API の説明書' }).click()
  await expect(page).toHaveURL(/\/settings\/api$/)
  expect(await page.evaluate(() => (window as any).__kept)).toBe(true)
  await ctx.close()
})

test('監査ログ: 管理者は操作の記録を見られ、種類で絞り込める', async ({ browser }) => {
  const u = makeAdmin(makeUser('監査 管理者'))
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  // 記録を作る
  await page.goto('/settings/tokens')
  await page.getByPlaceholder('用途（例: バックアップスクリプト）').fill('監査の確認')
  await page.getByRole('button', { name: '発行' }).click()
  await expect(page.getByTestId('new-token')).toBeVisible()

  await page.goto('/admin/audit')
  const mine = page.locator('tbody tr', { hasText: '監査 管理者' }).filter({ hasText: 'APIトークン発行' })
  await expect(mine.first()).toContainText('{"name":"監査の確認"')
  await expect(mine.first()).toContainText('画面')
  await page.locator('.filters select').selectOption('auth.')
  await expect(page.locator('tbody tr', { hasText: 'APIトークン発行' })).toHaveCount(0)
  await page.locator('.filters select').selectOption('token.')
  await expect(mine.first()).toBeVisible()
  await ctx.close()
})

test('監査ログ: 管理者でなければエラーを表示する', async ({ browser }) => {
  const u = makeUser('一般 利用者')
  const ctx = await browser.newContext()
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto('/admin/audit')
  await expect(page.locator('.error')).toHaveText('管理者だけが使えます')
  await ctx.close()
})
