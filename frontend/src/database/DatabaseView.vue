<script setup lang="ts">
import HelpLink from '../ui/HelpLink.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { dbApi, type CalcFn, type Database, type DbFilter, type DbOption, type DbProp, type DbPropType, type DbRow, type DbView, type RollupFn } from '../api'
import { useWorkspaceStore } from '../stores/workspace'
import { alertDialog, alertError, chooseDialog, confirmDialog, promptDialog } from '../ui/dialog'
import DbCell from './DbCell.vue'
import { applyView, calc, CALC_LABEL, calcsFor, cellValue, COLOR_LABEL, COLORS, COMPUTED, type Ctx, FN_LABEL, like, needsValue, newId, opsFor, option, planCsvImport, readCsv, text, thisMonth, toCsv, TYPE_ICON, TYPE_LABEL, weekAgo } from './db'
import { pickFiles } from '../ui/fileDrop'
import { check as checkFormula, FORMULA_HELP } from './formula'
import FormsDialog from './FormsDialog.vue'
import DbCalendar from './DbCalendar.vue'
import DbTimeline from './DbTimeline.vue'
import { download } from '../editor/markdown'
import { useEventListener, useStoredRef } from '../ui/composables'

/** データベース: 子ページを項目として、表・ボード・カレンダーで見る。列（項目の種類）とビューはデータベースのページに保存する */
/** embedded: ほかのページの中に置いたデータベース（localViews: その場所だけのビュー。変えると views で知らせる） */
const props = defineProps<{ pageId: string; wid: string; embedded?: boolean; localViews?: DbView[] | null }>()
const emit = defineEmits<{ views: [views: DbView[]] }>()
const store = useWorkspaceStore()
const db = ref<Database | null>(null)
const error = ref('')
const canEdit = computed(() => !!db.value?.canEdit)

async function load() {
  try { db.value = await dbApi.get(props.pageId); error.value = '' } catch (e) { error.value = (e as Error).message }
}
onMounted(load)
// ツリーで項目（子ページ）を追加・削除・名前の変更をしたら読み直す
watch(() => store.pages.filter((p) => p.parentId === props.pageId).map((p) => `${p.id}:${p.title}:${p.icon}`).join(), (_v, old) => { if (old !== undefined && db.value) void load() })
// ほかの画面（項目のページ）で変えた値を反映する
const onFocus = () => { if (document.visibilityState === 'visible') void load() }
useEventListener(document, 'visibilitychange', onFocus)

// --- 定義の保存（列・ビュー）。閲覧のみの人のビューの変更は、この画面だけ ---
let saveTimer: ReturnType<typeof setTimeout> | undefined
function saveSoon() {
  if (!canEdit.value || !db.value) return
  clearTimeout(saveTimer)
  saveTimer = setTimeout(save, 400)
}
async function save() {
  if (!db.value) return
  try {
    const r = await dbApi.save(props.pageId, db.value.props, db.value.views)
    db.value = { ...db.value, props: r.props, views: r.views, targets: r.targets, rows: r.rows }
  } catch (e) { await alertError('保存できませんでした', e) }
}

// --- ビュー ---
const viewKey = () => `skiwi.dbView.${props.pageId}`
const activeId = useStoredRef<string | null>(viewKey, (raw) => (props.embedded ? null : raw), (v) => (props.embedded || !v ? null : v))
/** 表示するビュー（ページの中に置いたものは、その場所のビュー） */
const views = computed<DbView[]>(() => (props.embedded && props.localViews?.length ? props.localViews : db.value?.views ?? []))
const view = computed<DbView | null>(() => views.value.find((v) => v.id === activeId.value) ?? views.value[0] ?? null)
function setViews(vs: DbView[]) {
  if (!db.value) return
  if (props.embedded) { emit('views', vs); return }
  db.value = { ...db.value, views: vs }
  saveSoon()
}
function patchView(patch: Partial<DbView>) {
  if (!view.value) return
  const id = view.value.id
  setViews(views.value.map((v) => (v.id === id ? { ...v, ...patch } : v)))
}
async function addView() {
  if (!db.value) return
  const type = await chooseDialog('ビューの種類', (Object.keys(VIEW_LABEL) as DbView['type'][]).map((t) => ({ label: `${VIEW_ICON[t]} ${VIEW_LABEL[t]}`, value: t }))) as DbView['type'] | null
  if (!type) return
  const name = (await promptDialog('ビューの名前', VIEW_LABEL[type].replace(/（.*）/, ''), { okLabel: '追加' }))?.trim()
  if (!name) return
  const d0 = dateProps.value[0]?.id ?? null
  const v: DbView = {
    id: newId(), name, type,
    groupBy: type === 'board' ? selectProps.value[0]?.id ?? null : null,
    dateProp: type === 'calendar' ? d0 : null,
    ...(type === 'timeline' ? { startProp: d0, endProp: dateProps.value[1]?.id ?? null } : {}),
    ...(type === 'gallery' ? { cover: 'content' } : {}),
  }
  setViews([...views.value, v])
  activeId.value = v.id
}
// --- CSV ---
function exportCsv() {
  if (!db.value) return
  const name = store.byId.get(props.pageId)?.title || 'データベース'
  download(`${name}.csv`, '', 'text/csv', new Blob([toCsv(visibleProps.value, rows.value, ctx.value)], { type: 'text/csv;charset=utf-8' }))
}
/** CSV を取り込む: 1行目は列名（取り込み方は planCsvImport） */
async function importCsv() {
  const [f] = await pickFiles('.csv,text/csv')
  if (!f || !db.value) return
  const rowsIn = await readCsv(f)
  if (rowsIn.length < 2) { await alertDialog('取り込める行がありません（1行目は列名にしてください）'); return }
  if (rowsIn.length - 1 > 2000) { await alertDialog('一度に取り込めるのは2000行までです'); return }
  const { props: ps, added, items } = planCsvImport(rowsIn, db.value)
  const ok = await confirmDialog(`${items.length} 件の項目を取り込みますか？${added.length ? `\n新しい列（テキスト）: ${added.join('、')}` : ''}`, { okLabel: '取り込む' })
  if (!ok) return
  try {
    const saved = await dbApi.save(props.pageId, ps, db.value.views)
    db.value = { ...db.value, props: saved.props, targets: saved.targets }
    const r = await dbApi.addRows(props.pageId, items)
    await load()
    void store.reloadPages()
    await alertDialog(`${r.created} 件を取り込みました`)
  } catch (e) { await alertError('取り込めませんでした', e) }
}

