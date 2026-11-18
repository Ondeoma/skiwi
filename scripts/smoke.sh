#!/usr/bin/env bash
# 開発環境（DEV_LOGIN=true）で API を一通り呼ぶ確認用スクリプト。使い方: scripts/smoke.sh [BASE_URL]
set -euo pipefail
B=${1:-http://localhost:8080}
J=$(mktemp -d)
CA="$J/a" CB="$J/b"
req() { # cookie method path [json]
  local c=$1 m=$2 p=$3 d=${4:-}
  if [ -n "$d" ]; then curl -sS -b "$c" -c "$c" -X "$m" -H 'Content-Type: application/json' -d "$d" "$B$p"
  else curl -sS -b "$c" -c "$c" -X "$m" "$B$p"; fi
}
jqv() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1"; }
md() { python3 -c "import json,sys; print(json.dumps({'mode': sys.argv[1], 'markdown': sys.argv[2]}))" "$1" "$2"; }
echo "== login";          req $CA POST /auth/dev-login '{"name":"山田 太郎","email":"yamada@example.com","admin":true}' >/dev/null
                          req $CB POST /auth/dev-login '{"name":"佐藤 花子","email":"sato@example.com"}' >/dev/null
req $CA GET /api/me | jqv "(d['name'], d['isAdmin'], d['groups'])"
S=$(req $CB GET /api/me | jqv "d['id']")
N="スモーク$(date +%H%M%S)"
echo "== workspace $N";   W=$(req $CA POST /api/workspaces "{\"name\":\"$N\"}" | jqv "d['id']"); echo $W
echo "== page";           P=$(req $CA POST /api/workspaces/$W/pages '{"title":"確認用のページ","icon":"🧪"}' | jqv "d['id']"); echo $P
echo "== content";        req $CA POST /api/pages/$P/content "$(md replace "# 見出し

障害の**報告**です https://example.com")" | jqv "d"
                          req $CA POST /api/pages/$P/content "$(md append "- 追記した行")" >/dev/null
                          req $CA GET /api/pages/$P/content | jqv "str(d)[:80]"
echo "== child";          C=$(req $CA POST /api/workspaces/$W/pages "{\"parentId\":\"$P\",\"title\":\"子ページ\"}" | jqv "d['id']"); req $CA GET /api/workspaces/$W/pages | jqv "[p['title'] for p in d]"
echo "== 佐藤は見えない";    req $CB GET /api/pages/$P | jqv "d.get('error')"
echo "== share (佐藤)";    req $CA PUT /api/pages/$P/acl "{\"inherit\":true,\"entries\":[{\"principalType\":\"user\",\"principalId\":\"$S\",\"role\":\"edit\"}]}" >/dev/null
                          req $CB GET /api/pages/$P | jqv "d['title']"
echo "== labels";         req $CA PUT /api/pages/$P/labels '{"labels":["確認","スモーク"]}' | jqv "d['labels']"
echo "== comment (佐藤)";  req $CB POST /api/pages/$P/comments '{"body":"確認しました"}' | jqv "d['body']"
                          req $CA GET /api/pages/$P/comments | jqv "len(d)"
echo "== versions";       req $CA GET /api/pages/$P/versions | jqv "len(d)"
echo "== move";           req $CA POST /api/pages/$C/move '{"parentId":null,"placement":"last"}' >/dev/null; req $CA GET /api/pages/$C | jqv "d.get('parentId')"
enc() { python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1"; }
echo "== search";         sleep 1; req $CA GET "/api/search?q=$(enc "報告")&ws=$W" | jqv "[h['page']['title'] for h in d][:3]"
echo "== notifications";  req $CA GET /api/notifications | jqv "type(d).__name__"
echo "== delete/trash";   req $CA DELETE /api/pages/$C >/dev/null; req $CA GET /api/pages/$C | jqv "d.get('error')"
echo "== mcp";            KEY=$(req $CA POST /api/tokens '{"name":"smoke","scope":"write"}' | jqv "d['token']")
curl -sS -X POST $B/mcp -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jqv "[t['name'] for t in d['result']['tools']][:8]"
curl -sS -X POST $B/mcp -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_page\",\"arguments\":{\"pageId\":\"$P\"}}}" | jqv "d['result']['content'][0]['text'][:80]"
echo "== logout";         req $CA POST /auth/logout | jqv "d['redirect']"; req $CA GET /api/me | jqv "d.get('error')"
echo "ok"
