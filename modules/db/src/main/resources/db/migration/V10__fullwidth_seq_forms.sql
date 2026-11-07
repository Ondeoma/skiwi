-- ページを全幅で表示する
alter table page add column full_width boolean not null default false;

-- データベースの項目の連番（ID の列）。次に振る番号
alter table page_database add column next_seq int not null default 1;

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
