-- ユーザーのアイコン（ブラウザで正方形に縮めた小さな画像。1人1枚）
create table user_avatar (
  user_id      uuid primary key references app_user(id) on delete cascade,
  content_type text not null,
  data         bytea not null,
  updated_at   timestamptz not null default now()
);
