<script setup lang="ts">
import { computed, ref } from 'vue'
import type { DbProp, DbRow } from '../api'
import { ymd } from '../ui/date'
import { weekAgo } from './db'

/** データベースのタイムライン: 開始〜終了の期間を横棒で。横棒のドラッグで日付を変える（表示範囲 start・zoom は、ビューを切り替えても残るよう親が持つ） */
const props = defineProps<{
  rows: DbRow[]
  startProp: DbProp | null
  endProp: DbProp | null
  hasDateProps: boolean
  rowHref: (r: DbRow) => string
  setValue: (r: DbRow, p: DbProp, v: unknown) => Promise<void>
}>()

const DAYMS = 86400000
const today = ymd(new Date())
const zoom = defineModel<'day' | 'week'>('zoom', { required: true })
const start = defineModel<Date>('start', { required: true })
const days = computed(() => (zoom.value === 'day' ? 42 : 120))
const colW = computed(() => (zoom.value === 'day' ? 34 : 12))
const parseD = (s: unknown) => (typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) ? new Date(`${s}T00:00:00`) : null)
const cols = computed(() => Array.from({ length: days.value }, (_, i) => { const d = new Date(start.value); d.setDate(d.getDate() + i); return d }))
const items = computed(() => {
  const s = props.startProp
  if (!s) return []
  return props.rows.map((r) => {
    const a = parseD(r.values[s.id])
    const b = props.endProp ? parseD(r.values[props.endProp.id]) ?? a : a
    if (!a || !b) return { row: r, left: -1, width: 0 }
    const from = Math.round((a.getTime() - start.value.getTime()) / DAYMS)
    const to = Math.round((Math.max(a.getTime(), b.getTime()) - start.value.getTime()) / DAYMS)
    return { row: r, left: from, width: to - from + 1 }
  })
})
function shift(n: number) { const d = new Date(start.value); d.setDate(d.getDate() + n); start.value = d }

/** 横棒のドラッグ: 移動（両端を同じだけずらす）・右端で終了日だけ変える */
const drag = ref<{ id: string; mode: 'move' | 'end'; x0: number; dx: number } | null>(null)
function down(e: MouseEvent, r: DbRow, mode: 'move' | 'end') {
  if (r.page.role !== 'edit') return
  e.preventDefault()
  drag.value = { id: r.page.id, mode, x0: e.clientX, dx: 0 }
  const move = (ev: MouseEvent) => { if (drag.value) drag.value = { ...drag.value, dx: ev.clientX - drag.value.x0 } }
  const up = async () => {
    window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up)
    const d = drag.value
    drag.value = null
    if (!d) return
    const n = Math.round(d.dx / colW.value)
    const sp = props.startProp
    const ep = props.endProp
    if (!n || !sp) return
    const shifted = (s: unknown) => { const x = parseD(s); if (!x) return null; x.setDate(x.getDate() + n); return ymd(x) }
    const row = props.rows.find((x) => x.page.id === d.id)
    if (!row) return
    if (d.mode === 'move') {
      await props.setValue(row, sp, shifted(row.values[sp.id]))
      if (ep && row.values[ep.id]) await props.setValue(row, ep, shifted(row.values[ep.id]))
    } else if (ep) {
      await props.setValue(row, ep, shifted(row.values[ep.id] ?? row.values[sp.id]))
    }
  }
  window.addEventListener('mousemove', move); window.addEventListener('mouseup', up)
}
const barStyle = (it: { row: DbRow; left: number; width: number }) => {
  const d = drag.value?.id === it.row.page.id ? drag.value : null
  const dl = d ? Math.round(d.dx / colW.value) : 0
  const left = it.left + (d?.mode === 'move' ? dl : 0)
  const width = Math.max(1, it.width + (d?.mode === 'end' ? dl : 0))
  return { left: `${left * colW.value}px`, width: `${width * colW.value - 4}px` }
}
</script>

