<script setup lang="ts">
import { computed, watchEffect } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { router } from '../router'
import { useWorkspaceStore } from '../stores/workspace'
import { guestContext, guestHref, pageHref, pageInfo } from './pageLinks'
import { useEditable } from './useEditable'

const props = defineProps(nodeViewProps)
const store = useWorkspaceStore()
const editable = useEditable(props.editor)

const pageId = computed(() => String(props.node.attrs.pageId ?? ''))

/** 表示内容。ゲスト表示では公開範囲のページだけリンクにする。 */
const view = computed(() => {
  const g = guestContext.value
  if (g) {
    const p = g.pages.get(pageId.value)
    return p
      ? { kind: 'ok' as const, title: p.title, icon: p.icon, href: guestHref(g.token, p) }
      : { kind: 'denied' as const, title: '非公開のページ', icon: null, href: null }
  }
  // 開いているワークスペースのページは一覧の情報（名前の変更が即座に反映される）
  const local = store.byId.get(pageId.value)
  if (local) return { kind: 'ok' as const, title: local.title, icon: local.icon, href: pageHref(local.workspaceId, local.id) }
  const info = pageInfo(pageId.value)
  if (info.state === 'ok') return { kind: 'ok' as const, title: info.title, icon: info.icon, href: pageHref(info.workspaceId, pageId.value) }
  // 開くと閲覧権限を申請できる画面になる
  if (info.state === 'denied') return { kind: 'denied' as const, title: 'アクセスできないページ', icon: null, href: pageHref(String(props.node.attrs.workspaceId), pageId.value) }
  return { kind: 'loading' as const, title: String(props.node.attrs.title || '…'), icon: null, href: pageHref(String(props.node.attrs.workspaceId), pageId.value) }
})

// 編集中は、保存しているタイトル（検索・テキスト出力用）を最新に合わせる
watchEffect(() => {
  const v = view.value
  if (editable.value && !guestContext.value && v.kind === 'ok' && v.title !== props.node.attrs.title) {
    queueMicrotask(() => { if (!props.editor.isDestroyed) props.updateAttributes({ title: v.title }) })
  }
})

/** ブロックへのリンクなら #block-ID を付ける */
const fullHref = computed(() => view.value.href && props.node.attrs.anchor && !guestContext.value ? `${view.value.href}#block-${props.node.attrs.anchor}` : view.value.href)
function onClick(e: MouseEvent) {
  const href = fullHref.value
  // 編集モードでは選択する（Ctrl/⌘+クリックで開く）
  if (!href || (editable.value && !(e.metaKey || e.ctrlKey))) { e.preventDefault(); return }
  if (e.metaKey || e.ctrlKey || e.shiftKey) return // 新しいタブ
  e.preventDefault()
  router.push(href)
}
</script>

<template>
  <NodeViewWrapper as="span" class="page-link-wrap">
    <a
      class="page-link"
      :class="view.kind"
      :href="fullHref ?? undefined"
      :title="view.kind === 'denied' ? 'リンク先のページが削除されたか、閲覧する権限がありません（開くと閲覧権限を申請できます）' : editable ? 'Ctrl/⌘+クリックで開く' : undefined"
      data-testid="page-link"
      contenteditable="false"
      draggable="false"
      @click="onClick"
    ><span class="pl-icon">{{ view.kind === 'denied' ? '🔒' : view.icon ?? '📄' }}</span>{{ view.title || '無題' }}</a>
  </NodeViewWrapper>
</template>
