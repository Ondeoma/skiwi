import { Node, mergeAttributes } from '@tiptap/core'
import { safeEmbedUrl } from '../util/url'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    embed: { insertEmbed: (url: string) => ReturnType }
  }
}

/** 埋め込みに対応しているサービスの URL なら、埋め込み用の URL（それ以外は null） */
export function knownEmbedUrl(raw: string): string | null {
  let u: URL
  try { u = new URL(raw.trim()) } catch { return null }
  if (u.protocol !== 'https:') return null
  const host = u.hostname.replace(/^www\./, '')
  const path = u.pathname
  if (host === 'youtube.com' || host === 'm.youtube.com') {
    const id = u.searchParams.get('v') ?? (path.match(/^\/(?:shorts|embed|live)\/([\w-]+)/)?.[1])
    if (id) return `https://www.youtube-nocookie.com/embed/${id}`
  }
  if (host === 'youtu.be' && path.length > 1) return `https://www.youtube-nocookie.com/embed/${path.slice(1)}`
  if (host === 'vimeo.com') { const id = path.match(/^\/(\d+)/)?.[1]; if (id) return `https://player.vimeo.com/video/${id}` }
  // Figma（ファイル・デザイン・プロトタイプ・FigJam）
  if (host === 'figma.com' && /^\/(file|design|proto|board)\//.test(path)) return `https://www.figma.com/embed?embed_host=skiwi&url=${encodeURIComponent(u.toString())}`
  // Google ドキュメント・スプレッドシート・スライド・フォーム・ドライブ
  if (host === 'docs.google.com') {
    const m = path.match(/^\/(document|spreadsheets|presentation|forms)\/d\/(e\/)?([\w-]+)/)
    if (m) {
      const base = `https://docs.google.com/${m[1]}/d/${m[2] ?? ''}${m[3]}`
      return m[1] === 'presentation' ? `${base}/embed` : m[1] === 'forms' ? `${base}/viewform?embedded=true` : `${base}/preview`
    }
  }
  if (host === 'drive.google.com') { const id = path.match(/^\/file\/d\/([\w-]+)/)?.[1]; if (id) return `https://drive.google.com/file/d/${id}/preview` }
  if (host === 'loom.com') { const id = path.match(/^\/(?:share|embed)\/(\w+)/)?.[1]; if (id) return `https://www.loom.com/embed/${id}` }
  if (host === 'miro.com') { const id = path.match(/^\/app\/board\/([^/]+)/)?.[1]; if (id) return `https://miro.com/app/live-embed/${id}/` }
  if (host === 'codepen.io') { const m = path.match(/^\/([^/]+)\/(?:pen|embed)\/(\w+)/); if (m) return `https://codepen.io/${m[1]}/embed/${m[2]}?default-tab=result` }
  if (host === 'open.spotify.com') { const m = path.match(/^\/(track|album|playlist|episode|show)\/(\w+)/); if (m) return `https://open.spotify.com/embed/${m[1]}/${m[2]}` }
  return null
}

/** 共有URLを埋め込み用URLに変換する。https 以外は拒否。対応サービス以外はそのまま埋め込む */
function toEmbedUrl(raw: string): string | null {
  const known = knownEmbedUrl(raw)
  if (known) return known
  let u: URL
  try { u = new URL(raw.trim()) } catch { return null }
  if (u.protocol !== 'https:') return null
  const host = u.hostname.replace(/^www\./, '')
  if (host === 'youtube.com' || host === 'm.youtube.com') {
    const id = u.searchParams.get('v') ?? (u.pathname.match(/^\/(?:shorts|embed|live)\/([\w-]+)/)?.[1])
    if (id) return `https://www.youtube-nocookie.com/embed/${id}`
  }
  if (host === 'youtu.be') return `https://www.youtube-nocookie.com/embed/${u.pathname.slice(1)}`
  if (host === 'vimeo.com') {
    const id = u.pathname.match(/^\/(\d+)/)?.[1]
    if (id) return `https://player.vimeo.com/video/${id}`
  }
  return safeEmbedUrl(u.toString())
}

/** 外部コンテンツの埋め込み(iframe)。sandbox で動作を制限する。 */
export const Embed = Node.create({
  name: 'embed',
  group: 'block',
  atom: true,
  draggable: true,
  addAttributes() { return { src: { default: null }, height: { default: 400 } } },
  parseHTML() { return [{ tag: 'div[data-embed]', getAttrs: (el) => ({ src: safeEmbedUrl((el as HTMLElement).querySelector('iframe')?.getAttribute('src')) }) }] },
  renderHTML({ node, HTMLAttributes }) {
    // 本文は同時編集で好きな値にできるので、描画の直前にも https の外部 URL だけに限る（javascript: などは出さない）
    const src = safeEmbedUrl(node.attrs.src)
    if (!src) return ['div', mergeAttributes(HTMLAttributes, { 'data-embed': '', class: 'embed-block embed-invalid' }), '埋め込めない URL です']
    return ['div', mergeAttributes(HTMLAttributes, { 'data-embed': '', class: 'embed-block' }),
      ['iframe', {
        src, height: Number(node.attrs.height) || 400, loading: 'lazy', allowfullscreen: 'true',
        referrerpolicy: 'strict-origin-when-cross-origin',
        sandbox: 'allow-scripts allow-same-origin allow-popups allow-presentation allow-forms',
      }]]
  },
  renderText({ node }) { return `[embed: ${node.attrs.src}]` },
  addCommands() {
    return {
      insertEmbed: (url) => ({ commands }) => {
        const src = toEmbedUrl(url)
        return src ? commands.insertContent({ type: this.name, attrs: { src } }) : false
      },
    }
  },
})
