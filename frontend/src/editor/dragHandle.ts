import { onMounted, onUpdated, type Ref } from 'vue'

/**
 * ノードビューのブロックを「つまみ（data-drag-handle）でだけ」ドラッグできるようにする。
 * ProseMirror はドラッグできるブロックの外枠全体に draggable を付けるため、そのままだと
 * 中の操作（ホワイトボードの描画・図形の移動、埋め込みの文字の選択など）でブロックごと動いてしまう。
 */
export function useHandleOnlyDrag(inner: Ref<HTMLElement | null>, wrapperSelector: string) {
  const fix = () => {
    const wrapper = inner.value?.closest<HTMLElement>(wrapperSelector)
    if (wrapper && wrapper.draggable) wrapper.draggable = false
  }
  onMounted(() => { fix(); requestAnimationFrame(fix) })
  onUpdated(fix)
}
