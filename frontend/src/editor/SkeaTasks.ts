import type { Node as PMNode } from '@tiptap/pm/model'
import { DecorationSet, type Decoration } from '@tiptap/pm/view'
import type { SkeaTask } from '../api'
import { host } from '../ui/host'
import { chip, eachTextblock, linkedExtension, linkedInline, linkedWidget, textblockText, type LinkedState } from './linkedDecorations'

/**
 * 本文中の skea のタスクID（例: SKEA-123）を、skea のタスクへのリンクとして表示する（docs/skea-integration.md）。
 * 対象は管理者が設定したプロジェクトキーだけ（大文字と小文字を区別）。コードブロックとインラインのコードの中は対象外。
 * 直後にタイトルと状態（連携していなければ ↗）を出す。
 */
const skea = () => host().integration<SkeaTask>('skea')
const KEY_RE = /^[A-Z][A-Z0-9_]*$/
const COLOR_RE = /^#[0-9a-fA-F]{3,8}$/

export interface TaskKeyMatch { from: number; to: number; key: string }

/** タスクIDの位置。コードブロックの中と、インラインのコードの文字は見ない */
export function findTaskKeys(doc: PMNode, projectKeys: string[]): TaskKeyMatch[] {
  const keys = projectKeys.filter((k) => KEY_RE.test(k))
  if (!keys.length) return []
  const re = new RegExp(`\\b(?:${keys.join('|')})-\\d+\\b`, 'g')
  const out: TaskKeyMatch[] = []
  eachTextblock(doc, (node, start) => {
    for (const m of textblockText(node).matchAll(re)) out.push({ from: start + m.index!, to: start + m.index! + m[0].length, key: m[0] })
  })
  return out
}

function build(doc: PMNode): LinkedState {
  const s = skea().status()
  if (!s?.enabled || !s.url) return { deco: DecorationSet.empty, keys: [] }
  const found = findTaskKeys(doc, s.projectKeys)
  const decos: Decoration[] = []
  for (const f of found) {
    const task = s.connected ? skea().item(f.key) : undefined
    const url = task?.url ?? `${s.url}/tasks/${f.key}`
    const title = !s.connected ? 'skea と連携するとタイトルを表示します'
      : task ? `${task.title}（${task.status.name}）`
      : task === null ? 'skea で見つからないか、見る権限がありません' : 'skea のタスク'
    decos.push(linkedInline(f.from, f.to, url, { class: 'skea-key', title, 'data-testid': 'skea-key' }))
    decos.push(linkedWidget(f.to, `skea:${url}:${task ? `${task.title}\u0000${task.status.name}\u0000${task.status.color}` : String(task)}`, () =>
      chip('skea-chip', url, task ? `${task.title}（${task.status.name}）を skea で開く` : 'skea で開く', (a) => {
        a.dataset.testid = 'skea-chip'
        if (!task) { a.classList.add('icon'); a.textContent = '↗'; return }
        const t = document.createElement('span')
        t.className = 'linked-name'
        t.textContent = task.title
        const st = document.createElement('span')
        st.className = 'skea-status'
        st.textContent = task.status.name
        if (COLOR_RE.test(task.status.color)) st.style.background = task.status.color
        a.append(t, st)
      })))
  }
  return { deco: DecorationSet.create(doc, decos), keys: [...new Set(found.map((f) => f.key))] }
}

export const SkeaTasks = linkedExtension('skeaTasks', skea, build)
