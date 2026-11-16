<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { useEventListener } from '../ui/composables'
import { skawau, skea } from '../ui/integration'
import { cycleTheme, themeIcons, themeLabels, themeMode } from '../ui/theme'
import UserAvatar from '../ui/UserAvatar.vue'
import AvatarDialog from './AvatarDialog.vue'
import IntegrationConnect from './IntegrationConnect.vue'

/** 画面上部のヘッダ。左にサイドメニューを開くボタンとロゴ、右に自分のアイコン（押すとメニュー）。 */
defineProps<{ sideOpen: boolean; shortcut: string }>()
const emit = defineEmits<{ openSide: [] }>()
const store = useWorkspaceStore()

const menuOpen = ref(false)
const avatarOpen = ref(false)
const menuWrap = ref<HTMLElement | null>(null)
const anyIntegration = computed(() => !!(skea.status.value?.enabled || skawau.status.value?.enabled))
useEventListener(document, 'mousedown', (e) => {
  if (menuOpen.value && !menuWrap.value?.contains(e.target as Node)) menuOpen.value = false
})
useEventListener<KeyboardEvent>(document, 'keydown', (e) => { if (e.key === 'Escape') menuOpen.value = false })

async function logout() {
  await api.logout()
  location.href = '/'
}
</script>

<template>
  <header class="app-header" data-testid="app-header">
    <button v-if="!sideOpen" class="side-open" :title="`サイドメニューを開く（${shortcut}）`" data-testid="side-open" @click="emit('openSide')">☰</button>
    <router-link to="/" class="brand" title="skiwi">
      <img src="/logo.svg" alt="" width="28" height="28" />
      <span>skiwi</span>
    </router-link>
    <IntegrationConnect part="notice" />
    <div ref="menuWrap" class="user-wrap">
      <button class="user" :aria-expanded="menuOpen" aria-haspopup="menu" data-testid="user-menu"
              :title="store.me?.email ? `${store.me?.name}（${store.me.email}）` : store.me?.name" @click="menuOpen = !menuOpen">
        <UserAvatar :name="store.me?.name" :url="store.me?.avatarUrl" :size="28" data-testid="me-avatar" />
      </button>
      <div v-if="menuOpen" class="menu" role="menu" @click="menuOpen = false">
        <div class="who">
          <UserAvatar :name="store.me?.name" :url="store.me?.avatarUrl" :size="36" />
          <div class="who-text">
            <b>{{ store.me?.name }}</b>
            <span v-if="store.me?.email">{{ store.me.email }}</span>
          </div>
        </div>
        <button role="menuitem" data-testid="avatar-open" @click="avatarOpen = true">🖼 アイコンを変更</button>
        <button role="menuitem" data-testid="theme-toggle" :data-mode="themeMode" @click.stop="cycleTheme">
          {{ themeIcons[themeMode] }} 表示: {{ themeLabels[themeMode] }}
        </button>
        <router-link role="menuitem" to="/help" data-testid="open-help">📖 使い方</router-link>
        <template v-if="anyIntegration">
          <hr />
          <IntegrationConnect part="links" />
        </template>
        <hr />
        <router-link role="menuitem" to="/settings/tokens">🔑 APIトークン</router-link>
        <router-link role="menuitem" to="/settings/api">📘 API の説明書</router-link>
        <template v-if="store.me?.isAdmin">
          <hr />
          <p class="group">管理者</p>
          <router-link role="menuitem" to="/admin/audit">📋 監査ログ</router-link>
          <router-link role="menuitem" to="/admin/shares">🌐 公開中のページ</router-link>
          <router-link role="menuitem" to="/admin/integrations" data-testid="admin-integrations">🔗 外部連携</router-link>
        </template>
        <hr />
        <button role="menuitem" data-testid="logout" @click="logout">ログアウト</button>
      </div>
    </div>
    <Teleport to="body"><AvatarDialog v-if="avatarOpen" @close="avatarOpen = false" /></Teleport>
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
.menu { position: absolute; top: calc(100% + 6px); right: 0; width: max-content; min-width: 220px; max-width: calc(100vw - 1rem); max-height: calc(100vh - 60px); overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 10px 28px rgba(0,0,0,.2); display: flex; flex-direction: column; }
.menu :deep(a), .menu button { text-align: left; border: none; padding: .35rem .6rem; border-radius: 6px; font-size: .86rem; color: var(--fg); text-decoration: none; background: none; white-space: nowrap; }
.menu :deep(a:hover), .menu button:hover { background: var(--hover); }
.menu hr { border: none; border-top: 1px solid var(--line); margin: 4px 0; width: 100%; }
.who { display: flex; align-items: center; gap: .6rem; padding: .4rem .6rem .5rem; }
.who-text { display: flex; flex-direction: column; min-width: 0; font-size: .86rem; }
.who-text span { font-size: .74rem; color: var(--muted); overflow: hidden; text-overflow: ellipsis; }
.group { margin: .2rem .6rem 0; font-size: .74rem; color: var(--muted); }
@media (max-width: 768px) {
  .app-header { padding: 0 .5rem; }
  .brand span { display: none; }
}
</style>
