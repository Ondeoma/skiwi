import { expect, test } from '@playwright/test'
import { api, cleanup, editorRun, makeUser, open, psql, waitContent, type TestUser } from './helpers'

/** 点検・ウォッチの細分化・一括操作・公開の履歴・Webhook・PDF・データベース */
test.describe.configure({ mode: 'serial' })

let owner: TestUser
test.beforeAll(() => { owner = makeUser('業務 担当') })
test.afterAll(() => cleanup())


test('点検: 孤立ページと長く更新されていないページを一覧する', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '点検の確認' })
  const root = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '目次' })
  const child = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'どこからもリンクされない', parentId: root.id })
  const old = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '古い手順', parentId: root.id })
  psql(`update page set updated_at = now() - interval '400 days' where id = '${old.id}'`)
  const page = await open(browser, owner, `/w/${ws.id}/p/${root.id}`)
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-broken').click()
  await page.getByTestId('health-tab-orphans').click()
  await expect(page.getByTestId('health-orphans')).toContainText('どこからもリンクされない')
  await page.getByTestId('health-tab-stale').click()
  await expect(page.getByTestId('health-stale')).toContainText('古い手順')
  await expect(page.getByTestId('health-stale')).not.toContainText('どこからもリンクされない')
  void child
  await page.context().close()
})

test('ウォッチ: 子ページも含めてウォッチすると、子ページでも通知の設定が引き継がれる', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'ウォッチの確認' })
  const parent = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '親ページ' })
  const child = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '子ページ', parentId: parent.id })
  const page = await open(browser, owner, `/w/${ws.id}/p/${parent.id}`)
  await page.getByTestId('watch').click()
  await expect(page.getByTestId('watch-edits')).toBeChecked() // 作成者は自動でウォッチ
  await page.getByTestId('watch-subtree').check()
  await expect(page.getByTestId('watch-subtree')).toBeChecked()
  await page.getByTestId('watch-edits').uncheck()
  await expect(page.getByTestId('watch')).toContainText('コメントのみ')
  await page.goto(`/w/${ws.id}/p/${child.id}`)
  // 子ページは作成者として直接ウォッチしているので、いったんやめると親の設定が見える
  await page.getByTestId('watch').click()
  await page.getByTestId('watch-stop').click()
  await page.getByTestId('watch').click()
  await expect(page.getByTestId('watch-inherited')).toContainText('親ページ')
  await expect(page.getByTestId('watch')).toContainText('ウォッチ中（親）')
  await page.context().close()
})

