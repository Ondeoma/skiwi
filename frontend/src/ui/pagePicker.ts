import { host } from './host'

export interface PickedPage { id: string; workspaceId: string; title: string }

/** ページ選択モーダル（Scalactive の PagePicker）を開く */
export const openPagePicker = (): Promise<PickedPage | null> => host().pickPage()