async function viewMenu() {
  if (!db.value || !view.value) return
  const a = await chooseDialog(`ビュー「${view.value.name}」`, [
    ...(canEdit.value && !props.embedded ? [{ label: '📝 フォーム（回答が項目になる）', value: 'forms' }] : []),
    { label: '⤓ CSVで書き出し（このビューの項目・列）', value: 'csv-out' },
    ...(canEdit.value ? [{ label: '⤒ CSVから取り込み', value: 'csv-in' }] : []),
    { label: '✏️ 名前を変更', value: 'rename' },
    ...(views.value.length > 1 ? [{ label: '🗑 このビューを削除', value: 'delete' }] : []),
  ])
  if (a === 'forms') { formsOpen.value = true; return }
  if (a === 'csv-out') { exportCsv(); return }
  if (a === 'csv-in') { importCsv(); return }
  if (a === 'rename') {
    const n = (await promptDialog('ビューの名前', view.value.name, { okLabel: '変更' }))?.trim()
    if (n) patchView({ name: n })
  } else if (a === 'delete' && await confirmDialog(`ビュー「${view.value.name}」を削除しますか？（項目は消えません）`, { okLabel: '削除', danger: true })) {
    const id = view.value.id
    const rest = views.value.filter((v) => v.id !== id)
    setViews(rest)
    activeId.value = rest[0]?.id ?? null
  }
}
const formsOpen = ref(false)
const VIEW_LABEL: Record<DbView['type'], string> = {
  table: '表', board: 'ボード（状態などで列に分ける）', calendar: 'カレンダー（日付で並べる）', list: 'リスト（名前と主な値を一覧）',
  gallery: 'ギャラリー（カードで並べる。本文の画像を表紙に）', timeline: 'タイムライン（開始〜終了の期間を横棒で）',
}
const VIEW_ICON: Record<DbView['type'], string> = { table: '▦', board: '▥', calendar: '📅', list: '☰', gallery: '🖼', timeline: '📊' }

// --- 表示する項目 ---
const q = ref('')
const ctx = computed<Ctx>(() => ({ props: db.value?.props ?? [], targets: db.value?.targets ?? {}, users: db.value?.users ?? {} }))
/**
 * セルの値（数式・集計を含む）の計算結果。表示のたびに計算し直さないよう、行ごと・列ごとに覚えておく。
 * 行の値・情報・名前、列、関連先のどれかが変わったら作り直す。
 */
const valCache = computed(() => {
  for (const r of db.value?.rows ?? []) { void r.values; void r.meta; void r.page.title }
  void ctx.value
  return new Map<DbRow, Map<string, unknown>>()
})
function val(p: DbProp, r: DbRow): unknown {
  let m = valCache.value.get(r)
  if (!m) valCache.value.set(r, (m = new Map()))
  if (!m.has(p.id)) m.set(p.id, cellValue(p, like(r), ctx.value))
  return m.get(p.id)
}
/** 表のサブ項目を親の下に入れる（既定） */
const nest = computed(() => view.value?.type === 'table' && view.value.subItems !== false)
const filtered = computed(() => (db.value && view.value ? applyView(view.value, db.value.props, db.value.rows, db.value.users, store.me?.id, q.value, db.value.targets) : []))
/** 表示する項目（表でサブ項目を入れるときは、最上位の項目だけ。サブ項目は subsOf で） */
const rows = computed(() => (nest.value ? filtered.value.filter((r) => r.page.parentId === props.pageId) : filtered.value))
const subsOf = (id: string) => (nest.value ? filtered.value.filter((r) => r.page.parentId === id) : [])
const parentIds = computed(() => new Set(db.value?.rows.map((r) => r.page.parentId)))
const hasSubs = (id: string) => parentIds.value.has(id)
const openSubs = ref(new Set<string>())
function toggleSubs(id: string) { const n = new Set(openSubs.value); n.has(id) ? n.delete(id) : n.add(id); openSubs.value = n }
/** 表の行（サブ項目を開いていれば、その下に入れる） */
const tableRows = computed(() => {
  const out: { row: DbRow; depth: number }[] = []
  for (const r of rows.value) {
    out.push({ row: r, depth: 0 })
    if (nest.value && openSubs.value.has(r.page.id)) for (const c of subsOf(r.page.id)) out.push({ row: c, depth: 1 })
  }
  return out
})
async function addSub(parent: DbRow) {
  const t = (await promptDialog(`「${parent.page.title || '無題'}」のサブ項目の名前`, '', { okLabel: '追加' }))?.trim()
  if (!t || !db.value) return
  try {
    const r = await dbApi.addRow(props.pageId, t, {}, parent.page.id)
    db.value.rows = [...db.value.rows, r]
    openSubs.value = new Set([...openSubs.value, parent.page.id])
    void store.reloadPages()
  } catch (e) { await alertError('追加できませんでした', e) }
}

// --- グループ化（表・リスト）: 選択・チェック・ユーザーの列ごとに区切る ---
const groupableProps = computed(() => (db.value?.props ?? []).filter((p) => ['select', 'checkbox', 'person', 'multi_select'].includes(p.type)))
const tableGroup = computed(() => db.value?.props.find((p) => p.id === view.value?.group && groupableProps.value.includes(p)) ?? null)
const collapsedGroups = ref(new Set<string>())
function toggleGroup(k: string) { const n = new Set(collapsedGroups.value); n.has(k) ? n.delete(k) : n.add(k); collapsedGroups.value = n }
/** グループ: [キー, 見出し, 色, 項目の行] */
const groups = computed(() => {
  const g = tableGroup.value
  const all = tableRows.value
  if (!g) return [{ key: '', label: '', color: null as string | null, items: all }]
  const keyOf = (r: DbRow): string[] => {
    const v = r.values[g.id]
    if (g.type === 'checkbox') return [v ? 'true' : 'false']
    if (g.type === 'select') return [option(g, v) ? String(v) : '']
    const a = Array.isArray(v) ? (v as string[]).filter((x) => g.type !== 'multi_select' || option(g, x)) : []
    return a.length ? a : ['']
  }
  const order: { key: string; label: string; color: string | null }[] =
    g.type === 'checkbox' ? [{ key: 'true', label: '✓ あり', color: null }, { key: 'false', label: 'なし', color: null }]
    : g.type === 'person' ? [...new Set(all.flatMap((x) => (x.depth === 0 ? keyOf(x.row) : [])))].filter(Boolean).map((k) => ({ key: k, label: `👤 ${db.value?.users[k] ?? '（不明）'}`, color: null }))
    : (g.options ?? []).map((o) => ({ key: o.id, label: o.name, color: o.color }))
  order.push(...(g.type === 'checkbox' ? [] : [{ key: '', label: '未設定', color: 'gray' }]))
  // サブ項目は親と同じグループに入れる
  const out = order.map((o) => ({ ...o, items: [] as { row: DbRow; depth: number }[] }))
  let cur: string[] = []
  for (const x of all) {
    if (x.depth === 0) cur = keyOf(x.row)
    for (const k of cur) out.find((o) => o.key === k)?.items.push(x)
  }
  return out.filter((o) => o.items.length || o.key !== '')
})

