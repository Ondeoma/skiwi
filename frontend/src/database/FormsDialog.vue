<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import { computed, onMounted, ref } from 'vue'
import { formApi, formatDate, type DbFormDef, type DbProp, type FormField } from '../api'
import { confirmDialog } from '../ui/dialog'
import HelpLink from '../ui/HelpLink.vue'
import { TYPE_ICON } from './db'
import { useCopy } from '../ui/composables'

/** データベースのフォーム: 回答がこのデータベースの項目になる。URL を知っている人が回答できる（ログインを求めることもできる） */
const props = defineProps<{ pageId: string; props: DbProp[] }>()
const emit = defineEmits<{ close: [] }>()
const forms = ref<DbFormDef[] | null>(null)
const error = ref('')
async function load() { try { forms.value = await formApi.list(props.pageId) } catch (e) { error.value = (e as Error).message } }
onMounted(load)

const ANSWERABLE = ['text', 'number', 'select', 'multi_select', 'date', 'checkbox', 'url']
const answerable = computed(() => props.props.filter((p) => ANSWERABLE.includes(p.type)))
const personProps = computed(() => props.props.filter((p) => p.type === 'person'))

// --- 作成・編集 ---
const editing = ref<string | null>(null)
const open = ref(false)
const title = ref('')
const description = ref('')
const fields = ref<FormField[]>([])
const requireLogin = ref(false)
const respondentProp = ref<string | null>(null)
const enabled = ref(true)
function startNew() {
  editing.value = null
  title.value = 'お問い合わせ'
  description.value = ''
  fields.value = [{ prop: 'title', label: '件名', required: true }, ...answerable.value.slice(0, 4).map((p) => ({ prop: p.id, required: false }))]
  requireLogin.value = false
  respondentProp.value = null
  enabled.value = true
  open.value = true
}
function startEdit(f: DbFormDef) {
  editing.value = f.id
  title.value = f.title
  description.value = f.description ?? ''
  fields.value = f.fields.map((x) => ({ ...x }))
  requireLogin.value = f.requireLogin
  respondentProp.value = f.respondentProp
  enabled.value = f.enabled
  open.value = true
}
const has = (id: string) => fields.value.some((f) => f.prop === id)
function toggleField(id: string, on: boolean) {
  fields.value = on ? [...fields.value, { prop: id, required: false }] : fields.value.filter((f) => f.prop !== id)
}
function move(i: number, d: -1 | 1) {
  const j = i + d
  if (j < 0 || j >= fields.value.length) return
  const fs = [...fields.value]
  ;[fs[i], fs[j]] = [fs[j], fs[i]]
  fields.value = fs
}
const nameOf = (id: string) => (id === 'title' ? '名前（項目の名前）' : props.props.find((p) => p.id === id)?.name ?? id)
const iconOf = (id: string) => (id === 'title' ? '≡' : TYPE_ICON[props.props.find((p) => p.id === id)?.type ?? 'text'])
async function save() {
  error.value = ''
  const body = { title: title.value.trim(), description: description.value.trim() || null, fields: fields.value, requireLogin: requireLogin.value,
    respondentProp: requireLogin.value ? respondentProp.value : null, enabled: enabled.value }
  try {
    if (editing.value) await formApi.update(editing.value, body)
    else await formApi.create(props.pageId, body)
    open.value = false
    await load()
  } catch (e) { error.value = (e as Error).message }
}
async function remove(f: DbFormDef) {
  if (await confirmDialog(`フォーム「${f.title}」を削除しますか？（集まった回答＝項目は残ります）`, { okLabel: '削除', danger: true })) { await formApi.remove(f.id); await load() }
}
const { copied, copy: copyText } = useCopy<string>({ flashOnError: true })
const copy = (f: DbFormDef) => copyText(f.url, f.id)
</script>

