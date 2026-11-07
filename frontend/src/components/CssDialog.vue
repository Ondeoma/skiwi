<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { cssApi, type CssLayer } from '../api'
import { scopeCss } from '../ui/customCss'
import { confirmDialog } from '../ui/dialog'

/**
 * カスタムCSSの編集。page: そのページ（子ページにも適用するか選べる）、workspace: ワークスペースの全ページ。
 * 入力中は preview を親に渡して、その場で見た目を確かめられる。
 */
const props = defineProps<{ kind: 'page' | 'workspace'; id: string; title: string }>()
const emit = defineEmits<{ close: []; saved: []; preview: [css: string | null] }>()

const css = ref('')
const inherit = ref(true)
const inherited = ref<CssLayer[]>([])
const original = ref({ css: '', inherit: true })
const loading = ref(true)
const saving = ref(false)
const error = ref('')

onMounted(async () => {
  try {
    if (props.kind === 'page') {
      const s = await cssApi.pageStyles(props.id)
      css.value = s.css ?? ''
      inherit.value = s.inherit
      inherited.value = s.layers.filter((l) => !l.own)
    } else {
      css.value = (await cssApi.workspaceCss(props.id)).css ?? ''
    }
    original.value = { css: css.value, inherit: inherit.value }
  } catch (e) { error.value = (e as Error).message } finally { loading.value = false }
})

const checked = computed(() => scopeCss(css.value))
const dirty = computed(() => css.value !== original.value.css || inherit.value !== original.value.inherit)
// 入力中の見た目をその場で反映（ページのとき）
watch(css, (v) => { if (!loading.value) emit('preview', v) })

async function save() {
  saving.value = true
  error.value = ''
  try {
    const v = css.value.trim() || null
    if (props.kind === 'page') await cssApi.setPageCss(props.id, v, inherit.value)
    else await cssApi.setWorkspaceCss(props.id, v)
    window.dispatchEvent(new Event('skiwi:css-changed'))
    emit('saved')
    emit('close')
  } catch (e) { error.value = (e as Error).message } finally { saving.value = false }
}
async function cancel() {
  if (dirty.value && !(await confirmDialog('変更を保存せずに閉じますか？', { okLabel: '閉じる' }))) return
  emit('preview', null)
  emit('close')
}
const label = (l: CssLayer) => (l.level === 'workspace' ? '🗂 ワークスペース' : `📄 親ページ「${l.title ?? '（閲覧できないページ）'}」`)
const example = props.kind === 'page'
  ? '/* 例: 見出しの色と本文の行間 */\nh1, h2 { color: #1d4ed8; }\np { line-height: 1.9; }\n.ProseMirror table td { font-size: 0.9em; }'
  : '/* 例: このワークスペースの全ページ */\nh1 { border-bottom: 2px solid #e5e7eb; padding-bottom: .2em; }'
</script>

<template>
  <ModalDialog label="カスタムCSS" testid="css-dialog" :width="520" :max-height="86" :top="6" side small-title close-title="閉じる" @close="cancel">
    <template #title>🎨 {{ kind === 'page' ? 'このページのスタイル' : 'ワークスペースのスタイル' }} ― {{ title || '無題' }}</template>
    <p class="note">
      {{ kind === 'page' ? 'このページの本文とタイトルの見た目を CSS で変えられます。' : 'このワークスペースの全ページの本文とタイトルに適用します。' }}
      効くのはページの表示エリアの中だけです（サイドバーなどには効きません）。外部のURL・@import・position: fixed は使えません。
    </p>
    <p v-if="loading" class="muted">読み込み中…</p>
    <template v-else>
      <details v-if="inherited.length" class="inherited">
        <summary>上位から適用されているスタイル（{{ inherited.length }}件）</summary>
        <div v-for="(l, i) in inherited" :key="i" class="layer">
          <div class="layer-title">{{ label(l) }}</div>
          <pre>{{ l.css }}</pre>
        </div>
        <p class="note">このページのスタイルは、これらの後に適用されます（同じ指定ならこちらが優先）。</p>
      </details>
      <textarea v-model="css" class="code" spellcheck="false" :placeholder="example" data-testid="css-input" />
      <ul v-if="checked.warnings.length" class="warn" data-testid="css-warnings">
        <li v-for="w in checked.warnings" :key="w">⚠ {{ w }}（この部分は適用されません）</li>
      </ul>
      <label v-if="kind === 'page'" class="row">
        <input v-model="inherit" type="checkbox" data-testid="css-inherit" />
        子ページ（その下の階層すべて）にも適用する
      </label>
    </template>
    <p v-if="error" class="error">{{ error }}</p>
    <div class="actions">
      <span class="muted small">{{ css.length.toLocaleString() }} / 20,000 文字</span>
      <button @click="cancel">キャンセル</button>
      <button class="primary" :disabled="loading || saving || css.length > 20000" data-testid="css-save" @click="save">保存</button>
    </div>
  </ModalDialog>
</template>

<style scoped>
.note { margin: .4rem 0; }
.code { width: 100%; box-sizing: border-box; min-height: 260px; resize: vertical; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .82rem; line-height: 1.5; padding: .5rem .6rem; border: 1px solid var(--line); border-radius: 8px; background: var(--side); color: inherit; tab-size: 2; }
.code:focus { outline: none; border-color: var(--accent); }
.inherited { margin: .4rem 0; font-size: .82rem; }
.inherited summary { cursor: pointer; color: var(--muted); }
.layer { margin: .3rem 0; }
.layer-title { font-size: .75rem; color: var(--muted); }
.layer pre { margin: .1rem 0; padding: .3rem .5rem; background: var(--hover); border-radius: 6px; font-size: .75rem; max-height: 120px; overflow: auto; white-space: pre-wrap; }
.warn { margin: .3rem 0; padding: 0 0 0 1rem; font-size: .78rem; color: #b45309; }
.row { display: flex; align-items: center; gap: .4rem; margin: .5rem 0; font-size: .88rem; }
.actions { display: flex; align-items: center; justify-content: flex-end; gap: .5rem; margin-top: .6rem; }
.actions .small { margin-right: auto; font-size: .75rem; }
.primary:disabled { opacity: .5; }
.error { font-size: .85rem; }
</style>
