<script setup lang="ts">
import ModalDialog from '../ui/ModalDialog.vue'
import HelpLink from '../ui/HelpLink.vue'
import { computed, onMounted, ref } from 'vue'
import { taskApi, formatDate, STATUS_LABEL, type BrokenLink, type PageHealth } from '../api'

/** ワークスペースの点検: リンク切れ・孤立ページ（本文からリンクされていない）・長く更新されていないページ。見えるページだけが対象 */
const props = defineProps<{ wid: string; initialTab?: 'broken' | 'orphans' | 'stale' }>()
const emit = defineEmits<{ close: [] }>()
const tab = ref<'broken' | 'orphans' | 'stale'>(props.initialTab ?? 'broken')
const items = ref<BrokenLink[] | null>(null)
const health = ref<PageHealth[] | null>(null)
const error = ref('')
onMounted(async () => {
  try {
    const [b, h] = await Promise.all([taskApi.brokenLinks(props.wid), taskApi.health(props.wid)])
    items.value = b
    health.value = h
  } catch (e) { error.value = (e as Error).message }
})

const REASON = { missing: '存在しないページ', deleted: 'ゴミ箱にあるページ', no_access: '閲覧できないページ' } as const
const bySource = computed(() => {
  const m = new Map<string, { title: string; links: BrokenLink[] }>()
  for (const b of items.value ?? []) {
    const e = m.get(b.sourceId) ?? { title: b.sourceTitle, links: [] }
    e.links.push(b)
    m.set(b.sourceId, e)
  }
  return [...m.entries()]
})

// --- 孤立ページ（ほかのページの本文からリンクされていない）。最上位・子ページのあるページは、ツリーからたどりやすいので既定では除く ---
const includeTop = ref(false)
const orphans = computed(() => (health.value ?? [])
  .filter((h) => h.incoming === 0 && (includeTop.value || (h.page.parentId !== null && h.children === 0)))
  .sort((a, b) => a.lastEditedAt.localeCompare(b.lastEditedAt)))

// --- 長く更新されていないページ（古い順） ---
const months = ref(6)
const stale = computed(() => {
  const cutoff = new Date()
  cutoff.setMonth(cutoff.getMonth() - months.value)
  return (health.value ?? []).filter((h) => new Date(h.lastEditedAt) < cutoff && h.page.status !== 'deprecated')
    .sort((a, b) => a.lastEditedAt.localeCompare(b.lastEditedAt))
})
const ago = (iso: string) => {
  const d = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000)
  return d >= 365 ? `${Math.floor(d / 365)}年以上前` : d >= 30 ? `${Math.floor(d / 30)}か月前` : `${d}日前`
}
/** 祖先のタイトル（どこにあるページかを示す） */
const pathOf = (h: PageHealth) => {
  const byId = new Map((health.value ?? []).map((x) => [x.page.id, x.page]))
  const out: string[] = []
  let cur = h.page.parentId ? byId.get(h.page.parentId) : undefined
  while (cur && out.length < 4) { out.unshift(cur.title || '無題'); cur = cur.parentId ? byId.get(cur.parentId) : undefined }
  return out.join(' › ')
}
</script>

