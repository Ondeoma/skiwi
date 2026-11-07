<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import HelpLink from '../ui/HelpLink.vue'
import { computed, onMounted, ref } from 'vue'
import { formatDate, inboundApi, type InboundHook } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { confirmDialog } from '../ui/dialog'
import { openPagePicker } from '../ui/pagePicker'
import { useCopy } from '../ui/composables'

/** 受信用の Webhook: URL に POST すると、データベースの項目・子ページ・コメントを作る（作った人の権限で） */
const props = defineProps<{ wid: string; name: string }>()
const emit = defineEmits<{ close: [] }>()
const store = useWorkspaceStore()
const hooks = ref<InboundHook[] | null>(null)
const error = ref('')
const shown = ref<{ id: string; url: string } | null>(null)
// コピーできなくても表示する（URLは画面にも出ている）
const { copied, copy } = useCopy({ flashOnError: true })
async function load() {
  try { hooks.value = await inboundApi.list(props.wid) } catch (e) { error.value = (e as Error).message }
}
onMounted(load)

const MODE = { row: 'データベースに項目を追加', child: '子ページを作る', comment: 'コメントを付ける' } as const
const adding = ref(false)
const form = ref<{ name: string; mode: InboundHook['mode']; target: { id: string; title: string } | null }>({ name: '', mode: 'row', target: null })
const targetIsDb = computed(() => !!form.value.target && !!store.byId.get(form.value.target!.id)?.database)
async function pickTarget() {
  const p = await openPagePicker()
  if (!p) return
  if (p.workspaceId !== props.wid) { error.value = 'このワークスペースのページを選んでください'; return }
  form.value.target = { id: p.id, title: p.title }
}
const message = (e: unknown) => {
  const m = (e as Error).message
  return m === 'target is not a database' ? '「データベースに項目を追加」には、データベースのページを選んでください'
    : m === 'insufficient permission' ? '受け取り先のページの編集権限がありません' : m
}
async function create() {
  if (!form.value.target) { error.value = '受け取り先のページを選んでください'; return }
  error.value = ''
  try {
    const h = await inboundApi.create(props.wid, { name: form.value.name.trim() || MODE[form.value.mode], targetPageId: form.value.target.id, mode: form.value.mode })
    if (h.url) shown.value = { id: h.id, url: h.url }
    adding.value = false
    form.value = { name: '', mode: 'row', target: null }
    await load()
  } catch (e) { error.value = message(e) }
}
async function rotate(h: InboundHook) {
  if (!(await confirmDialog(`「${h.name}」のURLを作り直しますか？\n今のURLは使えなくなります。`, { okLabel: '作り直す', danger: true }))) return
  try { const r = await inboundApi.rotate(h.id); if (r.url) shown.value = { id: h.id, url: r.url } } catch (e) { error.value = message(e) }
}
async function toggle(h: InboundHook) { try { await inboundApi.update(h.id, { enabled: !h.enabled }); await load() } catch (e) { error.value = message(e) } }
async function remove(h: InboundHook) {
  if (await confirmDialog(`「${h.name}」を削除しますか？\nこのURLへの送信は受け付けなくなります。`, { okLabel: '削除', danger: true })) {
    try { await inboundApi.remove(h.id); await load() } catch (e) { error.value = message(e) }
  }
}
const sample = (h: InboundHook | undefined, url: string) => {
  const body = h?.mode === 'comment' ? { text: 'お問い合わせがありました' }
    : h?.mode === 'row' ? { title: 'A社 問い合わせ', text: '本文（改行で段落）', values: { 状態: '未着手', 期限: '2026-12-01' } }
    : { title: '新しいページ', text: '本文（改行で段落）' }
  return `curl -X POST '${url}' \\\n  -H 'Content-Type: application/json' \\\n  -d '${JSON.stringify(body)}'`
}
</script>

