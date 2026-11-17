import { Node, mergeAttributes } from '@tiptap/core'
import { DueDateView } from './DueDateView'
import { ymd } from '../lib'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    dueDate: { insertDueDate: (date?: string) => ReturnType }
  }
}

/** 今日（この端末の日付）を YYYY-MM-DD で */
export function today(offsetDays = 0): string {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  return ymd(d)
}

/** 期限の印（ToDo の項目に置くと「自分のToDo」の期限になる）。 */
export const DueDate = Node.create({
  name: 'dueDate',
  group: 'inline',
  inline: true,
  atom: true,
  addAttributes() {
    return { date: { default: null, parseHTML: (el) => el.getAttribute('data-date'), renderHTML: (a) => ({ 'data-date': a.date }) } }
  },
  parseHTML() { return [{ tag: 'span[data-due]' }] },
  renderHTML({ node, HTMLAttributes }) { return ['span', mergeAttributes(HTMLAttributes, { 'data-due': '', class: 'due-date' }), `📅 ${node.attrs.date ?? ''}`] },
  renderText({ node }) { return `📅${node.attrs.date ?? ''}` },
  addNodeView() { return DueDateView },
  addCommands() {
    return { insertDueDate: (date) => ({ chain }) => chain().insertContent([{ type: this.name, attrs: { date: date ?? today(7) } }, { type: 'text', text: ' ' }]).run() }
  },
})
