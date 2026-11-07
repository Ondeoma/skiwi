<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { taskApi, type Task } from '../api'
import { today } from '../editor/DueDate'

/** 自分に割り当てられたToDo（すべてのワークスペース）。ToDo の項目で @メンションされると担当になる。 */
const props = defineProps<{ wid: string }>()
const showDone = ref(false)
const tasks = ref<Task[] | null>(null)

async function load() {
  tasks.value = null
  try { tasks.value = await taskApi.mine(showDone.value) } catch { tasks.value = [] }
}
watch([showDone, () => props.wid], load, { immediate: true })

const groups = computed(() => {
  const t0 = today(), t1 = today(1)
  const g: { key: string; label: string; items: Task[] }[] = [
    { key: 'overdue', label: '⚠ 期限切れ', items: [] },
    { key: 'today', label: '今日・明日', items: [] },
    { key: 'later', label: 'これから', items: [] },
    { key: 'none', label: '期限なし', items: [] },
    { key: 'done', label: '完了', items: [] },
  ]
  for (const t of tasks.value ?? []) {
    const k = t.checked ? 'done' : !t.due ? 'none' : t.due < t0 ? 'overdue' : t.due <= t1 ? 'today' : 'later'
    g.find((x) => x.key === k)!.items.push(t)
  }
  return g.filter((x) => x.items.length)
})
const fmt = (d: string) => { const [, m, dd] = d.split('-').map(Number); return `${m}/${dd}` }
</script>

<template>
  <div class="tasks" data-testid="tasks-panel">
    <label class="opt"><input v-model="showDone" type="checkbox" /> 完了も表示</label>
    <p v-if="tasks === null" class="muted">読み込み中…</p>
    <p v-else-if="!tasks.length" class="muted">担当のToDoはありません。ToDoリストの項目で @名前 を書くと、その人の担当になります（「📅」で期限）。</p>
    <section v-for="g in groups" :key="g.key">
      <p class="head" :class="g.key">{{ g.label }}（{{ g.items.length }}）</p>
      <router-link v-for="t in g.items" :key="`${t.pageId}:${t.seq}`" :to="`/w/${t.workspaceId}/p/${t.pageId}`" class="item" :class="{ checked: t.checked }" data-testid="task-item">
        <div class="t">{{ t.checked ? '☑' : '☐' }} {{ t.text || '（内容なし）' }}</div>
        <div class="m">
          <span v-if="t.due" class="due" :class="g.key">📅 {{ fmt(t.due) }}</span>
          {{ t.pageIcon ?? '📄' }} {{ t.pageTitle || '無題' }}<template v-if="t.workspaceId !== props.wid"> ・ 🗂 {{ t.workspaceName }}</template>
        </div>
      </router-link>
    </section>
  </div>
</template>

<style scoped>
.tasks { padding: 0 .4rem; }
.opt { display: flex; align-items: center; gap: .3rem; font-size: .75rem; color: var(--muted); margin: 0 .3rem .3rem; }
.head { font-size: .75rem; font-weight: 600; color: var(--muted); margin: .6rem .3rem .2rem; }
.head.overdue { color: var(--danger); }
.item { display: block; padding: .35rem .5rem; border-radius: 6px; color: inherit; text-decoration: none; }
.item:hover, .item.router-link-active { background: var(--hover); }
.item.checked .t { text-decoration: line-through; color: var(--muted); }
.t { font-size: .88rem; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.m { font-size: .72rem; color: var(--muted); }
.due { margin-right: .3rem; }
.due.overdue { color: var(--danger); font-weight: 600; }
.due.today { color: #d97706; font-weight: 600; }
.muted { font-size: .8rem; padding: 0 .4rem; }
</style>
