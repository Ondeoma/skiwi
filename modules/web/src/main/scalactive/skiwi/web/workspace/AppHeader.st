<script lang="scala">
import skiwi.web.Nav
import skiwi.web.{Api, Store, Theme}
import skiwi.web.parts.Avatar

import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue

// 画面上部のヘッダ. 左にサイドメニューを開くボタンとロゴ、右に自分のアイコン（押すとメニュー）.
// メニューは外側のクリック（.menu-bg）・Esc で閉じる.
case class Props(sideOpen: Reactive[Boolean], shortcut: String, onOpenSide: Unit => Unit)

case class State(menuOpen: RV[Boolean] = RV(false), avatarOpen: RV[Boolean] = RV(false), ls: Listeners = new Listeners)

def initState() = State()

def mounted(s: State): Unit =
  s.ls.on[org.scalajs.dom.KeyboardEvent](org.scalajs.dom.document, "keydown")(e => if (e.key == "Escape") s.menuOpen := false)

def unmounted(s: State): Unit = s.ls.clear()

def logout(): Unit = Api.logout().onComplete(r => org.scalajs.dom.window.location.href = r.getOrElse("/"))

def meName = Store.me.v.map(_.name).getOrElse("")

def meUrl = Store.me.v.flatMap(_.avatarUrl)

def userTitle: String = Store.me.v.fold("")(m => m.email.fold(m.name)(e => s"${m.name}（$e）"))

def themeLabel = s"${Theme.icons(Theme.mode.v)} 表示: ${Theme.labels(Theme.mode.v)}"

def onThemeClick(e: org.scalajs.dom.Event): Unit = {
  e.stopPropagation()
  Theme.cycle()
}
</script>

<template>
  <header class="app-header" data-testid="app-header">
    <button v-if="!props.sideOpen.v" class="side-open" :title='s"サイドメニューを開く（${props.shortcut}）"' data-testid="side-open" @click="props.onOpenSide(())">☰</button>
    <a href="/" class="brand" title="skiwi" @click='Nav.link(event, "/")'>
      <img src="/logo.svg" alt="" width="28" height="28">
      <span>skiwi</span>
    </a>
    <IntegrationConnect part="notice" />
    <div class="user-wrap">
      <button class="user" :aria-expanded="s.menuOpen.v.toString" aria-haspopup="menu" data-testid="user-menu" :title="userTitle" @click="s.menuOpen := !s.menuOpen.v">
        <Avatar :name="meName" :url="meUrl" :size="28" testid="me-avatar" />
      </button>
      <div v-if="s.menuOpen.v" class="menu-bg" @click="s.menuOpen := false"></div>
      <div v-if="s.menuOpen.v" class="menu" role="menu" @click="s.menuOpen := false">
        <div class="who">
          <Avatar :name="meName" :url="meUrl" :size="36" />
          <div class="who-text">
            <b>{{ meName }}</b>
            <span v-if="Store.me.v.exists(_.email.isDefined)">{{ Store.me.v.flatMap(_.email).getOrElse("") }}</span>
          </div>
        </div>
        <button role="menuitem" data-testid="avatar-open" @click="s.avatarOpen := true">🖼 アイコンを変更</button>
        <button role="menuitem" data-testid="theme-toggle" :data-mode="Theme.mode.v" @click="onThemeClick(event)">{{ themeLabel }}</button>
        <a role="menuitem" href="/help" data-testid="open-help" @click='Nav.link(event, "/help")'>📖 使い方</a>
        <template v-if="Integrations.any">
          <hr>
          <IntegrationConnect part="links" />
        </template>
        <hr>
        <a role="menuitem" href="/settings/tokens" @click='Nav.link(event, "/settings/tokens")'>🔑 APIトークン</a>
        <a role="menuitem" href="/settings/api" @click='Nav.link(event, "/settings/api")'>📘 API の説明書</a>
        <template v-if="Store.me.v.exists(_.isAdmin)">
          <hr>
          <p class="group">管理者</p>
          <a role="menuitem" href="/admin/audit" @click='Nav.link(event, "/admin/audit")'>📋 監査ログ</a>
          <a role="menuitem" href="/admin/shares" @click='Nav.link(event, "/admin/shares")'>🌐 公開中のページ</a>
          <a role="menuitem" href="/admin/integrations" data-testid="admin-integrations" @click='Nav.link(event, "/admin/integrations")'>🔗 外部連携</a>
          <a role="menuitem" href="/admin/agents" data-testid="admin-agents" @click='Nav.link(event, "/admin/agents")'>🤖 エージェント</a>
        </template>
        <hr>
        <button role="menuitem" data-testid="logout" @click="logout()">ログアウト</button>
      </div>
    </div>
    <Teleport to="body"><AvatarDialog v-if="s.avatarOpen.v" :on-close="(_: Unit) => s.avatarOpen := false" /></Teleport>
  </header>
</template>

<style scoped>
.app-header { flex: none; height: 44px; display: flex; align-items: center; gap: .5rem; padding: 0 .8rem; border-bottom: 1px solid var(--line); background: var(--side); position: relative; z-index: 65; }
.side-open { width: 30px; height: 28px; padding: 0; color: var(--muted); background: var(--bg); }
.side-open:hover { color: var(--fg); }
.brand { display: inline-flex; align-items: center; gap: .45rem; color: var(--fg); text-decoration: none; font-weight: 700; font-size: 1.05rem; }
.brand img { display: block; border-radius: 7px; }
.user-wrap { position: relative; margin-left: auto; }
.user { display: grid; place-items: center; border: none; border-radius: 50%; padding: 2px; background: none; }
.user:hover, .user[aria-expanded="true"] { background: var(--hover); }
.menu { position: absolute; top: calc(100% + 6px); right: 0; z-index: 200; width: max-content; min-width: 220px; max-width: calc(100vw - 1rem); max-height: calc(100vh - 60px); overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 10px 28px rgba(0,0,0,.2); display: flex; flex-direction: column; }
.menu a, .menu button { text-align: left; border: none; padding: .35rem .6rem; border-radius: 6px; font-size: .86rem; color: var(--fg); text-decoration: none; background: none; white-space: nowrap; }
.menu a:hover, .menu button:hover { background: var(--hover); }
.menu hr { border: none; border-top: 1px solid var(--line); margin: 4px 0; width: 100%; }
.who { display: flex; align-items: center; gap: .6rem; padding: .4rem .6rem .5rem; }
.who-text { display: flex; flex-direction: column; min-width: 0; font-size: .86rem; }
.who-text span { font-size: .74rem; color: var(--muted); overflow: hidden; text-overflow: ellipsis; }
.group { margin: .2rem .6rem 0; font-size: .74rem; color: var(--muted); }
@media (max-width: 768px) {
  .app-header { padding: 0 .5rem; }
  .brand span { display: none; }
}
@media print { .app-header { display: none !important; } }
</style>
