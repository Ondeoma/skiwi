<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import type { DbProp } from '../api'
import { alertDialog, pickUser } from '../ui/dialog'
import { option, text, type Ctx } from './db'

/** データベースの1つの値（表のセル・項目のプロパティ）。編集できるときはその場で変更する */
const props = defineProps<{ prop: DbProp; value: unknown; users: Record<string, string>; editable: boolean; compact?: boolean; ctx?: Ctx }>()
const emit = defineEmits<{ update: [value: unknown]; user: [id: string, name: string] }>()

const arr = computed(() => (Array.isArray(props.value) ? (props.value as unknown[]).filter((x): x is string => typeof x === 'string') : []))
const editing = ref(false)
const draft = ref('')
function startText() {
  if (!props.editable) return
  draft.value = props.value === null || props.value === undefined ? '' : String(props.value)
  editing.value = true
  void nextTick(() => { inputEl.value?.focus(); inputEl.value?.select() })
}
const inputEl = ref<HTMLInputElement | null>(null)
function commitText() {
  if (!editing.value) return
  editing.value = false
  const v = draft.value.trim()
  const cur = props.value === null || props.value === undefined ? '' : String(props.value)
  if (v === cur) return
  if (props.prop.type === 'number') {
    if (v === '') emit('update', null)
    else if (Number.isFinite(Number(v))) emit('update', Number(v))
    else void alertDialog('数値を入力してください')
  } else emit('update', v === '' ? null : v)
}
function onKey(e: KeyboardEvent) {
  if (e.isComposing) return
  if (e.key === 'Enter' && !(e.shiftKey && props.prop.type === 'text')) { e.preventDefault(); commitText() }
  if (e.key === 'Escape') { e.preventDefault(); editing.value = false }
}

// 複数選択: その場でチェック
const multiOpen = ref(false)
function toggleMulti(id: string, on: boolean) {
  emit('update', on ? [...arr.value, id] : arr.value.filter((x) => x !== id))
}

// ユーザー: 名前・メールで検索して追加
async function addPerson() {
  const u = await pickUser('追加するユーザー（名前・メールで検索）', { chooseMessage: 'ユーザーを選んでください' })
  if (!u || arr.value.includes(u.id)) return
  emit('user', u.id, u.name)
  emit('update', [...arr.value, u.id])
}
// 関連: 関連先のデータベースの項目から選ぶ
const relOpen = ref(false)
const relQ = ref('')
const target = computed(() => (props.prop.target ? props.ctx?.targets[props.prop.target] : undefined))
const relRows = computed(() => {
  const q = relQ.value.trim().toLowerCase()
  return (target.value?.rows ?? []).filter((r) => !q || (r.title || '無題').toLowerCase().includes(q)).slice(0, 50)
})
const relTitle = (id: string) => target.value?.rows.find((r) => r.id === id)?.title || '（見られない項目）'
function toggleRel(id: string, on: boolean) {
  emit('update', on ? [...arr.value, id] : arr.value.filter((x) => x !== id))
}
const urlOk = computed(() => typeof props.value === 'string' && /^(https?:\/\/|mailto:)/i.test(props.value))
</script>

