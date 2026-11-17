import { addToGroup, createSession, devPsql } from '../test/lib.mjs'
import type { Browser, BrowserContext, BrowserContextOptions, Page } from '@playwright/test'

export const BASE = process.env.BASE_URL ?? 'http://localhost:8080'

/** 開発用DBに直接SQLを流す（テストの準備・後片付け用） */
export const psql = devPsql

export interface TestUser { id: string; token: string; name: string }
const created: TestUser[] = []
const run = Date.now().toString(36)

/** ログイン済みのテスト用ユーザー（セッションをDBに直接作る） */
export function makeUser(name: string): TestUser {
  const esc = name.replace(/'/g, "''")
  const id = psql(`insert into app_user (oidc_sub, name, email) values ('ui-e2e-${run}-${created.length}', '${esc}', 'ui${created.length}-${run}@example.com') returning id`).split('\n')[0]
  const u = { id, token: createSession(psql, id), name }
  created.push(u)
  return u
}

/** 管理者にする（ADMIN_GROUPS の skiwi-admins に入れる） */
export function makeAdmin(u: TestUser): TestUser {
  addToGroup(psql, u.id, 'skiwi-admins')
  return u
}

/** テストで作ったワークスペース・ユーザーを消す */
export function cleanup() {
  if (!created.length) return
  const ids = created.map((u) => `'${u.id}'`).join(',')
  psql(`delete from workspace where created_by in (${ids})`)
  psql(`update page set created_by = null, owner_id = null where created_by in (${ids}) or owner_id in (${ids})`)
  psql(`delete from app_user where id in (${ids})`)
  created.length = 0
}

export async function api<T = any>(u: TestUser, method: string, path: string, body?: unknown): Promise<T> {
  const r = await fetch(BASE + path, { method, headers: { Cookie: `skiwi_session=${u.token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${await r.text()}`)
  return (r.status === 204 ? undefined : await r.json()) as T
}

export async function login(context: BrowserContext, u: TestUser) {
  await context.addCookies([{ name: 'skiwi_session', value: u.token, url: BASE }])
}

/**
 * u でログインした新しいブラウザ（コンテキスト）で path を開く。
 * context: コンテキストの設定（画面の大きさ・権限など）、setup: ログイン前の準備（通信の差し替えなど）
 */
export async function open(browser: Browser, u: TestUser, path: string, opts: { context?: BrowserContextOptions; setup?: (ctx: BrowserContext) => Promise<unknown> } = {}): Promise<Page> {
  const ctx = await browser.newContext(opts.context ?? {})
  await opts.setup?.(ctx)
  await login(ctx, u)
  const page = await ctx.newPage()
  await page.goto(path)
  return page
}

/** ProseMirror の JSON から、type のノードをすべて集める */
export const nodes = (doc: any, type: string): any[] => {
  const out: any[] = []
  const walk = (n: any) => { if (n?.type === type) out.push(n); (n?.content ?? []).forEach(walk) }
  walk(doc)
  return out
}

/** ページのエディタ（Tiptap）を操作する。日本語の入力など、自動操作で打ちにくいものに使う */
export async function editorRun(page: Page, fn: string, arg?: unknown) {
  // 同期が済む前に本文を変えると、あとから届いたサーバーの本文と合わさって両方残るため、同期済みになるまで待つ
  // （同期の表示がない読み取り専用の表示ではそのまま）
  await page.waitForFunction(() => {
    if (!(document.querySelector('.ProseMirror') as any)?.editor) return false
    const st = document.querySelector('.skiwi-editor .status')
    return !st || st.textContent!.includes('同期済み')
  })
  return page.evaluate(([src, a]) => new Function('editor', 'arg', src)((document.querySelector('.ProseMirror') as any).editor, a), [fn, arg] as const)
}

/** サーバーに保存された本文（圧縮済み）が条件を満たすまで待つ */
export async function waitContent(u: TestUser, pageId: string, pred: (text: string, json: unknown) => boolean, timeout = 30_000) {
  const until = Date.now() + timeout
  for (;;) {
    const c = await api<{ text: string; content: unknown }>(u, 'GET', `/api/pages/${pageId}/content`)
    if (pred(c.text, c.content)) return c
    if (Date.now() > until) throw new Error(`content not saved: ${c.text}`)
    await new Promise((r) => setTimeout(r, 500))
  }
}
