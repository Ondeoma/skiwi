import CodeBlockLowlight from '@tiptap/extension-code-block-lowlight'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import { common, createLowlight } from 'lowlight'
import CodeBlockView from './CodeBlockView.vue'

const lowlight = createLowlight(common)

/** 選択肢に出す言語（表示名）。lowlight の common に含まれるものだけ。 */
const NAMES: Record<string, string> = {
  plaintext: 'プレーンテキスト', bash: 'Bash / シェル', c: 'C', cpp: 'C++', csharp: 'C#', css: 'CSS', diff: 'Diff',
  go: 'Go', graphql: 'GraphQL', ini: 'INI / TOML', java: 'Java', javascript: 'JavaScript', json: 'JSON',
  kotlin: 'Kotlin', less: 'Less', lua: 'Lua', makefile: 'Makefile', markdown: 'Markdown', objectivec: 'Objective-C',
  perl: 'Perl', php: 'PHP', python: 'Python', r: 'R', ruby: 'Ruby', rust: 'Rust', scss: 'SCSS', shell: 'シェルセッション',
  sql: 'SQL', swift: 'Swift', typescript: 'TypeScript', vbnet: 'VB.NET', wasm: 'WebAssembly', xml: 'HTML / XML', yaml: 'YAML',
}
export const LANGUAGES = lowlight.listLanguages()
  .filter((l) => NAMES[l])
  .map((id) => ({ id, label: NAMES[id] }))
  .sort((a, b) => (a.id === 'plaintext' ? -1 : b.id === 'plaintext' ? 1 : a.label.localeCompare(b.label)))

export const languageLabel = (id: string | null | undefined) => (id ? NAMES[id] ?? id : '自動判定')

/** 言語を明示指定できるコードブロック（未指定なら自動判定でハイライト）。 */
export const CodeBlock = CodeBlockLowlight.extend({
  addNodeView() { return VueNodeViewRenderer(CodeBlockView) },
}).configure({ lowlight, defaultLanguage: null })
