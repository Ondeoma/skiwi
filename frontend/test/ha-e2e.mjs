// 3ノード構成（docker-compose.ha.yml）に対する障害試験。
//   前提: scripts/ha-up.sh で起動済み。入口は HAProxy (BASE_URL, 既定 http://localhost:8090)。
//   検証: ノード間の同期、ページの担当ノードへの集約、アプリノード停止・復帰、DBプライマリ停止（Patroniの昇格）、
//         いずれの場合も編集が失われず全員の内容が一致すること。
import * as Y from 'yjs'
import * as encoding from 'lib0/encoding'
import * as decoding from 'lib0/decoding'
import { WebsocketProvider } from 'y-websocket'
import WebSocket from 'ws'
import { execFileSync } from 'node:child_process'
import { addToGroup, caller, createSession } from './lib.mjs'

const BASE = process.env.BASE_URL ?? 'http://localhost:8090'
const WS = BASE.replace(/^http/, 'ws')
const COMPOSE = ['compose', '-f', new URL('../../docker-compose.ha.yml', import.meta.url).pathname]
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const t0 = Date.now()
const log = (m) => console.log(`[${((Date.now() - t0) / 1000).toFixed(1).padStart(6)}s] ${m}`)
let failed = 0
const ok = (c, m) => { log((c ? 'PASS ' : 'FAIL ') + m); if (!c) failed++ }

const dc = (...args) => execFileSync('docker', [...COMPOSE, ...args], { stdio: ['ignore', 'pipe', 'pipe'] }).toString().trim()
// プライマリに接続（どのPGコンテナからでも、read-writeのノードを選ぶ）
const psql = (sql) => {
  for (const pg of ['pg1', 'pg2', 'pg3']) {
    try {
      return dc('exec', '-T', pg, 'psql', '-At', '-v', 'ON_ERROR_STOP=1',
        'postgresql://postgres:postgres-secret@pg1,pg2,pg3/skiwi?target_session_attrs=read-write&connect_timeout=3', '-c', sql)
    } catch { /* 停止中のコンテナ → 次へ */ }
  }
  throw new Error('no reachable postgres')
}
const patroni = () => {
  for (const pg of ['pg1', 'pg2', 'pg3']) {
    try { return JSON.parse(dc('exec', '-T', pg, 'patronictl', '-c', '/etc/patroni/patroni.yml', 'list', '-f', 'json')) } catch { /* next */ }
  }
  return []
}
const leader = () => patroni().find((m) => m.Role === 'Leader')?.Member

function makeUser(name, groups = []) {
  const id = psql(`insert into app_user (sub, name) values ('ha-${name}-${Date.now()}', '${name}') returning id`).split('\n')[0]
  const token = createSession(psql, id, '2 hours')
  for (const g of groups) addToGroup(psql, id, g)
  return { id, token }
}
const call = caller(BASE)
// 各アプリノードの状態（コンテナ内から自ノードに直接問い合わせる）
const nodeStatus = (node, u, pageId) => {
  try {
    return JSON.parse(dc('exec', '-T', node, 'wget', '-qO-', '--header', `Cookie: skiwi_session=${u.token}`,
      `http://localhost:8080/api/admin/cluster?page=${pageId}`))
  } catch { return null }
}

// --- クライアント（Editor.vue と同じく、再接続後に全状態を再送する） ---------------------------
function client(user, pageId, name) {
  class AuthWS extends WebSocket {
    constructor(url, protocols) { super(url, protocols, { headers: { Cookie: `skiwi_session=${user.token}` } }) }
  }
  const doc = new Y.Doc()
  const p = new WebsocketProvider(`${WS}/ws/pages`, pageId, doc, { WebSocketPolyfill: AuthWS, resyncInterval: -1, disableBc: true, maxBackoffTime: 1000 })
  const text = doc.getText('t')
  const c = { name, doc, p, text, reconnects: 0 }
  let needsResend = false
  doc.on('update', (_u, origin) => { if (origin !== p && !p.synced) needsResend = true })
  p.on('status', (e) => { if (e.status === 'disconnected') { needsResend = true; c.reconnects++ } })
  p.on('sync', (s) => {
    if (s && needsResend && p.ws?.readyState === 1) {
      needsResend = false
      const enc = encoding.createEncoder()
      encoding.writeVarUint(enc, 0); encoding.writeVarUint(enc, 2)
      encoding.writeVarUint8Array(enc, Y.encodeStateAsUpdate(doc))
      p.ws.send(encoding.toUint8Array(enc))
    }
  })
  p.messageHandlers[102] = (_e, d) => { decoding.readVarString(d) }
  p.messageHandlers[100] = (encoder, decoder) => {
    const token = decoding.readVarString(decoder)
    if (!p.synced) return
    encoding.writeVarUint(encoder, 101)
    encoding.writeVarString(encoder, token)
    encoding.writeVarUint8Array(encoder, Y.encodeStateAsUpdate(doc))
    encoding.writeVarString(encoder, JSON.stringify({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: text.toString() || ' ' }] }] }))
    encoding.writeVarString(encoder, text.toString())
  }
  return c
}
const until = async (f, ms = 15000) => { const t = Date.now(); while (Date.now() - t < ms) { if (await f()) return true; await sleep(100) } return false }
const words = (c) => new Set(c.text.toString().split(' ').filter(Boolean))
const converged = (cs, expected) => cs.every((c) => expected.every((w) => words(c).has(w))) &&
  cs.every((c) => c.text.toString() === cs[0].text.toString())

// --- 準備 ---------------------------------------------------------------------------------
const admin = makeUser('ha-admin', ['skiwi-admins'])
const ws = (await call(admin, 'POST', '/api/workspaces', { name: 'HA試験' })).json
const page = (await call(admin, 'POST', `/api/workspaces/${ws.id}/pages`, { title: '障害試験' })).json
log(`page ${page.id}, patroni leader = ${leader()}`)