// --- 集計行 ---
function setCalc(pid: string, fn: string) {
  const c = { ...(view.value?.calcs ?? {}) }
  if (fn) c[pid] = fn as CalcFn
  else delete c[pid]
  patchView({ calcs: c })
}
const calcRows = (items: { row: DbRow; depth: number }[]) => items.filter((x) => x.depth === 0).map((x) => x.row)
const calcText = (pid: string, items: { row: DbRow; depth: number }[]) => {
  const fn = view.value?.calcs?.[pid]
  if (!fn) return ''
  return calc(fn, pid === 'title' ? null : db.value?.props.find((p) => p.id === pid) ?? null, calcRows(items), ctx.value)
}

// --- リスト・ギャラリー ---
const listProps = computed(() => visibleProps.value.slice(0, 4))
const coverOf = (r: DbRow): string | null => {
  const c = view.value?.cover ?? 'content'
  if (c === 'none') return null
  if (c === 'content') return r.meta?.cover ?? null
  const v = r.values[c]
  return typeof v === 'string' && /^https?:\/\//.test(v) ? v : null
}
const urlProps = computed(() => (db.value?.props ?? []).filter((p) => p.type === 'url'))

// --- タイムライン・カレンダー（表示範囲は、ビューを切り替えても残るようここで持つ） ---
const startProp = computed(() => db.value?.props.find((p) => p.id === view.value?.startProp && p.type === 'date') ?? null)
const endProp = computed(() => db.value?.props.find((p) => p.id === view.value?.endProp && p.type === 'date') ?? null)
const tlZoom = ref<'day' | 'week'>('day')
const tlStart = ref(weekAgo())
const dateProp = computed(() => db.value?.props.find((p) => p.id === view.value?.dateProp && p.type === 'date') ?? null)
const month = ref(thisMonth())
const visibleProps = computed(() => (db.value?.props ?? []).filter((p) => !view.value?.hidden?.includes(p.id)))
const selectProps = computed(() => (db.value?.props ?? []).filter((p) => p.type === 'select'))
const dateProps = computed(() => (db.value?.props ?? []).filter((p) => p.type === 'date'))

// --- 値の変更・項目の追加 ---
async function setValue(r: DbRow, p: DbProp, v: unknown) {
  if (!db.value) return
  const before = r.values
  r.values = { ...r.values, [p.id]: v } // すぐに反映
  if (v === null) delete r.values[p.id]
  try {
    const res = await dbApi.setValues(r.page.id, { [p.id]: v })
    // 変えた値だけを反映する（続けて別の値を変えたとき、先の応答で後の変更を戻さないように）
    const next = { ...r.values }
    if (res.values[p.id] === undefined) delete next[p.id]
    else next[p.id] = res.values[p.id]
    r.values = next
    db.value.users = { ...db.value.users, ...res.users }
  } catch (e) {
    r.values = before
    await alertError('変更できませんでした', e)
  }
}
const onUser = (id: string, name: string) => { if (db.value) db.value.users = { ...db.value.users, [id]: name } }
const newTitle = ref('')
async function addRow(title: string, values: Record<string, unknown> = {}) {
  if (!db.value) return
  try {
    const r = await dbApi.addRow(props.pageId, title.trim(), values)
    db.value.rows = [...db.value.rows, r]
    void store.reloadPages()
  } catch (e) { await alertError('追加できませんでした', e) }
}
async function addFromTable() {
  const t = newTitle.value.trim()
  if (!t) return
  newTitle.value = ''
  // 絞り込みの「が」条件は、新しい項目の値にも入れる（追加した項目が見えなくならないように）
  const vals: Record<string, unknown> = {}
  for (const f of view.value?.filters ?? []) {
    const p = db.value?.props.find((x) => x.id === f.prop)
    if (p && f.op === 'is' && f.value && (p.type === 'select' || p.type === 'date' || p.type === 'text')) vals[p.id] = f.value
  }
  await addRow(t, vals)
}
async function addPrompt(values: Record<string, unknown>, label: string) {
  const t = (await promptDialog(`新しい項目の名前（${label}）`, '', { okLabel: '追加' }))?.trim()
  if (t) await addRow(t, values)
}

