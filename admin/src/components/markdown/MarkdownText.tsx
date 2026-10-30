import { Fragment, type ReactNode } from 'react'
import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import Typography from '@mui/material/Typography'
import { checkboxCount, toggleCheckbox } from './markdownEdit'

interface MarkdownTextProps {
  value: string
  /** Called with the rewritten Markdown when a box is ticked; without it the boxes are read-only. */
  onChange?: (value: string) => void
}

const INLINE = /\*\*([^*]+)\*\*|\[([^\]]+)\]\((https?:\/\/[^)\s]+|mailto:[^)\s]+)\)/g

const inline = (text: string): ReactNode[] => {
  const nodes: ReactNode[] = []
  let last = 0
  for (const match of text.matchAll(INLINE)) {
    if (match.index > last) nodes.push(text.slice(last, match.index))
    if (match[1] !== undefined) nodes.push(<strong key={match.index}>{match[1]}</strong>)
    else
      nodes.push(
        <a key={match.index} href={match[3]} target="_blank" rel="noopener noreferrer">
          {match[2]}
        </a>,
      )
    last = match.index + match[0].length
  }
  if (last < text.length) nodes.push(text.slice(last))

  return nodes
}

const BOX = /^\s*[-*+] \[([ xX])\]( .*|)$/
const ITEM = /^\s*[-*+] (.*)$/
const HEADING = /^(#{1,6}) (.*)$/

/** Renders a Markdown text (GFM subset: headings, bold, links, bullets, task boxes); plain text shows as typed. */
export function MarkdownText({ value, onChange }: MarkdownTextProps) {
  const lines = value.split('\n')
  const { done, total } = checkboxCount(value)
  const blocks: ReactNode[] = []
  let paragraph: string[] = []

  const flush = (key: number) => {
    if (paragraph.length === 0) return
    blocks.push(
      <Typography key={`p${key}`} variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
        {paragraph.map((line, i) => (
          <Fragment key={i}>
            {i > 0 ? '\n' : null}
            {inline(line)}
          </Fragment>
        ))}
      </Typography>,
    )
    paragraph = []
  }

  lines.forEach((line, index) => {
    const box = BOX.exec(line)
    const item = ITEM.exec(line)
    const heading = HEADING.exec(line)
    if (!box && !item && !heading) {
      paragraph.push(line)

      return
    }
    flush(index)
    if (box) {
      const checked = box[1] !== ' '
      const text = box[2].trim()
      blocks.push(
        <Box key={index} sx={{ display: 'flex', alignItems: 'center' }}>
          <Checkbox
            size="small"
            checked={checked}
            disabled={!onChange}
            onChange={() => onChange?.(toggleCheckbox(value, index))}
            slotProps={{ input: { 'aria-label': text } }}
            sx={{ p: 0.5 }}
          />
          <Typography variant="body2" sx={{ textDecoration: checked ? 'line-through' : 'none', opacity: checked ? 0.6 : 1 }}>
            {inline(text)}
          </Typography>
        </Box>,
      )
    } else if (item) {
      blocks.push(
        <Typography key={index} variant="body2" sx={{ pl: 1 }}>
          • {inline(item[1])}
        </Typography>,
      )
    } else if (heading) {
      blocks.push(
        <Typography key={index} variant="subtitle1" component="div" sx={{ fontWeight: 700 }}>
          {inline(heading[2])}
        </Typography>,
      )
    }
  })
  flush(lines.length)

  return (
    <Box>
      {total > 0 ? (
        <Typography variant="caption" color="text.secondary" data-testid="markdown-progress">
          {done}/{total}
        </Typography>
      ) : null}
      {blocks}
    </Box>
  )
}
