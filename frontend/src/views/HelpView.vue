<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { chapters, search } from '../help/manual'

/** ユーザーマニュアル（/help、/help/章のID#節）。左に章と節の目次、上に検索 */
const props = defineProps<{ chapter?: string }>()
const route = useRoute()
const router = useRouter()

const current = computed(() => chapters.find((c) => c.id === props.chapter) ?? null)
const index = computed(() => (current.value ? chapters.indexOf(current.value) : -1))
const prev = computed(() => (index.value > 0 ? chapters[index.value - 1] : null))
const next = computed(() => (index.value >= 0 && index.value < chapters.length - 1 ? chapters[index.value + 1] : index.value < 0 ? chapters[0] : null))

const q = ref(typeof route.query.q === 'string' ? route.query.q : '')
const hits = computed(() => search(q.value))
// 検索語を URL に残す（戻るで検索結果に戻れる）。章を移ったら検索を閉じる
watch(q, (v) => { if ((route.query.q ?? '') !== v.trim()) router.replace({ query: v.trim() ? { q: v.trim() } : {}, hash: route.hash }) })
watch(() => route.path, () => { if (!route.query.q) q.value = '' })

const body = ref<HTMLElement | null>(null)
async function scrollToHash() {
  await nextTick()
  const id = decodeURIComponent(route.hash.slice(1))
  const el = id ? document.getElementById(id) : null
  if (el) { el.scrollIntoView({ block: 'start' }); el.classList.add('flash'); setTimeout(() => el.classList.remove('flash'), 1400) }
  else body.value?.scrollTo?.(0, 0)
}
watch(() => [props.chapter, route.hash], scrollToHash)
onMounted(scrollToHash)
watch(current, (c) => { document.title = c ? `${c.title} - 使い方 - skiwi` : '使い方 - skiwi' }, { immediate: true })

/** 本文のリンク: アプリ内（/help・/w など）はページを切り替える */
function onBodyClick(e: MouseEvent) {
  const a = (e.target as HTMLElement).closest('a')
  if (!a || a.target === '_blank' || e.metaKey || e.ctrlKey) return
  const href = a.getAttribute('href') ?? ''
  if (href.startsWith('#')) { e.preventDefault(); router.replace({ hash: href }) }
  else if (href.startsWith('/')) { e.preventDefault(); router.push(href) }
}
const sideOpen = ref(false)
watch(() => route.fullPath, () => (sideOpen.value = false))
const back = () => (window.history.length > 1 ? router.back() : router.push('/'))
</script>