// --- 列（項目の種類） ---
const colMenu = ref<string | null>(null)
const colMenuAt = ref<{ x: number; y: number }>({ x: 0, y: 0 })
function openCol(e: MouseEvent, id: string) {
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  colMenuAt.value = { x: Math.min(r.left, window.innerWidth - 270), y: r.bottom + 2 }
  colMenu.value = colMenu.value === id ? null : id
}
const colProp = computed(() => db.value?.props.find((p) => p.id === colMenu.value) ?? null)
function patchProp(id: string, patch: Partial<DbProp>) {
  if (!db.value) return
  db.value = { ...db.value, props: db.value.props.map((p) => (p.id === id ? { ...p, ...patch } : p)) }
  saveSoon()
}
async function addProp() {
  if (!db.value) return
  const type = await chooseDialog('追加する列の種類', (Object.keys(TYPE_LABEL) as DbPropType[]).map((t) => ({ label: `${TYPE_ICON[t]} ${TYPE_LABEL[t]}`, value: t }))) as DbPropType | null
  if (!type) return
  const name = (await promptDialog('列の名前', TYPE_LABEL[type].replace(/（.*）/, ''), { okLabel: '追加' }))?.trim()
  if (!name) return
  const extra: Partial<DbProp> = {}
  if (type === 'formula') {
    const ex = await askFormula('')
    if (ex === null) return
    extra.expr = ex
  } else if (type === 'unique_id') {
    const pre = (await promptDialog('ID の前に付ける文字（任意。例: TASK → TASK-12）', '', { okLabel: '追加' }))
    if (pre === null || pre === undefined) return
    if (pre.trim()) extra.prefix = pre.trim().replace(/[^0-9A-Za-z_-]/g, '').slice(0, 12)
  } else if (type === 'relation') {
    const dbs = store.pages.filter((x) => x.database)
    if (!dbs.length) { await alertDialog('関連先にできるデータベースがありません（このワークスペースのデータベースから選びます）'); return }
    const t = await chooseDialog('関連先のデータベース', dbs.map((x) => ({ label: `📊 ${x.title || '無題'}${x.id === props.pageId ? '（このデータベース）' : ''}`, value: x.id })))
    if (!t) return
    extra.target = t
  } else if (type === 'rollup') {
    const rels = db.value.props.filter((x) => x.type === 'relation')
    if (!rels.length) { await alertDialog('先に「関連」の列を追加してください'); return }
    const rel = rels.length === 1 ? rels[0].id : await chooseDialog('どの関連を集計しますか？', rels.map((x) => ({ label: `↗ ${x.name}`, value: x.id })))
    if (!rel) return
    const fn = await chooseDialog('集計のしかた', (Object.keys(FN_LABEL) as RollupFn[]).map((f) => ({ label: FN_LABEL[f], value: f }))) as RollupFn | null
    if (!fn) return
    extra.relation = rel
    extra.fn = fn
    if (fn !== 'count') {
      const target = db.value.targets[db.value.props.find((x) => x.id === rel)?.target ?? '']
      const cand = (target?.props ?? []).filter((x) => fn === 'checked' ? x.type === 'checkbox' : fn === 'count_values' ? true : x.type === 'number')
      if (!cand.length) { await alertDialog(fn === 'checked' ? '関連先に「チェック」の列がありません' : fn === 'count_values' ? '関連先に列がありません' : '関連先に「数値」の列がありません'); return }
      const tp = await chooseDialog('関連先のどの列を集計しますか？', cand.map((x) => ({ label: `${TYPE_ICON[x.type]} ${x.name}`, value: x.id })))
      if (!tp) return
      extra.prop = tp
    }
  }
  const p: DbProp = { id: newId(), name, type, ...(type === 'select' || type === 'multi_select' ? { options: [] } : {}), ...extra }
  db.value = { ...db.value, props: [...db.value.props, p] }
  saveSoon()
}
/** 数式を入力してもらう（正しく読めるまで聞き直す） */
async function askFormula(cur: string): Promise<string | null> {
  let v = cur
  for (;;) {
    const r = await promptDialog(`数式\n${FORMULA_HELP}`, v, { okLabel: '設定', placeholder: 'prop("金額") * 1.1' })
    if (r === null || r === undefined) return null
    v = r.trim()
    if (!v) return null
    const err = checkFormula(v)
    if (!err) return v
    await alertDialog(`数式を読めませんでした: ${err}`)
  }
}
async function editFormula(p: DbProp) {
  const ex = await askFormula(p.expr ?? '')
  if (ex !== null) patchProp(p.id, { expr: ex })
}
async function renameProp(p: DbProp) {
  const n = (await promptDialog('列の名前', p.name, { okLabel: '変更' }))?.trim()
  if (n) patchProp(p.id, { name: n })
}
async function changeType(p: DbProp) {
  const t = await chooseDialog(`「${p.name}」の種類（今の値は、新しい種類に合うものだけ表示されます）`,
    (Object.keys(TYPE_LABEL) as DbPropType[]).filter((x) => x !== p.type && x !== 'relation' && !COMPUTED.includes(x)).map((x) => ({ label: `${TYPE_ICON[x]} ${TYPE_LABEL[x]}`, value: x }))) as DbPropType | null
  if (t) patchProp(p.id, { type: t, options: t === 'select' || t === 'multi_select' ? p.options ?? [] : undefined })
}
async function deleteProp(p: DbProp) {
  colMenu.value = null
  if (!db.value || !await confirmDialog(`列「${p.name}」を削除しますか？`, { okLabel: '削除', danger: true })) return
  db.value = { ...db.value, props: db.value.props.filter((x) => x.id !== p.id) }
  saveSoon()
}
function moveProp(p: DbProp, d: -1 | 1) {
  if (!db.value) return
  const ps = [...db.value.props]
  const i = ps.findIndex((x) => x.id === p.id)
  const j = i + d
  if (j < 0 || j >= ps.length) return
  ;[ps[i], ps[j]] = [ps[j], ps[i]]
  db.value = { ...db.value, props: ps }
  saveSoon()
}
function hideProp(p: DbProp) {
  colMenu.value = null
  patchView({ hidden: [...(view.value?.hidden ?? []), p.id] })
}
async function addOption(p: DbProp) {
  const n = (await promptDialog('選択肢の名前', '', { okLabel: '追加' }))?.trim()
  if (!n) return
  patchProp(p.id, { options: [...(p.options ?? []), { id: newId(), name: n, color: COLORS[(p.options?.length ?? 0) % COLORS.length] }] })
}
async function renameOption(p: DbProp, o: DbOption) {
  const n = (await promptDialog('選択肢の名前', o.name, { okLabel: '変更' }))?.trim()
  if (n) patchProp(p.id, { options: (p.options ?? []).map((x) => (x.id === o.id ? { ...x, name: n } : x)) })
}
function colorOption(p: DbProp, o: DbOption, color: string) {
  patchProp(p.id, { options: (p.options ?? []).map((x) => (x.id === o.id ? { ...x, color } : x)) })
}
async function deleteOption(p: DbProp, o: DbOption) {
  if (await confirmDialog(`選択肢「${o.name}」を削除しますか？（この値の項目は空になります）`, { okLabel: '削除', danger: true }))
    patchProp(p.id, { options: (p.options ?? []).filter((x) => x.id !== o.id) })
}
const hiddenProps = computed(() => (db.value?.props ?? []).filter((p) => view.value?.hidden?.includes(p.id)))

// --- 絞り込み・並べ替え ---
const filterOpen = ref(false)
const fieldOptions = computed(() => [{ id: 'title', name: '名前', type: 'title' as const }, ...(db.value?.props ?? [])])
function addFilter() {
  patchView({ filters: [...(view.value?.filters ?? []), { prop: 'title', op: 'contains', value: '' }] })
}
function setFilter(i: number, patch: Partial<DbFilter>) {
  const fs = [...(view.value?.filters ?? [])]
  fs[i] = { ...fs[i], ...patch }
  if (patch.prop) fs[i] = { prop: patch.prop, op: opsFor(typeOf(patch.prop))[0].value, value: '' }
  patchView({ filters: fs })
}
const removeFilter = (i: number) => patchView({ filters: (view.value?.filters ?? []).filter((_, j) => j !== i) })
const typeOf = (id: string): DbPropType | 'title' => (id === 'title' ? 'title' : db.value?.props.find((p) => p.id === id)?.type ?? 'text')
const propOf = (id: string) => db.value?.props.find((p) => p.id === id)
function setSort(v: string) {
  if (!v) { patchView({ sort: null }); return }
  const [prop, dir] = v.split(':') as [string, 'asc' | 'desc']
  patchView({ sort: { prop, dir } })
}

