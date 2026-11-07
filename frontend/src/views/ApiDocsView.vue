<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { confirmDialog } from '../ui/dialog'

/** API の説明書（/openapi.json を読んで表示する）。「試す」はログイン中のこの画面の権限で実際に呼ぶ */
type Schema = { type?: string; format?: string; enum?: string[]; items?: Schema; properties?: Record<string, Schema>; $ref?: string; description?: string; nullable?: boolean; additionalProperties?: Schema }
interface Param { name: string; in: string; required: boolean; description?: string; schema: Schema }
interface Op { tags: string[]; summary: string; description?: string; parameters: Param[]; requestBody?: { content: Record<string, { schema: Schema }> }; responses: Record<string, { description: string; content?: Record<string, { schema: Schema }> }>; security?: unknown[] }
interface Doc { info: { title: string; description: string }; paths: Record<string, Record<string, Op>>; components: { schemas: Record<string, Schema> }; tags: { name: string }[] }

const doc = ref<Doc | null>(null)
const error = ref('')
const q = ref('')
onMounted(async () => {
  try { doc.value = await (await fetch('/openapi.json')).json() } catch (e) { error.value = (e as Error).message }
})

interface Entry { key: string; method: string; path: string; op: Op }
const entries = computed<Entry[]>(() => {
  if (!doc.value) return []
  const out: Entry[] = []
  for (const [path, ms] of Object.entries(doc.value.paths)) for (const [method, op] of Object.entries(ms)) out.push({ key: `${method} ${path}`, method, path, op })
  const k = q.value.trim().toLowerCase()
  return k ? out.filter((e) => `${e.method} ${e.path} ${e.op.summary} ${e.op.tags.join(' ')}`.toLowerCase().includes(k)) : out
})
const groups = computed(() => (doc.value?.tags ?? []).map((t) => ({ tag: t.name, items: entries.value.filter((e) => e.op.tags.includes(t.name)) })).filter((g) => g.items.length))

const resolve = (s: Schema | undefined): Schema | undefined => (s?.$ref ? doc.value?.components.schemas[s.$ref.split('/').pop()!] : s)
/** スキーマから例を作る */
function example(s0: Schema | undefined, depth = 0): unknown {
  const s = resolve(s0)
  if (!s || depth > 4) return null
  if (s.enum) return s.enum[0]
  switch (s.type) {
    case 'object': return s.properties ? Object.fromEntries(Object.entries(s.properties).map(([k, v]) => [k, example(v, depth + 1)])) : {}
    case 'array': return [example(s.items, depth + 1)]
    case 'integer': case 'number': return 0
    case 'boolean': return false
    case 'string': return s.format === 'uuid' ? '00000000-0000-0000-0000-000000000000' : s.format === 'date-time' ? new Date().toISOString() : s.format === 'date' ? new Date().toISOString().slice(0, 10) : ''
  }
  return null
}
const pretty = (v: unknown) => JSON.stringify(v, null, 2)
function schemaText(s0: Schema | undefined, indent = ''): string {
  const s = resolve(s0)
  if (!s) return ''
  if (s.type === 'object' && s.properties)
    return '{\n' + Object.entries(s.properties).map(([k, v]) => {
      const r = resolve(v)
      const t = r?.enum ? r.enum.map((x) => `"${x}"`).join(' | ') : r?.type === 'array' ? `${resolve(r.items)?.type ?? 'object'}[]` : r?.type ?? 'object'
      return `${indent}  ${k}: ${t}${r?.nullable ? ' | null' : ''}${(v.description ?? r?.description) ? `  // ${v.description ?? r?.description}` : ''}`
    }).join('\n') + `\n${indent}}`
  if (s.type === 'array') return `[${schemaText(s.items, indent)}]`
  return s.type ?? 'object'
}

