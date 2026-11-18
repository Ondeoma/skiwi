-- skiwi のスキーマ
-- 適用済みのマイグレーションは書き換えないこと。変更は新しい番号の V<n>__*.sql を追加する。

-- 全文検索（日本語）
create extension if not exists pgroonga;

-- ============================================================ ユーザー・グループ（IdP から同期）

create table app_user (
  id               uuid primary key default gen_random_uuid(),
  sub              text not null unique,                 -- OIDC の sub（エージェント専用アカウントは agent:<uuid>、開発用のログインは dev:<名前>）
  email            text,
  name             text not null,
  avatar_url       text,                                 -- IdP の OIDC の picture クレームのアイコンの URL。自分で登録したアイコン（user_avatar）がなければこれを使う
  disabled         boolean not null default false,
  -- SCIM: IdP 側のID（externalId）とユーザー名
  scim_external_id text,
  username         text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

-- external_id: IdP・SCIM のグループ名（ADMIN_GROUPS・ログイン時の groups クレームと突き合わせる）
create table user_group (
  id               uuid primary key default gen_random_uuid(),
  external_id      text not null unique,
  name             text not null,
  scim_external_id text,
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now()
);

create table group_member (
  group_id uuid not null references user_group(id) on delete cascade,
  user_id  uuid not null references app_user(id) on delete cascade,
  primary key (group_id, user_id)
);
create index group_member_user_idx on group_member(user_id);

-- ユーザーのアイコン（ブラウザで正方形に縮めた小さな画像。1人1枚）
create table user_avatar (
  user_id      uuid primary key references app_user(id) on delete cascade,
  content_type text not null,
  data         bytea not null,
  updated_at   timestamptz not null default now()
);

-- ============================================================ 認証

-- セッション（3ノードで共有するためDBに保存。トークンはSHA-256ハッシュのみ）。
-- id_token: IdP からもログアウトするときに渡す id_token_hint（OIDC でログインしたセッションだけ）
create table session (
  token_hash bytea primary key,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  id_token   text
);
create index session_expires_idx on session(expires_at);
create index session_user_idx on session(user_id);   -- SCIM の無効化などでユーザーのセッションを消す

-- OIDC のログイン途中の状態（state, PKCE verifier, nonce）。return_to: ログイン後の戻り先（同じサイトの相対パス）
create table login_state (
  state      text primary key,
  verifier   text not null,
  nonce      text not null,
  created_at timestamptz not null default now(),
  return_to  text
);
create index login_state_created_idx on login_state(created_at);   -- ログインのたびに古いものを消す

-- API キー（ハッシュのみ保存）。scopes: {read}=閲覧のみ / {read,write}=ユーザーの権限どおり。
-- oauth_client: 認可（skiwi が OAuth の認可サーバー）で発行したキーのクライアント（許可し直したときの失効・クライアントからの失効に使う）。
-- oauth_full: OAUTH_CLIENTS のクライアント（skakapo など）のキーは scopes のとおり。管理画面で登録したクライアント（skea）のキーはページの一括参照だけ
create table api_key (
  id           uuid primary key default gen_random_uuid(),
  user_id      uuid not null references app_user(id) on delete cascade,
  name         text not null,
  key_hash     bytea not null unique,
  scopes       text[] not null,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz,
  last_used_at timestamptz,
  revoked_at   timestamptz,
  oauth_client text,
  oauth_full   boolean not null default false
);
create index api_key_user_idx on api_key(user_id);
create index api_key_oauth_idx on api_key(user_id, oauth_client) where oauth_client is not null;

-- ============================================================ ワークスペース

-- 削除するとゴミ箱へ（deleted_at。中のページも同じ時刻で削除扱いにする）。
-- custom_css: カスタムCSS。guest_top_html・guest_bottom_html: ゲスト閲覧時に本文の上下に入れるHTML（サンドボックス化した iframe の中で表示する）
create table workspace (
  id                uuid primary key default gen_random_uuid(),
  name              text not null,
  default_role      text check (default_role in ('view', 'edit')),
  created_by        uuid references app_user(id) on delete set null,
  created_at        timestamptz not null default now(),
  deleted_at        timestamptz,
  deleted_by        uuid references app_user(id) on delete set null,
  custom_css        text,
  guest_top_html    text,
  guest_bottom_html text
);

-- expires_at: 期限付きの権限（期限を過ぎたものは権限の判定に使わず、定期的に消す）
create table workspace_acl (
  workspace_id   uuid not null references workspace(id) on delete cascade,
  principal_type text not null check (principal_type in ('user', 'group')),
  principal_id   uuid not null,
  role           text not null check (role in ('view', 'edit')),
  expires_at     timestamptz,
  primary key (workspace_id, principal_type, principal_id)
);
create index workspace_acl_expires_idx on workspace_acl(expires_at) where expires_at is not null;

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
--   owner_id・status: 所有者と状態
--   custom_css・css_inherit: カスタムCSS（css_inherit=true なら子孫ページにも適用する。ワークスペース → 祖先 → そのページ の順に重ねる）
--   guest_top_html・guest_bottom_html・guest_html_inherit: ゲスト閲覧時に本文の上下に入れるHTML（上: ワークスペース → 祖先 → そのページ、下: その逆順）
--   guest_slug: 公開ページの URL 名（/s/<リンク>/<URL名>。ワークスペース内で重複しない）
--   props: データベースの項目（データベースの子ページ）の値。キーは列のID
--   locked_*・lock_editors: ページの保護（保護中は、保護した人・所有者・指定した人だけが編集できる）
--   full_width: 全幅で表示する
create table page (
  id                 uuid primary key default gen_random_uuid(),
  workspace_id       uuid not null references workspace(id) on delete cascade,
  parent_id          uuid references page(id),
  position           text collate "C" not null,
  title              text not null default '',
  icon               text,
  inherit_acl        boolean not null default true,
  created_by         uuid references app_user(id) on delete set null,
  created_at         timestamptz not null default now(),
  updated_at         timestamptz not null default now(),
  deleted_at         timestamptz,
  deleted_by         uuid references app_user(id) on delete set null,
  deleted_root       uuid,
  owner_id           uuid references app_user(id) on delete set null,
  status             text check (status in ('draft', 'review', 'published', 'deprecated')),
  custom_css         text,
  css_inherit        boolean not null default true,
  guest_top_html     text,
  guest_bottom_html  text,
  guest_html_inherit boolean not null default true,
  guest_slug         text check (guest_slug ~ '^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$|^[a-z0-9]$'),
  props              jsonb not null default '{}',
  locked_at          timestamptz,
  locked_by          uuid references app_user(id) on delete set null,
  lock_reason        text,
  lock_editors       uuid[] not null default '{}',
  full_width         boolean not null default false
);
create index page_tree_idx on page(workspace_id, parent_id, position) where deleted_at is null;
-- 子孫をたどる再帰問い合わせ（削除・公開範囲・完全削除）用
create index page_parent_idx on page(parent_id);
create index page_trash_idx on page(workspace_id, deleted_at) where deleted_at is not null;
create index page_deleted_root_idx on page(deleted_root) where deleted_at is not null;
create index page_owner_idx on page(owner_id) where owner_id is not null;
create unique index page_guest_slug_uidx on page(workspace_id, guest_slug) where guest_slug is not null;

-- expires_at: 期限付きの権限
create table page_acl (
  page_id        uuid not null references page(id) on delete cascade,
  principal_type text not null check (principal_type in ('user', 'group')),
  principal_id   uuid not null,
  role           text not null check (role in ('view', 'edit')),
  expires_at     timestamptz,
  primary key (page_id, principal_type, principal_id)
);
create index page_acl_principal_idx on page_acl(principal_id);
create index page_acl_expires_idx on page_acl(expires_at) where expires_at is not null;

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

-- 本文中のメンション（本文の保存時に作り直す。新しく増えた人にだけ通知する）
create table page_mention (
  page_id uuid not null references page(id) on delete cascade,
  user_id uuid not null references app_user(id) on delete cascade,
  primary key (page_id, user_id)
);

-- ToDo（本文の保存時に、ToDoリストの項目から作り直す）。
-- 担当者は項目内のメンション、期限は項目内の期限の印。担当者がいない項目は記録しない。
create table page_task (
  page_id     uuid not null references page(id) on delete cascade,
  seq         int not null,
  text        text not null,
  checked     boolean not null,
  assignee_id uuid not null references app_user(id) on delete cascade,
  due         date,
  primary key (page_id, seq, assignee_id)
);
create index page_task_assignee_idx on page_task(assignee_id, checked, due);

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
create index attachment_created_idx on attachment(created_at desc);   -- 文字を取り出す前の添付を新しいものから探す

-- 添付ファイル（PDF・Word・Excel・PowerPoint・テキストなど）から取り出した文字。検索の対象にする。
-- status: ok（文字あり）/ empty（文字なし。画像だけの PDF など）/ unsupported（読めない形式）/ too_large / error
create table attachment_text (
  attachment_id uuid primary key references attachment(id) on delete cascade,
  status        text not null check (status in ('ok', 'empty', 'unsupported', 'too_large', 'error')),
  body          text not null default '',
  extracted_at  timestamptz not null default now()
);
create index attachment_text_pgroonga on attachment_text using pgroonga (body) with (
  normalizers = 'NormalizerNFKC150("unify_kana", true, "unify_kana_case", true, "unify_hyphen_and_prolonged_sound_mark", true, "unify_middle_dot", true)',
  tokenizer = 'TokenNgram("unify_alphabet", false, "unify_symbol", false, "unify_digit", false)'
);

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

-- コメント。thread_id が null のものがスレッドの先頭（返信は先頭の id を持つ）。
-- anchor: null=ページ全体、'text:<id>'=本文の範囲（本文側に同じ id の印を付ける）、'wb:<id>'=ホワイトボード
-- body 中のメンションは @[名前](ユーザーID) の形で保存する。削除は論理削除（返信が残るため）。
create table comment (
  id          uuid primary key default gen_random_uuid(),
  page_id     uuid not null references page(id) on delete cascade,
  thread_id   uuid references comment(id) on delete cascade,
  anchor      text,
  quote       text,
  body        text not null,
  author_id   uuid references app_user(id) on delete set null,
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now(),
  resolved_at timestamptz,
  resolved_by uuid references app_user(id) on delete set null,
  deleted_at  timestamptz
);
create index comment_page_idx on comment(page_id, created_at);
create index comment_thread_idx on comment(thread_id);

-- 閲覧権限の申請。page_id は存在しないページでも受け付ける（ページの有無を申請者に知らせないため。外部キーにしない）
create table access_request (
  id          uuid primary key default gen_random_uuid(),
  page_id     uuid not null,
  user_id     uuid not null references app_user(id) on delete cascade,
  role        text not null check (role in ('view', 'edit')),
  message     text,
  status      text not null default 'pending' check (status in ('pending', 'approved', 'denied')),
  created_at  timestamptz not null default now(),
  decided_by  uuid references app_user(id) on delete set null,
  decided_at  timestamptz
);
create unique index access_request_pending_uidx on access_request(page_id, user_id) where status = 'pending';
create index access_request_page_idx on access_request(page_id) where status = 'pending';

-- ============================================================ ゲスト向けの公開

-- ゲスト（ログインしていない人）向けの読み取り専用公開リンク。
-- token を知っている人は誰でも、そのページ（include_children なら子孫も）を閲覧できる。
-- 子孫のうち「権限を引き継がない」ページ（個別に閲覧制限したページ）とその下は公開に含めない。
--   expires_at: 有効期限、password_hash: 閲覧用パスワード（PBKDF2）
--   hide_header: 上部のヘッダー（「公開ページ」「ログイン」）を出さない
--   sidebar: サイドメニュー（公開範囲のページ一覧）。auto: 2ページ以上なら表示 / show: 常に表示 / hide: 表示しない
--   mode: live（編集中の内容をそのまま公開）| pinned（「公開」を押した時点の内容を公開。page_share_pub に保存）
--   starts_at: この日時まではゲストは閲覧できない（公開の予約）
--   slug: わかりやすいURL（/s/<slug>）。トークンのURLも引き続き使える
--   indexable: 検索エンジンに載せる（既定は載せない）。description: 共有したときの説明文（OGP）
--   show_releases: ゲスト画面に「更新履歴」（公開日時と変更点のメモ）を出す
create table page_share (
  page_id          uuid primary key references page(id) on delete cascade,
  token            text not null unique,
  include_children boolean not null default true,
  created_by       uuid references app_user(id) on delete set null,
  created_at       timestamptz not null default now(),
  expires_at       timestamptz,
  password_hash    text,
  hide_header      boolean not null default false,
  sidebar          text not null default 'auto' check (sidebar in ('auto', 'show', 'hide')),
  mode             text not null default 'live' check (mode in ('live', 'pinned')),
  published_at     timestamptz,
  published_by     uuid references app_user(id) on delete set null,
  starts_at        timestamptz,
  slug             text unique check (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$'),
  indexable        boolean not null default false,
  description      text,
  show_releases    boolean not null default false
);

-- 公開版（mode=pinned のとき、ゲストに見せる内容）。公開範囲のページごとに、公開した時点のタイトル・本文を持つ。
-- 公開後に削除・非公開にしたページは、ゲストには見せない（表示時に現在の公開範囲と照合する）。
create table page_share_pub (
  share_page_id uuid not null references page_share(page_id) on delete cascade,
  page_id       uuid not null,
  parent_id     uuid,
  position      text collate "C" not null,
  title         text not null,
  icon          text,
  content       jsonb,
  plain_text    text not null default '',
  updated_at    timestamptz not null,
  primary key (share_page_id, page_id)
);

-- 公開版の変更履歴（「現在の内容を公開」ごとに、変更点のメモと公開した内容を残す）
create table page_share_release (
  id            uuid primary key default gen_random_uuid(),
  share_page_id uuid not null references page_share(page_id) on delete cascade,
  published_at  timestamptz not null default now(),
  published_by  uuid references app_user(id) on delete set null,
  note          text,
  page_count    int not null default 0
);
create index page_share_release_idx on page_share_release(share_page_id, published_at desc);

create table page_share_release_page (
  release_id uuid not null references page_share_release(id) on delete cascade,
  page_id    uuid not null,
  parent_id  uuid,
  position   text not null,
  title      text not null,
  icon       text,
  content    jsonb,
  plain_text text not null default '',
  primary key (release_id, page_id)
);

-- ============================================================ 通知

-- ページのウォッチ（更新通知の購読）。edits・comments: 本文の更新・コメントのどちらを受け取るか、subtree: 子ページも含めるか
create table page_watch (
  page_id    uuid not null references page(id) on delete cascade,
  user_id    uuid not null references app_user(id) on delete cascade,
  created_at timestamptz not null default now(),
  edits      boolean not null default true,
  comments   boolean not null default true,
  subtree    boolean not null default false,
  primary key (page_id, user_id)
);
create index page_watch_user_idx on page_watch(user_id);
create index page_watch_subtree_idx on page_watch(page_id) where subtree;

-- メール送信アウトボックス。dedupe_key が同じ未送信メールは1通にまとめる。
-- 本文は送信時に組み立てる（その時点の権限・タイトルで判断するため）。
create table mail_outbox (
  id          bigserial primary key,
  user_id     uuid not null references app_user(id) on delete cascade,
  kind        text not null check (kind in ('page_edited', 'shared', 'mention', 'comment', 'task_due', 'access_request', 'access_granted', 'automation')),
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
  kind         text not null check (kind in ('page_edited', 'shared', 'shared_workspace', 'mention', 'comment', 'task_due', 'access_request', 'access_granted', 'access_denied', 'automation')),
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
-- 未読の数（ベル）と、期限の近い ToDo の通知の重複確認
create index notification_unread_idx on notification(user_id) where read_at is null;
create index notification_task_due_idx on notification(user_id, (detail->>'key')) where kind = 'task_due';
create index notification_created_idx on notification(created_at);   -- 古い通知を消す

-- プッシュ通知（PWA）. VAPID の鍵（全ノードで同じものを使う）と、ブラウザ・端末ごとの購読
create table push_config (
  id            int primary key check (id = 1),
  vapid_public  text not null,
  vapid_private text not null,
  created_at    timestamptz not null default now()
);

create table push_subscription (
  id           uuid primary key,
  user_id      uuid not null references app_user(id) on delete cascade,
  endpoint     text not null unique,
  p256dh       text not null,
  auth         text not null,
  user_agent   text,
  created_at   timestamptz not null default now(),
  last_used_at timestamptz
);
create index push_subscription_user_idx on push_subscription (user_id);

-- ============================================================ データベース・フォーム

-- データベース（子ページを項目として、表・ボード・カレンダーで見る）。next_seq: 項目の連番（ID の列）の次に振る番号
create table page_database (
  page_id    uuid primary key references page(id) on delete cascade,
  props      jsonb not null default '[]',
  views      jsonb not null default '[]',
  updated_at timestamptz not null default now(),
  next_seq   int not null default 1
);

-- データベースのフォーム（回答がデータベースの項目になる）
create table db_form (
  id               uuid primary key default gen_random_uuid(),
  database_page_id uuid not null references page(id) on delete cascade,
  token            text not null unique,
  title            text not null,
  description      text,
  fields           jsonb not null default '[]',
  require_login    boolean not null default false,
  respondent_prop  text,
  enabled          boolean not null default true,
  created_by       uuid references app_user(id) on delete set null,
  created_at       timestamptz not null default now(),
  last_at          timestamptz,
  use_count        int not null default 0
);
create index db_form_db_idx on db_form(database_page_id);

-- ============================================================ Webhook・自動化

-- Webhook（ワークスペースの出来事を外部へ送る）
create table webhook (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspace(id) on delete cascade,
  name         text not null,
  url          text not null,
  format       text not null check (format in ('generic', 'slack', 'teams')),
  events       text[] not null,
  secret       text not null,
  enabled      boolean not null default true,
  created_by   uuid references app_user(id) on delete set null,
  created_at   timestamptz not null default now(),
  last_status  int,
  last_error   text,
  last_at      timestamptz
);
create index webhook_ws_idx on webhook(workspace_id);

-- 送信待ち（mail_outbox と同じ考え方。dedupe_key が同じ未送信のものは1件にまとめる）
create table webhook_delivery (
  id          bigserial primary key,
  webhook_id  uuid not null references webhook(id) on delete cascade,
  event       text not null,
  page_id     uuid references page(id) on delete set null,
  detail      jsonb not null default '{}',
  dedupe_key  text,
  created_at  timestamptz not null default now(),
  not_before  timestamptz not null default now(),
  attempts    int not null default 0,
  sent_at     timestamptz,
  skipped     boolean not null default false,
  last_error  text
);
create unique index webhook_delivery_dedupe_uidx on webhook_delivery(dedupe_key) where sent_at is null and not skipped;
create index webhook_delivery_due_idx on webhook_delivery(not_before) where sent_at is null and not skipped;
-- ページ・Webhook の削除（外部キー）と、送信済みの古い記録の削除
create index webhook_delivery_page_idx on webhook_delivery(page_id) where page_id is not null;
create index webhook_delivery_webhook_idx on webhook_delivery(webhook_id);
create index webhook_delivery_done_idx on webhook_delivery(created_at) where sent_at is not null or skipped;

-- 自動化ルール（きっかけ trigger と動作 action はJSON）
create table automation_rule (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspace(id) on delete cascade,
  name         text not null,
  enabled      boolean not null default true,
  trigger      jsonb not null,
  action       jsonb not null,
  created_by   uuid references app_user(id) on delete set null,
  created_at   timestamptz not null default now(),
  last_run_at  timestamptz,
  last_error   text,
  run_count    int not null default 0
);
create index automation_rule_ws_idx on automation_rule(workspace_id);
-- 日付による実行の重複防止（同じ項目・同じ日付には1回だけ）
create table automation_fired (
  rule_id  uuid not null references automation_rule(id) on delete cascade,
  key      text not null,
  fired_at timestamptz not null default now(),
  primary key (rule_id, key)
);

-- 受信用の Webhook（URLに POST すると、データベースの項目・子ページ・コメントを作る）
create table inbound_hook (
  id             uuid primary key default gen_random_uuid(),
  workspace_id   uuid not null references workspace(id) on delete cascade,
  target_page_id uuid not null references page(id) on delete cascade,
  name           text not null,
  mode           text not null check (mode in ('row', 'child', 'comment')),
  token_hash     bytea not null unique,
  enabled        boolean not null default true,
  created_by     uuid references app_user(id) on delete set null,
  created_at     timestamptz not null default now(),
  last_at        timestamptz,
  use_count      int not null default 0
);
create index inbound_hook_ws_idx on inbound_hook(workspace_id);

-- ============================================================ バックアップ

-- 定期バックアップ（ワークスペースごと。Markdown＋添付のZIPをオブジェクトストレージに保存）
create table backup_config (
  workspace_id uuid primary key references workspace(id) on delete cascade,
  enabled      boolean not null default true,
  hour         int not null default 3 check (hour between 0 and 23),
  keep         int not null default 7 check (keep between 1 and 90),
  updated_by   uuid references app_user(id) on delete set null,
  updated_at   timestamptz not null default now()
);
create table backup (
  id           uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspace(id) on delete cascade,
  created_at   timestamptz not null default now(),
  finished_at  timestamptz,
  status       text not null check (status in ('running', 'done', 'failed')),
  s3_key       text,
  size         bigint,
  pages        int,
  attachments  int,
  error        text,
  manual       boolean not null default false
);
create index backup_ws_idx on backup(workspace_id, created_at desc);

-- ============================================================ 外部サービスとの連携

-- 外部サービス（skea・skawau・smohua）との連携の設定（キーと値。秘密の値はアプリで暗号化して入れる）。
-- キーの例: skea.url, skea.client_id, skea.client_secret, skea.project_keys。
-- skiwi が OAuth の認可サーバーになるときのクライアントの登録もここ（キー oauth_client.*。client_secret はハッシュだけ）
create table integration_setting (
  key        text primary key,
  value      text not null,
  updated_by uuid references app_user(id) on delete set null,
  updated_at timestamptz not null default now()
);

-- 外部サービスと連携したユーザーのアクセストークン（AES-GCM で暗号化したもの）
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

-- OAuth の認可コード（skiwi が認可サーバー。docs/skea-integration.md）。
-- ハッシュだけ保存。60秒・一度だけ。client・redirect_uri・ユーザー・code_challenge・scope に結び付ける
create table oauth_code (
  code_hash      bytea primary key,
  client_id      text not null,
  redirect_uri   text not null,
  user_id        uuid not null references app_user(id) on delete cascade,
  code_challenge text not null,
  expires_at     timestamptz not null,
  used_at        timestamptz,
  scope          text not null default 'read'
);
create index oauth_code_expires_idx on oauth_code (expires_at);

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
create index audit_log_action_idx on audit_log(action text_pattern_ops, id);   -- 監査ログの画面で操作の種類（前方一致）で絞る

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
