-- IdP（sruru など）の OIDC の picture クレームのアイコンの URL。自分で登録したアイコン（user_avatar）がなければこれを使う
alter table app_user add column picture_url text;