<template>
  <div class="help" data-testid="help">
    <header class="bar">
      <button class="menu-btn" title="目次" @click="sideOpen = !sideOpen">☰</button>
      <router-link to="/help" class="brand">📖 skiwi の使い方</router-link>
      <input v-model="q" type="search" class="search" placeholder="🔍 マニュアルを検索（例: 共有、期限、データベース）" data-testid="help-search" />
      <button class="back" @click="back">← skiwi に戻る</button>
    </header>
    <div class="layout">
      <nav class="side" :class="{ open: sideOpen }" aria-label="マニュアルの目次" data-testid="help-toc">
        <template v-for="c in chapters" :key="c.id">
          <router-link :to="`/help/${c.id}`" class="ch" :class="{ on: c.id === current?.id }" data-testid="help-chapter">{{ c.icon }} {{ c.title }}</router-link>
          <div v-if="c.id === current?.id" class="secs">
            <router-link v-for="s in c.sections.filter((x) => x.level === 2)" :key="s.id" :to="{ path: `/help/${c.id}`, hash: `#${s.id}` }" class="sec">{{ s.title }}</router-link>
          </div>
        </template>
      </nav>
      <main ref="body" class="main">
        <!-- 検索結果 -->
        <section v-if="q.trim()" class="hits" data-testid="help-hits">
          <h1>「{{ q }}」の検索結果（{{ hits.length }}件）</h1>
          <p v-if="!hits.length" class="muted">見つかりませんでした。言葉を変えて試してください（例: 「権限」→「共有」）。</p>
          <router-link
            v-for="(h, i) in hits"
            :key="i"
            :to="{ path: `/help/${h.chapter.id}`, hash: h.section ? `#${h.section.id}` : '' }"
            class="hit"
            data-testid="help-hit"
          >
            <div class="hit-t">{{ h.chapter.icon }} {{ h.chapter.title }}<template v-if="h.section"> › {{ h.section.title }}</template></div>
            <div class="hit-s">{{ h.snippet }}</div>
          </router-link>
        </section>

        <!-- 章 -->
        <article v-else-if="current" class="doc" data-testid="help-doc" @click="onBodyClick">
          <p class="crumb"><router-link to="/help">目次</router-link> › {{ current.title }}</p>
          <!-- 本文はこのアプリに同梱したマニュアル（Markdown）を変換したもの -->
          <div class="md" v-html="current.html" />
          <nav class="pager">
            <router-link v-if="prev" :to="`/help/${prev.id}`" class="pg">← {{ prev.icon }} {{ prev.title }}</router-link>
            <span v-else />
            <router-link v-if="next" :to="`/help/${next.id}`" class="pg next">{{ next.icon }} {{ next.title }} →</router-link>
          </nav>
        </article>

        <!-- 目次（トップ） -->
        <section v-else class="top">
          <h1>📖 skiwi の使い方</h1>
          <p class="lead">チームの知識をページにまとめ、同時に編集し、必要な人と共有するための Wiki です。知りたいことを上の検索欄で探すか、下の章から読んでください。
            画面の「?」キーでキーボードショートカットの一覧、各画面の「？ 使い方」からその機能の説明を開けます。</p>
          <div class="cards">
            <router-link v-for="c in chapters" :key="c.id" :to="`/help/${c.id}`" class="card" data-testid="help-card">
              <span class="ic">{{ c.icon }}</span>
              <span class="ct">{{ c.title }}</span>
              <span class="cs">{{ c.summary }}</span>
            </router-link>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
