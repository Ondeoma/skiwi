/** ユーザーのアイコンまわり（エディタの同時編集で使う分. 画像の加工は Scala の workspace.AvatarImage、丸の色は parts/Avatar.st） */

/** サーバーが発行するアイコンの URL の形（同時編集で他の人から届いた値は、この形のものだけを使う） */
const AVATAR_URL = /^\/api\/users\/[0-9a-f-]{36}\/avatar\?v=\d+$/
export const safeAvatarUrl = (u: unknown): string | null => (typeof u === 'string' && AVATAR_URL.test(u) ? u : null)

/** 同じページを開いている人（同時編集の awareness から） */
export interface Person { name: string; color: string; editing: boolean; avatar: string | null }