<template>
  <ModalDialog label="受信用の Webhook" testid="inbound" :width="660" @close="emit('close')">
    <template #title>📥 受信用の Webhook — {{ name }} <HelpLink to="automation#受信用の-webhook外からページを作る" /></template>
    <p class="note">URL に JSON を POST すると、データベースに項目を追加したり、子ページ・コメントを作ったりします。フォーム・監視ツール・問い合わせ窓口の通知を skiwi に集められます。
      作った人の権限で動きます（その人が受け取り先を編集できなくなると止まります）。1分に60件まで。</p>
    <p v-if="error" class="error" data-testid="inbound-error">{{ error }}</p>

    <div v-if="shown" class="url" data-testid="inbound-url">
      <b>URL（この画面でしか表示しません。知っている人は誰でも送れるので、大切に扱ってください）</b>
      <code>{{ shown.url }}</code>
      <button @click="copy(shown.url)">{{ copied ? 'コピーしました' : 'URLをコピー' }}</button>
      <details><summary>送り方の例（curl）</summary><pre>{{ sample(hooks?.find((h) => h.id === shown!.id), shown.url) }}</pre></details>
      <button class="link" @click="shown = null">閉じる</button>
    </div>

    <ul v-if="hooks" class="list">
      <li v-if="!hooks.length && !adding" class="muted">まだ設定がありません。</li>
      <li v-for="h in hooks" :key="h.id" class="hook" :class="{ off: !h.enabled }" data-testid="inbound-item">
        <div class="h-head">
          <label class="en"><input type="checkbox" :checked="h.enabled" @change="toggle(h)" /></label>
          <b>{{ h.name }}</b><span class="tag">{{ MODE[h.mode] }}</span>
          <span class="spacer" />
          <button class="link" data-testid="inbound-rotate" @click="rotate(h)">URLを作り直す</button>
          <button class="link danger" @click="remove(h)">削除</button>
        </div>
        <div class="muted small">
          受け取り先: <router-link :to="`/w/${wid}/p/${h.targetPageId}`" @click="emit('close')">{{ h.targetTitle || '（見られないページ）' }}</router-link>
          ・ 作成: {{ h.createdBy ?? '—' }}
          ・ {{ h.lastAt ? `最後の受信 ${formatDate(h.lastAt)}（${h.useCount}件）` : 'まだ受信していません' }}
        </div>
      </li>
    </ul>

    <form v-if="adding" class="form" data-testid="inbound-form" @submit.prevent="create">
      <div class="modes">
        <label v-for="(l, m) in MODE" :key="m"><input v-model="form.mode" type="radio" :value="m" :data-testid="`inbound-mode-${m}`" /> {{ l }}</label>
      </div>
      <div class="row">
        受け取り先: <b>{{ form.target ? form.target.title || '無題' : '（未選択）' }}</b>
        <button type="button" data-testid="inbound-target" @click="pickTarget">ページを選ぶ</button>
        <span v-if="form.mode === 'row' && form.target && !targetIsDb" class="error">データベースのページではありません</span>
      </div>
      <input v-model="form.name" maxlength="100" placeholder="名前（例: 問い合わせフォーム）" data-testid="inbound-name" />
      <p class="hint" v-if="form.mode === 'row'">送る内容: <code>{ "title": "…", "text": "本文", "values": { "列名": 値 } }</code>（選択肢は名前、日付は 2026-12-01、ユーザーはメールアドレスでも可）</p>
      <p class="hint" v-else-if="form.mode === 'child'">送る内容: <code>{ "title": "…", "text": "本文" }</code></p>
      <p class="hint" v-else>送る内容: <code>{ "text": "コメント" }</code>（Slack 形式の通知もそのまま受け付けます）</p>
      <div class="f-acts">
        <button type="button" @click="adding = false">やめる</button>
        <button type="submit" class="primary" data-testid="inbound-save">作成</button>
      </div>
    </form>
    <button v-else class="add" data-testid="inbound-add" @click="adding = true">＋ 受信用の URL を作る</button>
  </ModalDialog>
</template>

<style scoped>
:deep(.x) { padding: 0 4px; }
.note, .hint { font-size: .78rem; color: var(--muted); line-height: 1.55; }
.url { font-size: .82rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .5rem .7rem; margin: .4rem 0; display: flex; flex-direction: column; gap: .35rem; align-items: flex-start; }
.url code { word-break: break-all; }
pre { font-size: .75rem; white-space: pre-wrap; word-break: break-all; background: var(--bg); padding: .4rem; border-radius: 6px; }
.list { list-style: none; padding: 0; margin: .4rem 0; }
.hook { border: 1px solid var(--line); border-radius: 8px; padding: .45rem .7rem; margin-bottom: .45rem; font-size: .85rem; }
.hook.off { opacity: .55; }
.h-head { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
.tag { font-size: .7rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 5px; }
.spacer { flex: 1; }
.link { border: none; background: none; color: var(--accent); padding: 0 .2rem; font-size: .8rem; }
.danger:hover { color: var(--danger); }
.form { display: flex; flex-direction: column; gap: .5rem; border: 1px solid var(--line); border-radius: 8px; padding: .6rem .7rem; font-size: .85rem; }
.modes { display: flex; gap: 1rem; flex-wrap: wrap; }
.modes label { display: flex; align-items: center; gap: .25rem; }
.row { display: flex; align-items: center; gap: .4rem; flex-wrap: wrap; }
input:not([type=checkbox]):not([type=radio]) { font: inherit; padding: .3rem .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; }
.f-acts { display: flex; justify-content: flex-end; gap: .4rem; }
.add { margin-top: .3rem; }
.error { font-size: .82rem; }
</style>
