-- コメントと @メンション

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

-- 本文中のメンション（本文の保存時に作り直す。新しく増えた人にだけ通知する）
create table page_mention (
  page_id uuid not null references page(id) on delete cascade,
  user_id uuid not null references app_user(id) on delete cascade,
  primary key (page_id, user_id)
);

-- 通知の種類を追加: mention（メンションされた）、comment（コメント・返信）
alter table notification drop constraint notification_kind_check;
alter table notification add constraint notification_kind_check
  check (kind in ('page_edited', 'shared', 'shared_workspace', 'mention', 'comment'));
alter table mail_outbox drop constraint mail_outbox_kind_check;
alter table mail_outbox add constraint mail_outbox_kind_check
  check (kind in ('page_edited', 'shared', 'mention', 'comment'));
