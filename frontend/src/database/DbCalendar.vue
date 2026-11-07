<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DbProp, DbRow } from '../api'
import { ymd } from '../ui/date'
import { thisMonth } from './db'

/** データベースのカレンダー（月曜はじまり）。項目を別の日へドラッグすると日付を変える（表示する月 month は、ビューを切り替えても残るよう親が持つ） */
const props = defineProps<{
  rows: DbRow[]
  dateProp: DbProp | null
  hasDateProps: boolean
  canEdit: boolean
  rowHref: (r: DbRow) => string
}>()
const emit = defineEmits<{ set: [row: DbRow, prop: DbProp, value: unknown]; add: [values: Record<string, unknown>, label: string] }>()

const today = ymd(new Date())
const month = defineModel<Date>('month', { required: true })
const days = computed(() => {
  const first = new Date(month.value)
  const start = new Date(first)
  start.setDate(1 - ((first.getDay() + 6) % 7)) // 月曜はじまり
  return Array.from({ length: 42 }, (_, i) => { const d = new Date(start); d.setDate(start.getDate() + i); return d })
})
const byDay = computed(() => {
  const m = new Map<string, DbRow[]>()
  const p = props.dateProp
  if (!p) return m
  for (const r of props.rows) {
    const v = r.values[p.id]
    if (typeof v === 'string') m.set(v, [...(m.get(v) ?? []), r])
  }
  return m
})
const undated = computed(() => (props.dateProp ? props.rows.filter((r) => typeof r.values[props.dateProp!.id] !== 'string') : []))
function shiftMonth(n: number) { const d = new Date(month.value); d.setMonth(d.getMonth() + n); month.value = d }

const dragRow = ref<string | null>(null)
const dropDay = ref<string | null>(null)
function dropOnDay(day: string) {
  const p = props.dateProp
  const r = props.rows.find((x) => x.page.id === dragRow.value)
  dragRow.value = null
  dropDay.value = null
  if (p && r && r.values[p.id] !== day) emit('set', r, p, day)
}
</script>

<template>
  <div class="cal" data-testid="db-calendar">
    <p v-if="!dateProp" class="muted">「⚙ 絞り込み・並べ替え」で、日付の項目を選んでください。<template v-if="!hasDateProps">先に「日付」の列を追加してください。</template></p>
    <template v-else>
      <div class="cal-head">
        <button class="ghost" @click="shiftMonth(-1)">‹</button>
        <b data-testid="db-cal-month">{{ month.getFullYear() }}年{{ month.getMonth() + 1 }}月</b>
        <button class="ghost" @click="shiftMonth(1)">›</button>
        <button class="ghost" @click="month = thisMonth()">今月</button>
      </div>
      <div class="grid">
        <div v-for="w in ['月', '火', '水', '木', '金', '土', '日']" :key="w" class="wd">{{ w }}</div>
        <div
          v-for="d in days"
          :key="ymd(d)"
          class="day"
          :class="{ out: d.getMonth() !== month.getMonth(), today: ymd(d) === today, drop: dropDay === ymd(d) }"
          :data-day="ymd(d)"
          data-testid="db-day"
          @dragover.prevent="dropDay = ymd(d)"
          @dragleave="dropDay = null"
          @drop.prevent="dropOnDay(ymd(d))"
        >
          <div class="dnum">{{ d.getDate() }}<button v-if="canEdit" class="ghost dadd" title="この日に追加" @click="emit('add', { [dateProp.id]: ymd(d) }, ymd(d))">＋</button></div>
          <router-link v-for="r in byDay.get(ymd(d)) ?? []" :key="r.page.id" :to="rowHref(r)" class="ev" :draggable="r.page.role === 'edit'" data-testid="db-event" @dragstart="dragRow = r.page.id" @dragend="dragRow = null">
            {{ r.page.title || '無題' }}
          </router-link>
        </div>
      </div>
      <details v-if="undated.length" class="undated">
        <summary>日付のない項目（{{ undated.length }}）</summary>
        <router-link v-for="r in undated" :key="r.page.id" :to="rowHref(r)" class="ev" :draggable="r.page.role === 'edit'" @dragstart="dragRow = r.page.id" @dragend="dragRow = null">{{ r.page.title || '無題' }}</router-link>
      </details>
    </template>
  </div>
</template>

<style scoped src="./shared.css"></style>
<style scoped>
.grid { display: grid; grid-template-columns: repeat(7, 1fr); border-left: 1px solid var(--line); border-top: 1px solid var(--line); }
.wd { font-size: .72rem; color: var(--muted); text-align: center; padding: 2px; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.day { min-height: 5.2rem; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); padding: 2px 3px; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.day.out { background: var(--side); opacity: .6; }
.day.today .dnum { color: var(--accent); font-weight: 700; }
.day.drop { outline: 2px dashed var(--accent); outline-offset: -2px; }
.dnum { font-size: .72rem; display: flex; justify-content: space-between; }
.dadd { padding: 0 3px; font-size: .7rem; opacity: 0; }
.day:hover .dadd { opacity: 1; }
.ev { display: block; font-size: .74rem; background: color-mix(in srgb, var(--accent) 16%, transparent); border-radius: 4px; padding: 0 4px; color: inherit; text-decoration: none; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; cursor: grab; }
.undated { margin-top: .5rem; font-size: .82rem; }
.undated .ev { display: inline-block; margin: 2px; }
@media (max-width: 640px) { .day { min-height: 3.6rem; } }
@media print { .dadd { display: none !important; } }
</style>
