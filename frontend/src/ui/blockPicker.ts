import type { SlashItem } from '../editor/commands'
import { createRequest } from './request'

const req = createRequest<{ items: SlashItem[]; query: string }, SlashItem | null>(() => null)
/** ブロック一覧モーダルの状態（App.vue の BlockPicker が表示する）。 */
export const currentBlockPicker = req.current

export const openBlockPicker = (items: SlashItem[], query = ''): Promise<SlashItem | null> => req.open({ items, query })
