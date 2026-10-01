import { useState } from 'react'
import { useInput } from 'react-admin'
import TextField from '@mui/material/TextField'

const parseTags = (text: string): string[] =>
  text
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)

// Keeps the typed text locally so a trailing comma survives; the form only ever holds the list.
export const TagsInput = ({ source, label }: { source: string; label: string }) => {
  const { field } = useInput({ source })
  const [text, setText] = useState(() => (Array.isArray(field.value) ? field.value.join(', ') : ''))

  return (
    <TextField
      label={label}
      value={text}
      onChange={(event) => {
        setText(event.target.value)
        field.onChange(parseTags(event.target.value))
      }}
      onBlur={field.onBlur}
      fullWidth
    />
  )
}
