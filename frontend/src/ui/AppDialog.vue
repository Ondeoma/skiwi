<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { currentDialog } from './dialog'

const text = ref('')
const input = ref<HTMLInputElement | HTMLTextAreaElement | null>(null)
const okBtn = ref<HTMLButtonElement | null>(null)

watch(currentDialog, async (d) => {
  if (!d) return
  text.value = d.value
  await nextTick()
  if (d.kind === 'prompt') { input.value?.focus(); input.value?.select() } else if (d.kind === 'choose') document.querySelector<HTMLElement>('[data-testid=dialog-choice]')?.focus(); else okBtn.value?.focus()
})

function ok() {
  const d = currentDialog.value
  if (!d) return
  d.resolve(d.kind === 'prompt' ? text.value : true)
}
function cancel() {
  const d = currentDialog.value
  if (!d) return
  d.resolve(d.kind === 'confirm' ? false : null)
}
function onKey(e: KeyboardEvent) {
  // IME変換中のEnterは確定に使われるので無視する
  if (e.isComposing) return
  if (e.key === 'Escape') { e.preventDefault(); cancel() }
  if (e.key === 'Enter' && !(currentDialog.value?.multiline && !(e.metaKey || e.ctrlKey))) { e.preventDefault(); ok() }
}
</script>

<template>
  <div v-if="currentDialog" class="backdrop" @mousedown.self="cancel" @keydown="onKey">
    <div class="dialog" role="dialog" aria-modal="true" data-testid="app-dialog">
      <p class="message">{{ currentDialog.message }}</p>
      <div v-if="currentDialog.kind === 'choose'" class="choices">
        <button v-for="o in currentDialog.options" :key="o.value" class="choice" data-testid="dialog-choice" @click="currentDialog.resolve(o.value)">
          <span>{{ o.label }}</span><small v-if="o.detail">{{ o.detail }}</small>
        </button>
      </div>
      <template v-if="currentDialog.kind === 'prompt'">
        <textarea v-if="currentDialog.multiline" ref="input" v-model="text" rows="6" :placeholder="currentDialog.placeholder" />
        <input v-else ref="input" v-model="text" type="text" :placeholder="currentDialog.placeholder" />
      </template>
      <div class="buttons">
        <button v-if="currentDialog.kind !== 'alert'" @click="cancel">キャンセル</button>
        <button v-if="currentDialog.kind !== 'choose'" ref="okBtn" class="ok" :class="{ danger: currentDialog.danger }" data-testid="dialog-ok" @click="ok">{{ currentDialog.okLabel }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.backdrop { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: flex; align-items: flex-start; justify-content: center; padding-top: 18vh; z-index: 1000; }
.dialog { background: var(--bg); border: 1px solid var(--line); border-radius: 10px; padding: 1rem 1.2rem; width: min(440px, calc(100vw - 32px)); box-shadow: 0 12px 40px rgba(0,0,0,.25); }
.message { margin: 0 0 .8rem; white-space: pre-wrap; }
input, textarea { width: 100%; padding: .4rem .6rem; border: 1px solid var(--line); border-radius: 6px; background: var(--bg); margin-bottom: .8rem; font-family: inherit; }
textarea { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .9em; }
.buttons { display: flex; justify-content: flex-end; gap: .5rem; }
.ok { background: var(--accent); border-color: var(--accent); color: #fff; }
.ok:hover { background: var(--accent); filter: brightness(1.1); }
.ok.danger { background: var(--danger); border-color: var(--danger); }
.choices { display: flex; flex-direction: column; gap: 4px; max-height: 50vh; overflow: auto; margin: .4rem 0; }
.choice { display: flex; flex-direction: column; align-items: flex-start; text-align: left; padding: .45rem .6rem; border-radius: 7px; }
.choice small { color: var(--muted); font-size: .75rem; }
.choice:hover { border-color: var(--accent); }
</style>
