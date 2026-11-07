-- 公開リンクの強化（公開版の固定・公開の予約・わかりやすいURL・検索エンジン/OGP）と、閲覧権限の申請

-- ---------------------------------------------------------------- 公開リンク
-- mode: live（編集中の内容をそのまま公開）| pinned（「公開」を押した時点の内容を公開。page_share_pub に保存）
-- starts_at: この日時まではゲストは閲覧できない（公開の予約）
-- slug: わかりやすいURL（/s/<slug>）。トークンのURLも引き続き使える
-- indexable: 検索エンジンに載せる（既定は載せない）。description: 共有したときの説明文（OGP）
alter table page_share add column mode text not null default 'live' check (mode in ('live', 'pinned'));
alter table page_share add column published_at timestamptz;
alter table page_share add column published_by uuid references app_user(id) on delete set null;
alter table page_share add column starts_at timestamptz;
alter table page_share add column slug text unique check (slug ~ '^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$');
alter table page_share add column indexable boolean not null default false;
alter table page_share add column description text;

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

-- ページのURL名（公開ページで /s/<リンク>/<URL名> として使う）。ワークスペース内で重複しない
alter table page add column guest_slug text check (guest_slug ~ '^[a-z0-9][a-z0-9-]{0,62}[a-z0-9]$|^[a-z0-9]$');
create unique index page_guest_slug_uidx on page(workspace_id, guest_slug) where guest_slug is not null;

-- ---------------------------------------------------------------- 閲覧権限の申請
-- page_id は存在しないページでも受け付ける（ページの有無を申請者に知らせないため。外部キーにしない）
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

alter table notification drop constraint notification_kind_check;
alter table notification add constraint notification_kind_check
  check (kind in ('page_edited', 'shared', 'shared_workspace', 'mention', 'comment', 'task_due', 'access_request', 'access_granted', 'access_denied'));
alter table mail_outbox drop constraint mail_outbox_kind_check;
alter table mail_outbox add constraint mail_outbox_kind_check
  check (kind in ('page_edited', 'shared', 'mention', 'comment', 'task_due', 'access_request', 'access_granted'));