.help { height: 100%; display: flex; flex-direction: column; background: var(--bg); }
.bar { display: flex; align-items: center; gap: .8rem; padding: .5rem 1rem; border-bottom: 1px solid var(--line); background: var(--side); }
.brand { font-weight: 700; color: inherit; text-decoration: none; white-space: nowrap; }
.search { flex: 1; max-width: 36rem; font: inherit; font-size: .88rem; padding: .35rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: inherit; }
.back { margin-left: auto; font-size: .85rem; white-space: nowrap; }
.menu-btn { display: none; border: none; font-size: 1.1rem; padding: 0 .3rem; }
.layout { flex: 1; display: flex; min-height: 0; }
.side { width: 260px; flex: none; overflow: auto; padding: .8rem .5rem 2rem; border-right: 1px solid var(--line); background: var(--side); }
.ch { display: block; padding: .35rem .6rem; border-radius: 6px; color: inherit; text-decoration: none; font-size: .9rem; }
.ch:hover, .ch.on { background: var(--hover); }
.ch.on { font-weight: 600; }
.secs { margin: .1rem 0 .4rem; }
.sec { display: block; padding: .2rem .6rem .2rem 1.8rem; font-size: .8rem; color: var(--muted); text-decoration: none; border-radius: 6px; }
.sec:hover { color: var(--fg); background: var(--hover); }
.main { flex: 1; overflow: auto; padding: 1.4rem 2.4rem 4rem; }
.doc, .top, .hits { max-width: 820px; margin: 0 auto; }
.crumb { font-size: .8rem; color: var(--muted); margin: 0 0 .4rem; }
.crumb a { color: var(--muted); }
.lead { line-height: 1.8; color: var(--muted); }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: .7rem; margin-top: 1.2rem; }
.card { display: grid; grid-template-columns: 2rem 1fr; grid-template-rows: auto auto; gap: .1rem .5rem; padding: .8rem; border: 1px solid var(--line); border-radius: 10px; color: inherit; text-decoration: none; }
.card:hover { border-color: var(--accent); background: var(--hover); }
.card .ic { grid-row: 1 / 3; font-size: 1.4rem; }
.card .ct { font-weight: 600; }
.card .cs { font-size: .78rem; color: var(--muted); line-height: 1.5; }
.hit { display: block; padding: .6rem .7rem; border-radius: 8px; color: inherit; text-decoration: none; border-bottom: 1px solid var(--line); }
.hit:hover { background: var(--hover); }
.hit-t { font-weight: 600; font-size: .92rem; }
.hit-s { font-size: .8rem; color: var(--muted); margin-top: .2rem; }
.pager { display: flex; justify-content: space-between; gap: 1rem; margin-top: 3rem; padding-top: 1rem; border-top: 1px solid var(--line); }
.pg { color: var(--accent); text-decoration: none; font-size: .9rem; }
/* 本文 */
.md { line-height: 1.85; }
.md :deep(h1) { font-size: 1.9rem; margin: .2rem 0 1rem; }
.md :deep(h2) { font-size: 1.35rem; margin: 2.2rem 0 .6rem; padding-bottom: .25rem; border-bottom: 1px solid var(--line); }
.md :deep(h3) { font-size: 1.08rem; margin: 1.6rem 0 .4rem; }
.md :deep(h1), .md :deep(h2), .md :deep(h3) { position: relative; scroll-margin-top: 1rem; }
.md :deep(.anchor) { position: absolute; left: -1.1em; color: var(--muted); text-decoration: none; opacity: 0; font-weight: 400; }
.md :deep(h2:hover .anchor), .md :deep(h3:hover .anchor) { opacity: .6; }
.md :deep(.flash) { animation: flash 1.4s ease; }
@keyframes flash { 0% { background: rgba(250,204,21,.45); } 100% { background: transparent; } }
.md :deep(a) { color: var(--accent); }
.md :deep(code) { font-size: .86em; background: var(--hover); padding: 1px 5px; border-radius: 4px; }
.md :deep(pre) { background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .7rem .9rem; overflow: auto; line-height: 1.5; }
.md :deep(pre code) { background: none; padding: 0; }
.md :deep(kbd) { display: inline-block; font: inherit; font-size: .8em; padding: 0 6px; border: 1px solid var(--line); border-bottom-width: 2px; border-radius: 5px; background: var(--side); }
.md :deep(blockquote) { margin: 1rem 0; padding: .5rem .9rem; border-left: 4px solid var(--accent); background: var(--side); border-radius: 0 8px 8px 0; color: inherit; }
.md :deep(blockquote p) { margin: .2rem 0; }
.md :deep(table) { border-collapse: collapse; margin: 1rem 0; font-size: .9rem; width: 100%; }
.md :deep(th), .md :deep(td) { border: 1px solid var(--line); padding: .35rem .6rem; text-align: left; vertical-align: top; }
.md :deep(th) { background: var(--side); }
.md :deep(li) { margin: .2rem 0; }
.md :deep(ol > li::marker) { font-weight: 600; color: var(--accent); }
@media (max-width: 768px) {
  .menu-btn { display: block; }
  .brand { display: none; }
  .back { font-size: .78rem; }
  .side { position: fixed; z-index: 30; top: 0; bottom: 0; left: 0; width: min(290px, 85vw); transform: translateX(-100%); transition: transform .18s; box-shadow: 8px 0 24px rgba(0,0,0,.2); }
  .side.open { transform: none; }
  .main { padding: 1rem 1rem 3rem; }
}
@media print { .bar, .side, .pager { display: none !important; } .main { overflow: visible; padding: 0; } }
</style>
