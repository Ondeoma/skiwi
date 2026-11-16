# skea 連携の取り決め

skiwi のページ本文に出てくる skea のタスクID（`SKEA-123` など）を、skea のタスクへのリンクにし、タイトルと状態を横に表示する。
タスクは **ログイン中のユーザー自身の skea での権限** で読む（共有の API キーは使わない）。

- 各ユーザーは skiwi の画面で「skea と連携」を押し、skea の画面で許可する（OAuth 2.0 の認可コード + PKCE）。
- skea は、そのユーザーの **読み取り専用** のトークンを発行する。skiwi はそれを暗号化して保存し、サーバーから skea の API を呼ぶ。
- 連携していないユーザーには、リンクだけを表示する（タイトルは出さない）。ゲスト向けの公開ページでは何もしない。

## skea 側に必要なもの

### 1. クライアントの登録

skea の管理者が、skiwi をクライアントとして登録する（環境変数でも管理画面でもよい）。

| 項目 | 例 |
|---|---|
| `client_id` | `skiwi` |
| `client_secret` | ランダムな文字列（skiwi の管理画面に同じ値を入れる） |
| `redirect_uri` | `https://skiwi.example.com/api/integrations/skea/callback`（完全一致で照合する） |
| 表示名 | `skiwi`（許可画面に出す） |

### 2. `GET /oauth/authorize`（許可画面）

クエリ:

| 名前 | 値 |
|---|---|
| `response_type` | `code` |
| `client_id` | 登録した ID |
| `redirect_uri` | 登録した URI と完全一致 |
| `scope` | `read` |
| `state` | skiwi が作る値。そのまま返す |
| `code_challenge` | PKCE（BASE64URL(SHA-256(code_verifier))） |
| `code_challenge_method` | `S256`（これ以外は拒否） |

- 未ログインなら skea のログイン（OIDC）をしてから、この URL に戻る。
- 「skiwi が、あなたの権限で skea のタスクを読み取ります」と、許可・拒否のボタンを出す。
- 許可したら `302 {redirect_uri}?code=…&state=…` を返す。拒否したら `302 {redirect_uri}?error=access_denied&state=…` を返す。
- `client_id` または `redirect_uri` が不正なときは、リダイレクトせずに skea の画面でエラーを表示する。
- 認可コードはランダムな値にする。有効期限は60秒で、一度だけ使える。`client_id`・`redirect_uri`・ユーザー・`code_challenge` に結び付ける。

### 3. `POST /oauth/token`（トークンの発行）

`application/x-www-form-urlencoded`。

- **クライアント認証**: HTTP Basic（`client_id:client_secret`）。
- **項目**:
  - `grant_type=authorization_code`
  - `code`
  - `redirect_uri`
  - `code_verifier`

成功したとき（`Cache-Control: no-store`）:

```json
{ "access_token": "skea_…", "token_type": "Bearer", "scope": "read" }
```

- `access_token` は、そのユーザーの **スコープ `read` の API キー** でよい。
  - 名前は「skiwi 連携」にする。
  - 期限は付けない。skea の API キー画面から失効できる。
- 同じユーザーが同じクライアントで許可し直したら、以前のキーを失効させる。
- 失敗したときは、RFC 6749 の形で `400 {"error":"invalid_grant"}` などを返す。
  - `invalid_request`・`invalid_client`（401）・`invalid_grant`・`unsupported_grant_type` を使う。
  - 次の場合は `invalid_grant` にする: コードの期限切れ、使用済み、`code_verifier` の不一致、`redirect_uri` の不一致。

### 4. `POST /oauth/revoke`（連携の解除。RFC 7009）

- 送り方: `application/x-www-form-urlencoded` の `token=…`。クライアント認証は Basic。
- そのトークン（API キー）を失効させる。トークンが存在しなくても `200` を返す。

### 5. `POST /api/tasks/lookup`（タスクをまとめて読む）

`Authorization: Bearer <access_token>`。

```json
{ "keys": ["SKEA-1", "DEMO-12"] }
```

`keys` は最大100件。応答はトークンの持ち主が見られるタスクだけを返す。存在しないタスクと権限のないタスクは区別せず、どちらも返さない。

```json
{ "tasks": [
  { "key": "SKEA-1", "title": "ログイン画面を作る",
    "status": { "name": "進行中", "category": "in_progress", "color": "#2563eb" } }
] }
```

トークンが失効しているときは `401` を返す（skiwi は連携が切れたと表示する）。

### 6. タスクの画面の URL

`{skea の URL}/tasks/{KEY}`（今の skea のルーティングのまま）。

## skiwi 側（このリポジトリ）

- **管理画面での設定**:
  - skea の URL
  - `client_id` と `client_secret`
  - 対象のプロジェクトキー（`SKEA, DEMO` のように並べる。`UTF-8` のような文字を誤ってタスクIDと見なさないよう、キーを絞る）
  - 画面には、skea に登録する `redirect_uri` も表示する。
- **エンドポイント**:
  - `GET /api/integrations/skea`: 設定（URL・キー）と、自分が連携済みかどうか
  - `GET /api/integrations/skea/connect` → skea の `/oauth/authorize` へリダイレクトする。`state` と `code_verifier` はサーバーで保存する（10分）
  - `GET /api/integrations/skea/callback`: コードをトークンに替え、暗号化して保存して、元のページへ戻る
  - `DELETE /api/integrations/skea/connection`: skea の `/oauth/revoke` を呼んでから削除する
  - `POST /api/integrations/skea/lookup` `{keys}`: 本人のトークンで skea の `/api/tasks/lookup` を呼ぶ。60秒キャッシュする
  - 実装は skawau 連携と共通（`IntegrationRoutes`。skea 固有の部分は `LinkedService.skea`）
