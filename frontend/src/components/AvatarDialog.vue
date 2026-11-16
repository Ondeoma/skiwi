<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { api } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { squareImage } from '../ui/avatar'
import UserAvatar from '../ui/UserAvatar.vue'

/** 自分のアイコンを設定する。選んだ画像は真ん中を正方形に切り抜いて縮め、保存するまでは見本だけを出す。 */
const emit = defineEmits<{ close: [] }>()
const store = useWorkspaceStore()
const fileEl = ref<HTMLInputElement | null>(null)
const picked = ref<Blob | null>(null)
const preview = ref<string | null>(null)
const error = ref('')
const busy = ref(false)

function setPreview(b: Blob | null) {
  if (preview.value) URL.revokeObjectURL(preview.value)
  picked.value = b
  preview.value = b ? URL.createObjectURL(b) : null
}
onBeforeUnmount(() => setPreview(null))

async function onPick(e: Event) {
  const f = (e.target as HTMLInputElement).files?.[0]
  ;(e.target as HTMLInputElement).value = ''
  if (!f) return
  error.value = ''
  try { setPreview(await squareImage(f)) } catch (er) { error.value = (er as Error).message }
}

async function run(op: () => Promise<{ avatarUrl: string | null }>) {
  busy.value = true
  error.value = ''
  try {
    const r = await op()
    if (store.me) store.me.avatarUrl = r.avatarUrl
    emit('close')
  } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
const save = () => picked.value && run(() => api.setAvatar(picked.value!))
const remove = () => run(() => api.deleteAvatar())
</script>

<template>
  <div class="backdrop" @click.self="emit('close')" @keydown.esc="emit('close')">
    <form class="dialog" role="dialog" aria-label="アイコンの設定" data-testid="avatar-dialog" @submit.prevent="save">
      <h2>🖼 アイコンの設定</h2>
      <div class="body">
        <UserAvatar :name="store.me?.name" :url="preview ?? store.me?.avatarUrl" :size="96" data-testid="avatar-preview" />
        <div class="side">
          <button type="button" data-testid="avatar-pick" @click="fileEl?.click()">画像を選ぶ…</button>
          <input ref="fileEl" type="file" accept="image/*" hidden data-testid="avatar-file" @change="onPick" />
          <p class="note">真ん中を正方形に切り抜いて、小さくしてから保存します。アイコンは同じ skiwi を使うほかの人にも見えます。</p>
          <button v-if="store.me?.avatarUrl && !picked" type="button" class="link danger" :disabled="busy" data-testid="avatar-remove" @click="remove">アイコンを外す</button>
        </div>
      </div>
      <p v-if="error" class="error" data-testid="avatar-error">{{ error }}</p>
      <div class="actions">
        <button type="button" @click="emit('close')">キャンセル</button>
        <button type="submit" class="primary" :disabled="busy || !picked" data-testid="avatar-save">{{ busy ? '保存中…' : '保存' }}</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 10vh; z-index: 90; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; width: min(440px, 92vw); padding: 1rem 1.2rem; box-shadow: 0 20px 50px rgba(0,0,0,.25); }
h2 { font-size: 1.05rem; margin: 0 0 .8rem; }
.body { display: flex; gap: 1rem; align-items: flex-start; }
.side { display: flex; flex-direction: column; align-items: flex-start; gap: .4rem; min-width: 0; }
.note { margin: 0; font-size: .8rem; color: var(--muted); }
.link { border: none; background: none; padding: 0; font-size: .82rem; color: var(--muted); }
.link.danger:hover { color: var(--danger); }
.error { font-size: .85rem; }
.actions { display: flex; justify-content: flex-end; gap: .5rem; margin-top: 1rem; }
.primary:disabled { opacity: .6; }
</style>
