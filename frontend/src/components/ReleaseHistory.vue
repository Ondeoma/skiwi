<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import { computed, onMounted, ref } from 'vue'
import { formatDate, shareApi, type Release, type ReleasePage } from '../api'
import { blockUnits } from '../editor/markdown'
import { changeCount, diffBlocks, type Row } from '../editor/diff'
import { promptDialog } from '../ui/dialog'

/** 公開の履歴（共有画面の中）。メモの編集と、ひとつ前の公開との比較 */
const props = defineProps<{ pageId: string }>()
const releases = ref<Release[] | null>(null)
const open = ref(false)
const error = ref('')
async function load() {
  try { releases.value = await shareApi.releases(props.pageId) } catch (e) { error.value = (e as Error).message }
}
onMounted(load)
defineExpose({ load })

async function editNote(r: Release) {
  const v = await promptDialog('変更点のメモ（ゲスト向けの更新履歴に表示されます）', r.note ?? '', { okLabel: '保存' })
  if (v === null || v === undefined) return
  await shareApi.setReleaseNote(props.pageId, r.id, v.trim() || null)
  await load()
}

// --- 比較: この公開 と ひとつ前の公開 ---
const cmp = ref<{ release: Release; prev: Release | null; changes: Change[] } | null>(null)
const picked = ref<string | null>(null)
interface Change { pageId: string; title: string; icon: string | null; kind: 'added' | 'removed' | 'changed'; renamed: string | null; rows: Row[] }
async function compare(r: Release) {
  const i = releases.value!.findIndex((x) => x.id === r.id)
  const prev = releases.value![i + 1] ?? null
  const [a, b] = await Promise.all([prev ? shareApi.release(props.pageId, prev.id) : Promise.resolve({ pages: [] as ReleasePage[] }), shareApi.release(props.pageId, r.id)])
  const before = new Map(a.pages.map((p) => [p.pageId, p]))
  const after = new Map(b.pages.map((p) => [p.pageId, p]))
  const changes: Change[] = []
  for (const p of b.pages) {
    const o = before.get(p.pageId)
    if (!o) { changes.push({ pageId: p.pageId, title: p.title, icon: p.icon, kind: 'added', renamed: null, rows: diffBlocks([], blockUnits(p.content)) }); continue }
    const rows = diffBlocks(blockUnits(o.content), blockUnits(p.content))
    const renamed = o.title !== p.title ? o.title : null
    if (changeCount(rows) || renamed || o.icon !== p.icon) changes.push({ pageId: p.pageId, title: p.title, icon: p.icon, kind: 'changed', renamed, rows })
  }
  for (const o of a.pages) if (!after.has(o.pageId)) changes.push({ pageId: o.pageId, title: o.title, icon: o.icon, kind: 'removed', renamed: null, rows: [] })
  cmp.value = { release: r, prev, changes }
  picked.value = changes.find((c) => c.kind !== 'removed')?.pageId ?? null
}
const pickedChange = computed(() => cmp.value?.changes.find((c) => c.pageId === picked.value) ?? null)
const KIND = { added: '追加', removed: '公開範囲から外れた', changed: '変更' } as const
</script>

