import { router } from '../router'
import { confirmDialog } from '../ui/dialog'

/**
 * Scalactive の画面（skiwi.web.Host）から使う Vue 側の機能.
 * 画面の移動やダイアログは、移行が終わるまで Vue の仕組みを使う.
 */
const host = {
  navigate: (path: string) => { void router.push(path) },
  confirm: (message: string, opts: { okLabel?: string; danger?: boolean }) => confirmDialog(message, opts),
}

;(globalThis as unknown as { __skiwiHost: typeof host }).__skiwiHost = host
