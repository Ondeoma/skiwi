<script setup lang="ts">
import HelpLink from '../ui/HelpLink.vue'
import { onMounted, ref } from 'vue'
import { copyApi, type AccessStatus, type Role } from '../api'
import { useWorkspaceStore } from '../stores/workspace'

/** 開けないページ（存在しない・閲覧権限がない）。閲覧権限を申請できる（ページの有無は申請者には分からない）。 */
const props = defineProps<{ pid: string }>()
const store = useWorkspaceStore()

const status = ref<AccessStatus | null>(null)
const role = ref<Role>('view')
const message = ref('')
const busy = ref(false)
const error = ref('')
const editing = ref(false)

onMounted(async () => {
  try { status.value = await copyApi.myRequest(props.pid) } catch { status.value = { status: 'none' } }
  if (status.value.role) role.value = status.value.role
})

async function submit() {
  busy.value = true
  error.value = ''
  try {
    status.value = await copyApi.requestAccess(props.pid, role.value, message.value.trim())
    editing.value = false
    if (status.value.status === 'has_access') { await store.reloadPages().catch(() => {}); location.reload() }
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="access" data-testid="access-request">
    <h1>🔒 このページを表示できません <HelpLink to="sharing#閲覧権限の申請" /></h1>
    <p class="muted">ページが存在しないか、閲覧する権限がありません。必要な場合は、権限を申請できます（ページの所有者・編集者に通知されます）。</p>
    <template v-if="status">
      <div v-if="status.status === 'pending' && !editing" class="done" data-testid="access-status">
        ✅ {{ status.role === 'edit' ? '編集' : '閲覧' }}権限を申請しました。承認されると通知が届きます。
        <button class="link" @click="editing = true">申請内容を変更</button>
      </div>
      <form v-else class="form" @submit.prevent="submit">
        <div class="roles">
          <label><input v-model="role" type="radio" value="view" /> 閲覧</label>
          <label><input v-model="role" type="radio" value="edit" data-testid="access-role-edit" /> 編集</label>
        </div>
        <textarea v-model="message" rows="3" maxlength="500" placeholder="申請の理由（任意）。例: 〇〇の作業で手順を確認したいため" data-testid="access-message" />
        <div class="actions">
          <button v-if="editing" type="button" @click="editing = false">やめる</button>
          <button type="submit" class="primary" :disabled="busy" data-testid="access-submit">{{ status.status === 'pending' ? '申請し直す' : '権限を申請する' }}</button>
        </div>
        <p v-if="error" class="error">{{ error }}</p>
      </form>
    </template>
  </section>
</template>

<style scoped>
.access { max-width: 34rem; margin: 4rem auto; padding: 0 1.2rem; }
h1 { font-size: 1.35rem; }
.muted { font-size: .9rem; line-height: 1.6; }
.form { margin-top: 1rem; display: flex; flex-direction: column; gap: .6rem; }
.roles { display: flex; gap: 1.2rem; font-size: .9rem; }
.roles label { display: flex; gap: .3rem; align-items: center; }
textarea { font: inherit; padding: .5rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; resize: vertical; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; }
.primary:disabled { opacity: .6; }
.done { margin-top: 1rem; padding: .7rem .9rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); font-size: .9rem; }
button.link { border: none; background: none; color: var(--accent); padding: 0 .3rem; font-size: .85rem; }
.error { font-size: .85rem; }
</style>
