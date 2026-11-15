# skawau 連携の取り決め

skiwi のページ本文にある skawau のファイル・フォルダのリンク（`{skawau の URL}/view/{ID}`・`/files/{ID}`・`/office/{ID}`）に、
ファイル名（フォルダ名）を出す。閲覧中は URL の文字の代わりに「📄 ファイル名」を表示し、編集中は URL の後ろに名前を添える。

ファイル名は **ログイン中のユーザー自身の skawau での権限** で読む（共有の API キーは使わない）。仕組みは skea 連携
（[skea-integration.md](skea-integration.md) の前半）と同じで、skawau が OAuth 2.0 の認可サーバー、skiwi がクライアントになる。

- 各ユーザーは skiwi の画面で「skawau と連携」を押し、skawau の画面で許可する（認可コード + PKCE）。
- skawau は、そのユーザーの **読み取り専用** のトークンを発行する。skiwi はそれを暗号化して保存し、サーバーから skawau の API を呼ぶ。
- 連携していないユーザー・見る権限のないファイルは、URL のまま表示する。ゲスト向けの公開ページでは何もしない。
- 公開リンク（`/s/{token}`）は対象外。

## skawau 側に必要なもの

### 1. クライアントの登録

skawau の管理者が、skiwi をクライアントとして登録する（環境変数でも管理画面でもよい）。

| 項目 | 例 |
|---|---|
| `client_id` | `skiwi` |
| `client_secret` | ランダムな文字列（skiwi の管理画面に同じ値を入れる） |
| `redirect_uri` | `https://skiwi.example.com/api/integrations/skawau/callback`（完全一致で照合する） |
| 表示名 | `skiwi`（許可画面に出す） |

開発用の値: `skiwi` / `skiwi-dev-secret-0123456789` / `http://localhost:8080/api/integrations/skawau/callback`。

### 2. `GET /oauth/authorize`（許可画面）

クエリ: `response_type=code`・`client_id`・`redirect_uri`（登録と完全一致）・`scope=read`・`state`（そのまま返す）・
`code_challenge`（BASE64URL(SHA-256(code_verifier))）・`code_challenge_method=S256`（これ以外は拒否）。

- 未ログインなら skawau のログイン（OIDC）をしてから、この URL に戻る。
- 「skiwi が、あなたの権限で skawau のファイル名を読み取ります」と、許可・拒否のボタンを出す（CSRF 対策をする）。
- 許可: `302 {redirect_uri}?code=…&state=…`。拒否: `302 {redirect_uri}?error=access_denied&state=…`。
- `client_id`・`redirect_uri` が不正なときは、リダイレクトせずに skawau の画面でエラーを表示する。
- 認可コードはランダム・60秒・一度だけ。`client_id`・`redirect_uri`・ユーザー・`code_challenge` に結び付ける。
- 許可画面のフォームの CSP に `form-action` を付けない（送信後のリダイレクトの連鎖にも効き、別 origin の `redirect_uri` へ戻れなくなる）。

### 3. `POST /oauth/token`

`application/x-www-form-urlencoded`。クライアント認証は HTTP Basic（`client_id:client_secret`）。
項目: `grant_type=authorization_code`・`code`・`redirect_uri`・`code_verifier`。

- 成功（`Cache-Control: no-store`）: `{"access_token":"…","token_type":"Bearer","scope":"read"}`
- `access_token` はそのユーザーの **読み取り専用の APIキー（アプリパスワード）** でよい。名前は「skiwi 連携」、期限なし。
  skawau の設定画面の APIキーの一覧に出て、そこから失効できること。WebDAV など書き込みには使えないこと。
- 同じユーザーが同じクライアントで許可し直したら、以前のキーを失効させる。
- 失敗は RFC 6749 の形: `invalid_request`・`invalid_client`（401）・`invalid_grant`（期限切れ・使用済み・`code_verifier` や `redirect_uri` の不一致）・`unsupported_grant_type`。

### 4. `POST /oauth/revoke`（RFC 7009）

`token=…`、Basic 認証。そのクライアントが発行したトークンを失効させる。存在しなくても `200`。

### 5. `POST /api/files/lookup`（ファイル・フォルダをまとめて読む）

`Authorization: Bearer <access_token>`。読み取り専用のキーで使えること。

```json
{ "ids": ["6f1c…-…", "…"] }
```

`ids` は UUID、最大100件（超えたら `400`）。応答はトークンの持ち主が見られるもの（自分のもの・共有されたもの）だけ。
存在しない・ごみ箱の中・権限のないものは区別せず、どれも返さない。

```json
{ "files": [
  { "id": "6f1c…", "name": "見積書.xlsx", "isDir": false, "mimeType": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
  { "id": "…", "name": "設計資料", "isDir": true, "mimeType": null }
] }
```

トークンが失効・無効なら `401`（skiwi は連携が切れたと表示する）。

### 6. 開発環境

skiwi のサーバーが `/oauth/token`・`/oauth/revoke`・`/api/files/lookup` を呼ぶ。skiwi の管理画面に入れる skawau の URL は、
ブラウザで開く画面の URL（開発中は Vite の `http://localhost:5176`）。そのため Vite は `/oauth` も `:8092` へ中継し、
IPv4（`127.0.0.1`）でも待ち受けること（skiwi のサーバーから `localhost` で届くように）。

## skiwi 側（このリポジトリ）

skea 連携と同じ仕組み（`IntegrationRoutes`。サービスごとの違いは `LinkedService`）を使う。

- 管理画面（`/admin/integrations`）: skawau の URL・`client_id`・`client_secret`。skawau に登録する `redirect_uri` も表示する。
- `GET /api/integrations/skawau`・`/connect`・`/callback`・`DELETE /connection`・`POST /lookup {keys}`（keys はファイルの ID。60秒キャッシュ）。
- エディタ（`editor/SkawauFiles.ts`）: 本文の文字は書き換えず、表示の飾り（decoration）で名前を出す。コードの中は対象外。
