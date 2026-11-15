#!/usr/bin/env bash
# 開発専用: DB起動 → フロントビルド → アプリ起動(:8080)。IdPはダミー(ログインは dev-session.sh のCookieで行う)。
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f docker-compose.dev.yml up -d --wait
(cd frontend && [ -d node_modules ] || npm i; npm run build)
export DATABASE_URL=jdbc:postgresql://localhost:54329/skiwi DATABASE_USER=skiwi DATABASE_PASSWORD=skiwi
export OIDC_ISSUER=https://idp.invalid/o/skiwi/ OIDC_CLIENT_ID=dev OIDC_CLIENT_SECRET=dev
export PUBLIC_URL=http://localhost:8080 STATIC_DIR="$PWD/frontend/dist"
export S3_ENDPOINT=http://localhost:3900 S3_BUCKET=skiwi S3_ACCESS_KEY=skiwi S3_SECRET_KEY=skiwiskiwiskiwi
export SMTP_HOST=localhost SMTP_PORT=1025 SMTP_TLS=none MAIL_FROM=skiwi@localhost   # Mailpit (http://localhost:8025)
export ADMIN_GROUPS=skiwi-admins
export WEBHOOK_ALLOW_HTTP=true   # 開発用: http:// の Webhook 先を許す
export ENCRYPTION_KEY="${ENCRYPTION_KEY:-ZGV2LW9ubHktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=}"   # 開発用の固定値（skea 連携のトークンの暗号化）
# 開発用: skea → skiwi の連携のクライアント（まだ登録されていないときだけ登録する。skea の開発環境の値と同じ）
export OAUTH_CLIENT_ID="${OAUTH_CLIENT_ID:-skea}" OAUTH_CLIENT_SECRET="${OAUTH_CLIENT_SECRET:-skea-dev-secret-0123456789}"
export OAUTH_CLIENT_REDIRECT_URI="${OAUTH_CLIENT_REDIRECT_URI:-http://localhost:8090/api/integrations/skiwi/callback}" OAUTH_CLIENT_NAME="${OAUTH_CLIENT_NAME:-skea}"
export SCIM_TOKEN="${SCIM_TOKEN:-}"   # 例: SCIM_TOKEN=dev-scim-token-123456 scripts/dev-up.sh でSCIMを有効化
exec sbt app/run
