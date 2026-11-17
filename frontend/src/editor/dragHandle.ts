/**
 * ノードビューのブロックを「つまみ（data-drag-handle）でだけ」ドラッグできるようにする。
 * ProseMirror はドラッグできるブロックの外枠全体に draggable を付けるため、そのままだと
 * 中の操作（ホワイトボードの描画・図形の移動、埋め込みの文字の選択など）でブロックごと動いてしまう。
 * 外枠は作った直後に ProseMirror が draggable を付けるので、表示のたびと次の描画のときに外す。
 */
export function handleOnlyDrag(wrapper: HTMLElement): () => void {
  const fix = () => { if (wrapper.draggable) wrapper.draggable = false }
  queueMicrotask(fix)
  requestAnimationFrame(fix)
  return fix
}