// --- ボード ---
const groupProp = computed(() => db.value?.props.find((p) => p.id === view.value?.groupBy && p.type === 'select') ?? null)
const columns = computed(() => {
  const g = groupProp.value
  if (!g) return []
  const cols = [{ id: '', name: '未設定', color: 'gray' }, ...(g.options ?? [])].map((o) => ({ option: o, rows: [] as DbRow[] }))
  for (const r of rows.value) {
    const v = r.values[g.id]
    ;(cols.find((c) => c.option.id === (option(g, v) ? v : '')) ?? cols[0]).rows.push(r)
  }
  return cols
})
const dragRow = ref<string | null>(null)
const dropCol = ref<string | null>(null)
async function dropOn(colId: string) {
  const g = groupProp.value
  const r = db.value?.rows.find((x) => x.page.id === dragRow.value)
  dragRow.value = null
  dropCol.value = null
  if (g && r && (r.values[g.id] ?? '') !== colId) await setValue(r, g, colId || null)
}
const cardProps = computed(() => visibleProps.value.filter((p) => p.id !== groupProp.value?.id).slice(0, 4))

const rowHref = (r: DbRow) => `/w/${props.wid}/p/${r.page.id}`
async function convertBack() {
  if (!await confirmDialog('データベースをやめて、普通のページに戻しますか？\n子ページと入力した値はそのまま残ります（もう一度データベースにすると表示されます）。', { okLabel: '戻す', danger: true })) return
  await dbApi.remove(props.pageId)
  await store.reloadPages()
}
</script>

