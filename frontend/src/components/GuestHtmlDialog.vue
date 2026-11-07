<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import { computed, onMounted, ref } from 'vue'
import { guestHtmlApi, type GuestHtmlLayer } from '../api'
import { confirmDialog } from '../ui/dialog'
import SandboxedHtml from '../ui/SandboxedHtml.vue'

/** ゲスト閲覧時に本文の上下へ入れるHTML。page: そのページ（子ページにも入れるか選べる）、workspace: 全ページ */
const props = defineProps<{ kind: 'page' | 'workspace'; id: string; title: string }>()
const emit = defineEmits<{ close: [] }>()

const top = ref('')
const bottom = ref('')
const inherit = ref(true)
const layers = ref<GuestHtmlLayer[]>([])
const original = ref('')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const preview = ref(false)

const snapshot = () => JSON.stringify([top.value, bottom.value, inherit.value])
onMounted(async () => {
  try {
    const r = props.kind === 'page' ? await guestHtmlApi.page(props.id) : await guestHtmlApi.workspace(props.id)
    top.value = r.top ?? ''
    bottom.value = r.bottom ?? ''
    inherit.value = r.inherit
    layers.value = r.layers ?? []
    original.value = snapshot()
  } catch (e) { error.value = (e as Error).message } finally { loading.value = false }
})
const dirty = computed(() => !loading.value && snapshot() !== original.value)
const tooLong = computed(() => top.value.length > 50000 || bottom.value.length > 50000)

async function save() {
  saving.value = true
  error.value = ''
  try {
    const body = { top: top.value.trim() || null, bottom: bottom.value.trim() || null, inherit: inherit.value }
    if (props.kind === 'page') await guestHtmlApi.setPage(props.id, body)
    else await guestHtmlApi.setWorkspace(props.id, body)
    emit('close')
  } catch (e) { error.value = (e as Error).message } finally { saving.value = false }
}
async function cancel() {
  if (dirty.value && !(await confirmDialog('変更を保存せずに閉じますか？', { okLabel: '閉じる' }))) return
  emit('close')
}
const label = (l: GuestHtmlLayer) => (l.level === 'workspace' ? '🗂 ワークスペース' : `📄 親ページ「${l.title ?? '（閲覧できないページ）'}」`)
</script>

<template>
  <ModalDialog label="ゲスト向けの表示" testid="guest-html-dialog" :max-height="88" :top="6" :z="65" :dim="0.35" small-title close-title="閉じる" @close="cancel">
    <template #title>🌐 ゲスト向けの表示 ― {{ title || '無題' }}</template>
    <p class="note">
      ゲスト向けの公開ページで、本文の上と下に入れるHTMLです（ログインしている人の画面には出ません）。
      {{ kind === 'workspace' ? 'このワークスペースの全ページに入ります。' : '' }}
      HTMLは隔離された枠（iframe）の中で表示するため、スクリプトも使えますが、skiwi の画面やログイン情報には触れられません。リンクはページ全体で開きます。
    </p>
    <p v-if="loading" class="muted">読み込み中…</p>
    <template v-else>
      <details v-if="layers.length" class="inherited">
        <summary>上位から入るHTML（{{ layers.length }}件）</summary>
        <div v-for="(l, i) in layers" :key="i" class="layer">
          <div class="layer-title">{{ label(l) }}</div>
          <pre v-if="l.top">上: {{ l.top }}</pre>
          <pre v-if="l.bottom">下: {{ l.bottom }}</pre>
        </div>
        <p class="note">本文の上は「ワークスペース → 親 → このページ」、下は「このページ → 親 → ワークスペース」の順に並びます。</p>
      </details>
      <label class="lbl">本文の上</label>
      <textarea v-model="top" class="code" spellcheck="false" placeholder="例: &lt;div style=&quot;padding:12px;background:#eef2ff&quot;&gt;お知らせ: …&lt;/div&gt;" data-testid="guest-html-top" />
      <label class="lbl">本文の下</label>
      <textarea v-model="bottom" class="code" spellcheck="false" placeholder="例: &lt;p&gt;© 2026 Example Inc. &lt;a href=&quot;https://example.com&quot;&gt;お問い合わせ&lt;/a&gt;&lt;/p&gt;" data-testid="guest-html-bottom" />
      <label v-if="kind === 'page'" class="row">
        <input v-model="inherit" type="checkbox" data-testid="guest-html-inherit" />
        子ページ（その下の階層すべて）にも入れる
      </label>
      <button class="link" @click="preview = !preview">{{ preview ? '▾ プレビューを閉じる' : '▸ プレビュー' }}</button>
      <div v-if="preview" class="preview">
        <SandboxedHtml v-if="top.trim()" :html="top" />
        <div class="body-dummy">（ページの本文）</div>
        <SandboxedHtml v-if="bottom.trim()" :html="bottom" />
      </div>
    </template>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="actions">
      <span class="muted small">それぞれ 50,000 文字まで</span>
      <button @click="cancel">キャンセル</button>
      <button class="primary" :disabled="loading || saving || tooLong" data-testid="guest-html-save" @click="save">保存</button>
    </div>
  </ModalDialog>
</template>

<style scoped>
.note { margin: .4rem 0; }
.lbl { display: block; font-size: .8rem; font-weight: 600; margin: .5rem 0 .2rem; }
.code { width: 100%; box-sizing: border-box; min-height: 110px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .8rem; line-height: 1.5; padding: .5rem .6rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); color: inherit; }
.code:focus { outline: none; border-color: var(--accent); }
.inherited { margin: .4rem 0; font-size: .82rem; }
.inherited summary { cursor: pointer; color: var(--muted); }
.layer-title { font-size: .75rem; color: var(--muted); margin-top: .3rem; }
.layer pre { margin: .1rem 0; padding: .3rem .5rem; background: var(--hover); border-radius: 6px; font-size: .72rem; max-height: 90px; overflow: auto; white-space: pre-wrap; }
.row { display: flex; align-items: center; gap: .4rem; margin: .5rem 0; font-size: .88rem; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .8rem; }
.preview { margin-top: .4rem; border: 1px dashed var(--line); border-radius: 8px; padding: .5rem; }
.body-dummy { text-align: center; color: var(--muted); font-size: .8rem; padding: .8rem 0; border-top: 1px dashed var(--line); border-bottom: 1px dashed var(--line); margin: .3rem 0; }
.actions { display: flex; align-items: center; justify-content: flex-end; gap: .5rem; margin-top: .7rem; }
.actions .small { margin-right: auto; font-size: .75rem; }
.primary:disabled { opacity: .5; }
.error { font-size: .85rem; }
</style>
