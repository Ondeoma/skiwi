-- skea → skiwi の連携（skiwi が OAuth 2.0 の認可サーバーになる。docs/skea-integration.md）
-- クライアントの登録は integration_setting（キー oauth_client.*。client_secret はハッシュだけ）に置く

-- 認可コード（ハッシュだけ保存。60秒・一度だけ。client・redirect_uri・ユーザー・code_challenge に結び付ける）
create table oauth_code (
  code_hash      bytea primary key,
  client_id      text not null,
  redirect_uri   text not null,
  user_id        uuid not null references app_user(id) on delete cascade,
  code_challenge text not null,
  expires_at     timestamptz not null,
  used_at        timestamptz
);
create index oauth_code_expires_idx on oauth_code (expires_at);

-- 認可で発行した API トークンのクライアント（許可し直したときの失効・クライアントからの失効に使う）
alter table api_token add column oauth_client text;
create index api_token_oauth_idx on api_token (user_id, oauth_client) where oauth_client is not null;

-- ログイン後の戻り先（同じサイトの相対パス。/oauth/authorize などサーバーの画面へ戻るため）
alter table oidc_login add column return_to text;
