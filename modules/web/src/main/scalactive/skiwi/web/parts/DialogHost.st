<script lang="scala">
import org.scalajs.dom
import skiwi.web.*

// 画面内の確認・入力・選択・お知らせ（Dialog.confirm などで開く）.
// 開いたら、入力欄（文字を選択）・最初の選択肢・OK のどれかにフォーカスする（v-focus）

def onKey(e: dom.KeyboardEvent): Unit = Dialog.current.v.foreach { d =>
  // IME変換中のEnterは確定に使われるので無視する
  if (!e.asInstanceOf[scala.scalajs.js.Dynamic].isComposing.asInstanceOf[Boolean]) {
    if (e.key == "Escape") { e.preventDefault(); Dialog.cancel(d) }
    else if (e.key == "Enter" && !(d.multiline && !(e.metaKey || e.ctrlKey)) && d.kind != "choose") { e.preventDefault(); Dialog.ok(d) }
  }
}

def onBg(e: dom.Event): Unit = if (e.target == e.currentTarget) Dialog.current.v.foreach(Dialog.cancel)

def list = Dialog.current.mapC(_.toList)

def okClass(d: DialogReq): String = if (d.danger) "ok danger" else "ok"
</script>

<template>
  <div v-for="d in list" class="backdrop" @mousedown="onBg(event)" @keydown="onKey(event.asInstanceOf[org.scalajs.dom.KeyboardEvent])">
    <div class="dialog" role="dialog" aria-modal="true" data-testid="app-dialog">
      <p class="message">{{ d.message }}</p>
      <div v-if='d.kind == "choose"' class="choices">
        <button v-for="o in d.options" class="choice" data-testid="dialog-choice" v-focus="d.options.headOption.contains(o)" @click="Dialog.pick(d, o.value)">
          <span>{{ o.label }}</span><small v-if="o.detail.nonEmpty">{{ o.detail.getOrElse("") }}</small>
        </button>
      </div>
      <template v-if='d.kind == "prompt"'>
        <textarea v-if="d.multiline" v-model="d.value" v-focus.select rows="6" :placeholder="d.placeholder"></textarea>
        <input v-else v-model="d.value" type="text" v-focus.select :placeholder="d.placeholder">
      </template>
      <div class="buttons">
        <button v-if='d.kind != "alert"' @click="Dialog.cancel(d)">キャンセル</button>
        <button v-if='d.kind != "choose"' :class="okClass(d)" data-testid="dialog-ok" v-focus='d.kind != "prompt"' @click="Dialog.ok(d)">{{ d.okLabel }}</button>
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
