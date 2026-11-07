#!/usr/bin/env bash
# 開発専用: IdPなしで試すため、DBに直接ユーザーとセッションを作り、Cookie値を出力する。
# 使い方: scripts/dev-session.sh "山田 太郎" [user-1] [グループ,…] [メール]
#   2つ目はユーザー識別子（別ユーザーを作るときに変える）。3つ目は所属グループ（例: skiwi-admins で管理者）。
set -euo pipefail
cd "$(dirname "$0")/.."
NAME="${1:-山田 太郎}"; SUB="dev-${2:-user-1}"
psql() { docker compose -f docker-compose.dev.yml exec -T db psql -U skiwi -d skiwi -At -v ON_ERROR_STOP=1 -c "$1"; }
TOKEN="dev-$(openssl rand -hex 16)"
HASH=$(printf %s "$TOKEN" | shasum -a 256 | cut -d' ' -f1)
UID_=$(psql "insert into app_user (oidc_sub, name) values ('$SUB', '$NAME') on conflict (oidc_sub) do update set name = excluded.name returning id" | head -1)
psql "insert into session (token_hash, user_id, expires_at) values (decode('$HASH','hex'), '$UID_', now() + interval '7 days')" >/dev/null
if [ -n "${3:-}" ]; then
  psql "delete from group_member where user_id = '$UID_'" >/dev/null
  IFS=',' read -ra GS <<< "$3"
  for g in "${GS[@]}"; do
    GID=$(psql "insert into app_group (external_id, name) values ('$g', '$g') on conflict (external_id) do update set name = excluded.name returning id" | head -1)
    psql "insert into group_member (group_id, user_id) values ('$GID', '$UID_') on conflict do nothing" >/dev/null
  done
fi
if [ -n "${4:-}" ]; then psql "update app_user set email = '$4' where id = '$UID_'" >/dev/null; fi
echo "$TOKEN"
