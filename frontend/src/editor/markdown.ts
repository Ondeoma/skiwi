/** ファイルを保存させる（ホワイトボードの書き出しなど. ページの Markdown・ZIP の書き出しは Scala の skiwi.web.content.Export） */
export function download(filename: string, text: string, type = 'text/markdown;charset=utf-8', blob?: Blob) {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob ?? new Blob([text], { type }))
  a.download = filename.replace(/[\\/:*?"<>|]/g, '_')
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}
