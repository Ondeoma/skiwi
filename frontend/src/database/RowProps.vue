<script setup lang="ts">
import { ref, watch } from 'vue'
import { dbApi, type DbProp, type PageProps } from '../api'
import { alertError } from '../ui/dialog'
import DbCell from './DbCell.vue'
import { cellValue, COMPUTED, TYPE_ICON, type Ctx } from './db'
import { computed } from 'vue'

/** データベースの項目（子ページ）の値。ページの上部に出す */
const props = defineProps<{ pageId: string; wid: string }>()
const data = ref<PageProps | null>(null)
async function load() {
  try { data.value = await dbApi.props(props.pageId) } catch { data.value = null }
}
watch(() => props.pageId, load, { immediate: true })
defineExpose({ load })

async function set(p: DbProp, v: unknown) {
  if (!data.value) return
  const before = data.value.values
  data.value.values = { ...before, [p.id]: v }
  try {
    const r = await dbApi.setValues(props.pageId, { [p.id]: v })
    const next = { ...data.value.values }
    if (r.values[p.id] === undefined) delete next[p.id]
    else next[p.id] = r.values[p.id]
    data.value = { ...data.value, values: next, users: { ...data.value.users, ...r.users } }
  } catch (e) {
    data.value.values = before
    await alertError('変更できませんでした', e)
  }
}
const ctx = computed<Ctx>(() => ({ props: data.value?.database?.props ?? [], targets: data.value?.targets ?? {}, users: data.value?.users ?? {} }))
/** 作成日時など、項目の一覧でだけ分かる列は出さない */
const shownProps = computed(() => (data.value?.database?.props ?? []).filter((p) => !['created_time', 'edited_time', 'created_by', 'edited_by'].includes(p.type)))
const onUser = (id: string, name: string) => { if (data.value) data.value.users = { ...data.value.users, [id]: name } }
</script>

<template>
  <div v-if="data?.database && data.database.props.length" class="rowprops" data-testid="row-props">
    <div class="from">
      <router-link :to="`/w/${wid}/p/${data.database.pageId}`">📊 {{ data.database.title || '無題' }}</router-link> の項目
    </div>
    <div v-for="p in shownProps" :key="p.id" class="prop" data-testid="row-prop">
      <span class="name">{{ TYPE_ICON[p.type] }} {{ p.name }}</span>
      <DbCell class="val" :prop="p" :value="cellValue(p, { values: data.values }, ctx)" :users="data.users" :ctx="ctx" :editable="data.canEdit && !COMPUTED.includes(p.type)" @update="(v) => set(p, v)" @user="onUser" />
    </div>
  </div>
</template>

<style scoped>
.rowprops { margin: .6rem 0 .2rem; padding: .4rem 0; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); display: grid; grid-template-columns: minmax(7rem, 11rem) 1fr; gap: 2px .8rem; font-size: .85rem; }
.from { grid-column: 1 / -1; font-size: .75rem; color: var(--muted); margin-bottom: .2rem; }
.from a { color: var(--accent); text-decoration: none; }
.prop { display: contents; }
.name { color: var(--muted); padding: 3px 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.val { min-width: 0; }
@media (max-width: 560px) { .rowprops { grid-template-columns: 6.5rem 1fr; } }
</style>