- **トークンの保存**:
  - AES-GCM で暗号化する。鍵は環境変数 `ENCRYPTION_KEY`（32バイトの base64）。
  - `ENCRYPTION_KEY` が未設定のときは、連携の機能を使えないようにする。
- **エディタでの表示**:
  - 本文の文字を書き換えず、表示の飾り（ProseMirror の decoration）でリンクにして、タイトルと状態を付ける。
  - そのため、既存のページにもそのまま効き、同時編集にも影響しない。
  - コードブロックとインラインのコードの中は対象外にする。

---

# 逆向き: skea → skiwi（skea にページのタイトルを出す）

skea のタスクに書いた skiwi のページのリンク（`{skiwi の URL}/w/{ワークスペースID}/p/{ページID}`）に、ページのタイトルを出す。
今度は **skiwi が OAuth 2.0 の認可サーバー**、skea がクライアントになる（上の向きと鏡写し）。取り決めは skea のリポジトリの `docs/skiwi-integration.md`。

## skiwi 側（このリポジトリ）

- **クライアントの登録**（管理者。`/admin/integrations` の「skea → skiwi」）:
  - `client_id`・`client_secret`（16文字以上。SHA-256 のハッシュだけを保存し、照合は時間が一定の比較）・`redirect_uri`（完全一致）・表示名。
  - `integration_setting` のキー `oauth_client.*` に置く。API は `GET`・`PUT`・`DELETE /api/admin/integrations/skea/client`（監査ログに残す）。
  - `client_id` を変える・登録を消すと、そのクライアントに発行したトークンはすべて失効する。
  - 環境変数 `OAUTH_CLIENT_ID`・`OAUTH_CLIENT_SECRET`・`OAUTH_CLIENT_REDIRECT_URI`・`OAUTH_CLIENT_NAME`（既定 `skea`）でも登録できる。
    起動時に、まだ登録されていないときだけ書く（管理画面での変更を上書きしない）。`scripts/dev-up.sh` は開発用の値
    （`skea` / `skea-dev-secret-0123456789` / `http://localhost:8090/api/integrations/skiwi/callback`）を入れる。
  - `ENCRYPTION_KEY` は不要（秘密はハッシュだけを持つ）。
- **`GET /oauth/authorize`**（許可の画面。サーバーで HTML を返す）:
  - `response_type=code`・`client_id`・`redirect_uri`・`scope=read`・`state`・`code_challenge`・`code_challenge_method=S256`（S256 以外は `invalid_request` で戻す）。
  - `client_id`・`redirect_uri` が不正なら、リダイレクトせずに `400` のエラー画面を出す。
  - 未ログインなら `/auth/login?returnTo=…` へ送り、ログイン後にこの URL に戻る（`returnTo` は同じサイトの相対パスだけ。`oidc_login.return_to` に保存）。
  - 「skea が、あなたの権限で skiwi のページのタイトルを読み取ります」と、許可する・拒否するを出す。
  - CSRF 対策: `SameSite=Strict` の Cookie（`skiwi_oauth_csrf`）とフォームの hidden の値が一致すること。
    CSP の `form-action` に `redirect_uri` の origin を入れる（入れないと、送信後のリダイレクトがブラウザに止められる）。
  - 許可: `302 {redirect_uri}?code=…&state=…`。拒否: `302 {redirect_uri}?error=access_denied&state=…`。
  - 認可コードは60秒・一度だけ。ハッシュだけを `oauth_code` に保存し、client・redirect_uri・ユーザー・code_challenge に結び付ける。
- **`POST /oauth/token`**（Basic 認証。`grant_type=authorization_code`・`code`・`redirect_uri`・`code_verifier`）:
  - 成功: `{"access_token":"skiwi_…","token_type":"Bearer","scope":"read"}`（`Cache-Control: no-store`）。
  - トークンはそのユーザーの API トークン（名前「skea 連携」、期限なし）で、**`POST /api/pages/lookup` にだけ使える**（ほかの API・MCP・WebSocket では認証なしと同じ `401`）。`api_token.oauth_client` に発行したクライアントを記録する。
    「APIトークン」の画面（`/settings/tokens`）に出て、そこから失効できる。
  - 同じユーザーが許可し直したら、そのクライアントが以前に発行したトークンを失効させる。
  - エラーは RFC 6749 の形（`invalid_request`・`invalid_client`（401）・`invalid_grant`・`unsupported_grant_type`）。
- **`POST /oauth/revoke`**（RFC 7009。Basic 認証。`token=…`）: そのクライアントが発行したトークンだけを失効させる。なくても `200`。
- **`POST /api/pages/lookup`** `{"ids":[…]}`（100件まで。超えると `400`）:
  - `{"pages":[{"id","workspaceId","title","icon"}]}`（`icon` はなければ `null`）。
  - 見られるページだけを返す（削除済み・存在しない・権限のないものは区別しない）。
  - 読むだけなので、読み取り専用のトークンと、`/oauth/token` で発行したトークンでも使える（`SessionAuth`）。失効・無効なトークンは `401`。
- 実装: `modules/api/src/main/scala/skiwi/api/OAuthProviderRoutes.scala`、`V13__oauth_provider.sql`、テストは `OAuthProviderSpec`。
