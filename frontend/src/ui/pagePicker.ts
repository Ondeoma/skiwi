import { createRequest } from './request'

export interface PickedPage { id: string; workspaceId: string; title: string }

const req = createRequest<object, PickedPage | null>(() => null)
/** ページ選択モーダルの状態（App.vue の PagePicker が表示する）。 */
export const currentPagePicker = req.current

export const openPagePicker = (): Promise<PickedPage | null> => req.open({})