<template>
  <div class="timeline" data-testid="db-timeline">
    <p v-if="!startProp" class="muted">「⚙ 絞り込み・並べ替え」で、開始の日付の列を選んでください。<template v-if="!hasDateProps">先に「日付」の列を追加してください。</template></p>
    <template v-else>
      <div class="cal-head">
        <button class="ghost" @click="shift(zoom === 'day' ? -14 : -60)">‹</button>
        <button class="ghost" @click="start = weekAgo()">今日</button>
        <button class="ghost" @click="shift(zoom === 'day' ? 14 : 60)">›</button>
        <select v-model="zoom" class="tl-zoom"><option value="day">日</option><option value="week">週</option></select>
      </div>
      <div class="tl-wrap">
        <div class="tl-names">
          <div class="tl-hrow" />
          <div v-for="it in items" :key="it.row.page.id" class="tl-name"><router-link :to="rowHref(it.row)" class="rlink">{{ it.row.page.title || '無題' }}</router-link></div>
        </div>
        <div class="tl-grid" :style="{ width: `${days * colW}px` }">
          <div class="tl-hrow">
            <div v-for="(d, i) in cols" :key="i" class="tl-day" :class="{ today: ymd(d) === today, wk: d.getDay() === 0 || d.getDay() === 6 }" :style="{ width: `${colW}px` }">
              <template v-if="zoom === 'day' || d.getDate() === 1 || d.getDay() === 1">{{ d.getDate() === 1 || i === 0 ? `${d.getMonth() + 1}/` : '' }}{{ zoom === 'day' || d.getDay() === 1 ? d.getDate() : '' }}</template>
            </div>
          </div>
          <div v-for="it in items" :key="it.row.page.id" class="tl-row">
            <div v-if="it.left >= -it.width && it.width > 0" class="tl-bar" :class="{ ro: it.row.page.role !== 'edit' }" :style="barStyle(it)" :title="`${it.row.page.title || '無題'}`" data-testid="db-tl-bar" @mousedown="down($event, it.row, 'move')">
              <span class="tl-label">{{ it.row.page.title || '無題' }}</span>
              <span v-if="endProp" class="tl-grip" @mousedown.stop="down($event, it.row, 'end')" />
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped src="./shared.css"></style>
<style scoped>
.timeline .tl-wrap { display: flex; border: 1px solid var(--line); border-radius: 6px; overflow: hidden; }
.tl-names { flex: none; width: 11rem; border-right: 1px solid var(--line); background: var(--bg); }
.tl-name { height: 32px; display: flex; align-items: center; padding: 0 .5rem; font-size: .82rem; border-bottom: 1px solid var(--line); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.tl-grid { overflow-x: auto; position: relative; flex: 1; }
.tl-wrap .tl-grid { max-width: calc(100% - 11rem); }
.tl-hrow { height: 26px; display: flex; border-bottom: 1px solid var(--line); background: var(--side); }
.tl-day { flex: none; font-size: .66rem; color: var(--muted); text-align: center; line-height: 26px; border-right: 1px solid color-mix(in srgb, var(--line) 50%, transparent); }
.tl-day.wk { background: color-mix(in srgb, var(--hover) 60%, transparent); }
.tl-day.today { color: var(--accent); font-weight: 700; }
.tl-row { height: 32px; position: relative; border-bottom: 1px solid var(--line); }
.tl-bar { position: absolute; top: 5px; height: 22px; border-radius: 5px; background: color-mix(in srgb, var(--accent) 22%, var(--bg)); border: 1px solid color-mix(in srgb, var(--accent) 55%, transparent); cursor: grab; display: flex; align-items: center; overflow: hidden; margin-left: 2px; }
.tl-bar.ro { cursor: default; }
.tl-label { font-size: .74rem; padding: 0 6px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; flex: 1; }
.tl-grip { width: 7px; align-self: stretch; cursor: ew-resize; background: color-mix(in srgb, var(--accent) 40%, transparent); }
.tl-zoom { font: inherit; font-size: .78rem; }
</style>
