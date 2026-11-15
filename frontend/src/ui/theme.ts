import { computed, ref, watch } from 'vue'

/**
 * 画面の明暗（ライト / ダーク）。OS の設定に合わせるか、手で選ぶ（このブラウザに保存）。
 * 実際の切り替えは <html> の class "dark" で行い、CSS はそれを見る（style.css）。
 * 読み込み直後のちらつきを防ぐため、index.html でも同じ判定で先に class を付けている。
 */
export type ThemeMode = 'system' | 'light' | 'dark'
export const themeModes: readonly ThemeMode[] = ['system', 'light', 'dark']
export const themeLabels: Record<ThemeMode, string> = { system: 'OS に合わせる', light: 'ライト', dark: 'ダーク' }
export const themeIcons: Record<ThemeMode, string> = { system: '🖥', light: '☀️', dark: '🌙' }

const KEY = 'skiwi.theme'

function load(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch {
    return 'system'
  }
}

const media = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(media.matches)
media.addEventListener('change', (e) => { systemDark.value = e.matches })

export const themeMode = ref<ThemeMode>(load())
/** いまダークで表示しているか */
export const isDark = computed(() => themeMode.value === 'dark' || (themeMode.value === 'system' && systemDark.value))

watch(themeMode, (v) => {
  try {
    if (v === 'system') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, v)
  } catch { /* 保存できない環境 */ }
})
watch(isDark, (d) => document.documentElement.classList.toggle('dark', d), { immediate: true })

/** OS に合わせる → ライト → ダーク の順に切り替える */
export function cycleTheme() {
  themeMode.value = themeModes[(themeModes.indexOf(themeMode.value) + 1) % themeModes.length]
}
