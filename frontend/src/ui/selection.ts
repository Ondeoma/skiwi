import { ref } from 'vue'

/** ページツリーで選んでいるページ（一括操作用。選んだ順を保つ） */
export const selectedPages = ref<string[]>([])

export function toggleSelected(id: string) {
  const i = selectedPages.value.indexOf(id)
  selectedPages.value = i >= 0 ? selectedPages.value.filter((x) => x !== id) : [...selectedPages.value, id]
}
export function clearSelection() { selectedPages.value = [] }
