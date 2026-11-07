<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Editor, EditorContent } from '@tiptap/vue-3'
import 'katex/dist/katex.min.css'
import { api, guestApi, formatDate, type GuestPage } from '../api'
import { contentExtensions } from '../editor/extensions'
import { guestContext } from '../editor/pageLinks'
import { comparePositionThenId, groupByParent } from '../ui/pageTree'

/** PDF（印刷）用の表示: ページと子ページを、目次付きで1つにまとめる。
 *  - /print/w/:wid/p/:pid … ログインユーザー（閲覧できるページだけ。現在の内容）
 *  - /print/s/:token       … 公開リンク（ゲストに見えている内容。公開版を固定していればその版）
 *  ブラウザの印刷画面で「PDFに保存」を選ぶと、目次のリンク付きのPDFになる。 */
const props = defineProps<{ wid?: string; pid?: string; token?: string }>()
const route = useRoute()
const router = useRouter()

interface Item { id: string; parentId: string | null; position: string; title: string; icon: string | null; updatedAt: string }
interface Section { item: Item; depth: number; num: string; editor: Editor }

const children = ref(route.query.children !== '0')
const toc = ref(route.query.toc !== '0')
const loading = ref(true)
const progress = ref({ done: 0, total: 0 })
const error = ref('')
const sections = shallowRef<Section[]>([])
const docTitle = ref('')

/** 起点の下を深さ優先で並べる（番号付き） */
function order(all: Item[], rootId: string, withChildren: boolean): { item: Item; depth: number; num: string }[] {
  const kids = groupByParent(all, { cmp: comparePositionThenId })
  const root = all.find((p) => p.id === rootId)
  if (!root) return []
  const out: { item: Item; depth: number; num: string }[] = [{ item: root, depth: 0, num: '' }]
  const walk = (id: string, depth: number, prefix: string) => {
    ;(kids.get(id) ?? []).forEach((c, i) => {
      const num = prefix ? `${prefix}.${i + 1}` : `${i + 1}`
      out.push({ item: c, depth, num })
      walk(c.id, depth + 1, num)
    })
  }
  if (withChildren) walk(root.id, 1, '')
  return out
}

async function load() {
  loading.value = true
  error.value = ''
  sections.value.forEach((s) => s.editor.destroy())
  sections.value = []
  try {
    let list: { item: Item; depth: number; num: string }[]
    let fetchContent: (id: string) => Promise<unknown>
    if (props.token) {
      const t = await guestApi.tree(props.token)
      guestContext.value = { token: props.token, pages: new Map(t.pages.map((p: GuestPage) => [p.id, p])) }
      list = order(t.pages, t.rootId, children.value)
      fetchContent = async (id) => (await guestApi.page(props.token!, id)).content
    } else {
      const pages = await api.pages(props.wid!)
      list = order(pages, props.pid!, children.value)
      fetchContent = async (id) => (await api.pageContent(id)).content
    }
    if (!list.length) throw new Error('ページが見つかりません（削除されたか、閲覧する権限がありません）')
    docTitle.value = list[0].item.title || '無題'
    document.title = `${docTitle.value} - PDF`
    progress.value = { done: 0, total: list.length }
    const out: Section[] = []
    // 本文は少しずつ並行して取る
    const contents = new Array<unknown>(list.length)
    let next = 0
    await Promise.all(Array.from({ length: Math.min(4, list.length) }, async () => {
      while (next < list.length) {
        const i = next++
        try { contents[i] = await fetchContent(list[i].item.id) } catch { contents[i] = null }
        progress.value = { ...progress.value, done: progress.value.done + 1 }
      }
    }))
    list.forEach((x, i) => {
      const c = contents[i] as Record<string, unknown> | null
      out.push({ ...x, editor: new Editor({ editable: false, extensions: contentExtensions(), content: c && typeof c === 'object' ? c : '' }) })
    })
    sections.value = out
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await load()
  // 自動で印刷画面を開く（図・数式の描画を少し待つ）
  if (!error.value && route.query.auto !== '0') { await nextTick(); setTimeout(() => window.print(), 1200) }
})
onBeforeUnmount(() => { sections.value.forEach((s) => s.editor.destroy()); if (props.token) guestContext.value = null })

function setOpt(k: 'children' | 'toc', v: boolean) {
  if (k === 'children') { children.value = v; void load() } else toc.value = v
  router.replace({ query: { ...route.query, children: children.value ? '1' : '0', toc: toc.value ? '1' : '0', auto: '0' } })
}
const printNow = () => window.print()
const generated = formatDate(new Date().toISOString())
const tocItems = computed(() => sections.value.filter((s) => s.depth > 0 || sections.value.length > 1))
</script>

