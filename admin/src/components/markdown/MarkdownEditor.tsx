import { useLayoutEffect, useRef, type KeyboardEvent } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { applyFormat, clearFormat, continueList, type Edit, type Format } from './markdownEdit'

interface MarkdownEditorProps {
  label: string
  value: string
  onChange: (value: string) => void
  minRows?: number
  autoFocus?: boolean
  error?: boolean
  helperText?: string
  disabled?: boolean
  maxLength?: number
  onBlur?: () => void
}

const BUTTONS: { format: Format; label: string; glyph: string }[] = [
  { format: 'checkbox', label: 'Case à cocher', glyph: '☐' },
  { format: 'bullet', label: 'Puces', glyph: '•' },
  { format: 'bold', label: 'Gras', glyph: 'B' },
  { format: 'heading', label: 'Intertitre', glyph: 'T' },
  { format: 'link', label: 'Lien', glyph: '🔗' },
]

/** A multiline text stored as Markdown, with a formatting bar that is always shown. */
export function MarkdownEditor({ label, value, onChange, minRows = 2, autoFocus, error, helperText, disabled, maxLength, onBlur }: MarkdownEditorProps) {
  const input = useRef<HTMLTextAreaElement | null>(null)
  const pending = useRef<{ start: number; end: number } | null>(null)

  useLayoutEffect(() => {
    if (pending.current && input.current) {
      input.current.setSelectionRange(pending.current.start, pending.current.end)
      pending.current = null
    }
  })

  const run = (compute: (start: number, end: number) => Edit | null): boolean => {
    const el = input.current
    if (!el) return false
    const edit = compute(el.selectionStart ?? value.length, el.selectionEnd ?? value.length)
    if (!edit) return false
    pending.current = { start: edit.start, end: edit.end }
    onChange(edit.value)
    el.focus()

    return true
  }

  const format = (kind: Format) => run((start, end) => applyFormat(value, start, end, kind))

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'b') {
      event.preventDefault()
      format('bold')
    } else if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
      event.preventDefault()
      format('link')
    } else if (event.key === 'Enter' && !event.shiftKey && !event.ctrlKey && !event.metaKey && !event.nativeEvent.isComposing) {
      if (run((start, end) => (start === end ? continueList(value, start) : null))) event.preventDefault()
    }
  }

  return (
    <Box>
      <Typography variant="caption" color={error ? 'error' : 'text.secondary'} sx={{ display: 'block', mb: 0.5 }}>
        {label}
      </Typography>
      <Box sx={{ border: '1px solid #25222F', borderRadius: '10px', overflow: 'hidden' }}>
        <Box role="toolbar" aria-label="Barre de mise en forme" sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 0.5, py: 0.25, borderBottom: '1px solid #25222F' }}>
          {BUTTONS.map((button) => (
            <Button
              key={button.format}
              size="small"
              aria-label={button.label}
              title={button.label}
              disabled={disabled}
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => format(button.format)}
              sx={{ minWidth: 32, px: 0.5, fontWeight: button.format === 'bold' ? 800 : 500 }}
            >
              {button.glyph}
            </Button>
          ))}
          <Box sx={{ flex: 1 }} />
          <Button
            size="small"
            aria-label="Retirer la mise en forme"
            title="Retirer la mise en forme"
            disabled={disabled}
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => run((start, end) => clearFormat(value, start, end))}
            sx={{ minWidth: 32, px: 0.5 }}
          >
            Tx
          </Button>
        </Box>
        <TextField
          variant="standard"
          fullWidth
          multiline
          minRows={minRows}
          value={value}
          autoFocus={autoFocus}
          disabled={disabled}
          error={error}
          onChange={(event) => onChange(event.target.value)}
          onBlur={onBlur}
          onKeyDown={onKeyDown}
          inputRef={input}
          slotProps={{ input: { disableUnderline: true, sx: { px: 1.5, py: 1 } }, htmlInput: { 'aria-label': label, maxLength } }}
        />
      </Box>
      {helperText ? (
        <Typography variant="caption" color={error ? 'error' : 'text.secondary'} sx={{ display: 'block', mt: 0.5 }}>
          {helperText}
        </Typography>
      ) : null}
    </Box>
  )
}