<template>
  <div class="cell" :class="[`t-${prop.type}`, { compact, ro: !editable }]" data-testid="db-cell">
    <!-- チェック -->
    <input v-if="prop.type === 'checkbox'" type="checkbox" :checked="!!value" :disabled="!editable" @change="emit('update', ($event.target as HTMLInputElement).checked)" />

    <!-- 選択 -->
    <template v-else-if="prop.type === 'select'">
      <select v-if="editable" class="sel" :value="option(prop, value) ? value : ''" data-testid="db-select" @change="emit('update', ($event.target as HTMLSelectElement).value || null)">
        <option value="">（なし）</option>
        <option v-for="o in prop.options ?? []" :key="o.id" :value="o.id">{{ o.name }}</option>
      </select>
      <span v-if="option(prop, value)" class="tag" :class="`c-${option(prop, value)!.color}`">{{ option(prop, value)!.name }}</span>
    </template>

    <!-- 複数選択 -->
    <template v-else-if="prop.type === 'multi_select'">
      <span v-for="id in arr" :key="id" class="tag" :class="`c-${option(prop, id)?.color ?? 'gray'}`" v-show="option(prop, id)">{{ option(prop, id)?.name }}</span>
      <button v-if="editable" class="add" title="選ぶ" @click="multiOpen = !multiOpen">＋</button>
      <div v-if="multiOpen" class="pop" @mouseleave="multiOpen = false">
        <label v-for="o in prop.options ?? []" :key="o.id"><input type="checkbox" :checked="arr.includes(o.id)" @change="toggleMulti(o.id, ($event.target as HTMLInputElement).checked)" /> <span class="tag" :class="`c-${o.color}`">{{ o.name }}</span></label>
        <p v-if="!(prop.options ?? []).length" class="muted">選択肢がありません（列の設定で追加）</p>
      </div>
    </template>

    <!-- 関連 -->
    <template v-else-if="prop.type === 'relation'">
      <router-link v-for="id in arr" :key="id" :to="target ? `/w/${$route.params.wid}/p/${id}` : ''" class="rel" data-testid="db-rel">↗ {{ relTitle(id) }}</router-link>
      <button v-if="editable" class="add" :title="target ? '関連する項目を選ぶ' : '関連先のデータベースを閲覧できません'" :disabled="!target" data-testid="db-rel-add" @click="relOpen = !relOpen">＋</button>
      <div v-if="relOpen && target" class="pop rel-pop" data-testid="db-rel-pop" @mouseleave="relOpen = false">
        <input v-model="relQ" placeholder="🔍 項目を検索" class="rq" />
        <label v-for="r in relRows" :key="r.id"><input type="checkbox" :checked="arr.includes(r.id)" @change="toggleRel(r.id, ($event.target as HTMLInputElement).checked)" /> {{ r.icon ?? '📄' }} {{ r.title || '無題' }}</label>
        <p v-if="!relRows.length" class="muted">項目がありません（「{{ target.title || '無題' }}」）</p>
      </div>
    </template>

    <!-- 数式・自動の列 -->
    <span v-else-if="['formula', 'created_time', 'edited_time', 'created_by', 'edited_by', 'unique_id'].includes(prop.type)" class="txt ro" :class="{ num: typeof value === 'number' }" data-testid="db-computed">{{ text(prop, value, users, ctx) }}</span>

    <!-- 集計（計算した値） -->
    <span v-else-if="prop.type === 'rollup'" class="txt num ro" data-testid="db-rollup">{{ value ?? '' }}</span>

    <!-- ユーザー -->
    <template v-else-if="prop.type === 'person'">
      <span v-for="id in arr" :key="id" class="person">👤 {{ users[id] ?? '（不明）' }}<button v-if="editable" class="x" title="外す" @click="emit('update', arr.filter((x) => x !== id))">×</button></span>
      <button v-if="editable" class="add" title="ユーザーを追加" data-testid="db-person-add" @click="addPerson">＋</button>
    </template>

    <!-- 日付 -->
    <input v-else-if="prop.type === 'date' && editable" type="date" class="date" :value="typeof value === 'string' ? value : ''" @change="emit('update', ($event.target as HTMLInputElement).value || null)" />
    <span v-else-if="prop.type === 'date'">{{ value ?? '' }}</span>

    <!-- テキスト・数値・URL -->
    <template v-else>
      <input
        v-if="editing"
        v-model="draft"
        class="inp"
        :type="prop.type === 'number' ? 'text' : prop.type === 'url' ? 'url' : 'text'"
        :inputmode="prop.type === 'number' ? 'decimal' : undefined"
        ref="inputEl"
        data-testid="db-input"
        @blur="commitText"
        @keydown="onKey"
      />
      <span v-else class="txt" :class="{ num: prop.type === 'number', empty: value === null || value === undefined || value === '' }" :tabindex="editable ? 0 : -1" data-testid="db-text" @click="startText" @keydown.enter="startText">
        <a v-if="prop.type === 'url' && urlOk" :href="String(value)" target="_blank" rel="noopener" @click.stop>{{ value }}</a>
        <template v-else>{{ value ?? '' }}</template>
      </span>
    </template>
  </div>
</template>

<style scoped>
.cell { position: relative; display: flex; flex-wrap: wrap; align-items: center; gap: 3px; min-height: 26px; font-size: .85rem; }
.txt { flex: 1; min-width: 2rem; min-height: 1.3em; cursor: text; white-space: pre-wrap; word-break: break-word; }
.ro .txt { cursor: default; }
.txt.num { text-align: right; font-variant-numeric: tabular-nums; }
.inp { flex: 1; min-width: 4rem; font: inherit; padding: 1px 4px; border: 1px solid var(--accent); border-radius: 4px; background: var(--bg); color: inherit; }
.sel { font: inherit; font-size: .8rem; border: 1px solid transparent; background: transparent; color: transparent; position: absolute; inset: 0; opacity: 0; cursor: pointer; }
.date { font: inherit; font-size: .8rem; border: 1px solid transparent; background: transparent; color: inherit; padding: 0; }
.date:hover, .date:focus { border-color: var(--line); }
.tag { display: inline-block; padding: 0 7px; border-radius: 4px; font-size: .78rem; line-height: 1.6; white-space: nowrap; }
.rel { font-size: .8rem; color: var(--accent); text-decoration: none; background: var(--hover); border-radius: 4px; padding: 0 5px; white-space: nowrap; }
.rel-pop { max-height: 16rem; overflow: auto; min-width: 14rem; }
.rq { font: inherit; font-size: .8rem; padding: 2px 6px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; margin-bottom: .2rem; }
.person { display: inline-flex; align-items: center; gap: 2px; font-size: .8rem; background: var(--hover); border-radius: 10px; padding: 0 6px; white-space: nowrap; }
.x { border: none; padding: 0 2px; color: var(--muted); font-size: .8rem; }
.add { border: 1px dashed var(--line); border-radius: 4px; padding: 0 5px; font-size: .75rem; color: var(--muted); opacity: .6; }
.cell:hover .add { opacity: 1; }
.pop { position: absolute; z-index: 30; top: 100%; left: 0; min-width: 10rem; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: .4rem .5rem; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; gap: .2rem; }
.pop label { display: flex; align-items: center; gap: .3rem; cursor: pointer; }
.muted { font-size: .75rem; margin: 0; }
a { color: var(--accent); word-break: break-all; }
</style>
