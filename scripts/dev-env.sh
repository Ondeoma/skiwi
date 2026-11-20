# 開発用の環境変数（source scripts/dev-env.sh）。docker-compose.dev.yml の DB・ScalaS3・Mailpit を使う
export DATABASE_URL=jdbc:postgresql://localhost:54329/skiwi DATABASE_USER=skiwi DATABASE_PASSWORD=skiwi
export PUBLIC_URL=${PUBLIC_URL:-http://localhost:5173} HTTP_PORT=${HTTP_PORT:-8080}   # PUBLIC_URL は画面の開発サーバー（/api などは :8080 へ中継）
# 開発用のログイン（/login で名前を入れるだけ。「管理者として」は ADMIN_GROUPS のグループに入れる）。OIDC は設定しない
export DEV_LOGIN=true ADMIN_GROUPS=skiwi-admins
# IP ごとの回数の制限は、開発中（E2E がログインを繰り返す）は緩める
export RATE_LIMIT_AUTH=${RATE_LIMIT_AUTH:-300} RATE_LIMIT_PUBLIC=${RATE_LIMIT_PUBLIC:-1000}
export S3_ENDPOINT=http://localhost:3900 S3_BUCKET=skiwi S3_ACCESS_KEY=skiwi S3_SECRET_KEY=skiwiskiwiskiwi
export SMTP_HOST=localhost SMTP_PORT=1025 SMTP_TLS=none MAIL_FROM=skiwi@localhost   # Mailpit (http://localhost:8025)
export WEBHOOK_ALLOW_HTTP=true WEBHOOK_ALLOW_PRIVATE=true   # http:// と localhost の Webhook 先を許す
# skea → skiwi の連携のクライアント（まだ登録されていないときだけ登録する。skea の開発環境の値と同じ）
export OAUTH_CLIENT_ID="${OAUTH_CLIENT_ID:-skea}" OAUTH_CLIENT_SECRET="${OAUTH_CLIENT_SECRET:-skea-dev-secret-0123456789}"
export OAUTH_CLIENT_REDIRECT_URI="${OAUTH_CLIENT_REDIRECT_URI:-http://localhost:8090/api/integrations/skiwi/callback}" OAUTH_CLIENT_NAME="${OAUTH_CLIENT_NAME:-skea}"
export SCIM_TOKEN="${SCIM_TOKEN:-}"   # 例: SCIM_TOKEN=dev-scim-token-0123456789abcdef scripts/dev-restart.sh で SCIM を有効にする
