<script setup lang="ts">
import { NodeViewContent, NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { computed } from 'vue'
import { LANGUAGES, languageLabel } from './CodeBlock'
import { useEditable } from './useEditable'
import { useCopy } from '../ui/composables'

const props = defineProps(nodeViewProps)
const lang = computed(() => (props.node.attrs.language as string | null) ?? '')
const editable = useEditable(props.editor)

function setLang(e: Event) {
  const v = (e.target as HTMLSelectElement).value
  props.updateAttributes({ language: v || null })
}
const { copied, copy: copyText } = useCopy()
const copy = () => copyText(props.node.textContent)
</script>

<template>
  <NodeViewWrapper class="code-block" :data-language="lang || undefined">
    <div class="code-head" contenteditable="false">
      <select v-if="editable" :value="lang" title="言語" data-testid="code-language" @change="setLang">
        <option value="">自動判定</option>
        <option v-for="l in LANGUAGES" :key="l.id" :value="l.id">{{ l.label }}</option>
      </select>
      <span v-else class="lang">{{ lang ? languageLabel(lang) : '' }}</span>
      <button class="copy" title="コードをコピー" @click="copy">{{ copied ? 'コピーしました' : 'コピー' }}</button>
    </div>
    <pre><NodeViewContent as="code" :class="lang ? `language-${lang}` : undefined" /></pre>
  </NodeViewWrapper>
</template>
