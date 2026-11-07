-- ゲスト閲覧時の表示
-- 公開リンクごと: 上部のヘッダー（「公開ページ」「ログイン」）を出さない
alter table page_share add column hide_header boolean not null default false;

-- 本文の上下に入れるHTML（ゲスト閲覧時のみ。サンドボックス化した iframe の中で表示する）。
-- ページの guest_html_inherit=true なら子孫ページにも入れる（上: ワークスペース → 祖先 → そのページ、下: その逆順）
alter table workspace add column guest_top_html text;
alter table workspace add column guest_bottom_html text;
alter table page add column guest_top_html text;
alter table page add column guest_bottom_html text;
alter table page add column guest_html_inherit boolean not null default true;
