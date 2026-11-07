<script lang="ts">
/** 新しいコメントの対象: 本文の選択範囲・ホワイトボード・ページ全体 */
export interface CommentDraft { kind: 'text' | 'wb' | 'page'; from?: number; to?: number; quote?: string; boardId?: string }
</script>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { Editor } from '@tiptap/vue-3'
import { commentApi, formatDate, type Comment } from '../api'
import { confirmDialog, guarded } from '../ui/dialog'
import MentionInput from './MentionInput.vue'
import CommentBody from './CommentBody.vue'

/** コメントの一覧と投稿。draft は本文の選択範囲・ホワイトボードへの新しいコメント。 */

const props = defineProps<{
  pageId: string
  comments: Comment[]
  canEdit: boolean
  editor: Editor | null
  draft: CommentDraft | null
  focus: string | null
}>()
const emit = defineEmits<{ close: []; reload: []; 'clear-draft': [] }>()

const tab = ref<'open' | 'resolved'>('open')
const busy = ref(false)
const newBody = ref('')
const replyBody = ref<Record<string, string>>({})
const editing = ref<string | null>(null)
const editBody = ref('')
const listEl = ref<HTMLElement | null>(null)

interface Thread { root: Comment; replies: Comment[] }
const threads = computed<Thread[]>(() => {
  const roots = props.comments.filter((c) => !c.threadId)
  return roots
    .map((root) => ({ root, replies: props.comments.filter((c) => c.threadId === root.id) }))
    // 先頭が削除され返信もないスレッドは出さない
    .filter((t) => !(t.root.deleted && t.replies.every((r) => r.deleted)))
    .reverse()
})
const shown = computed(() => threads.value.filter((t) => (tab.value === 'open') === !t.root.resolvedAt))
const openCount = computed(() => threads.value.filter((t) => !t.root.resolvedAt).length)

const guard = (f: () => Promise<unknown>) => guarded(f, busy)

