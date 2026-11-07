import { expect, test } from '@playwright/test'
import { api, BASE, cleanup, makeUser, open, psql, waitContent, type TestUser } from './helpers'

/** ショートカット一覧・ページの保護・期限付きの権限・データベースの関連とCSV・自動化ルール・受信用の Webhook・定期バックアップ・API の説明書 */
test.describe.configure({ mode: 'serial' })

let owner: TestUser
let other: TestUser
test.beforeAll(() => {
  owner = makeUser('運用 担当')
  other = makeUser('ほかの 編集者')
})
test.afterAll(() => cleanup())


test('ショートカット一覧: 「?」で開き、絞り込める', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'キーの確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'ページ' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  await page.locator('#page-title').waitFor()
  await page.locator('body').click({ position: { x: 5, y: 400 } })
  await page.keyboard.press('Shift+Slash')
  await expect(page.getByTestId('shortcuts')).toBeVisible()
  await page.getByTestId('shortcuts-filter').fill('見出し')
  await expect(page.getByTestId('shortcuts')).toContainText('見出し1〜3')
  await expect(page.getByTestId('shortcuts')).not.toContainText('クイック切り替え')
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('shortcuts')).toHaveCount(0)
  await page.context().close()
})

test('保護: 保護したページは、ほかの編集者には閲覧のみになる', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '保護の確認' })
  await api(owner, 'PUT', `/api/workspaces/${ws.id}/acl`, { defaultRole: 'edit', entries: [] })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '就業規則' })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  await page.getByTestId('lock').click()
  await page.getByTestId('lock-reason').fill('2026年度版として確定')
  await page.getByTestId('lock-save').click()
  await expect(page.getByTestId('lock-banner')).toContainText('2026年度版として確定')
  await expect(page.getByTestId('tree-locked')).toBeVisible()
  await page.context().close()

  const ed = await open(browser, other, `/w/${ws.id}/p/${p.id}`)
  await expect(ed.getByTestId('lock-banner')).toContainText('編集できるのは')
  await expect(ed.getByTestId('edit')).toHaveCount(0)
  await ed.context().close()
})

test('期限付きの権限: 共有設定で期限の日付を付けられる', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '期限の確認' })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '協力会社向け資料' })
  await api(owner, 'PUT', `/api/pages/${p.id}/acl`, { inherit: true, entries: [{ principalType: 'user', principalId: other.id, role: 'view' }] })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  await page.getByTestId('share').click()
  const d = new Date(); d.setDate(d.getDate() + 10)
  const ymd = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  await page.getByTestId('acl-expiry').first().fill(ymd)
  await page.getByRole('button', { name: '保存', exact: true }).click()
  await expect.poll(async () => (await api(owner, 'GET', `/api/pages/${p.id}/acl`)).entries[0]?.expiresAt ?? null).not.toBeNull()
  await page.context().close()
})

test('データベース: 関連の列と集計、CSV の取り込み', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '関連の確認' })
  const cust = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '顧客' })
  const deals = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '案件' })
  await api(owner, 'PUT', `/api/pages/${cust.id}/database`, { props: [], views: [{ id: 'v', name: '表', type: 'table' }] })
  await api(owner, 'POST', `/api/pages/${cust.id}/database/rows`, { title: 'A社' })
  await api(owner, 'PUT', `/api/pages/${deals.id}/database`, {
    props: [{ id: 'amt', name: '金額', type: 'number' }, { id: 'c', name: '顧客', type: 'relation', target: cust.id }],
    views: [{ id: 'v', name: '表', type: 'table' }],
  })
  const page = await open(browser, owner, `/w/${ws.id}/p/${deals.id}`)
  // CSV の取り込み（列名で対応。ない列はテキストで追加）
  await page.getByTestId('db-view-menu').click()
  const chooser = page.waitForEvent('filechooser')
  await page.getByTestId('dialog-choice').filter({ hasText: 'CSVから取り込み' }).click()
  await (await chooser).setFiles({ name: 'deals.csv', mimeType: 'text/csv', buffer: Buffer.from('名前,金額,顧客,メモ\n導入,1200,A社,初回\n保守,"300",A社,"年間, 継続"\n') })
  await page.getByTestId('dialog-ok').click()
  await expect(page.getByTestId('app-dialog')).toContainText('2 件を取り込みました')
  await page.getByTestId('dialog-ok').click()
  await expect(page.getByTestId('db-row')).toHaveCount(2)
  await expect(page.getByTestId('db-row').filter({ hasText: '導入' }).getByTestId('db-rel')).toHaveText('↗ A社')
  await expect(page.getByTestId('db-table')).toContainText('年間, 継続')
  // 顧客側に集計（案件の金額の合計）
  await page.goto(`/w/${ws.id}/p/${cust.id}`)
  await api(owner, 'PUT', `/api/pages/${cust.id}/database`, {
    props: [{ id: 'd', name: '案件', type: 'relation', target: deals.id }, { id: 's', name: '合計', type: 'rollup', relation: 'd', fn: 'sum', prop: 'amt' }],
    views: [{ id: 'v', name: '表', type: 'table' }],
  })
  const dealRows = (await api(owner, 'GET', `/api/pages/${deals.id}/database`)).rows.map((r: any) => r.page.id)
  const aId = (await api(owner, 'GET', `/api/pages/${cust.id}/database`)).rows[0].page.id
  await api(owner, 'PATCH', `/api/pages/${aId}/props`, { values: { d: dealRows } })
  await page.reload()
  await expect(page.getByTestId('db-rollup')).toHaveText('1500')
  await page.context().close()
})

