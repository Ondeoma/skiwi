// 実サーバー(Scala)に対する同時編集のE2Eテスト。
// 前提: DB(docker compose -f docker-compose.dev.yml up -d) と アプリ(BASE_URL, 既定 http://localhost:8080)が起動済み。
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import { WebsocketProvider } from 'y-websocket'
import WebSocket from 'ws'
import { caller, createSession, devPsql as psql } from './lib.mjs'

const BASE = process.env.BASE_URL ?? 'http://localhost:8080'
const WS = BASE.replace(/^http/, 'ws')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let failed = 0
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) failed++ }

// --- テストデータ: ユーザー・セッションをDBに直接作る ------------------------------------
const extraUsers = []
function makeUser(name) {
  const id = psql(`insert into app_user (oidc_sub, name) values ('e2e-${name}-${Date.now()}', '${name}') returning id`).split('\n')[0]
  const u = { id, token: createSession(psql, id) }
  extraUsers.push(u)
  return u
}
const call = caller(BASE)

const owner = makeUser('owner')
const viewer = makeUser('viewer')
const stranger = makeUser('stranger')
const ws = (await call(owner, 'POST', '/api/workspaces', { name: 'e2e' })).json
const page = (await call(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'e2e' })).json
await call(owner, 'PUT', `/api/pages/${page.id}/acl`, { inherit: true, entries: [{ principalType: 'user', principalId: viewer.id, role: 'view' }] })

// --- クライアント -----------------------------------------------------------------------
function client(user, pageId = page.id, { compact = true } = {}) {
  class AuthWS extends WebSocket {
    constructor(url, protocols) { super(url, protocols, { headers: user ? { Cookie: `skiwi_session=${user.token}` } : {} }) }
  }
  const doc = new Y.Doc()
  const p = new WebsocketProvider(`${WS}/ws/pages`, pageId, doc, { WebSocketPolyfill: AuthWS, resyncInterval: -1, disableBc: true })
  const text = doc.getText('t')
  const c = { doc, p, text, compactRequests: 0, roles: [] }
  // サーバーからの権限通知 [102][role]
  p.messageHandlers[102] = (_encoder, decoder) => { c.roles.push(decoding.readVarString(decoder)) }
  if (compact) {
    p.messageHandlers[100] = (encoder, decoder) => {
      c.compactRequests++
      const token = decoding.readVarString(decoder)
      encoding.writeVarUint(encoder, 101)
      encoding.writeVarString(encoder, token)
      encoding.writeVarUint8Array(encoder, Y.encodeStateAsUpdate(doc))
      encoding.writeVarString(encoder, JSON.stringify({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: text.toString() }] }] }))
      encoding.writeVarString(encoder, text.toString())
    }
  }
  return c
}
const until = async (f, ms = 8000) => { const t = Date.now(); while (Date.now() - t < ms) { if (await f()) return true; await sleep(50) } return false }

// 1) 認証・認可
{
  const anon = client(null); await sleep(600)
  ok(!anon.p.wsconnected, 'unauthenticated websocket is rejected'); anon.p.destroy()
  const s = client(stranger); await sleep(600)
  ok(!s.p.wsconnected, 'user without access is rejected'); s.p.destroy()
}

// 2) 同期・日本語・同時編集
const a = client(owner), b = client(owner)
ok(await until(() => a.p.synced && b.p.synced), 'two editors synced')
a.text.insert(0, 'こんにちは、世界')
ok(await until(() => b.text.toString() === 'こんにちは、世界'), 'A -> B (Japanese)')
a.text.insert(0, 'A:'); b.text.insert(b.text.length, ':B')
ok(await until(() => a.text.toString() === b.text.toString() && a.text.toString() === 'A:こんにちは、世界:B'), 'concurrent edits converge')

// 3) 閲覧のみユーザーの書き込みは破棄される
const v = client(viewer)
ok(await until(() => v.p.synced && v.text.toString() === a.text.toString()), 'viewer receives content')
v.text.insert(0, 'HACK')
await sleep(500)
ok(!a.text.toString().includes('HACK'), 'viewer update is dropped by server')
a.text.insert(a.text.length, '!')
ok(await until(() => v.text.toString().endsWith('!')), 'viewer still receives live updates')

// 4) 不正フレームで接続が壊れない
a.p.ws.send(new Uint8Array([0, 2, 99, 1]))
a.p.ws.send(new Uint8Array([100, 5]))
a.text.insert(0, '[ok]')
ok(await until(() => b.text.toString().startsWith('[ok]')), 'connection survives malformed frames')

