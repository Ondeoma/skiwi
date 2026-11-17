const p2 = (n: number) => String(n).padStart(2, '0')

/** この端末の時刻での日付 YYYY-MM-DD */
export const ymd = (d: Date) => `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`