// --- 試す ---
const openKey = ref<string | null>(null)
const inputs = ref<Record<string, string>>({})
const body = ref('')
const result = ref<{ status: number; text: string } | null>(null)
const busy = ref(false)
function toggle(e: Entry) {
  if (openKey.value === e.key) { openKey.value = null; return }
  openKey.value = e.key
  inputs.value = {}
  result.value = null
  const b = e.op.requestBody?.content['application/json']?.schema
  body.value = b ? pretty(example(b)) : ''
}
async function tryIt(e: Entry) {
  if (e.method !== 'get' && !(await confirmDialog(`${e.method.toUpperCase()} ${e.path} を実行しますか？\nあなたの権限で、実際にデータが変わります。`, { okLabel: '実行する' }))) return
  let url = e.path
  const query = new URLSearchParams()
  for (const p of e.op.parameters) {
    const v = inputs.value[p.name] ?? ''
    if (p.in === 'path') url = url.replace(`{${p.name}}`, encodeURIComponent(v))
    else if (v) query.set(p.name, v)
  }
  if (query.toString()) url += `?${query}`
  busy.value = true
  try {
    const res = await fetch(url, {
      method: e.method.toUpperCase(), credentials: 'same-origin',
      headers: body.value && e.method !== 'get' ? { 'Content-Type': 'application/json' } : undefined,
      body: body.value && e.method !== 'get' ? body.value : undefined,
    })
    const t = await res.text()
    let shown = t
    try { shown = pretty(JSON.parse(t)) } catch { /* JSONでない */ }
    result.value = { status: res.status, text: shown.slice(0, 20000) }
  } catch (err) { result.value = { status: 0, text: (err as Error).message } } finally { busy.value = false }
}
const curl = (e: Entry) => {
  const b = e.op.requestBody ? ` \\\n  -H 'Content-Type: application/json' \\\n  -d '${JSON.stringify(example(e.op.requestBody.content['application/json']?.schema))}'` : ''
  const auth = e.op.security && !e.op.security.length ? '' : ` \\\n  -H 'Authorization: Bearer <APIトークン>'`
  return `curl -X ${e.method.toUpperCase()} '${location.origin}${e.path}'${auth}${b}`
}
</script>

<template>
  <div class="docs" data-testid="api-docs">
    <header>
      <router-link to="/" class="back">← skiwi に戻る</router-link>
      <h1>📘 API の説明書</h1>
      <p class="links"><a href="/openapi.json" target="_blank" rel="noopener">openapi.json</a>（OpenAPI 3.0。各種ツールに読み込めます）・<router-link to="/settings/tokens">APIトークンを発行</router-link></p>
    </header>
    <p v-if="error" class="error">{{ error }}</p>
    <template v-else-if="doc">
      <pre class="intro">{{ doc.info.description }}</pre>
      <input v-model="q" type="search" class="filter" placeholder="🔍 絞り込み（例: database、コメント）" data-testid="api-filter" />
      <section v-for="g in groups" :key="g.tag">
        <h2>{{ g.tag }}</h2>
        <div v-for="e in g.items" :key="e.key" class="ep" :class="{ open: openKey === e.key }" data-testid="api-endpoint">
          <button class="ep-head" @click="toggle(e)">
            <span class="m" :class="e.method">{{ e.method.toUpperCase() }}</span>
            <code>{{ e.path }}</code>
            <span class="sum">{{ e.op.summary }}</span>
          </button>
          <div v-if="openKey === e.key" class="ep-body">
            <p v-if="e.op.description" class="desc">{{ e.op.description }}</p>
            <table v-if="e.op.parameters.length" class="params">
              <tr v-for="p in e.op.parameters" :key="p.name">
                <td><code>{{ p.name }}</code><span v-if="p.required" class="req">*</span><div class="muted small">{{ p.in === 'path' ? 'パス' : 'クエリ' }}・{{ resolve(p.schema)?.type }}</div></td>
                <td>{{ p.description }}</td>
                <td><input v-model="inputs[p.name]" :placeholder="p.name" data-testid="api-param" /></td>
              </tr>
            </table>
            <template v-if="e.op.requestBody">
              <h4>送る内容（JSON）</h4>
              <pre class="schema">{{ schemaText(e.op.requestBody.content['application/json']?.schema) }}</pre>
              <textarea v-model="body" rows="6" spellcheck="false" data-testid="api-body" />
            </template>
            <template v-for="(r, st) in e.op.responses" :key="st">
              <template v-if="r.content">
                <h4>応答 {{ st }}</h4>
                <pre class="schema">{{ schemaText(r.content['application/json']?.schema) }}</pre>
              </template>
            </template>
            <h4>curl</h4>
            <pre class="schema">{{ curl(e) }}</pre>
            <div class="try">
              <button class="primary" :disabled="busy" data-testid="api-try" @click="tryIt(e)">試す（ログイン中の権限で実行）</button>
            </div>
            <div v-if="result" class="result" data-testid="api-result">
              <b :class="{ ok: result.status >= 200 && result.status < 300 }">HTTP {{ result.status }}</b>
              <pre>{{ result.text }}</pre>
            </div>
          </div>
        </div>
      </section>
    </template>
    <p v-else class="muted">読み込み中…</p>
  </div>
