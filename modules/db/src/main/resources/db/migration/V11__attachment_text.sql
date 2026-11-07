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
