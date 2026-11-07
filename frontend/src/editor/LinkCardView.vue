<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'

/** URL のカード。情報がなければ取りに行き（編集できる人だけが保存）、更新もできる */
const props = defineProps(nodeViewProps)
const loading = ref(false)
const host = computed(() => { try { return new URL(String(props.node.attrs.url)).hostname } catch { return '' } })
const editable = computed(() => props.editor.isEditable)

async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    const r = await fetch(`/api/unfurl?url=${encodeURIComponent(String(props.node.attrs.url))}`, { credentials: 'same-origin' })
    if (!r.ok) return
    const d = await r.json()
    if (!props.editor.isDestroyed && props.editor.isEditable)
      props.updateAttributes({ title: d.title ?? null, description: d.description ?? null, image: d.image ?? null, siteName: d.siteName ?? null, icon: d.icon ?? null })
  } catch { /* 取れなくても URL のカードとして出す */ } finally { loading.value = false }
}
onMounted(() => { if (!props.node.attrs.title && !props.node.attrs.siteName && editable.value) void refresh() })
function open(e: MouseEvent) {
  // 編集中はクリックで選ぶ（⌘/Ctrl+クリックで開く）
  if (editable.value && !(e.metaKey || e.ctrlKey)) { e.preventDefault(); return }
}
const imgOk = ref(true)
const iconOk = ref(true)
</script>

<template>
  <NodeViewWrapper class="link-card-wrap" data-drag-handle>
    <a class="link-card" :href="String(node.attrs.url)" target="_blank" rel="noopener noreferrer" data-testid="link-card" @click="open">
      <div class="lc-body">
        <div class="lc-title">{{ node.attrs.title || node.attrs.url }}</div>
        <div v-if="node.attrs.description" class="lc-desc">{{ node.attrs.description }}</div>
        <div class="lc-site">
          <img v-if="node.attrs.icon && iconOk" :src="String(node.attrs.icon)" alt="" class="lc-icon" referrerpolicy="no-referrer" @error="iconOk = false" />
          <span>{{ node.attrs.siteName || host }}</span>
          <span v-if="loading" class="muted">・読み込み中…</span>
        </div>
      </div>
      <img v-if="node.attrs.image && imgOk" :src="String(node.attrs.image)" alt="" class="lc-image" referrerpolicy="no-referrer" loading="lazy" @error="imgOk = false" />
    </a>
    <button v-if="editable" class="lc-refresh" title="情報を取り直す" contenteditable="false" @click.stop="refresh">🔄</button>
  </NodeViewWrapper>
</template>

<style scoped>
.link-card-wrap { position: relative; margin: .5em 0; }
.link-card { display: flex; align-items: stretch; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; text-decoration: none; color: inherit; min-height: 5.5rem; }
.link-card:hover { background: var(--hover); }
.lc-body { flex: 1; min-width: 0; padding: .6rem .8rem; display: flex; flex-direction: column; gap: .2rem; }
.lc-title { font-weight: 600; font-size: .95rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lc-desc { font-size: .8rem; color: var(--muted); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; line-height: 1.5; }
.lc-site { margin-top: auto; display: flex; align-items: center; gap: .35rem; font-size: .75rem; color: var(--muted); }
.lc-icon { width: 14px; height: 14px; }
.lc-image { width: 30%; max-width: 220px; object-fit: cover; flex: none; border-left: 1px solid var(--line); }
.lc-refresh { position: absolute; top: .3rem; right: .3rem; border: none; background: var(--bg); border-radius: 6px; padding: 1px 5px; font-size: .75rem; opacity: 0; }
.link-card-wrap:hover .lc-refresh { opacity: .8; }
@media (max-width: 560px) { .lc-image { display: none; } }
</style>
