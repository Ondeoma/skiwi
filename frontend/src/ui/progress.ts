import { shallowRef } from 'vue'

/** 時間のかかる処理（取り込み・書き出し）の進み具合。App.vue の右下に出す。 */
export const progress = shallowRef<{ label: string; done: number; total: number } | null>(null)
