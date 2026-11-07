-- Wiki のスキーマ（初版）。
-- 本番投入前に、開発中に積み重ねたマイグレーション（旧 V1〜V10）を最終形の1本にまとめたもの。
-- 以後の変更は V2 以降として追加すること（このファイルは書き換えない）。

-- 全文検索（日本語）
create extension if not exists pgroonga;

-- ============================================================ ユーザー・グループ（IdP から同期）

create table app_user (
  id               uuid primary key default gen_random_uuid(),
  oidc_sub         text not null unique,
  email            text,
  name             text not null,
  active           boolean not null default true,
  -- SCIM: IdP 側のID（externalId）とユーザー名
  scim_external_id text,
  username         text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create table app_group (
  id               uuid primary key default gen_random_uuid(),
  external_id      text not null unique,
  name             text not null,
  scim_external_id text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create table group_member (
  group_id uuid not null references app_group(id) on delete cascade,
  user_id  uuid not null references app_user(id) on delete cascade,
  primary key (group_id, user_id)
);
create index group_member_user_idx on group_member(user_id);

-- ============================================================ 認証

-- セッション（3ノードで共有するためDBに保存。トークンはSHA-256ハッシュのみ）
create table session (
  token_hash bytea primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);
create index session_expires_idx on session(expires_at);

-- OIDCログイン中の一時状態（state, PKCE verifier, nonce）
create table oidc_login (
  state         text primary key,
  code_verifier text not null,
  nonce         text not null,
  created_at    timestamptz not null default now()
);

-- APIトークン（ハッシュのみ保存）。scope: read=閲覧のみ / write=ユーザーの権限どおり
create table api_token (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references app_user(id) on delete cascade,
  name         text not null,
  token_hash   bytea not null unique,
  scope        text not null check (scope in ('read', 'write')),
  created_at   timestamptz not null default now(),
  expires_at   timestamptz,
  last_used_at timestamptz,
  revoked_at   timestamptz
);
create index api_token_user_idx on api_token(user_id);

-- ============================================================ ワークスペース

-- 削除するとゴミ箱へ（deleted_at。中のページも同じ時刻で削除扱いにする）
create table workspace (
  id           uuid primary key default gen_random_uuid(),
  name         text not null,
  default_role text check (default_role in ('view', 'edit')),
  created_by   uuid references app_user(id) on delete set null,
  created_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  deleted_by   uuid references app_user(id) on delete set null
);

create table workspace_acl (
  workspace_id   uuid not null references workspace(id) on delete cascade,
  principal_type text not null check (principal_type in ('user', 'group')),
  principal_id   uuid not null,
  role           text not null check (role in ('view', 'edit')),
  primary key (workspace_id, principal_type, principal_id)
);

-- ワークスペースのお気に入り（ユーザーごと。一覧の上に並ぶ）
create table workspace_favorite (
  user_id      uuid not null references app_user(id) on delete cascade,
  workspace_id uuid not null references workspace(id) on delete cascade,
  created_at   timestamptz not null default now(),
  primary key (user_id, workspace_id)
);
create index workspace_favorite_ws_idx on workspace_favorite(workspace_id);

-- ============================================================ ページ

-- 並び順 position は分数インデックス（照合順序 "C" で比較する）。
-- 削除するとゴミ箱へ（deleted_at）。一緒に削除したページは deleted_root（削除操作の対象ページ）でまとめて復元する。
create table page (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspace(id) on delete cascade,
  parent_id    uuid references page(id),
  position     text collate "C" not null,
  title        text not null default '',
  icon         text,
  inherit_acl  boolean not null default true,
  created_by   uuid references app_user(id) on delete set null,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz,
  deleted_by   uuid references app_user(id) on delete set null,
  deleted_root uuid
);
create index page_tree_idx on page(workspace_id, parent_id, position) where deleted_at is null;
-- 子孫をたどる再帰問い合わせ（削除・公開範囲・完全削除）用
create index page_parent_idx on page(parent_id);
create index page_trash_idx on page(workspace_id, deleted_at) where deleted_at is not null;
create index page_deleted_root_idx on page(deleted_root) where deleted_at is not null;

create table page_acl (
  page_id        uuid not null references page(id) on delete cascade,
  principal_type text not null check (principal_type in ('user', 'group')),
  principal_id   uuid not null,
  role           text not null check (role in ('view', 'edit')),
  primary key (page_id, principal_type, principal_id)
);
create index page_acl_principal_idx on page_acl(principal_id);

-- ============================================================ 本文（同時編集）

-- Yjs の update ログ（追記のみ。圧縮時に古い分を削除）
create table page_updates (
  seq        bigserial primary key,
  page_id    uuid not null references page(id) on delete cascade,
  data       bytea not null,
  created_at timestamptz not null default now()
);
create index page_updates_page_idx on page_updates(page_id, seq);

-- 本文の状態。
--   compact … 最新の圧縮状態（ページごとに1件。検索・API・表示用の本文）
--   part    … それ以前の状態断片（接続時に読み込むが本文には使わない。複数ノードが並行して圧縮しても安全にするため）
--   version … 版履歴（authors: その版までに編集した人、label: 任意の名前）
-- content / plain_text はクライアントから受け取る。
create table page_snapshot (
  id          uuid primary key default gen_random_uuid(),
  page_id     uuid not null references page(id) on delete cascade,
  kind        text not null check (kind in ('compact', 'part', 'version')),
  ydoc        bytea not null,
  content     jsonb not null,
  plain_text  text not null,
  authors     uuid[] not null default '{}',
  label       text,
  created_by  uuid references app_user(id) on delete set null,
  created_at  timestamptz not null default now()
);
create unique index page_snapshot_compact_uidx on page_snapshot(page_id) where kind = 'compact';
create index page_snapshot_page_idx on page_snapshot(page_id, created_at desc);

-- 被リンク: 本文の保存（圧縮）時に、本文中のページリンクから作り直す
create table page_link (
  source_id uuid not null references page(id) on delete cascade,
  target_id uuid not null,
  primary key (source_id, target_id)
);
create index page_link_target_idx on page_link(target_id);

-- ============================================================ 検索

-- 表記ゆれ対策: ひらがな/カタカナ・全角/半角・大文字/小文字・小書き文字（ゃ/や）・長音とハイフンを同一視し、
-- 英数字も2文字ずつ索引する（単語の途中からでも一致する）。正規化の指定は skiwi.db.TextNorm と揃えること。
create index page_title_pgroonga on page using pgroonga (title) with (
  normalizers = 'NormalizerNFKC150("unify_kana", true, "unify_kana_case", true, "unify_hyphen_and_prolonged_sound_mark", true, "unify_middle_dot", true)',
  tokenizer = 'TokenNgram("unify_alphabet", false, "unify_symbol", false, "unify_digit", false)'
);
create index page_snapshot_text_pgroonga on page_snapshot using pgroonga (plain_text) with (
  normalizers = 'NormalizerNFKC150("unify_kana", true, "unify_kana_case", true, "unify_hyphen_and_prolonged_sound_mark", true, "unify_middle_dot", true)',
  tokenizer = 'TokenNgram("unify_alphabet", false, "unify_symbol", false, "unify_digit", false)'
);

-- ============================================================ 添付ファイル

-- 実体は S3 互換ストレージに保存し、メタデータのみ DB に持つ
create table attachment (
  id           uuid primary key default gen_random_uuid(),
  page_id      uuid not null references page(id) on delete cascade,
  s3_key       text not null unique,
  filename     text not null,
  content_type text not null,
  size         bigint not null,
  sha256       text not null,
  created_by   uuid references app_user(id) on delete set null,
  created_at   timestamptz not null default now()
);
create index attachment_page_idx on attachment(page_id);

-- ============================================================ ページの付帯情報

-- ラベル（表記は利用者の入力のまま、比較は小文字化して行う）
create table page_label (
  page_id uuid not null references page(id) on delete cascade,
  label   text not null,
  primary key (page_id, label)
);
create index page_label_label_idx on page_label(lower(label));

-- ページのお気に入り（ユーザーごと。サイドバーの上部に並べる）
create table page_favorite (
  user_id    uuid not null references app_user(id) on delete cascade,
  page_id    uuid not null references page(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, page_id)
);
create index page_favorite_page_idx on page_favorite(page_id);

-- 最近見たページ・自分が編集したページ
create table page_visit (
  user_id    uuid not null references app_user(id) on delete cascade,
  page_id    uuid not null references page(id) on delete cascade,
  visited_at timestamptz not null default now(),
  primary key (user_id, page_id)
);
create index page_visit_recent_idx on page_visit(user_id, visited_at desc);
create index page_visit_page_idx on page_visit(page_id);

create table page_edit (
  user_id   uuid not null references app_user(id) on delete cascade,
  page_id   uuid not null references page(id) on delete cascade,
  edited_at timestamptz not null default now(),
  primary key (user_id, page_id)
);
create index page_edit_recent_idx on page_edit(user_id, edited_at desc);
create index page_edit_page_idx on page_edit(page_id);

-- ゲスト（ログインしていない人）向けの読み取り専用公開リンク。
-- token を知っている人は誰でも、そのページ（include_children なら子孫も）を閲覧できる。
-- 子孫のうち「権限を引き継がない」ページ（個別に閲覧制限したページ）とその下は公開に含めない。
-- expires_at: 有効期限、password_hash: 閲覧用パスワード（PBKDF2）
create table page_share (
  page_id          uuid primary key references page(id) on delete cascade,
  token            text not null unique,
  include_children boolean not null default true,
  created_by       uuid references app_user(id) on delete set null,
  created_at       timestamptz not null default now(),
  expires_at       timestamptz,
  password_hash    text
);

-- ============================================================ 通知

-- ページのウォッチ（更新通知の購読）
create table page_watch (
  page_id    uuid not null references page(id) on delete cascade,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (page_id, user_id)
);
create index page_watch_user_idx on page_watch(user_id);

-- メール送信アウトボックス。dedupe_key が同じ未送信メールは1通にまとめる。
-- 本文は送信時に組み立てる（その時点の権限・タイトルで判断するため）。
create table mail_outbox (
  id          bigserial primary key,
  user_id     uuid not null references app_user(id) on delete cascade,
  kind        text not null check (kind in ('page_edited', 'shared')),
  page_id     uuid references page(id) on delete cascade,
  dedupe_key  text,
  detail      jsonb not null default '{}',
  created_at  timestamptz not null default now(),
  not_before  timestamptz not null default now(),
  attempts    int not null default 0,
  last_error  text,
  sent_at     timestamptz,
  skipped     boolean not null default false
);
create unique index mail_outbox_dedupe_uidx on mail_outbox(dedupe_key) where sent_at is null and not skipped;
create index mail_outbox_due_idx on mail_outbox(not_before) where sent_at is null and not skipped;
create index mail_outbox_page_idx on mail_outbox(page_id);

-- 画面内の通知（ベル）
create table notification (
  id           bigserial primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  kind         text not null check (kind in ('page_edited', 'shared', 'shared_workspace')),
  page_id      uuid references page(id) on delete cascade,
  workspace_id uuid references workspace(id) on delete cascade,
  actors       text[] not null default '{}',
  detail       jsonb not null default '{}',
  created_at   timestamptz not null default now(),
  read_at      timestamptz
);
create index notification_user_idx on notification(user_id, created_at desc);
create index notification_page_idx on notification(page_id);
create index notification_workspace_idx on notification(workspace_id);
-- 同じページの未読の更新通知は1件にまとめる
create unique index notification_edit_uidx on notification(user_id, page_id) where kind = 'page_edited' and read_at is null;

-- ============================================================ 監査ログ（追記専用。閲覧は記録しない）

create table audit_log (
  id          bigserial primary key,
  at          timestamptz not null default now(),
  actor_id    uuid,
  actor_name  text,
  via         text not null check (via in ('session', 'token', 'scim', 'system')),
  action      text not null,
  target_type text,
  target_id   text,
  detail      jsonb not null default '{}'
);
create index audit_log_at_idx on audit_log(at desc);
create index audit_log_target_idx on audit_log(target_type, target_id);

create function audit_log_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'audit_log is append-only';
end $$;
create trigger audit_log_no_update before update or delete on audit_log
  for each row execute function audit_log_append_only();

-- ============================================================ 3ノード構成

-- クラスタのノード（生存確認のハートビート）。ページの担当ノードはこの生存ノード集合から決める
create table cluster_node (
  node_id      text primary key,
  internal_url text not null,
  started_at   timestamptz not null default now(),
  heartbeat_at timestamptz not null default now()
);
