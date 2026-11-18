#!/usr/bin/env bash
# 開発用: デモのワークスペース・ページ・コメントを作る（DEV_LOGIN=true の環境）。使い方: scripts/seed-demo.sh [BASE_URL]
set -euo pipefail
B=${1:-http://localhost:8080}
J=$(mktemp -d)
py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
req() { local c=$1; shift; if [ -n "${3:-}" ]; then curl -sS -b $c -c $c -X "$1" -H 'Content-Type: application/json' -d "$3" "$B$2"; else curl -sS -b $c -c $c -X "$1" "$B$2"; fi; }
login() { curl -sS -c "$J/$1" -X POST -H 'Content-Type: application/json' -d "{\"name\":\"$2\",\"email\":\"$3\",\"admin\":$4}" $B/auth/dev-login >/dev/null; }
md() { python3 -c "import json,sys; print(json.dumps({'mode': sys.argv[1], 'markdown': sys.argv[2]}))" "$1" "$2"; }
login a "山田 太郎" yamada@example.com true
login b "佐藤 花子" sato@example.com false
SATO=$(req $J/b GET /api/me | py "d['id']")
W=$(req $J/a POST /api/workspaces '{"name":"開発チーム"}' | py "d['id']")
req $J/a PUT /api/workspaces/$W/acl "{\"defaultRole\":\"view\",\"entries\":[{\"principalType\":\"user\",\"principalId\":\"$SATO\",\"role\":\"edit\"}]}" >/dev/null
HOME_=$(req $J/a POST /api/workspaces/$W/pages '{"title":"ようこそ","icon":"👋"}' | py "d['id']")
req $J/a POST /api/pages/$HOME_/content "$(md replace "# 開発チームの Wiki

チームの手順書・議事録をここにまとめます。

- [ ] 新しく入った人は「開発環境の作り方」を読む
- [x] リポジトリの権限をもらう

> 困ったら #開発 で聞いてください。")" >/dev/null
SETUP=$(req $J/a POST /api/workspaces/$W/pages "{\"parentId\":\"$HOME_\",\"title\":\"開発環境の作り方\",\"icon\":\"🛠\"}" | py "d['id']")
req $J/a POST /api/pages/$SETUP/content "$(md replace "## 必要なもの

| 道具 | 版 |
|---|---|
| JDK | 21 |
| Node.js | 22 |

\`\`\`bash
docker compose -f docker-compose.dev.yml up -d --wait
scripts/dev-restart.sh
\`\`\`")" >/dev/null
MTG=$(req $J/b POST /api/workspaces/$W/pages '{"title":"議事録","icon":"📝"}' | py "d['id']")
req $J/b POST /api/pages/$MTG/content "$(md replace "## 11月の定例

- リリースは来週の水曜日
- 監視の通知のしきい値を見直す（担当: 佐藤）")" >/dev/null
req $J/a PUT /api/pages/$SETUP/labels '{"labels":["手順書","新人向け"]}' >/dev/null
req $J/b POST /api/pages/$SETUP/comments '{"body":"Node.js は 20 でも動きますか？"}' >/dev/null
echo "デモのデータを作りました（山田 太郎 でログインしてください）"
