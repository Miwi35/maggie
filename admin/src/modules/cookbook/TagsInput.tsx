import { useEffect, useState } from 'react'
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

  // The list changed under the field (another client, Maggie): show it. Typing never gets here,
  // because the form then holds exactly what the text parses to.
  useEffect(() => {
    const tags: string[] = Array.isArray(field.value) ? field.value : []
    setText((current) => (parseTags(current).join('\u0000') === tags.join('\u0000') ? current : tags.join(', ')))
  }, [field.value])

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
