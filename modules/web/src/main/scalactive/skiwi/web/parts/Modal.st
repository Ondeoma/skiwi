<script lang="scala">
import org.scalajs.dom

// 設定画面などのモーダルの枠（背景・見出し・✕）. 中身は既定のスロット、見出しは title（後ろに置く部品は名前付きスロット head）.
// 背景をクリック（mousedown）するか ✕ で onClose.
// width: 幅の上限（px）/ maxHeight・top: 高さの上限・上の余白（vh）/ dim: 背景の暗さ /
// smallTitle: 見出しを小さく / side: 右に寄せて、後ろの画面を操作できるままにする（背景を暗くしない）
case class Props(label: String,
                 onClose: Unit => Unit,
                 title: String = "",
                 testid: String = "",
                 width: Int = 640,
                 maxHeight: Int = 84,
                 top: Int = 8,
                 z: Int = 60,
                 dim: Double = 0.4,
                 side: Boolean = false,
                 closeTitle: String = "",
                 smallTitle: Boolean = false,
                 slot: Slot = Slot.empty,
                 head: Slot = Slot.empty)

def bgStyle(p: Props): String =
  s"padding-top: ${p.top}vh; z-index: ${p.z}; background: ${if (p.side) "transparent" else s"rgba(0,0,0,${p.dim})"}"

def boxStyle(p: Props): String = s"width: min(${p.width}px, 100%); max-height: ${p.maxHeight}vh"

def bgClass(p: Props): String = if (p.side) "modal-backdrop docked" else "modal-backdrop"

def onBg(p: Props, e: dom.Event): Unit = if (e.target == e.currentTarget) p.onClose(())
</script>

<template>
  <Teleport to="body">
    <div :class="bgClass(props)" :style="bgStyle(props)" @mousedown="onBg(props, event)">
      <div class="modal-dialog" role="dialog" :aria-label="props.label" :data-testid="props.testid" :style="boxStyle(props)">
        <header>
          <h2 :class='if (props.smallTitle) "compact" else ""'>{{ props.title }} <slot name="head"></slot></h2>
          <button class="x" :title="props.closeTitle" @click="props.onClose(())">✕</button>
        </header>
        <slot></slot>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; display: flex; align-items: flex-start; justify-content: center; padding: 8vh 16px 16px; }
.modal-backdrop.docked { justify-content: flex-end; pointer-events: none; }
.modal-dialog { pointer-events: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; overflow: auto; padding: .8rem 1rem 1rem; box-shadow: 0 16px 48px rgba(0,0,0,.3); }
header { display: flex; align-items: center; justify-content: space-between; gap: .5rem; }
h2 { font-size: 1.05rem; margin: 0; }
h2.compact { font-size: 1rem; }
.x { border: none; color: var(--muted); }
</style>
