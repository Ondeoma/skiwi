/**
 * ファイルのドラッグ＆ドロップの受け皿。
 *  - 編集中のエディタの上では、落とせる場所を点線で示す（エディタがアップロードする）
 *  - それ以外（表示モードの本文・余白・サイドメニューなど）に落としても、ブラウザがファイルを開いてページから離れないようにする
 * ホワイトボード（Excalidraw）は自分で受け取るので邪魔しない。
 */
const ACCEPTS = '.ProseMirror[contenteditable="true"], .excalidraw'
const hasFiles = (e: DragEvent) => !!e.dataTransfer && [...e.dataTransfer.types].includes('Files')
const accepts = (t: EventTarget | null) => t instanceof Element && !!t.closest(ACCEPTS)

export function installFileDropGuard() {
  let depth = 0
  const set = (on: boolean) => document.body.classList.toggle('dragging-files', on)
  window.addEventListener('dragenter', (e) => { if (hasFiles(e)) { depth++; set(true) } })
  window.addEventListener('dragleave', (e) => { if (hasFiles(e) && --depth <= 0) { depth = 0; set(false) } })
  window.addEventListener('dragover', (e) => {
    if (!hasFiles(e) || e.defaultPrevented || accepts(e.target)) return
    e.preventDefault()
    e.dataTransfer!.dropEffect = 'none'
  })
  window.addEventListener('drop', (e) => {
    depth = 0
    set(false)
    if (hasFiles(e) && !e.defaultPrevented && !accepts(e.target)) e.preventDefault()
  })
}

/** ファイルを選んでもらう（取り消したら空） */
export function pickFiles(accept: string, multiple = false): Promise<File[]> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.multiple = multiple
    input.accept = accept
    input.onchange = () => resolve([...(input.files ?? [])])
    input.oncancel = () => resolve([])
    input.click()
  })
}
