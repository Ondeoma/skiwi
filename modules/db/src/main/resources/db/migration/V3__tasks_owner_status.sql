-- ページの所有者・状態
alter table page add column owner_id uuid references app_user(id) on delete set null;
alter table page add column status text check (status in ('draft', 'review', 'published', 'deprecated'));
create index page_owner_idx on page(owner_id) where owner_id is not null;

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

-- 期限の近いToDoの通知
alter table notification drop constraint notification_kind_check;
alter table notification add constraint notification_kind_check
  check (kind in ('page_edited', 'shared', 'shared_workspace', 'mention', 'comment', 'task_due'));
alter table mail_outbox drop constraint mail_outbox_kind_check;
alter table mail_outbox add constraint mail_outbox_kind_check
  check (kind in ('page_edited', 'shared', 'mention', 'comment', 'task_due'));
