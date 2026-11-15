-- 外部サービス（skea・skawau）との連携の設定（キーと値。秘密の値はアプリで暗号化して入れる）。キーの例: skea.url, skea.client_id, skea.client_secret, skea.project_keys
create table integration_setting (
  key        text primary key,
  value      text not null,
  updated_by uuid references app_user(id) on delete set null,
  updated_at timestamptz not null default now()
);

-- 外部サービスと連携したユーザーのアクセストークン（AES-GCM で暗号化したもの）。service: skea・skawau
create table integration_connection (
  service    text not null,
  user_id    uuid not null references app_user(id) on delete cascade,
  token_enc  text not null,
  created_at timestamptz not null default now(),
  primary key (service, user_id)
);

-- 外部サービスの許可画面へ送り出したときの state と PKCE の code_verifier（10分で無効。複数ノードで共有するため DB に置く）
create table integration_oauth_state (
  state         text primary key,
  service       text not null,
  user_id       uuid not null references app_user(id) on delete cascade,
  code_verifier text not null,
  return_to     text not null,
  created_at    timestamptz not null default now()
);
create index integration_oauth_state_created on integration_oauth_state (created_at);