<template>
  <ModalDialog label="ワークスペースの点検" testid="broken-links" :width="680" :max-height="75" :top="10" @close="emit('close')">
    <template #title>🩺 ワークスペースの点検 <HelpLink to="manage#ワークスペースの点検" /></template>
    <div class="tabs" role="tablist">
      <button role="tab" :class="{ on: tab === 'broken' }" data-testid="health-tab-broken" @click="tab = 'broken'">🔗 リンク切れ<span v-if="items" class="n">{{ items.length }}</span></button>
      <button role="tab" :class="{ on: tab === 'orphans' }" data-testid="health-tab-orphans" @click="tab = 'orphans'">🏝 孤立ページ<span v-if="health" class="n">{{ orphans.length }}</span></button>
      <button role="tab" :class="{ on: tab === 'stale' }" data-testid="health-tab-stale" @click="tab = 'stale'">🕸 更新されていない<span v-if="health" class="n">{{ stale.length }}</span></button>
    </div>
    <p v-if="error" class="err">{{ error }}</p>
    <p v-else-if="items === null" class="muted">確認中…</p>

    <template v-else-if="tab === 'broken'">
      <p class="note">このワークスペースのページにある、ページへのリンク・同期ブロックの埋め込みのうち、先がなくなっているものです（本文の保存時点の内容で判定）。</p>
      <p v-if="!items.length" class="muted ok">リンク切れはありません。</p>
      <ul v-else class="list">
        <li v-for="[id, s] in bySource" :key="id">
          <router-link :to="`/w/${wid}/p/${id}`" class="src" @click="emit('close')">📄 {{ s.title || '無題' }}</router-link>
          <ul>
            <li v-for="b in s.links" :key="b.targetId" :class="b.reason">
              <span class="reason">{{ REASON[b.reason] }}</span>
              <span>{{ b.targetTitle ? `「${b.targetTitle}」` : '' }}</span>
              <span v-if="b.reason === 'deleted' && b.targetTitle" class="muted">（ゴミ箱から戻せます）</span>
            </li>
          </ul>
        </li>
      </ul>
    </template>

    <template v-else-if="tab === 'orphans'">
      <p class="note">ほかのページの本文から一度もリンクされていないページです。ツリーをたどらないと見つからないため、関連するページからリンクするか、不要なら整理を検討してください。</p>
      <label class="opt"><input v-model="includeTop" type="checkbox" data-testid="health-include-top" /> 最上位のページ・子ページのあるページも含める</label>
      <p v-if="!orphans.length" class="muted ok">孤立しているページはありません。</p>
      <ul v-else class="rows" data-testid="health-orphans">
        <li v-for="h in orphans" :key="h.page.id" data-testid="health-row">
          <router-link :to="`/w/${wid}/p/${h.page.id}`" class="src" @click="emit('close')">{{ h.page.icon ?? '📄' }} {{ h.page.title || '無題' }}</router-link>
          <span class="where">{{ pathOf(h) }}</span>
          <span class="when">{{ ago(h.lastEditedAt) }}に更新</span>
        </li>
      </ul>
    </template>

    <template v-else>
      <p class="note">本文・タイトルが長く変わっていないページです（「廃止」のページは除きます）。内容が今も正しいか、所有者に確認してもらいましょう。</p>
      <label class="opt">
        最終更新が
        <select v-model.number="months" data-testid="health-months">
          <option :value="3">3か月</option>
          <option :value="6">6か月</option>
          <option :value="12">1年</option>
          <option :value="24">2年</option>
        </select>
        より前
      </label>
      <p v-if="!stale.length" class="muted ok">該当するページはありません。</p>
      <ul v-else class="rows" data-testid="health-stale">
        <li v-for="h in stale" :key="h.page.id" data-testid="health-row">
          <router-link :to="`/w/${wid}/p/${h.page.id}`" class="src" @click="emit('close')">{{ h.page.icon ?? '📄' }} {{ h.page.title || '無題' }}</router-link>
          <span v-if="h.page.status" class="badge">{{ STATUS_LABEL[h.page.status] }}</span>
          <span class="where">{{ pathOf(h) }}</span>
          <span class="when" :title="formatDate(h.lastEditedAt)">{{ ago(h.lastEditedAt) }}<template v-if="h.lastEditor">（{{ h.lastEditor }}）</template></span>
          <span class="owner">{{ h.page.ownerName ? `所有者: ${h.page.ownerName}` : '所有者なし' }}</span>
        </li>
      </ul>
    </template>
  </ModalDialog>
</template>

<style scoped>
.tabs { display: flex; gap: 2px; margin: .6rem 0 .2rem; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.tabs button { border: none; border-bottom: 2px solid transparent; border-radius: 0; padding: 4px 10px; color: var(--muted); font-size: .85rem; }
.tabs button.on { color: var(--fg); border-bottom-color: var(--accent); }
.n { margin-left: 4px; font-size: .72rem; background: var(--hover); border-radius: 8px; padding: 0 6px; }
.opt { display: flex; align-items: center; gap: .35rem; font-size: .82rem; margin-bottom: .4rem; }
.opt select { font: inherit; padding: 1px 4px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; }
.list { list-style: none; padding: 0; margin: 0; }
.list > li { padding: .4rem 0; border-bottom: 1px solid var(--line); }
.src { font-weight: 600; color: inherit; text-decoration: none; }
.src:hover { color: var(--accent); }
.list ul { list-style: none; margin: .2rem 0 0 1.2rem; padding: 0; font-size: .85rem; }
.reason { font-size: .72rem; border-radius: 4px; padding: 0 6px; margin-right: .3rem; background: var(--hover); }
li.missing .reason { color: var(--danger); }
li.deleted .reason { color: #d97706; }
.rows { list-style: none; padding: 0; margin: 0; }
.rows li { display: flex; flex-wrap: wrap; align-items: baseline; gap: .2rem .6rem; padding: .4rem 0; border-bottom: 1px solid var(--line); font-size: .85rem; }
.rows .src { font-size: .9rem; }
.where { color: var(--muted); font-size: .75rem; flex: 1; min-width: 6rem; }
.when, .owner { color: var(--muted); font-size: .75rem; white-space: nowrap; }
.badge { font-size: .68rem; color: var(--muted); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; }
.ok { padding: 1rem 0; }
</style>