<template>
  <section class="db" data-testid="database">
    <p v-if="error" class="error">{{ error }}</p>
    <template v-else-if="db && view">
 <div v-if="embedded" class="emb-head">
        <router-link :to="`/w/${wid}/p/${pageId}`" class="emb-title" data-testid="db-embed-title">📊 {{ store.byId.get(pageId)?.title || 'データベース' }} ↗</router-link>
        <span class="muted small">（ここでのビューの変更は、この場所だけに保存されます）</span>
      </div>
      <div class="views" role="tablist">
        <button v-for="v in views" :key="v.id" role="tab" :class="{ on: v.id === view.id }" data-testid="db-view-tab" @click="activeId = v.id" @dblclick="(canEdit || embedded) && viewMenu()">
          {{ VIEW_ICON[v.type] }} {{ v.name }}
        </button>
        <button v-if="canEdit || embedded" class="ghost" title="ビューを追加" data-testid="db-view-add" @click="addView">＋</button>
        <span class="spacer" />
        <input v-model="q" type="search" class="search" placeholder="🔍 検索" data-testid="db-search" />
        <button :class="{ on: filterOpen || (view.filters ?? []).length }" data-testid="db-filter-toggle" @click="filterOpen = !filterOpen">⚙ 絞り込み・並べ替え<span v-if="(view.filters ?? []).length" class="n">{{ view.filters!.length }}</span></button>
        <button v-if="canEdit || embedded" class="ghost" title="ビュー・データベースの操作" data-testid="db-view-menu" @click="viewMenu">⋯</button>
        <HelpLink to="database" />
      </div>

      <div v-if="filterOpen" class="filters" data-testid="db-filters">
        <div v-for="(f, i) in view.filters ?? []" :key="i" class="frow">
          <select :value="f.prop" @change="setFilter(i, { prop: ($event.target as HTMLSelectElement).value })">
            <option v-for="o in fieldOptions" :key="o.id" :value="o.id">{{ o.name }}</option>
          </select>
          <template v-if="needsValue(f.op)">
            <select v-if="typeOf(f.prop) === 'select' || typeOf(f.prop) === 'multi_select'" :value="f.value" data-testid="db-filter-value" @change="setFilter(i, { value: ($event.target as HTMLSelectElement).value })">
              <option value="">（選ぶ）</option>
              <option v-for="o in propOf(f.prop)?.options ?? []" :key="o.id" :value="o.id">{{ o.name }}</option>
            </select>
            <select v-else-if="typeOf(f.prop) === 'checkbox'" :value="f.value" @change="setFilter(i, { value: ($event.target as HTMLSelectElement).value })">
              <option value="true">✓ あり</option><option value="false">なし</option>
            </select>
            <select v-else-if="typeOf(f.prop) === 'person'" :value="f.value" @change="setFilter(i, { value: ($event.target as HTMLSelectElement).value })">
              <option value="">（選ぶ）</option><option value="@me">自分</option>
              <option v-for="(n, id) in db.users" :key="id" :value="id">{{ n }}</option>
            </select>
            <input v-else :type="typeOf(f.prop) === 'date' ? 'date' : typeOf(f.prop) === 'number' ? 'number' : 'text'" :value="f.value" data-testid="db-filter-input" @change="setFilter(i, { value: ($event.target as HTMLInputElement).value })" />
          </template>
          <select :value="f.op" @change="setFilter(i, { op: ($event.target as HTMLSelectElement).value as DbFilter['op'] })">
            <option v-for="o in opsFor(typeOf(f.prop))" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
          <button class="ghost" title="条件を外す" @click="removeFilter(i)">×</button>
        </div>
        <div class="frow">
          <button class="ghost" data-testid="db-filter-add" @click="addFilter">＋ 条件を追加</button>
          <span class="spacer" />
          並べ替え:
          <select :value="view.sort ? `${view.sort.prop}:${view.sort.dir}` : ''" data-testid="db-sort" @change="setSort(($event.target as HTMLSelectElement).value)">
            <option value="">手動（ページの並び）</option>
            <template v-for="o in [{ id: 'title', name: '名前' }, { id: 'updated', name: '更新日時' }, ...db.props]" :key="o.id">
              <option :value="`${o.id}:asc`">{{ o.name }}（昇順）</option>
              <option :value="`${o.id}:desc`">{{ o.name }}（降順）</option>
            </template>
          </select>
        </div>
        <div v-if="view.type === 'board'" class="frow">
          列に分ける項目:
          <select :value="view.groupBy ?? ''" data-testid="db-group-by" @change="patchView({ groupBy: ($event.target as HTMLSelectElement).value || null })">
            <option value="">（選ぶ）</option>
            <option v-for="p in selectProps" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <div v-if="view.type === 'table' || view.type === 'list'" class="frow">
          グループ化:
          <select :value="view.group ?? ''" data-testid="db-group" @change="patchView({ group: ($event.target as HTMLSelectElement).value || null })">
            <option value="">しない</option>
            <option v-for="p in groupableProps" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          <label v-if="view.type === 'table'" class="chk"><input type="checkbox" :checked="view.subItems !== false" data-testid="db-subitems" @change="patchView({ subItems: ($event.target as HTMLInputElement).checked })" /> サブ項目を親の下に表示</label>
        </div>
        <div v-if="view.type === 'gallery'" class="frow">
          表紙:
          <select :value="view.cover ?? 'content'" data-testid="db-cover" @change="patchView({ cover: ($event.target as HTMLSelectElement).value })">
            <option value="content">本文の最初の画像</option>
            <option value="none">なし</option>
            <option v-for="p in urlProps" :key="p.id" :value="p.id">URL の列: {{ p.name }}</option>
          </select>
        </div>
        <div v-if="view.type === 'timeline'" class="frow">
          開始:
          <select :value="view.startProp ?? ''" data-testid="db-tl-start" @change="patchView({ startProp: ($event.target as HTMLSelectElement).value || null })">
            <option value="">（選ぶ）</option>
            <option v-for="p in dateProps" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
          終了:
          <select :value="view.endProp ?? ''" data-testid="db-tl-end" @change="patchView({ endProp: ($event.target as HTMLSelectElement).value || null })">
            <option value="">（なし: 1日だけ）</option>
            <option v-for="p in dateProps" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <div v-if="view.type === 'calendar'" class="frow">
          日付の項目:
          <select :value="view.dateProp ?? ''" data-testid="db-date-prop" @change="patchView({ dateProp: ($event.target as HTMLSelectElement).value || null })">
            <option value="">（選ぶ）</option>
            <option v-for="p in dateProps" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <div v-if="hiddenProps.length" class="frow">
          表示していない列:
          <button v-for="p in hiddenProps" :key="p.id" class="ghost" @click="patchView({ hidden: (view.hidden ?? []).filter((x) => x !== p.id) })">👁 {{ p.name }}</button>
        </div>
        <p v-if="!canEdit" class="muted small">閲覧のみのため、ビューの変更はこの画面だけに反映されます。</p>
        <div v-if="canEdit && !embedded" class="frow"><span class="spacer" /><button class="ghost danger" data-testid="db-convert-back" @click="convertBack">データベースをやめる…</button></div>
      </div>

      <!-- 表 -->
      <div v-if="view.type === 'table'" class="table-wrap">
        <table class="tbl" data-testid="db-table">
          <thead>
            <tr>
              <th class="title-col">名前</th>
              <th v-for="p in visibleProps" :key="p.id" :class="`t-${p.type}`">
                <button class="th" :disabled="!canEdit" data-testid="db-col" @click="openCol($event, p.id)">{{ TYPE_ICON[p.type] }} {{ p.name }}</button>
              </th>
              <th v-if="canEdit" class="add-col"><button class="ghost" title="列を追加" data-testid="db-col-add" @click="addProp">＋</button></th>
            </tr>
          </thead>
          <tbody v-for="g in groups" :key="g.key" data-testid="db-group-body">
            <tr v-if="tableGroup" class="ghead" data-testid="db-group-head">
              <td :colspan="visibleProps.length + 2">
                <button class="ghost twisty" @click="toggleGroup(g.key)">{{ collapsedGroups.has(g.key) ? '▸' : '▾' }}</button>
                <span class="tag" :class="g.color ? `c-${g.color}` : ''">{{ g.label }}</span>
                <span class="muted small">{{ calcRows(g.items).length }}</span>
              </td>
            </tr>
            <template v-if="!collapsedGroups.has(g.key)">
              <tr v-for="x in g.items" :key="x.row.page.id" :class="{ sub: x.depth > 0 }" data-testid="db-row">
                <td class="title-col">
                  <span class="tcell" :style="{ paddingLeft: `${x.depth * 1.3}rem` }">
                    <button v-if="nest && x.depth === 0 && hasSubs(x.row.page.id)" class="ghost twisty" :title="openSubs.has(x.row.page.id) ? 'サブ項目を閉じる' : 'サブ項目を開く'" data-testid="db-sub-toggle" @click="toggleSubs(x.row.page.id)">{{ openSubs.has(x.row.page.id) ? '▾' : '▸' }}</button>
                    <span v-else-if="nest && x.depth === 0" class="twisty-space" />
                    <router-link :to="rowHref(x.row)" class="rlink">{{ x.row.page.icon ?? '📄' }} {{ x.row.page.title || '無題' }}</router-link>
                    <button v-if="nest && x.depth === 0 && x.row.page.role === 'edit'" class="ghost add-sub" title="サブ項目を追加" data-testid="db-sub-add" @click="addSub(x.row)">＋</button>
                  </span>
                </td>
                <td v-for="p in visibleProps" :key="p.id" :class="`t-${p.type}`">
                  <DbCell :prop="p" :value="val(p, x.row)" :users="db.users" :ctx="ctx" :editable="x.row.page.role === 'edit' && !COMPUTED.includes(p.type)" @update="(v) => setValue(x.row, p, v)" @user="onUser" />
                </td>
                <td v-if="canEdit" />
              </tr>
            </template>
            <tr class="calc" data-testid="db-calc-row">
              <td>
                <select class="calc-sel" :value="view.calcs?.title ?? ''" title="集計" @change="setCalc('title', ($event.target as HTMLSelectElement).value)">
                  <option value="">{{ view.calcs?.title ? '' : '集計' }}</option><option value="count">件数</option>
                </select>
                <span class="calc-v">{{ calcText('title', g.items) }}</span>
              </td>
              <td v-for="p in visibleProps" :key="p.id">
                <select class="calc-sel" :value="view.calcs?.[p.id] ?? ''" title="集計" data-testid="db-calc" @change="setCalc(p.id, ($event.target as HTMLSelectElement).value)">
                  <option value="">{{ view.calcs?.[p.id] ? '（なし）' : '集計' }}</option>
                  <option v-for="f in calcsFor(p.type)" :key="f" :value="f">{{ CALC_LABEL[f] }}</option>
                </select>
                <span class="calc-v" data-testid="db-calc-value">{{ calcText(p.id, g.items) }}</span>
              </td>
              <td v-if="canEdit" />
            </tr>
          </tbody>
          <tbody>
            <tr v-if="canEdit" class="new">
              <td :colspan="visibleProps.length + 2">
                <input v-model="newTitle" placeholder="＋ 新しい項目（名前を入力して Enter）" data-testid="db-new-row" @keydown.enter="!$event.isComposing && addFromTable()" />
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!rows.length" class="muted small empty">{{ db.rows.length ? '条件に合う項目はありません' : 'まだ項目がありません' }}</p>
      </div>

      <!-- リスト -->
      <div v-else-if="view.type === 'list'" class="list" data-testid="db-list">
        <template v-for="g in groups" :key="g.key">
          <div v-if="tableGroup" class="lgh"><span class="tag" :class="g.color ? `c-${g.color}` : ''">{{ g.label }}</span><span class="muted small">{{ g.items.length }}</span></div>
          <div v-for="x in g.items" :key="x.row.page.id" class="litem" data-testid="db-list-item">
            <router-link :to="rowHref(x.row)" class="rlink">{{ x.row.page.icon ?? '📄' }} {{ x.row.page.title || '無題' }}</router-link>
            <span class="lprops">
              <template v-for="p in listProps" :key="p.id">
                <DbCell v-show="text(p, val(p, x.row), db.users, ctx)" :prop="p" :value="val(p, x.row)" :users="db.users" :ctx="ctx" :editable="false" compact />
              </template>
            </span>
          </div>
        </template>
        <button v-if="canEdit" class="ghost addc" @click="addPrompt({}, view.name)">＋ 新しい項目</button>
      </div>

      <!-- ギャラリー -->
      <div v-else-if="view.type === 'gallery'" class="gallery" data-testid="db-gallery">
        <router-link v-for="r in rows" :key="r.page.id" :to="rowHref(r)" class="gcard" data-testid="db-gallery-card">
          <div v-if="view.cover !== 'none'" class="gcover">
            <img v-if="coverOf(r)" :src="coverOf(r)!" alt="" loading="lazy" />
            <span v-else class="gexcerpt">{{ r.meta?.excerpt ?? '' }}</span>
          </div>
          <div class="gbody">
            <div class="gtitle">{{ r.page.icon ?? '📄' }} {{ r.page.title || '無題' }}</div>
            <template v-for="p in listProps" :key="p.id">
              <DbCell v-show="text(p, val(p, r), db.users, ctx)" :prop="p" :value="val(p, r)" :users="db.users" :ctx="ctx" :editable="false" compact />
            </template>
          </div>
        </router-link>
        <button v-if="canEdit" class="gcard gadd" @click="addPrompt({}, view.name)">＋ 新しい項目</button>
      </div>

      <!-- タイムライン -->
      <DbTimeline v-else-if="view.type === 'timeline'" v-model:zoom="tlZoom" v-model:start="tlStart" :rows="rows" :start-prop="startProp" :end-prop="endProp" :has-date-props="dateProps.length > 0" :row-href="rowHref" :set-value="setValue" />

      <!-- ボード -->
      <div v-else-if="view.type === 'board'" class="board" data-testid="db-board">
        <p v-if="!groupProp" class="muted">「⚙ 絞り込み・並べ替え」で、列に分ける項目（選択）を選んでください。<template v-if="!selectProps.length">先に「選択」の列を追加してください。</template></p>
        <div
          v-for="c in columns"
          v-else
          :key="c.option.id"
          class="bcol"
          :class="{ drop: dropCol === c.option.id }"
          data-testid="db-board-col"
          @dragover.prevent="dropCol = c.option.id"
          @dragleave="dropCol = null"
          @drop.prevent="dropOn(c.option.id)"
        >
          <div class="bhead"><span class="tag" :class="`c-${c.option.color}`">{{ c.option.name }}</span><span class="muted small">{{ c.rows.length }}</span></div>
          <div v-for="r in c.rows" :key="r.page.id" class="card" :draggable="r.page.role === 'edit'" data-testid="db-card" @dragstart="dragRow = r.page.id" @dragend="dragRow = null">
            <router-link :to="rowHref(r)" class="rlink">{{ r.page.icon ?? '📄' }} {{ r.page.title || '無題' }}</router-link>
            <div v-for="p in cardProps" :key="p.id" v-show="text(p, val(p, r), db.users, ctx)" class="cprop">
              <DbCell :prop="p" :value="val(p, r)" :users="db.users" :ctx="ctx" :editable="false" compact />
            </div>
          </div>
          <button v-if="canEdit" class="ghost addc" @click="addPrompt(c.option.id ? { [groupProp!.id]: c.option.id } : {}, c.option.name)">＋ 追加</button>
        </div>
      </div>

      <!-- カレンダー -->
      <DbCalendar v-else v-model:month="month" :rows="rows" :date-prop="dateProp" :has-date-props="dateProps.length > 0" :can-edit="canEdit" :row-href="rowHref" @set="setValue" @add="addPrompt" />

      <!-- 列の設定 -->
      <div v-if="colProp && canEdit" class="colmenu" :style="{ left: colMenuAt.x + 'px', top: colMenuAt.y + 'px' }" data-testid="db-col-menu" @mouseleave="colMenu = null">
        <div class="cm-head">{{ TYPE_ICON[colProp.type] }} {{ colProp.name }} <span class="muted small">（{{ TYPE_LABEL[colProp.type] }}）</span></div>
        <button @click="renameProp(colProp)">✏️ 名前を変更</button>
        <button @click="changeType(colProp)">🔁 種類を変更</button>
        <button @click="moveProp(colProp, -1)">← 左へ</button>
        <button @click="moveProp(colProp, 1)">→ 右へ</button>
        <button @click="hideProp(colProp)">🙈 このビューで隠す</button>
        <button v-if="colProp.type === 'formula'" data-testid="db-formula-edit" @click="editFormula(colProp)">ƒ 数式を編集</button>
        <div v-if="colProp.type === 'formula'" class="cm-sub mono">{{ colProp.expr }}</div>
        <template v-if="colProp.type === 'select' || colProp.type === 'multi_select'">
          <div class="cm-sub">選択肢</div>
          <div v-for="o in colProp.options ?? []" :key="o.id" class="opt">
            <span class="tag" :class="`c-${o.color}`" @click="renameOption(colProp, o)">{{ o.name }}</span>
            <select :value="o.color" title="色" @change="colorOption(colProp, o, ($event.target as HTMLSelectElement).value)">
              <option v-for="c in COLORS" :key="c" :value="c">{{ COLOR_LABEL[c] }}</option>
            </select>
            <button class="ghost" title="削除" @click="deleteOption(colProp, o)">×</button>
          </div>
          <button data-testid="db-option-add" @click="addOption(colProp)">＋ 選択肢を追加</button>
        </template>
        <button class="danger" @click="deleteProp(colProp)">🗑 列を削除</button>
      </div>
    </template>
    <p v-else class="muted">読み込み中…</p>
    <FormsDialog v-if="formsOpen && db" :page-id="pageId" :props="db.props" @close="formsOpen = false" />
  </section>