const nodes = ['app1', 'app2', 'app3']
const owner = nodeStatus('app1', admin, page.id)?.owner
ok(nodes.includes(owner) && nodes.every((n) => nodeStatus(n, admin, page.id)?.owner === owner), `all nodes agree on the page owner (${owner})`)

// 1) 4クライアント（HAProxyのラウンドロビンで別々のノードに入る）→ 全員が担当ノードに集約される
const cs = [1, 2, 3, 4].map((i) => client(admin, page.id, `c${i}`))
ok(await until(() => cs.every((c) => c.p.synced)), 'four clients synced through HAProxy')
const expected = []
const write = (c, w) => { c.text.insert(c.text.length, w + ' '); expected.push(w) }
cs.forEach((c, i) => write(c, `初期${i + 1}`))
ok(await until(() => converged(cs, expected)), 'edits from all clients converge')
const st = Object.fromEntries(nodes.map((n) => [n, nodeStatus(n, admin, page.id)]))
ok(st[owner]?.connections === 4 && nodes.filter((n) => n !== owner).every((n) => st[n]?.openPages === 0),
  `all 4 connections are served by the owner (${nodes.map((n) => `${n}:${st[n]?.connections}`).join(' ')})`)

// 2) 担当ノードの停止 → 他ノードが引き継ぎ、停止中の編集も失われない
// HARD=1 なら強制終了（停止処理なし。ハートビート切れ=約15秒で検出）
const HARD = process.env.HARD === '1'
log(`${HARD ? 'killing' : 'stopping'} owner ${owner}`)
HARD ? dc('kill', owner) : dc('stop', '-t', '10', owner)
let n = 0
const writer = setInterval(() => { const c = cs[n % 4]; write(c, `停止中${++n}`) }, 300)
await sleep(8000)
clearInterval(writer)
ok(await until(() => cs.every((c) => c.p.synced) && converged(cs, expected), 40000), `edits during app-node failure converge (${n} edits, reconnects ${cs.map((c) => c.reconnects).join('/')})`)
const alive2 = nodes.filter((x) => x !== owner)
let owner2
ok(await until(() => { owner2 = nodeStatus(alive2[0], admin, page.id)?.owner; return alive2.includes(owner2) }, 30000), `ownership moved (${owner2})`)
log(`registry: ${psql("select string_agg(node_id, ',' order by node_id) from cluster_node")}`)
ok(await until(() => nodeStatus(owner2, admin, page.id)?.connections === 4, 30000), 'clients regrouped on the new owner')

// 3) 停止したノードの復帰 → 担当が戻り、再び集約される
log(`starting ${owner} again`)
dc('start', owner)
ok(await until(() => nodeStatus(alive2[0], admin, page.id)?.alive?.length === 3, 90000), 'node rejoined the cluster')
write(cs[0], '復帰後')
ok(await until(() => converged(cs, expected), 30000), 'edits after rejoin converge')
ok(await until(() => nodeStatus(owner, admin, page.id)?.connections === 4, 40000), `clients moved back to ${owner}`)

// 4) DBプライマリの停止 → Patroniが昇格、アプリは再接続し、編集は失われない
const pgLeader = leader()
log(`${HARD ? 'killing' : 'stopping'} postgres leader ${pgLeader}`)
HARD ? dc('kill', pgLeader) : dc('stop', '-t', '2', pgLeader)
let m = 0
const writer2 = setInterval(() => { const c = cs[m % 4]; write(c, `DB切替中${++m}`) }, 400)
await sleep(10000)
clearInterval(writer2)
ok(await until(() => { const l = leader(); return l && l !== pgLeader }, 60000), `patroni promoted a new leader (${leader()})`)
ok(await until(() => cs.every((c) => c.p.synced) && converged(cs, expected), 90000), `edits during DB failover converge (${m} edits)`)
// 全文検索索引（PGroonga）が新プライマリでも使える: 検索でき、索引付きの表に書き込める
{
  await sleep(7000) // 圧縮（本文の索引更新）を待つ
  const s1 = await call(admin, 'GET', `/api/search?workspaceId=${ws.id}&q=${encodeURIComponent('DB切替中1')}`)
  ok(s1.status === 200 && s1.json?.some((r) => r.page.id === page.id), `full-text search works on the new primary (status ${s1.status})`)
  const p2 = await call(admin, 'POST', `/api/workspaces/${ws.id}/pages`, { title: 'フェイルオーバー後のページ' })
  const s2 = await call(admin, 'GET', `/api/search?workspaceId=${ws.id}&q=${encodeURIComponent('フェイルオーバー後')}`)
  ok(p2.status === 201 && s2.json?.some((r) => r.page.id === p2.json.id), 'new pages are indexed after failover')
}

// 5) 永続化の確認: 全員切断後、新しいクライアントが全編集を読み込める
await sleep(7000) // 最後の圧縮を待つ
for (const c of cs) c.p.destroy()
await sleep(1000)
const fresh = client(admin, page.id, 'fresh')
ok(await until(() => fresh.p.synced && expected.every((w) => words(fresh).has(w)), 30000), `all ${expected.length} edits persisted after failures`)
fresh.p.destroy()

// 6) 停止したDBノードの復帰（レプリカとして再参加）
dc('start', pgLeader)
ok(await until(() => patroni().find((x) => x.Member === pgLeader)?.State === 'streaming', 120000), `${pgLeader} rejoined as a streaming replica`)

console.log(failed === 0 ? '\nALL PASSED' : `\n${failed} FAILED`)
process.exit(failed === 0 ? 0 : 1)