const newAnchorId = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}${Math.random()}`).replace(/-/g, '').slice(0, 20)

async function post() {
  const body = newBody.value.trim()
  if (!body) return
  const d = props.draft
  await guard(async () => {
    let anchor: string | undefined
    let markRange: { from: number; to: number; id: string } | null = null
    if (d?.kind === 'text' && d.from != null && d.to != null && props.canEdit && props.editor) {
      // 編集できる人は本文に印を付ける（閲覧のみの人は引用だけ残す）
      const id = newAnchorId()
      anchor = `text:${id}`
      markRange = { from: d.from, to: d.to, id }
    } else if (d?.kind === 'wb' && d.boardId) {
      anchor = `wb:${d.boardId}`
    }
    await commentApi.create(props.pageId, { body, anchor, quote: d?.quote })
    if (markRange && props.editor && !props.editor.isDestroyed) {
      const { from, to, id } = markRange
      props.editor.chain().setTextSelection({ from, to }).setCommentMark(id).setTextSelection(to).run()
    }
    newBody.value = ''
    tab.value = 'open'
    emit('clear-draft')
    emit('reload')
  })
}

async function reply(t: Thread) {
  const body = (replyBody.value[t.root.id] ?? '').trim()
  if (!body) return
  await guard(async () => {
    await commentApi.create(props.pageId, { body, threadId: t.root.id })
    replyBody.value[t.root.id] = ''
    emit('reload')
  })
}

const setResolved = (t: Thread, v: boolean) => guard(async () => { await commentApi.resolve(t.root.id, v); emit('reload') })

function startEdit(c: Comment) { editing.value = c.id; editBody.value = c.body }
const saveEdit = (c: Comment) => guard(async () => {
  if (!editBody.value.trim()) return
  await commentApi.edit(c.id, editBody.value.trim())
  editing.value = null
  emit('reload')
})

async function remove(c: Comment, t: Thread) {
  const isRoot = c.id === t.root.id
  if (!(await confirmDialog(isRoot ? 'このコメントを削除しますか？（返信は残ります）' : 'この返信を削除しますか？', { okLabel: '削除', danger: true }))) return
  await guard(async () => {
    await commentApi.remove(c.id)
    // スレッドごとなくなる場合は本文の印も外す
    const anchor = t.root.anchor
    if (isRoot && anchor?.startsWith('text:') && t.replies.every((r) => r.deleted) && props.canEdit && props.editor && !props.editor.isDestroyed) {
      props.editor.commands.unsetCommentMark(anchor.slice(5))
    }
    emit('reload')
  })
}

/** スレッドの対象（本文の範囲・ホワイトボード）へ移動して目立たせる */
function reveal(t: Thread) {
  const a = t.root.anchor
  if (!a) return
  const el = a.startsWith('text:')
    ? document.querySelector<HTMLElement>(`.ProseMirror [data-comment="${CSS.escape(a.slice(5))}"]`)
    : document.querySelector<HTMLElement>(`.ProseMirror [data-whiteboard-id="${CSS.escape(a.slice(3))}"]`)
  if (!el) return
  el.scrollIntoView({ block: 'center', behavior: 'smooth' })
  const marks = a.startsWith('text:') ? document.querySelectorAll(`.ProseMirror [data-comment="${CSS.escape(a.slice(5))}"]`) : [el]
  marks.forEach((m) => m.classList.add('flash'))
  setTimeout(() => marks.forEach((m) => m.classList.remove('flash')), 1200)
}
const anchorMissing = (t: Thread) =>
  t.root.anchor?.startsWith('text:') && !document.querySelector(`.ProseMirror [data-comment="${CSS.escape(t.root.anchor.slice(5))}"]`)

// 通知などから開いたスレッドへスクロール
watch(() => [props.focus, props.comments.length] as const, async ([id]) => {
  if (!id) return
  const t = threads.value.find((x) => x.root.id === id || x.root.anchor === id || x.replies.some((r) => r.id === id))
  if (!t) return
  tab.value = t.root.resolvedAt ? 'resolved' : 'open'
  await nextTick()
  const el = listEl.value?.querySelector<HTMLElement>(`[data-thread="${t.root.id}"]`)
  el?.scrollIntoView({ block: 'center' })
  el?.classList.add('focus')
  setTimeout(() => el?.classList.remove('focus'), 1500)
  reveal(t)
}, { immediate: true })
</script>

<template>
  <aside class="comments" aria-label="コメント" data-testid="comments">
    <header>
      <strong>💬 コメント</strong>
      <div class="tabs">
        <button :class="{ on: tab === 'open' }" @click="tab = 'open'">未解決（{{ openCount }}）</button>
        <button :class="{ on: tab === 'resolved' }" @click="tab = 'resolved'">解決済み（{{ threads.length - openCount }}）</button>
      </div>
      <button class="x" title="閉じる" @click="emit('close')">✕</button>
    </header>

    <div class="compose">
      <div v-if="draft && draft.kind !== 'page'" class="quote">
        <span>{{ draft.kind === 'wb' ? '🧩 ホワイトボードへのコメント' : `「${draft.quote}」へのコメント` }}</span>
        <button class="link" @click="emit('clear-draft')">ページ全体へのコメントにする</button>
      </div>
      <MentionInput v-model="newBody" :autofocus="!!draft" @submit="post" />
      <div class="row">
        <small class="muted">⌘/Ctrl+Enter で送信</small>
        <button class="primary" :disabled="busy || !newBody.trim()" data-testid="comment-post" @click="post">コメントする</button>
      </div>
    </div>

    <div ref="listEl" class="list">
      <p v-if="!shown.length" class="muted empty">{{ tab === 'open' ? '未解決のコメントはありません。本文を選択して「💬」からもコメントできます。' : '解決済みのコメントはありません。' }}</p>
      <article v-for="t in shown" :key="t.root.id" class="thread" :data-thread="t.root.id" data-testid="thread">
        <button v-if="t.root.anchor" class="anchor" :class="{ missing: anchorMissing(t) }" @click="reveal(t)">
          <template v-if="t.root.anchor.startsWith('wb:')">🧩 ホワイトボード</template>
          <template v-else>「{{ t.root.quote }}」<span v-if="anchorMissing(t)">（本文から削除された箇所）</span></template>
        </button>
        <div v-else-if="t.root.quote" class="anchor static">「{{ t.root.quote }}」</div>
        <div v-for="c in [t.root, ...t.replies]" :key="c.id" class="c" :class="{ reply: c.id !== t.root.id }">
          <div class="meta">
            <b>{{ c.authorName ?? '（不明）' }}</b>
            <span class="muted">{{ formatDate(c.createdAt) }}<template v-if="c.updatedAt !== c.createdAt && !c.deleted">（編集済み）</template></span>
            <span class="acts">
              <button v-if="c.canEdit && editing !== c.id" class="link" @click="startEdit(c)">編集</button>
              <button v-if="c.canDelete" class="link danger" @click="remove(c, t)">削除</button>
            </span>
          </div>
          <p v-if="c.deleted" class="muted deleted">（削除されたコメント）</p>
          <template v-else-if="editing === c.id">
            <MentionInput v-model="editBody" @submit="saveEdit(c)" @cancel="editing = null" />
            <div class="row"><button class="link" @click="editing = null">キャンセル</button><button class="primary sm" :disabled="busy" @click="saveEdit(c)">保存</button></div>
          </template>
          <CommentBody v-else :body="c.body" />
        </div>
        <div v-if="t.root.resolvedAt" class="resolved muted">✓ {{ t.root.resolvedBy ?? '' }} が解決（{{ formatDate(t.root.resolvedAt) }}）</div>
        <div class="thread-foot">
          <MentionInput v-if="!t.root.resolvedAt" v-model="replyBody[t.root.id]" placeholder="返信…" @submit="reply(t)" />
          <div class="row">
            <button v-if="t.root.canDelete || canEdit" class="link" data-testid="thread-resolve" @click="setResolved(t, !t.root.resolvedAt)">
              {{ t.root.resolvedAt ? '↺ 未解決に戻す' : '✓ 解決' }}
            </button>
            <button v-if="!t.root.resolvedAt" class="primary sm" :disabled="busy || !(replyBody[t.root.id] ?? '').trim()" @click="reply(t)">返信</button>
          </div>
        </div>
      </article>
    </div>
  </aside>
</template>

<style scoped>
.comments { position: fixed; top: 0; right: 0; bottom: 0; width: min(380px, 100vw); z-index: 45; background: var(--bg); border-left: 1px solid var(--line); box-shadow: -10px 0 30px rgba(0,0,0,.15); display: flex; flex-direction: column; }
header { display: flex; align-items: center; gap: .5rem; padding: .6rem .8rem; border-bottom: 1px solid var(--line); }
.tabs { display: flex; gap: 2px; flex: 1; justify-content: flex-end; }
.tabs button { font-size: .72rem; padding: 1px 6px; color: var(--muted); }
.tabs button.on { color: var(--fg); border-color: var(--accent); background: var(--hover); }
.x { border: none; color: var(--muted); }
.compose { padding: .6rem .8rem; border-bottom: 1px solid var(--line); }
.quote { display: flex; flex-direction: column; gap: 2px; font-size: .78rem; background: rgba(234,179,8,.12); border-left: 3px solid rgba(234,179,8,.7); padding: .3rem .5rem; border-radius: 4px; margin-bottom: .4rem; }
.row { display: flex; justify-content: space-between; align-items: center; gap: .4rem; margin-top: .3rem; }
.primary { font-size: .82rem; }
.primary:disabled { opacity: .5; }
.primary.sm { font-size: .75rem; padding: 1px 10px; }
.link { border: none; background: none; color: var(--accent); padding: 0; font-size: .75rem; }
.link.danger:hover { color: var(--danger); }
.list { flex: 1; overflow: auto; padding: .4rem .8rem 2rem; }
.empty { font-size: .82rem; padding: 1rem 0; }
.thread { border: 1px solid var(--line); border-radius: 9px; padding: .5rem .6rem; margin: .5rem 0; transition: box-shadow .3s; }
.thread.focus { box-shadow: 0 0 0 2px var(--accent); }
.anchor { display: block; width: 100%; text-align: left; border: none; border-left: 3px solid rgba(234,179,8,.7); background: rgba(234,179,8,.10); font-size: .78rem; padding: .2rem .45rem; border-radius: 3px; margin-bottom: .35rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.anchor.missing { opacity: .6; border-left-color: var(--line); }
.anchor.static { cursor: default; }
.c { padding: .25rem 0; }
.c.reply { margin-left: .8rem; padding-left: .5rem; border-left: 2px solid var(--line); }
.meta { display: flex; align-items: baseline; gap: .4rem; font-size: .78rem; }
.meta .acts { margin-left: auto; display: flex; gap: .4rem; }
.deleted { font-size: .8rem; font-style: italic; }
.resolved { font-size: .75rem; margin-top: .3rem; }
.thread-foot { margin-top: .4rem; }
</style>
