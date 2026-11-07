<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import HelpLink from '../ui/HelpLink.vue'
import { onMounted, ref } from 'vue'
import { api4, formatDate, lockApi, type DirectoryEntry, type PageLockState } from '../api'
import { confirmDialog } from '../ui/dialog'
import { useDebouncedSearch } from '../ui/composables'

/** ページの保護: 保護中は、保護した人・所有者・ここで指定した人だけが編集できる（ほかの編集者は閲覧のみ） */
const props = defineProps<{ pageId: string; title: string; ownerName?: string | null }>()
const emit = defineEmits<{ close: []; changed: [locked: boolean] }>()
const st = ref<PageLockState | null>(null)
const reason = ref('')
const editors = ref<{ id: string; name: string }[]>([])
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  try {
    st.value = await lockApi.get(props.pageId)
    reason.value = st.value.reason ?? ''
    editors.value = [...st.value.editors]
  } catch (e) { error.value = (e as Error).message }
})

// 編集できる人の追加（ユーザーのみ）
const q = ref('')
const found = ref<DirectoryEntry[]>([])
const search = useDebouncedSearch(250)
function onSearch() {
  search.run(async (latest) => {
    const r = q.value.trim() ? (await api4.directory(q.value.trim()).catch(() => [])).filter((d) => d.principalType === 'user') : []
    if (latest()) found.value = r
  })
}
function addEditor(d: DirectoryEntry) {
  if (!editors.value.some((e) => e.id === d.id)) editors.value.push({ id: d.id, name: d.name })
  q.value = ''
  found.value = []
}

async function save() {
  busy.value = true
  error.value = ''
  try {
    st.value = await lockApi.set(props.pageId, { reason: reason.value.trim() || null, editors: editors.value.map((e) => e.id) })
    emit('changed', true)
    emit('close')
  } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
async function unlock() {
  if (!(await confirmDialog(`「${props.title || '無題'}」の保護を解除しますか？\n編集権限のある人は、また編集できるようになります。`, { okLabel: '解除する' }))) return
  busy.value = true
  try { await lockApi.unlock(props.pageId); emit('changed', false); emit('close') } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
</script>

<template>
  <ModalDialog label="ページの保護" testid="lock-dialog" :width="520" :max-height="80" :top="10" @close="emit('close')">
    <template #title>🔒 ページの保護 <HelpLink to="sharing#ページの保護" /></template>
    <p class="note">保護すると、保護した人・所有者・下で指定した人だけが編集できます。ほかの編集者は閲覧のみになります（共有設定はそのまま）。規程や確定した手順書の誤編集を防げます。</p>
    <p v-if="error" class="error">{{ error }}</p>
    <template v-if="st">
      <p v-if="st.locked" class="state" data-testid="lock-state">
        {{ formatDate(st.lockedAt!) }} に{{ st.lockedBy ? `${st.lockedBy}さんが` : '' }}保護しました。
      </p>
      <fieldset :disabled="!st.canChange || busy">
        <label class="col">
          <span>理由（ページの上部に表示します）</span>
          <textarea v-model="reason" rows="2" maxlength="500" placeholder="例: 2026年度版として確定。変更は総務部まで" data-testid="lock-reason" />
        </label>
        <div class="col">
          <span>保護中も編集できる人<small>（保護した人{{ ownerName ? `・所有者（${ownerName}）` : '・所有者' }}は常に編集できます）</small></span>
          <ul class="eds">
            <li v-for="(e, i) in editors" :key="e.id" data-testid="lock-editor">👤 {{ e.name }}<button class="x" title="外す" @click="editors.splice(i, 1)">×</button></li>
            <li v-if="!editors.length" class="muted">（指定なし）</li>
          </ul>
          <input v-model="q" placeholder="名前・メールで検索して追加" data-testid="lock-editor-search" @input="onSearch" />
          <ul v-if="found.length" class="found">
            <li v-for="d in found.slice(0, 8)" :key="d.id"><button data-testid="lock-editor-add" @click="addEditor(d)">👤 {{ d.name }} <small class="muted">{{ d.detail }}</small></button></li>
          </ul>
        </div>
      </fieldset>
      <p v-if="!st.canChange" class="muted">保護の設定・解除は、保護中も編集できる人と管理者だけができます。</p>
      <div class="actions">
        <button v-if="st.locked && st.canChange" class="danger" :disabled="busy" data-testid="lock-remove" @click="unlock">保護を解除</button>
        <span class="spacer" />
        <button @click="emit('close')">閉じる</button>
        <button v-if="st.canChange" class="primary" :disabled="busy" data-testid="lock-save" @click="save">{{ st.locked ? '保存' : '保護する' }}</button>
      </div>
    </template>
  </ModalDialog>
</template>

<style scoped>
:deep(.x) { padding: 0 4px; }
.note { line-height: 1.55; }
.state { font-size: .85rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .4rem .6rem; }
fieldset { border: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: .7rem; }
.col { display: flex; flex-direction: column; gap: .3rem; font-size: .85rem; }
.col small { color: var(--muted); font-size: .75rem; }
textarea, input { font: inherit; padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.eds { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: .3rem; }
.eds li { background: var(--hover); border-radius: 12px; padding: 1px 4px 1px 8px; font-size: .82rem; }
.found { list-style: none; margin: 0; padding: 0; border: 1px solid var(--line); border-radius: 6px; }
.found button { width: 100%; text-align: left; border: none; padding: .3rem .5rem; }
.found button:hover { background: var(--hover); }
.actions { display: flex; gap: .4rem; margin-top: 1rem; align-items: center; }
.spacer { flex: 1; }
.danger:hover { color: var(--danger); border-color: var(--danger); }
.muted { font-size: .8rem; }
.error { font-size: .85rem; }
</style>
