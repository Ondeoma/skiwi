-- ウォッチの細分化: 本文の更新・コメントのどちらを受け取るか、子ページも含めるか
alter table page_watch add column edits    boolean not null default true;
alter table page_watch add column comments boolean not null default true;
alter table page_watch add column subtree  boolean not null default false;
create index page_watch_subtree_idx on page_watch(page_id) where subtree;

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

-- ゲスト画面に「更新履歴」（公開日時と変更点のメモ）を出す
alter table page_share add column show_releases boolean not null default false;

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

-- データベース（子ページを項目として、表・ボード・カレンダーで見る）
create table page_database (
  page_id    uuid primary key references page(id) on delete cascade,
  props      jsonb not null default '[]',
  views      jsonb not null default '[]',
  updated_at timestamptz not null default now()
);
-- 項目（データベースの子ページ）の値。キーは列のID
alter table page add column props jsonb not null default '{}';
