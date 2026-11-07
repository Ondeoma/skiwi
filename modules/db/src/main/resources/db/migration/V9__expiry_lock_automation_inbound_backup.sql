-- 期限付きの権限（期限を過ぎたものは権限の判定に使わず、定期的に消す）
alter table page_acl add column expires_at timestamptz;
alter table workspace_acl add column expires_at timestamptz;
create index page_acl_expires_idx on page_acl(expires_at) where expires_at is not null;
create index workspace_acl_expires_idx on workspace_acl(expires_at) where expires_at is not null;

-- ページの保護（保護中は、保護した人・所有者・指定した人だけが編集できる）
alter table page add column locked_at    timestamptz;
alter table page add column locked_by    uuid references app_user(id) on delete set null;
alter table page add column lock_reason  text;
alter table page add column lock_editors uuid[] not null default '{}';

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

alter table notification drop constraint notification_kind_check;
alter table notification add constraint notification_kind_check
  check (kind in ('page_edited', 'shared', 'shared_workspace', 'mention', 'comment', 'task_due', 'access_request', 'access_granted', 'access_denied', 'automation'));
alter table mail_outbox drop constraint mail_outbox_kind_check;
alter table mail_outbox add constraint mail_outbox_kind_check
  check (kind in ('page_edited', 'shared', 'mention', 'comment', 'task_due', 'access_request', 'access_granted', 'automation'));