<template>
  <ModalDialog label="フォーム" testid="forms" :width="620" @close="emit('close')">
    <template #title>📝 フォーム <HelpLink to="database#フォーム" /></template>
    <p class="note">回答はこのデータベースの項目になります（フォームを作った人の権限で作ります）。URL を知っている人は誰でも回答できます。ログインを求めると、回答者をユーザーの列に記録できます。</p>
    <p v-if="error" class="error" data-testid="form-error">{{ error }}</p>
    <ul v-if="forms && !open" class="list">
      <li v-if="!forms.length" class="muted">まだフォームがありません。</li>
      <li v-for="f in forms" :key="f.id" class="item" :class="{ off: !f.enabled }" data-testid="form-item">
        <div class="h"><b>{{ f.title }}</b><span v-if="!f.enabled" class="tag">停止中</span><span v-if="f.requireLogin" class="tag">ログインが必要</span>
          <span class="spacer" /><button class="link" @click="startEdit(f)">編集</button><button class="link danger" @click="remove(f)">削除</button></div>
        <div class="url"><a :href="f.url" target="_blank" rel="noopener" data-testid="form-url">{{ f.url }}</a> <button class="link" @click="copy(f)">{{ copied === f.id ? 'コピーしました' : 'コピー' }}</button></div>
        <div class="muted small">{{ f.useCount }}件の回答{{ f.lastAt ? `・最後の回答 ${formatDate(f.lastAt)}` : '' }}</div>
      </li>
    </ul>
    <form v-if="open" class="form" data-testid="form-edit" @submit.prevent="save">
      <label class="col"><span>フォームの名前</span><input v-model="title" maxlength="200" required data-testid="form-title" /></label>
      <label class="col"><span>説明（回答者に表示）</span><textarea v-model="description" rows="2" maxlength="4000" /></label>
      <div class="col">
        <span>質問（上から順に表示）</span>
        <div v-for="(f, i) in fields" :key="f.prop" class="field" data-testid="form-field">
          <span class="ic">{{ iconOf(f.prop) }}</span>
          <input v-model="f.label" :placeholder="nameOf(f.prop)" class="lbl" maxlength="200" />
          <label class="req"><input v-model="f.required" type="checkbox" /> 必須</label>
          <button type="button" class="ghost" @click="move(i, -1)">↑</button><button type="button" class="ghost" @click="move(i, 1)">↓</button>
          <button type="button" class="ghost" title="外す" @click="toggleField(f.prop, false)">×</button>
        </div>
        <div class="add">
          追加:
          <button v-if="!has('title')" type="button" class="link" @click="toggleField('title', true)">≡ 名前</button>
          <button v-for="p in answerable.filter((x) => !has(x.id))" :key="p.id" type="button" class="link" :data-testid="`form-add-${p.name}`" @click="toggleField(p.id, true)">{{ TYPE_ICON[p.type] }} {{ p.name }}</button>
        </div>
        <span class="muted small">回答できる列: テキスト・数値・選択・複数選択・日付・チェック・URL</span>
      </div>
      <label class="row"><input v-model="requireLogin" type="checkbox" data-testid="form-login" /> 回答にはログインが必要</label>
      <label v-if="requireLogin && personProps.length" class="row">回答者を記録する列:
        <select v-model="respondentProp"><option :value="null">（記録しない）</option><option v-for="p in personProps" :key="p.id" :value="p.id">{{ p.name }}</option></select>
      </label>
      <label v-if="editing" class="row"><input v-model="enabled" type="checkbox" /> 回答を受け付ける</label>
      <div class="acts"><button type="button" @click="open = false">やめる</button><button type="submit" class="primary" data-testid="form-save">{{ editing ? '保存' : '作成' }}</button></div>
    </form>
    <button v-else class="addbtn" data-testid="form-add" @click="startNew">＋ フォームを作る</button>
  </ModalDialog>
</template>

<style scoped>
.note { line-height: 1.55; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.item { border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin-bottom: .45rem; font-size: .85rem; }
.item.off { opacity: .6; }
.h { display: flex; align-items: center; gap: .4rem; }
.tag { font-size: .7rem; border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; color: var(--muted); }
.spacer { flex: 1; }
.url { margin: .2rem 0; word-break: break-all; font-size: .8rem; }
.url a { color: var(--accent); }
.link { border: none; background: none; color: var(--accent); padding: 0 .3rem; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .6rem; font-size: .85rem; }
.col { display: flex; flex-direction: column; gap: .3rem; }
.col > span { font-size: .78rem; color: var(--muted); }
.row { display: flex; align-items: center; gap: .4rem; }
input:not([type=checkbox]), textarea, select { font: inherit; padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.field { display: flex; align-items: center; gap: .35rem; }
.field .ic { width: 1.4rem; text-align: center; color: var(--muted); }
.field .lbl { flex: 1; min-width: 0; }
.req { font-size: .78rem; white-space: nowrap; display: flex; align-items: center; gap: .2rem; }
.ghost { border: none; background: none; color: var(--muted); padding: 0 4px; }
.add { display: flex; flex-wrap: wrap; align-items: center; gap: .2rem; font-size: .78rem; color: var(--muted); }
.acts { display: flex; justify-content: flex-end; gap: .4rem; }
.addbtn { margin-top: .3rem; }
.error { font-size: .82rem; }
</style>
