-- ゲスト閲覧ページのサイドメニュー（公開範囲のページ一覧）。auto: 2ページ以上なら表示 / show: 常に表示 / hide: 表示しない
alter table page_share add column sidebar text not null default 'auto' check (sidebar in ('auto', 'show', 'hide'));