<template>
  <div class="releases" data-testid="releases">
    <button class="toggle" data-testid="releases-toggle" @click="open = !open">{{ open ? '▾' : '▸' }} 公開の履歴<span v-if="releases">（{{ releases.length }}）</span></button>
    <p v-if="error" class="error">{{ error }}</p>
    <ul v-if="open && releases" class="list">
      <li v-if="!releases.length" class="muted">まだ公開の履歴はありません。</li>
      <li v-for="r in releases" :key="r.id" data-testid="release-item">
        <div class="r-head">
          <span>{{ formatDate(r.publishedAt) }}<span v-if="r.publishedBy" class="muted">（{{ r.publishedBy }}）</span></span>
          <span class="acts">
            <button class="link" data-testid="release-compare" @click="compare(r)">前回との差分</button>
            <button class="link" @click="editNote(r)">メモを編集</button>
          </span>
        </div>
        <div v-if="r.note" class="note-text" data-testid="release-note">{{ r.note }}</div>
        <div v-else class="muted small">（メモなし）</div>
      </li>
    </ul>

    <ModalDialog v-if="cmp" label="公開の差分" testid="release-diff" :width="900" :top="6" :z="80" @close="cmp = null">
      <template #title>公開の差分</template>
      <p class="muted small">{{ cmp.prev ? `前回の公開（${formatDate(cmp.prev.publishedAt)}）→ この公開（${formatDate(cmp.release.publishedAt)}）` : `最初の公開（${formatDate(cmp.release.publishedAt)}）です。すべて追加として表示します` }}</p>
      <p v-if="cmp.release.note" class="note-text">{{ cmp.release.note }}</p>
      <p v-if="!cmp.changes.length" class="muted">前回の公開から変わったページはありません。</p>
      <div v-else class="cmp">
        <ul class="pages">
          <li v-for="c in cmp.changes" :key="c.pageId">
            <button :class="{ on: picked === c.pageId, [c.kind]: true }" :disabled="c.kind === 'removed'" data-testid="release-diff-page" @click="picked = c.pageId">
              <span class="kind">{{ KIND[c.kind] }}</span>{{ c.icon ?? '📄' }} {{ c.title || '無題' }}
              <small v-if="c.renamed !== null" class="muted">（旧: {{ c.renamed || '無題' }}）</small>
              <small v-if="c.kind === 'changed' && changeCount(c.rows)" class="muted">{{ changeCount(c.rows) }}か所</small>
            </button>
          </li>
        </ul>
        <div v-if="pickedChange" class="diff" data-testid="release-diff-body">
          <template v-for="(r, i) in pickedChange.rows" :key="i">
            <div v-if="r.kind === 'fold'" class="fold">… 変更のない {{ r.count }} ブロック …</div>
            <div v-else-if="r.kind === 'change'" class="blk change"><span v-for="(p, j) in r.pieces" :key="j" :class="p.kind">{{ p.text }}</span></div>
            <div v-else class="blk" :class="r.kind">{{ r.text }}</div>
          </template>
          <p v-if="!changeCount(pickedChange.rows)" class="muted small">本文の違いはありません（タイトル・アイコンのみ）。</p>
        </div>
      </div>
    </ModalDialog>
  </div>
</template>

<style scoped>
.releases { margin-top: .4rem; font-size: .85rem; }
.toggle { border: none; padding: 0; color: var(--muted); font-size: .82rem; }
.list { list-style: none; margin: .3rem 0 0; padding: 0; max-height: 14rem; overflow: auto; border: 1px solid var(--line); border-radius: 8px; }
.list li { padding: .4rem .6rem; border-bottom: 1px solid var(--line); }
.list li:last-child { border-bottom: none; }
.r-head { display: flex; justify-content: space-between; gap: .5rem; flex-wrap: wrap; }
.acts { display: flex; gap: .5rem; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .78rem; }
.note-text { white-space: pre-wrap; margin-top: .2rem; font-size: .82rem; }
.small { font-size: .76rem; }
.cmp { display: grid; grid-template-columns: minmax(180px, 240px) 1fr; gap: .8rem; margin-top: .5rem; }
.pages { list-style: none; margin: 0; padding: 0; }
.pages button { width: 100%; text-align: left; border: none; border-radius: 6px; padding: .3rem .4rem; font-size: .82rem; }
.pages button.on { background: var(--hover); }
.kind { font-size: .66rem; border-radius: 4px; padding: 0 5px; margin-right: .3rem; background: var(--hover); }
.added .kind { color: #16a34a; }
.removed .kind { color: var(--danger); }
.changed .kind { color: #d97706; }
.diff { border-left: 1px solid var(--line); padding-left: .8rem; min-width: 0; }
.blk { white-space: pre-wrap; padding: .2rem .4rem; border-radius: 4px; margin: 2px 0; font-size: .85rem; }
.blk.add { background: rgba(22,163,74,.12); }
.blk.del { background: rgba(220,38,38,.12); text-decoration: line-through; }
.blk.change .add { background: rgba(22,163,74,.25); }
.blk.change .del { background: rgba(220,38,38,.25); text-decoration: line-through; }
.fold { color: var(--muted); font-size: .75rem; text-align: center; }
@media (max-width: 640px) { .cmp { grid-template-columns: 1fr; } .diff { border-left: none; padding-left: 0; } }
</style>