test('一括操作: 複数のページを選んで状態とラベルを変え、まとめて移動する', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '一括の確認' })
  const a = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '一括A' })
  const b = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '一括B' })
  const c = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '一括C' })
  const dest = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '移動先' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${dest.id}`)
  const row = (t: string) => page.getByTestId('tree-row').filter({ hasText: t }).locator('a.title')
  await row('一括A').click({ modifiers: ['ControlOrMeta'] })
  await row('一括C').click({ modifiers: ['Shift'] })
  await expect(page.getByTestId('bulk-count')).toHaveText('3')
  // 状態
  await page.getByTestId('bulk-status').click()
  await page.getByTestId('dialog-choice').filter({ hasText: 'レビュー中' }).click()
  await expect(page.getByTestId('tree-status').filter({ hasText: 'レビュー中' })).toHaveCount(3)
  // ラベル
  await page.getByTestId('bulk-labels').click()
  await page.getByTestId('dialog-choice').filter({ hasText: 'ラベルを付ける' }).click()
  await page.getByTestId('app-dialog').locator('input').fill('手順書')
  await page.getByTestId('dialog-ok').click()
  await expect.poll(async () => (await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)).filter((p: any) => p.labels?.includes('手順書')).length).toBe(3)
  // 移動（ページの下へ）
  await page.getByTestId('bulk-move').click()
  await page.getByTestId('dialog-choice').filter({ hasText: 'ページの下へ' }).click()
  await page.getByTestId('page-picker-search').fill('移動先')
  await page.getByTestId('page-picker').getByText('移動先').first().click()
  await expect(page.getByTestId('bulk-bar')).toHaveCount(0)
  await expect.poll(async () => (await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)).filter((p: any) => p.parentId === dest.id).map((p: any) => p.title).sort()).toEqual(['一括A', '一括B', '一括C'])
  void a; void b; void c
  await page.context().close()
})

test('公開の履歴: 変更点のメモを残して公開し、差分とゲスト向けの更新履歴を見る', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '履歴の確認' })
  const root = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '製品マニュアル' })
  const ed = await open(browser, owner, `/w/${ws.id}/p/${root.id}?edit=1`)
  await editorRun(ed, `editor.chain().focus().insertContent('最初の説明です。').run()`)
  await waitContent(owner, root.id, (t) => t.includes('最初の説明'))
  const share = await api(owner, 'PUT', `/api/pages/${root.id}/share`, { mode: 'pinned' })
  await editorRun(ed, `editor.chain().focus().setContent('<p>新しい説明です。</p>').run()`)
  await waitContent(owner, root.id, (t) => t.includes('新しい説明'))
  await ed.goto(`/w/${ws.id}/p/${root.id}`)
  await ed.getByTestId('share').click()
  await ed.getByTestId('public-release-note').fill('説明を書き直しました')
  await ed.getByTestId('public-publish').click()
  await expect(ed.getByTestId('public-pinned')).toContainText('公開版は現在の内容と同じです')
  await ed.getByTestId('releases-toggle').click()
  await expect(ed.getByTestId('release-item').first()).toContainText('説明を書き直しました')
  await ed.getByTestId('release-compare').first().click()
  await expect(ed.getByTestId('release-diff-body')).toContainText('新しい説明です')
  await ed.getByTestId('release-diff').locator('.x').click()
  await ed.getByTestId('public-show-releases').check()
  await expect(ed.getByTestId('public-show-releases')).toBeChecked()

  const gctx = await browser.newContext()
  const guest = await gctx.newPage()
  await guest.goto(`/s/${share.token}`)
  await guest.getByTestId('guest-releases-toggle').click()
  await expect(guest.getByTestId('guest-releases')).toContainText('説明を書き直しました')
  await gctx.close()
  await ed.context().close()
})

test('Webhook: 送り先を追加してテスト送信する', async ({ browser }) => {
  // 受け口: アプリ自身の存在しないURL（404）。送れたかどうかの表示を確かめる
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'Webhookの確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'ページ' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-webhooks').click()
  await page.getByTestId('webhook-add').click()
  await page.getByTestId('webhook-format-generic').check()
  await page.getByTestId('webhook-url').fill('http://localhost:8080/api/__webhook_test')
  await page.getByTestId('webhook-name').fill('テスト用')
  await page.getByTestId('webhook-save').click()
  await expect(page.getByTestId('webhook-secret')).toBeVisible()
  await expect(page.getByTestId('webhook-item')).toContainText('テスト用')
  await page.getByTestId('webhook-test').click()
  await expect(page.getByTestId('webhook-test-result')).toContainText('送れませんでした')
  await expect(page.getByTestId('webhook-item')).toContainText('HTTP 404')
  await page.context().close()
})

test('PDF: 子ページを目次付きでまとめる', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'PDFの確認' })
  const root = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '運用手順書' })
  await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '第1章 準備', parentId: root.id })
  const c2 = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '第2章 作業', parentId: root.id })
  await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '2.1 確認', parentId: c2.id })
  const page = await open(browser, owner, `/print/w/${ws.id}/p/${root.id}?children=1&toc=1&auto=0`)
  await expect(page.getByTestId('print-title')).toHaveText('運用手順書')
  await expect(page.getByTestId('print-section')).toHaveCount(4)
  const toc = page.getByTestId('print-toc-list')
  await expect(toc).toContainText('1第1章 準備')
  await expect(toc).toContainText('2.12.1 確認')
  await page.getByTestId('print-children').uncheck()
  await expect(page.getByTestId('print-section')).toHaveCount(1)
  await page.context().close()
})

test('データベース: 表で項目を追加して値を入れ、ボードで状態を変える', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'DBの確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '案件一覧' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}?edit=1`)
  await page.getByTestId('make-database').click()
  await expect(page.getByTestId('db-table')).toBeVisible()
  await page.getByTestId('db-new-row').fill('A社 導入')
  await page.getByTestId('db-new-row').press('Enter')
  await page.getByTestId('db-new-row').fill('B社 保守')
  await page.getByTestId('db-new-row').press('Enter')
  await expect(page.getByTestId('db-row')).toHaveCount(2)
  // 状態（選択）と期限（日付）
  const rowA = page.getByTestId('db-row').filter({ hasText: 'A社 導入' })
  await rowA.getByTestId('db-select').selectOption({ label: '進行中' })
  await rowA.locator('input[type=date]').fill('2026-11-20')
  await expect.poll(async () => {
    const d = await api(owner, 'GET', `/api/pages/${p.id}/database`)
    return Object.keys(d.rows.find((r: any) => r.page.title === 'A社 導入').values).filter((k) => k !== '_seq').length
  }).toBe(2)
  // ツリーにも項目が出る
  await expect(page.getByTestId('tree-row').filter({ hasText: 'B社 保守' })).toBeVisible()
  // ボード: 「未設定」から「完了」へドラッグ
  await page.getByTestId('db-view-tab').filter({ hasText: 'ボード' }).click()
  const done = page.getByTestId('db-board-col').filter({ hasText: '完了' })
  const card = page.getByTestId('db-card').filter({ hasText: 'B社 保守' })
  await card.hover({ position: { x: 5, y: 5 } })
  await page.mouse.down()
  await done.hover({ position: { x: 20, y: 20 } })
  await done.hover({ position: { x: 30, y: 25 } })
  await page.mouse.up()
  await expect(done.getByTestId('db-card')).toContainText('B社 保守')
  // カレンダー: 期限の日に出る
  await page.getByTestId('db-view-tab').filter({ hasText: 'カレンダー' }).click()
  const month = await page.getByTestId('db-cal-month').textContent()
  if (month !== '2026年11月') {
    // 今月が11月でなければ移動する
    for (let i = 0; i < 24 && (await page.getByTestId('db-cal-month').textContent()) !== '2026年11月'; i++) {
      const cur = await page.getByTestId('db-cal-month').textContent()
      const [y, m] = cur!.match(/\d+/g)!.map(Number)
      await page.locator('.cal-head button').nth(y * 12 + m > 2026 * 12 + 11 ? 0 : 1).click()
    }
  }
  await expect(page.locator('[data-day="2026-11-20"]').getByTestId('db-event')).toHaveText('A社 導入')
  // 項目のページには値が出る
  await page.getByTestId('db-event').filter({ hasText: 'A社 導入' }).click()
  await expect(page.getByTestId('row-props')).toContainText('案件一覧')
  await expect(page.getByTestId('row-props')).toContainText('進行中')
  await page.context().close()
})
