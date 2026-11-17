/** いまダークで表示しているか（明暗の切り替えは Scalactive の skiwi.web.Theme が <html> の class "dark" で行う） */
export const isDark = () => document.documentElement.classList.contains('dark')

/** 明暗が変わったら f を呼ぶ。戻り値で解除 */
export function onThemeChange(f: (dark: boolean) => void): () => void {
  const mo = new MutationObserver(() => f(isDark()))
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  return () => mo.disconnect()
}
