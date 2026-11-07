<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { PublicForm } from '../api'

/** フォームの回答画面（/f/:token。ログイン不要。「ログインが必要」のフォームはログインを案内する） */
const props = defineProps<{ token: string }>()
const form = ref<PublicForm | null>(null)
const error = ref('')
const values = ref<Record<string, unknown>>({})
const busy = ref(false)
const done = ref(false)
const fieldErr = ref<Record<string, string>>({})

onMounted(async () => {
  try {
    const r = await fetch(`/api/forms/public/${encodeURIComponent(props.token)}`, { credentials: 'same-origin' })
    if (!r.ok) throw new Error('このフォームは見つからないか、受付を終了しています。')
    form.value = await r.json()
    document.title = `${form.value!.title} - フォーム`
    for (const f of form.value!.fields) values.value[f.prop] = f.type === 'checkbox' ? false : f.type === 'multi_select' ? [] : ''
  } catch (e) { error.value = (e as Error).message }
})

function toggleMulti(prop: string, id: string, on: boolean) {
  const cur = (values.value[prop] as string[]) ?? []
  values.value[prop] = on ? [...cur, id] : cur.filter((x) => x !== id)
}
async function submit() {
  if (!form.value) return
  fieldErr.value = {}
  const out: Record<string, unknown> = {}
  for (const f of form.value.fields) {
    let v = values.value[f.prop]
    if (typeof v === 'string') v = v.trim()
    const empty = v === '' || v === null || v === undefined || (Array.isArray(v) && !v.length) || (f.type === 'checkbox' && !v)
    if (f.required && empty) { fieldErr.value[f.prop] = '入力してください'; continue }
    if (empty) continue
    if (f.type === 'number') { const n = Number(String(v).replace(/,/g, '')); if (!Number.isFinite(n)) { fieldErr.value[f.prop] = '数値を入力してください'; continue } v = n }
    if (f.type === 'url' && !/^(https?:\/\/|mailto:)/i.test(String(v))) { fieldErr.value[f.prop] = 'https:// で始まる URL を入力してください'; continue }
    out[f.prop] = v
  }
  if (Object.keys(fieldErr.value).length) return
  busy.value = true
  error.value = ''
  try {
    const r = await fetch(`/api/forms/public/${encodeURIComponent(props.token)}`, {
      method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ values: out }),
    })
    if (r.status === 429) throw new Error('送信が集中しています。少し待ってからもう一度お試しください。')
    if (r.status === 401) throw new Error('ログインしてから回答してください。')
    if (!r.ok) throw new Error('送信できませんでした。入力内容を確かめてください。')
    done.value = true
  } catch (e) { error.value = (e as Error).message } finally { busy.value = false }
}
/** ログインしたら、このフォームに戻る（App が受け取る） */
function rememberReturn() { try { sessionStorage.setItem('skiwi.returnTo', `/f/${props.token}`) } catch { /* 保存できない環境 */ } }
function again() { done.value = false; for (const f of form.value?.fields ?? []) values.value[f.prop] = f.type === 'checkbox' ? false : f.type === 'multi_select' ? [] : '' }
</script>

<template>
  <div class="formpage" data-testid="form-page">
    <div class="card">
      <p v-if="error && !form" class="error" data-testid="form-error">{{ error }}</p>
      <template v-else-if="form">
        <h1>{{ form.title }}</h1>
        <p v-if="form.description" class="desc">{{ form.description }}</p>
        <div v-if="form.requireLogin && !form.loggedIn" class="login">
          このフォームに回答するにはログインが必要です。<a href="/auth/login" @click="rememberReturn">ログインする</a>
        </div>
        <div v-else-if="done" class="done" data-testid="form-done">
          <p>✅ 回答を送信しました。ありがとうございました。</p>
          <button @click="again">もう一度回答する</button>
        </div>
        <form v-else @submit.prevent="submit">
          <p v-if="form.loggedIn && form.userName" class="muted small">{{ form.userName }} として回答します</p>
          <div v-for="f in form.fields" :key="f.prop" class="q" data-testid="form-q">
            <label class="ql">{{ f.label }}<span v-if="f.required" class="reqm">*</span></label>
            <p v-if="f.help" class="help">{{ f.help }}</p>
            <textarea v-if="f.type === 'text'" v-model="values[f.prop] as string" rows="3" :data-testid="`form-input-${f.label}`" />
            <input v-else-if="f.type === 'title'" v-model="values[f.prop] as string" maxlength="500" :data-testid="`form-input-${f.label}`" />
            <input v-else-if="f.type === 'number'" v-model="values[f.prop] as string" inputmode="decimal" :data-testid="`form-input-${f.label}`" />
            <input v-else-if="f.type === 'date'" v-model="values[f.prop] as string" type="date" :data-testid="`form-input-${f.label}`" />
            <input v-else-if="f.type === 'url'" v-model="values[f.prop] as string" type="url" placeholder="https://" :data-testid="`form-input-${f.label}`" />
            <label v-else-if="f.type === 'checkbox'" class="chk"><input v-model="values[f.prop] as boolean" type="checkbox" /> はい</label>
            <select v-else-if="f.type === 'select'" v-model="values[f.prop] as string" :data-testid="`form-input-${f.label}`">
              <option value="">（選んでください）</option>
              <option v-for="o in f.options" :key="o.id" :value="o.id">{{ o.name }}</option>
            </select>
            <div v-else-if="f.type === 'multi_select'" class="multi">
              <label v-for="o in f.options" :key="o.id" class="chk"><input type="checkbox" :checked="(values[f.prop] as string[]).includes(o.id)" @change="toggleMulti(f.prop, o.id, ($event.target as HTMLInputElement).checked)" /> {{ o.name }}</label>
            </div>
            <p v-if="fieldErr[f.prop]" class="error small">{{ fieldErr[f.prop] }}</p>
          </div>
          <p v-if="error" class="error">{{ error }}</p>
          <button type="submit" class="primary" :disabled="busy" data-testid="form-submit">送信</button>
        </form>
      </template>
      <p v-else class="muted">読み込み中…</p>
    </div>
  </div>
</template>

<style scoped>
.formpage { min-height: 100vh; background: var(--side); padding: 2rem 1rem; box-sizing: border-box; }
.card { max-width: 640px; margin: 0 auto; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 1.5rem 1.8rem 2rem; }
h1 { font-size: 1.5rem; margin: 0 0 .4rem; }
.desc { white-space: pre-wrap; color: var(--muted); line-height: 1.7; }
.q { margin: 1.1rem 0; display: flex; flex-direction: column; gap: .3rem; }
.ql { font-weight: 600; }
.reqm { color: var(--danger); margin-left: .2rem; }
.help { margin: 0; font-size: .8rem; color: var(--muted); }
input:not([type=checkbox]), textarea, select { font: inherit; padding: .45rem .6rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: inherit; }
.chk { display: flex; align-items: center; gap: .35rem; }
.multi { display: flex; flex-wrap: wrap; gap: .3rem 1rem; }
.primary { padding: .45rem 1.6rem; font-size: 1rem; margin-top: .5rem; }
.done { padding: 1rem 0; }
.login { padding: 1rem; background: var(--side); border-radius: 8px; }
.login a { color: var(--accent); }
.small { font-size: .8rem; }
</style>
