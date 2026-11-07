const p2 = (n: number) => String(n).padStart(2, '0')

/** この端末の時刻での日付 YYYY-MM-DD */
export const ymd = (d: Date) => `${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())}`

/** この端末の時刻での日時 YYYY-MM-DDTHH:mm（input[type=datetime-local] の値） */
export const localDateTime = (d: Date) => `${ymd(d)}T${p2(d.getHours())}:${p2(d.getMinutes())}`