test('自動化ルール: 状態がレビュー中になったら所有者に通知する', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '自動化の確認' })
  await api(owner, 'PUT', `/api/workspaces/${ws.id}/acl`, { defaultRole: 'edit', entries: [] })
  const p = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '設計書' })
  await api(owner, 'PATCH', `/api/pages/${p.id}`, { ownerId: owner.id })
  const page = await open(browser, owner, `/w/${ws.id}/p/${p.id}`)
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-automations').click()
  await page.getByTestId('automation-add').click()
  await page.getByTestId('automation-name').fill('レビュー依頼')
  await page.getByTestId('automation-to-status').selectOption({ label: 'レビュー中' })
  await page.getByTestId('automation-message').fill('「{title}」のレビューをお願いします（{actor}）')
  await page.getByTestId('automation-save').click()
  await expect(page.getByTestId('automation-item')).toContainText('状態が「レビュー中」になったとき → 所有者に通知')
  await page.context().close()

  await api(other, 'PATCH', `/api/pages/${p.id}`, { status: 'review' })
  const n = await api(owner, 'GET', '/api/notifications')
  expect(n.items.some((x: any) => x.kind === 'automation' && x.excerpt === '「設計書」のレビューをお願いします（ほかの 編集者）')).toBe(true)
})

test('受信用の Webhook: URL に POST すると、データベースに項目が本文付きで入る', async ({ browser }) => {
  const ws = await api(owner, 'POST', '/api/workspaces', { name: '受信の確認' })
  const db = await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '問い合わせ' })
  await api(owner, 'PUT', `/api/pages/${db.id}/database`, {
    props: [{ id: 'st', name: '状態', type: 'select', options: [{ id: 'n', name: '未対応', color: 'red' }] }], views: [{ id: 'v', name: '表', type: 'table' }],
  })
  await api(owner, 'GET', `/api/workspaces/${ws.id}/pages`)
  const page = await open(browser, owner, `/w/${ws.id}/p/${db.id}`)
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-inbound').click()
  await page.getByTestId('inbound-add').click()
  await page.getByTestId('inbound-target').click()
  await page.getByTestId('page-picker-search').fill('問い合わせ')
  await page.getByTestId('page-picker').getByText('問い合わせ').first().click()
  await page.getByTestId('inbound-name').fill('お問い合わせフォーム')
  await page.getByTestId('inbound-save').click()
  const url = (await page.getByTestId('inbound-url').locator('code').textContent())!.trim()
  expect(url).toMatch(/\/api\/hooks\//)
  const res = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: 'ログインできない', text: 'パスワードを変えたら\nログインできなくなりました', values: { 状態: '未対応' } }) })
  expect(res.status).toBe(201)
  const { pageId } = await res.json()
  await waitContent(owner, pageId, (t) => t.includes('ログインできなくなりました'))
  // サーバーで作った本文が、エディタでそのまま開けて編集できる
  await page.goto(`/w/${ws.id}/p/${pageId}?edit=1`)
  await expect(page.locator('.ProseMirror')).toContainText('パスワードを変えたら')
  await expect(page.locator('.ProseMirror p')).toHaveCount(2)
  await expect(page.getByTestId('row-props')).toContainText('未対応')
  await page.locator('.ProseMirror p').last().click()
  await page.keyboard.press('End')
  await page.keyboard.type(' OK')
  await waitContent(owner, pageId, (t) => t.includes('ログインできなくなりました OK'))
  await page.context().close()
})

test('定期バックアップ: 管理者が今すぐバックアップしてダウンロードできる', async ({ browser }) => {
  const admin = makeUser('管理 者')
  psql(`insert into app_group (external_id, name) values ('skiwi-admins', 'skiwi-admins') on conflict (external_id) do nothing`)
  psql(`insert into group_member (group_id, user_id) select id, '${admin.id}' from app_group where external_id = 'skiwi-admins' on conflict do nothing`)
  const ws = await api(owner, 'POST', '/api/workspaces', { name: 'バックアップの確認' })
  await api(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '手順書' })
  const page = await open(browser, admin, `/w/${ws.id}`)
  await page.getByTestId('ws-menu').click()
  await page.getByTestId('ws-backup').click()
  await page.getByTestId('backup-enabled').check()
  await page.getByTestId('backup-save').click()
  await page.getByTestId('backup-run').click()
  await expect(page.getByTestId('backup-item').first()).toContainText('1ページ', { timeout: 20_000 })
  const href = await page.getByTestId('backup-download').first().getAttribute('href')
  const zip = await page.request.get(BASE + href!)
  expect(zip.status()).toBe(200)
  expect((await zip.body()).subarray(0, 2).toString()).toBe('PK')
  await page.context().close()
})

test('API の説明書: 一覧を表示し、その場で試せる', async ({ browser }) => {
  const page = await open(browser, owner, '/settings/api')
  await page.getByTestId('api-filter').fill('/api/me')
  await page.getByTestId('api-endpoint').first().locator('button').first().click()
  await page.getByTestId('api-try').click()
  await expect(page.getByTestId('api-result')).toContainText('HTTP 200')
  await expect(page.getByTestId('api-result')).toContainText('運用 担当')
  const spec = await (await fetch(`${BASE}/openapi.json`)).json()
  expect(spec.openapi).toBe('3.0.3')
  await page.context().close()
})
