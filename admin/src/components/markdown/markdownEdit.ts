export interface Edit {
  value: string
  start: number
  end: number
}

export type Format = 'checkbox' | 'bullet' | 'bold' | 'heading' | 'link'

const LINE_PREFIX: Record<'checkbox' | 'bullet' | 'heading', string> = {
  checkbox: '- [ ] ',
  bullet: '- ',
  heading: '# ',
}

const ANY_PREFIX = /^(\s*)(- \[[ xX]\] |[-*+] |#{1,6} )/

const lineStart = (value: string, pos: number): number => value.lastIndexOf('\n', pos - 1) + 1

const wrapSelection = (value: string, start: number, end: number, before: string, after: string, placeholder: string): Edit => {
  const selected = value.slice(start, end) || placeholder
  const next = value.slice(0, start) + before + selected + after + value.slice(end)

  return { value: next, start: start + before.length, end: start + before.length + selected.length }
}

/** Applies a toolbar button at the selection: a line prefix toggles, a wrapper surrounds the selection. */
export const applyFormat = (value: string, start: number, end: number, format: Format): Edit => {
  if (format === 'bold') return wrapSelection(value, start, end, '**', '**', 'texte')
  if (format === 'link') {
    const selected = value.slice(start, end) || 'texte'
    const next = `${value.slice(0, start)}[${selected}](https://)${value.slice(end)}`
    const urlStart = start + selected.length + 3

    return { value: next, start: urlStart, end: urlStart + 'https://'.length }
  }

  const prefix = LINE_PREFIX[format]
  const from = lineStart(value, start)
  const rest = value.slice(from)
  if (rest.startsWith(prefix)) {
    return { value: value.slice(0, from) + rest.slice(prefix.length), start: Math.max(from, start - prefix.length), end: Math.max(from, end - prefix.length) }
  }
  const existing = ANY_PREFIX.exec(rest.split('\n')[0])
  const removed = existing ? existing[2].length : 0
  const indent = existing ? existing[1].length : 0
  const next = value.slice(0, from + indent) + prefix + rest.slice(indent + removed)
  const delta = prefix.length - removed

  return { value: next, start: start + delta, end: end + delta }
}

/** Strips the Markdown marks of the selected lines (or of the whole text when nothing is selected). */
export const clearFormat = (value: string, start: number, end: number): Edit => {
  const from = start === end ? 0 : lineStart(value, start)
  const endOfLine = value.indexOf('\n', end)
  const to = start === end ? value.length : endOfLine === -1 ? value.length : endOfLine
  const cleaned = value
    .slice(from, to)
    .split('\n')
    .map((line) => line.replace(ANY_PREFIX, '$1'))
    .join('\n')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
  const next = value.slice(0, from) + cleaned + value.slice(to)

  return { value: next, start: from, end: from + cleaned.length }
}

const LIST_LINE = /^(\s*)((?:- \[[ xX]\] )|(?:[-*+] ))(.*)$/

/** Enter in a list: continues it, or leaves it on an empty item. Null when the caret is not on a list item. */
export const continueList = (value: string, caret: number): Edit | null => {
  const from = lineStart(value, caret)
  const end = value.indexOf('\n', caret)
  const line = value.slice(from, end === -1 ? value.length : end)
  const match = LIST_LINE.exec(line)
  if (!match || caret < from + match[1].length + match[2].length) return null

  if (match[3].trim() === '') {
    const next = value.slice(0, from) + value.slice(end === -1 ? value.length : end)

    return { value: next, start: from, end: from }
  }
  const marker = match[1] + (match[2].startsWith('- [') ? '- [ ] ' : match[2])
  const insert = `\n${marker}`
  const next = value.slice(0, caret) + insert + value.slice(caret)

  return { value: next, start: caret + insert.length, end: caret + insert.length }
}

const CHECKBOX_LINE = /^(\s*[-*+] )\[([ xX])\]( .*|)$/

/** Flips the checkbox on the given source line. */
export const toggleCheckbox = (value: string, lineIndex: number): string => {
  const lines = value.split('\n')
  const match = CHECKBOX_LINE.exec(lines[lineIndex] ?? '')
  if (!match) return value
  lines[lineIndex] = `${match[1]}[${match[2] === ' ' ? 'x' : ' '}]${match[3]}`

  return lines.join('\n')
}

export const checkboxCount = (value: string): { done: number; total: number } => {
  let done = 0
  let total = 0
  for (const line of value.split('\n')) {
    const match = CHECKBOX_LINE.exec(line)
    if (!match) continue
    total += 1
    if (match[2] !== ' ') done += 1
  }

  return { done, total }
}
