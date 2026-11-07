-- カスタムCSS（ワークスペース・ページ）。
-- ページの css_inherit=true なら子孫ページにも適用する（ワークスペース → 祖先 → そのページ の順に重ねる）。
-- 表示エリアの外に効かないよう、範囲の限定と危険な記述の除去は表示側で行う。
alter table workspace add column custom_css text;
alter table page add column custom_css text;
alter table page add column css_inherit boolean not null default true;
