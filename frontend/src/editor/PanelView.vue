<script setup lang="ts">
import { NodeViewContent, NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { ref } from 'vue'
import IconPicker from '../ui/IconPicker.vue'
import { COLORS, type ColorId } from '../ui/palette'
import { useEditable } from './useEditable'

const props = defineProps(nodeViewProps)
const open = ref(false)
const editable = useEditable(props.editor)

function toggle() {
  if (editable.value) open.value = !open.value
}
function setIcon(icon: string | null) {
  props.updateAttributes({ icon })
  open.value = false
}
function setColor(color: ColorId) {
  props.updateAttributes({ color })
}
/** パネルを外して中身だけ残す */
function unwrap() {
  open.value = false
  const pos = props.getPos()
  if (typeof pos !== 'number') return
  props.editor.chain().focus().command(({ tr }) => {
    tr.replaceWith(pos, pos + props.node.nodeSize, props.node.content)
    return true
  }).run()
}
</script>

<template>
  <NodeViewWrapper class="panel" :data-color="props.node.attrs.color" data-panel="">
    <div class="panel-icon" contenteditable="false">
      <button
        v-if="props.node.attrs.icon || editable"
        class="panel-icon-btn"
        :class="{ empty: !props.node.attrs.icon, ro: !editable }"
        :title="editable ? 'アイコンと色を変更' : undefined"
        data-testid="panel-icon"
        @click="toggle"
      >{{ props.node.attrs.icon || '＋' }}</button>
      <IconPicker v-if="open" @select="setIcon" @close="open = false">
        <template #head>
          <div class="panel-colors">
            <button
              v-for="c in COLORS"
              :key="c.id"
              class="swatch"
              :class="{ on: props.node.attrs.color === c.id }"
              :data-color="c.id"
              :title="c.label"
              @click="setColor(c.id)"
            />
            <button class="unwrap" title="パネルを解除して中身だけ残す" @click="unwrap">解除</button>
          </div>
        </template>
      </IconPicker>
    </div>
    <NodeViewContent class="panel-body" />
  </NodeViewWrapper>
</template>