</template>

<style scoped>
.docs { max-width: 960px; margin: 0 auto; padding: 1.2rem 1.5rem 4rem; }
.back { font-size: .85rem; color: var(--muted); text-decoration: none; }
h1 { font-size: 1.6rem; margin: .4rem 0; }
h2 { font-size: 1.05rem; margin: 1.6rem 0 .5rem; padding-bottom: .2rem; border-bottom: 1px solid var(--line); }
h4 { font-size: .8rem; color: var(--muted); margin: .8rem 0 .3rem; }
.links { font-size: .85rem; color: var(--muted); }
.links a { color: var(--accent); }
.intro { white-space: pre-wrap; font: inherit; font-size: .85rem; background: var(--side); border: 1px solid var(--line); border-radius: 8px; padding: .6rem .8rem; }
.filter { width: 100%; font: inherit; padding: .4rem .7rem; border: 1px solid var(--line); border-radius: 8px; background: var(--bg); color: inherit; margin-top: .6rem; }
.ep { border: 1px solid var(--line); border-radius: 8px; margin-bottom: .4rem; overflow: hidden; }
.ep-head { width: 100%; display: flex; align-items: center; gap: .6rem; border: none; border-radius: 0; padding: .45rem .6rem; text-align: left; background: none; }
.ep-head:hover, .ep.open .ep-head { background: var(--hover); }
.m { font-size: .7rem; font-weight: 700; min-width: 3.6rem; text-align: center; border-radius: 4px; padding: 1px 0; color: #fff; }
.m.get { background: #2563eb; } .m.post { background: #16a34a; } .m.put { background: #d97706; } .m.patch { background: #9333ea; } .m.delete { background: var(--danger); }
code { font-size: .85rem; }
.sum { color: var(--muted); font-size: .85rem; margin-left: auto; text-align: right; }
.ep-body { padding: .4rem .8rem .8rem; font-size: .85rem; }
.desc { color: var(--muted); }
.params { width: 100%; border-collapse: collapse; }
.params td { border-bottom: 1px solid var(--line); padding: .3rem; vertical-align: top; }
.params input { font: inherit; font-size: .82rem; padding: .2rem .4rem; border: 1px solid var(--line); border-radius: 5px; background: var(--bg); color: inherit; width: 100%; min-width: 10rem; }
.req { color: var(--danger); margin-left: 2px; }
.schema, .result pre { background: var(--side); border: 1px solid var(--line); border-radius: 6px; padding: .5rem; font-size: .78rem; white-space: pre-wrap; word-break: break-all; margin: 0; }
textarea { width: 100%; box-sizing: border-box; font-family: ui-monospace, monospace; font-size: .8rem; padding: .5rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); color: inherit; margin-top: .4rem; }
.try { margin-top: .6rem; }
.result { margin-top: .6rem; }
.result b { color: var(--danger); } .result b.ok { color: #16a34a; }
.result pre { max-height: 24rem; overflow: auto; margin-top: .3rem; }
.small { font-size: .72rem; }
</style>