// 5) 圧縮: アイドル後にサーバーが要求→スナップショット保存→ログ削除
const expected = a.text.toString()
ok(await until(() => a.compactRequests + b.compactRequests > 0, 12000), 'server requests compaction after idle')
ok(await until(() => psql(`select count(*) from page_updates where page_id='${page.id}'`) === '0', 5000), 'update log truncated after compaction')
ok(psql(`select plain_text from page_snapshot where page_id='${page.id}' and kind='compact'`) === expected, 'snapshot plain_text saved (' + expected + ')')
ok(psql(`select count(*) from page_snapshot where page_id='${page.id}' and kind='version'`) === '1', 'first compaction also records a version')
ok(a.roles[0] === 'edit' && v.roles[0] === 'view', 'server announces role on connect')

// 6) 永続化: 全員切断→新規クライアントが復元
for (const c of [a, b, v]) c.p.destroy()
await sleep(500)
const c1 = client(owner)
ok(await until(() => c1.p.synced && c1.text.toString() === expected), 'content restored from snapshot after all clients left')

// 7) 圧縮後の追記と再接続（スナップショット + 差分ログ）
c1.text.insert(0, '>>')
await sleep(300); c1.p.destroy(); await sleep(300)
const c2 = client(owner, page.id, { compact: false })
ok(await until(() => c2.p.synced && c2.text.toString() === '>>' + expected), 'snapshot + later updates restored')

// 8) 切断中のオフライン編集が再接続で反映される（クライアント側の再送を模擬）
const c3 = client(owner, page.id, { compact: false })
await until(() => c3.p.synced)
c3.p.disconnect(); await sleep(200)
c3.text.insert(0, '(offline)')
c3.p.connect(); await until(() => c3.p.synced)
c3.p.ws.send(((e) => (encoding.writeVarUint(e, 0), encoding.writeVarUint(e, 2), encoding.writeVarUint8Array(e, Y.encodeStateAsUpdate(c3.doc)), encoding.toUint8Array(e)))(encoding.createEncoder()))
ok(await until(() => c2.text.toString().startsWith('(offline)')), 'offline edit delivered after resend')

for (const c of [c2, c3]) c.p.destroy()
await sleep(300)

// 9) 接続中の権限変更が即座に反映される（降格→閲覧のみ、削除→切断）、更新通知がウォッチ中の作成者に積まれる
{
  const member = makeUser('member')
  const page2 = (await call(owner, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'e2e-acl' })).json
  const grant = (role) => call(owner, 'PUT', `/api/pages/${page2.id}/acl`, { inherit: false, entries: role ? [{ principalType: 'user', principalId: member.id, role }] : [] })
  await grant('edit')
  const o = client(owner, page2.id), m = client(member, page2.id)
  ok(await until(() => o.p.synced && m.p.synced && m.roles.includes('edit')), 'member connects as editor')
  m.text.insert(0, '編集できる')
  ok(await until(() => o.text.toString() === '編集できる'), 'member edit is relayed')
  ok(await until(() => psql(`select count(*) from mail_outbox where page_id='${page2.id}' and kind='page_edited' and user_id='${owner.id}'`) === '1', 12000),
    'edit notification queued for the watching creator')
  await grant('view')
  ok(await until(() => m.roles.at(-1) === 'view'), 'downgrade to view is pushed to the live connection')
  m.text.insert(0, 'NG')
  await sleep(500)
  ok(!o.text.toString().includes('NG'), 'edits after downgrade are dropped')
  await grant(null)
  ok(await until(() => m.roles.includes('none')), 'revocation is pushed to the live connection')
  ok(await until(() => !m.p.wsconnected, 3000), 'revoked connection is closed by the server')
  for (const c of [o, m]) c.p.destroy()
  await sleep(300)
}

// 後片付け: 開発用DBに試験データを残さない（作成したワークスペースとユーザー。監査ログは追記専用なので残る）
try {
  psql(`delete from workspace where id = '${ws.id}'`)
  const ids = extraUsers.map((u) => `'${u.id}'`).join(',')
  for (const t of ['workspace', 'page', 'page_snapshot', 'attachment']) psql(`update ${t} set created_by = null where created_by in (${ids})`)
  psql(`delete from app_user where id in (${ids})`)
} catch (e) { console.log(`cleanup failed: ${e.message}`) }

console.log(failed === 0 ? '\nALL PASSED' : `\n${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
