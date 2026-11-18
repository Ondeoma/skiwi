# smohua 連携の取り決め

skiwi のページに「フォームの回答（smohua）」のブロックを置くと、smohua（フォーム）で集めた回答の **集計**（選択肢ごとの件数・平均・記述）と
**一覧**（新しいものから50件）を、そのページの中に表示する。回答はページの本文には保存せず、開くたびに smohua から読む。

回答は **ログイン中のユーザー自身の smohua での権限** で読む（共有の API キーは使わない）。仕組みは skea・skawau 連携
（[skea-integration.md](skea-integration.md)・[skawau-integration.md](skawau-integration.md)）と同じで、smohua が OAuth 2.0 の認可サーバー、skiwi がクライアントになる。

- 各ユーザーは skiwi の画面で「smohua と連携」を押し、smohua の画面で許可する（認可コード + PKCE）。
- smohua は、そのユーザーの **読み取り専用** のトークンを発行する。skiwi はそれを暗号化して保存し、サーバーから smohua の API を呼ぶ。
- 連携していないユーザーには「smohua と連携」への案内を、そのフォームを見られない（持ち主でも編集できる人でもない）ユーザーには
  「見つからないか、見る権限がありません」を、ブロックの中に表示する。ゲスト向けの公開ページでは回答を表示しない。

## smohua 側に必要なもの

### 1. クライアントの登録

smohua の環境変数 `OAUTH_CLIENTS` に、skiwi をクライアントとして登録する。

```json
[{"id":"skiwi","secret":"(16文字以上のランダムな値)","redirectUri":"https://skiwi.example.com/api/integrations/smohua/callback","name":"skiwi","scopes":["read"]}]
```

開発用の値: `skiwi` / `skiwi-dev-secret-0123456789` / `http://localhost:5173/api/integrations/smohua/callback`（smohua の `scripts/dev-env.sh` に入っている）。

### 2. OAuth のエンドポイント

`GET /oauth/authorize`（許可画面）・`POST /oauth/token`・`POST /oauth/revoke`。形は skawau 連携と同じ（[skawau-integration.md](skawau-integration.md) の 2〜4）。
`access_token` は、そのユーザーの読み取り専用の API キー（「skiwi 連携」。smohua の個人設定の API キーの一覧から失効できる）。

### 3. 読み取りの API（`Authorization: Bearer <access_token>`。読み取り専用のキーで使えること）

| API | 用途 | 応答 |
|---|---|---|
| `POST /api/forms/lookup` `{"ids":["…"]}`（100件まで） | フォームの題名・状態・回答の数 | `{"forms":[{"id","title","status","questions","responses","lastResponseAt","url"}]}`（見られないものは返さない） |
| `GET /api/forms/{ID}/summary` | 質問ごとの集計 | `{"formId","title","status","total","lastResponseAt","questions":[{"id","kind","label","answered","counts":[{"label","count"}],"average","texts":[…]}]}` |
| `GET /api/forms/{ID}/responses?limit=50` | 回答の一覧（新しい順） | `{"total","responses":[{"id","seq","respondent":{"id","name","avatarUrl"}|null,"answers":{"質問の id": 値},"createdAt"}]}` |

見られないフォームは `404`、トークンが失効していたら `401`。

## skiwi 側

- **設定（管理者）**: 「外部連携」（`/admin/integrations`）の「skiwi → smohua」に、smohua の URL・client_id・client_secret を入れる。
  画面に出る redirect_uri（`${PUBLIC_URL}/api/integrations/smohua/callback`）を smohua の `OAUTH_CLIENTS` に登録する。
- **連携（各ユーザー）**: 自分のアイコンのメニューの「🔗 smohua と連携」、またはブロックの中の「smohua と連携」。
- **ブロック**: エディタの `/` →「フォームの回答（smohua）」で、smohua のフォームの画面の URL（`…/forms/{ID}`・`…/f/{ID}`）か ID を入れる。
  ノードは `smohuaForm`（属性 `formId`。`frontend/src/editor/SmohuaForm.ts`）、中身は Scalactive の部品
  `SmohuaFormView.st`（`host().mountView('smohuaForm')`。データベースのブロックと同じ仕組み）。Markdown に書き出すと「（📋 フォームの回答）」になる。
- **取得**: `IntegrationRoutes` の `LinkedService.smohua`。
  - `POST /api/integrations/smohua/lookup {keys}`: 題名・状態・回答の数（60秒キャッシュ。skea・skawau と同じ）。
  - `GET /api/integrations/smohua/items/{フォームの ID}/summary`・`…/responses`: 本人のトークンで smohua の上の API を呼び、
    `{"connected": true, "data": smohua の応答}` を返す（キャッシュしない）。連携していない・smohua が 401 を返した（連携を消す）ときは `{"connected": false, "data": null}`。
    smohua が 404・403 なら 404、ID の形が違えば 400、そのほかの失敗は 502。