<template>
  <div class="print-root">
    <div class="bar no-print" data-testid="print-bar">
      <b>🖨 PDF・印刷</b>
      <label><input type="checkbox" :checked="children" data-testid="print-children" @change="setOpt('children', ($event.target as HTMLInputElement).checked)" /> 子ページも含める</label>
      <label><input type="checkbox" :checked="toc" data-testid="print-toc" @change="setOpt('toc', ($event.target as HTMLInputElement).checked)" /> 目次を付ける</label>
      <span class="muted">{{ loading ? `読み込み中… ${progress.done}/${progress.total}` : `${sections.length} ページ` }}</span>
      <button class="primary" :disabled="loading || !!error" data-testid="print-run" @click="printNow">印刷 / PDFに保存</button>
      <span class="muted small">印刷画面の「送信先」で「PDFに保存」を選んでください</span>
    </div>
    <p v-if="error" class="error" data-testid="print-error">{{ error }}</p>
    <template v-else-if="!loading">
      <section class="cover">
        <div v-if="sections[0]?.item.icon" class="cover-icon">{{ sections[0].item.icon }}</div>
        <h1 class="cover-title" data-testid="print-title">{{ docTitle }}</h1>
        <p class="muted">{{ sections.length }} ページ ・ {{ generated }} 作成</p>
        <nav v-if="toc && tocItems.length > 1" class="toc" data-testid="print-toc-list">
          <h2>目次</h2>
          <ol>
            <li v-for="s in tocItems" :key="s.item.id" :style="{ paddingLeft: `${Math.max(0, s.depth - 1) * 1.2}rem` }">
              <a :href="`#p-${s.item.id}`"><span v-if="s.num" class="num">{{ s.num }}</span>{{ s.item.title || '無題' }}</a>
            </li>
          </ol>
        </nav>
      </section>
      <article v-for="(s, i) in sections" :id="`p-${s.item.id}`" :key="s.item.id" class="sec" :class="{ first: i === 0 }" data-testid="print-section">
        <header v-if="i > 0 || sections.length > 1" class="sec-head">
          <h1 :class="`d${Math.min(s.depth, 3)}`"><span v-if="s.num" class="num">{{ s.num }}</span>{{ s.item.icon ? `${s.item.icon} ` : '' }}{{ s.item.title || '無題' }}</h1>
          <p class="muted small">最終更新 {{ formatDate(s.item.updatedAt) }}</p>
        </header>
        <EditorContent :editor="s.editor" class="editor-area readonly" />
      </article>
    </template>
  </div>
</template>

<style scoped>
.print-root { max-width: 820px; margin: 0 auto; padding: 0 1.5rem 3rem; background: var(--bg); min-height: 100vh; }
.bar { position: sticky; top: 0; z-index: 5; display: flex; flex-wrap: wrap; align-items: center; gap: .4rem 1rem; padding: .6rem 0; background: var(--bg); border-bottom: 1px solid var(--line); font-size: .85rem; }
.bar label { display: flex; align-items: center; gap: .3rem; }
.error { padding: 2rem 0; }
.cover { padding: 3rem 0 1rem; }
.cover-icon { font-size: 3rem; }
.cover-title { font-size: 2.2rem; margin: .2rem 0; }
.toc h2 { font-size: 1.1rem; margin: 2rem 0 .5rem; }
.toc ol { list-style: none; padding: 0; margin: 0; }
.toc li { padding: .15rem 0; border-bottom: 1px dotted var(--line); }
.toc a { color: inherit; text-decoration: none; }
.num { color: var(--muted); margin-right: .6em; font-variant-numeric: tabular-nums; }
.sec { padding-top: 1.5rem; }
.sec-head h1 { margin: 0 0 .2rem; font-size: 1.8rem; }
.sec-head h1.d2 { font-size: 1.5rem; }
.sec-head h1.d3 { font-size: 1.3rem; }
.sec-head { border-bottom: 1px solid var(--line); margin-bottom: 1rem; }
@media print {
  .no-print { display: none !important; }
  .print-root { max-width: none; padding: 0; }
  .cover { padding-top: 0; }
  .toc { break-after: page; }
  .sec { break-before: page; padding-top: 0; }
  .sec.first { break-before: auto; }
  .toc a::after { content: none; }
}
</style>
