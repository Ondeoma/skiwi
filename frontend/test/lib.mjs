// テスト（test/*.mjs と e2e/）で共通の準備: DBへの直接のSQL、ログインのセッション、APIの呼び出し
import { execFileSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'

/** 開発用DB（docker-compose.dev.yml）に直接SQLを流す */
export const devPsql = (sql) =>
  execFileSync('docker', ['compose', '-f', new URL('../../docker-compose.dev.yml', import.meta.url).pathname,
    'exec', '-T', 'db', 'psql', '-U', 'skiwi', '-d', 'skiwi', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql]).toString().trim()

/** ログインのセッションをDBに直接作り、Cookie（skiwi_session）の値を返す。ttl は PostgreSQL の interval */
export function createSession(psql, userId, ttl = '1 hour') {
  const token = randomBytes(24).toString('base64url')
  const hash = createHash('sha256').update(token).digest('hex')
  psql(`insert into session (token_hash, user_id, expires_at) values (decode('${hash}','hex'), '${userId}', now() + interval '${ttl}')`)
  return token
}

/** グループに入れる（グループがなければ作る） */
export function addToGroup(psql, userId, group) {
  const gid = psql(`insert into app_group (external_id, name) values ('${group}', '${group}') on conflict (external_id) do update set name = excluded.name returning id`).split('\n')[0]
  psql(`insert into group_member (group_id, user_id) values ('${gid}', '${userId}') on conflict do nothing`)
}

/** base のサーバーに、u としてログインして API を呼ぶ関数を作る（失敗しても例外にせず status を返す） */
export const caller = (base) => async (u, method, path, body) => {
  const r = await fetch(base + path, { method, headers: { Cookie: `skiwi_session=${u.token}`, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body) })
  return { status: r.status, json: r.status === 204 ? null : await r.json().catch(() => null) }
}