</template>

<style scoped src="./shared.css"></style>
<style scoped>
.db { margin: .5rem 0 1.5rem; }
.emb-head { display: flex; align-items: baseline; gap: .6rem; margin-bottom: .3rem; flex-wrap: wrap; }
.emb-title { font-weight: 700; font-size: 1.05rem; color: inherit; text-decoration: none; }
.emb-title:hover { color: var(--accent); }
.chk { display: inline-flex; align-items: center; gap: .25rem; }
.twisty { padding: 0 3px !important; font-size: .75rem; }
.twisty-space { display: inline-block; width: 1.1rem; }
.tcell { display: flex; align-items: center; gap: 2px; }
.add-sub { opacity: 0; font-size: .8rem; padding: 0 4px !important; margin-left: auto; }
tr:hover .add-sub { opacity: .8; }
tr.sub td { background: color-mix(in srgb, var(--side) 60%, transparent); }
.ghead td { background: var(--bg) !important; border-left: none; border-right: none; padding-top: .6rem; }
.calc td { border: none; border-top: 1px solid var(--line); background: none; font-size: .75rem; color: var(--muted); }
.calc-sel { font: inherit; font-size: .72rem; border: none; background: none; color: var(--muted); opacity: 0; max-width: 5rem; }
.calc:hover .calc-sel, .calc-sel:focus { opacity: 1; }
.calc-v { margin-left: .2rem; color: var(--fg); }
.mono { font-family: ui-monospace, monospace; word-break: break-all; }
.list { display: flex; flex-direction: column; }
.lgh { display: flex; gap: .5rem; align-items: center; margin: .6rem 0 .2rem; }
.litem { display: flex; align-items: center; gap: .8rem; padding: .35rem .3rem; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
.lprops { display: flex; gap: .6rem; margin-left: auto; flex-wrap: wrap; }
.gallery { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: .7rem; }
.gcard { display: flex; flex-direction: column; border: 1px solid var(--line); border-radius: 8px; overflow: hidden; color: inherit; text-decoration: none; min-height: 7rem; background: var(--bg); }
.gcard:hover { box-shadow: 0 4px 14px rgba(0,0,0,.1); }
.gcover { height: 120px; background: var(--side); overflow: hidden; display: flex; }
.gcover img { width: 100%; height: 100%; object-fit: cover; }
.gexcerpt { padding: .5rem .6rem; font-size: .72rem; color: var(--muted); line-height: 1.5; overflow: hidden; }
.gbody { padding: .45rem .6rem; display: flex; flex-direction: column; gap: 2px; }
.gtitle { font-weight: 600; font-size: .88rem; }
.gadd { align-items: center; justify-content: center; color: var(--muted); border-style: dashed; font-size: .85rem; }
.views { display: flex; align-items: center; gap: 3px; flex-wrap: wrap; border-bottom: 1px solid var(--line); padding-bottom: 4px; margin-bottom: 6px; }
.views button { font-size: .8rem; padding: 2px 9px; border-color: transparent; color: var(--muted); }
.views button.on { color: var(--fg); background: var(--hover); }
.spacer { flex: 1; }
.search { font: inherit; font-size: .8rem; padding: 2px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; width: 9rem; }
.n { margin-left: 3px; font-size: .7rem; background: var(--accent); color: #fff; border-radius: 8px; padding: 0 5px; }
.filters { border: 1px solid var(--line); border-radius: 8px; padding: .5rem .6rem; margin-bottom: .6rem; font-size: .82rem; display: flex; flex-direction: column; gap: .35rem; }
.frow { display: flex; align-items: center; gap: .35rem; flex-wrap: wrap; }
.frow select, .frow input { font: inherit; font-size: .8rem; padding: 1px 5px; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; }
.table-wrap { overflow-x: auto; }
.tbl { border-collapse: collapse; width: 100%; font-size: .85rem; }
.tbl th, .tbl td { border: 1px solid var(--line); padding: 2px 6px; vertical-align: top; text-align: left; }
.tbl th { font-weight: 500; color: var(--muted); background: var(--side); white-space: nowrap; }
.th { border: none; background: none; padding: 2px 0; color: inherit; font-size: .8rem; }
.title-col { min-width: 12rem; }
.tbl td.t-text { min-width: 10rem; }
.tbl td.t-person, .tbl td.t-multi_select { min-width: 8rem; }
.add-col { width: 2rem; }
.new input { width: 100%; border: none; background: none; font: inherit; font-size: .82rem; padding: 4px 0; color: inherit; }
.empty { padding: .4rem; }
.board { display: flex; gap: .6rem; overflow-x: auto; padding-bottom: .5rem; align-items: flex-start; }
.bcol { flex: 0 0 15rem; background: var(--side); border-radius: 8px; padding: .4rem; display: flex; flex-direction: column; gap: .35rem; min-height: 4rem; }
.bcol.drop { outline: 2px dashed var(--accent); }
.bhead { display: flex; justify-content: space-between; align-items: center; padding: 0 .2rem; }
.card { background: var(--bg); border: 1px solid var(--line); border-radius: 6px; padding: .4rem .5rem; font-size: .85rem; cursor: grab; }
.cprop { margin-top: 2px; }
.addc { align-self: flex-start; font-size: .78rem; }
.colmenu { position: fixed; z-index: 50; width: 260px; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 4px; box-shadow: 0 8px 24px rgba(0,0,0,.18); display: flex; flex-direction: column; font-size: .83rem; max-height: 70vh; overflow: auto; }
.colmenu > button { text-align: left; border: none; padding: 4px 8px; border-radius: 5px; }
.colmenu > button:hover { background: var(--hover); }
.cm-head { padding: 4px 8px; font-weight: 600; }
.cm-sub { padding: 4px 8px 0; color: var(--muted); font-size: .75rem; border-top: 1px solid var(--line); margin-top: 3px; }
.opt { display: flex; align-items: center; gap: 4px; padding: 2px 8px; }
.opt .tag { flex: 1; cursor: pointer; }
.opt select { font-size: .75rem; }
.danger:hover { color: var(--danger); }
.tag { display: inline-block; padding: 0 7px; border-radius: 4px; font-size: .78rem; line-height: 1.6; }
.small { font-size: .76rem; }
@media (max-width: 640px) { .search { width: 100%; order: 10; } }
@media print { .views button:not(.on), .views .ghost, .views .search, .new, .add-col, .addc { display: none !important; } }
</style>
