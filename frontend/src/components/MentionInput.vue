<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { api4 } from '../api'

/**
 * コメント入力欄。「@」に続けて名前を入力すると候補を出す。⌘/Ctrl+Enter で送信。
 * 画面には「@名前」と表示し、親（v-model）には保存用の @[名前](ユーザーID) の形で渡す。
 */
const props = defineProps<{ modelValue: string; placeholder?: string; autofocus?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [v: string]; submit: []; cancel: [] }>()

const MARK = /@\[([^\]]*)\]\(([0-9a-fA-F-]{36})\)/g
/** 表示用の名前 → ユーザーID（選んだ人・既存の本文にいた人） */
const known = new Map<string, string>()
function toDisplay(v: string): string {
  return v.replace(MARK, (_, name: string, id: string) => { known.set(name, id); return `@${name}` })
}
function toStored(v: string): string {
  // 長い名前から置き換える（「佐藤」と「佐藤 花子」がいる場合など）
  const names = [...known.keys()].sort((a, b) => b.length - a.length)
  if (!names.length) return v
  const re = new RegExp(`@(${names.map((n) => n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|')})(?![^\\s@])`, 'g')
  return v.replace(re, (_, name: string) => `@[${name}](${known.get(name)})`)
}
const display = ref(toDisplay(props.modelValue))
let lastStored = props.modelValue
watch(() => props.modelValue, (v) => { if (v !== lastStored) { display.value = toDisplay(v); lastStored = v } })
function push(v: string) {
  display.value = v
  lastStored = toStored(v)
  emit('update:modelValue', lastStored)
}

const ta = ref<HTMLTextAreaElement | null>(null)
const cands = ref<{ id: string; name: string; detail: string | null }[]>([])
const sel = ref(0)
let at = -1 // 「@」の位置
let seq = 0

async function onInput(e: Event) {
  const el = e.target as HTMLTextAreaElement
  push(el.value)
  autosize()
  const before = el.value.slice(0, el.selectionStart)
  const m = /(^|\s)@([^\s@\[\]()]{1,30})$/.exec(before)
  if (!m) { cands.value = []; at = -1; return }
  at = before.length - m[2].length - 1
  const my = ++seq
  const r = await api4.directory(m[2]).catch(() => [])
  if (my !== seq) return
  cands.value = r.filter((d) => d.principalType === 'user').slice(0, 6).map((d) => ({ id: d.id, name: d.name, detail: d.detail }))
  sel.value = 0
}
async function choose(i: number) {
  const c = cands.value[i]
  const el = ta.value
  if (!c || !el || at < 0) return
  const v = display.value
  const name = c.name.replace(/[\[\]()@]/g, '')
  known.set(name, c.id)
  const insert = `@${name} `
  const next = v.slice(0, at) + insert + v.slice(el.selectionStart)
  push(next)
  cands.value = []
  await nextTick()
  const pos = at + insert.length
  el.focus()
  el.setSelectionRange(pos, pos)
  at = -1
}
function onKey(e: KeyboardEvent) {
  if (e.isComposing) return
  if (cands.value.length) {
    if (e.key === 'ArrowDown') { e.preventDefault(); sel.value = (sel.value + 1) % cands.value.length; return }
    if (e.key === 'ArrowUp') { e.preventDefault(); sel.value = (sel.value - 1 + cands.value.length) % cands.value.length; return }
    if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); choose(sel.value); return }
    if (e.key === 'Escape') { e.preventDefault(); cands.value = []; return }
  }
  if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) { e.preventDefault(); emit('submit') }
  else if (e.key === 'Escape') emit('cancel')
}
function autosize() {
  const el = ta.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, 240)}px`
}
defineExpose({ focus: () => ta.value?.focus() })
</script>

<template>
  <div class="mi">
    <textarea ref="ta" :value="display" :placeholder="placeholder ?? 'コメント（@で名前を入力するとメンション）'" rows="2" :autofocus="autofocus"
      data-testid="comment-input" @input="onInput" @keydown="onKey" />
    <div v-if="cands.length" class="cands" role="listbox">
      <button v-for="(c, i) in cands" :key="c.id" :class="{ active: i === sel }" @mousedown.prevent="choose(i)" @mouseenter="sel = i">
        👤 {{ c.name }} <small>{{ c.detail ?? '' }}</small>
      </button>
    </div>
  </div>
</template>

<style scoped>
.mi { position: relative; }
textarea { width: 100%; box-sizing: border-box; resize: none; font: inherit; font-size: .88rem; padding: .4rem .5rem; border: 1px solid var(--line); border-radius: 7px; background: var(--bg); color: inherit; min-height: 2.6em; }
textarea:focus { outline: none; border-color: var(--accent); }
.cands { position: absolute; left: 0; right: 0; top: 100%; z-index: 5; margin-top: 2px; background: var(--bg); border: 1px solid var(--line); border-radius: 7px; padding: 3px; box-shadow: 0 8px 20px rgba(0,0,0,.18); }
.cands button { display: block; width: 100%; text-align: left; border: none; padding: .3rem .5rem; border-radius: 5px; font-size: .85rem; }
.cands button.active { background: var(--hover); }
.cands small { color: var(--muted); }
</style>
