import { useInput, type InputProps } from 'react-admin'
import { MarkdownEditor } from './MarkdownEditor'

interface MarkdownInputProps extends Pick<InputProps, 'source' | 'validate'> {
  label: string
  minRows?: number
}

/** MarkdownEditor bound to a react-admin record field. */
export function MarkdownInput({ source, label, minRows, validate }: MarkdownInputProps) {
  const { field, fieldState } = useInput({ source, validate })

  return (
    <MarkdownEditor
      label={label}
      minRows={minRows}
      value={(field.value as string | null | undefined) ?? ''}
      onChange={field.onChange}
      onBlur={field.onBlur}
      error={fieldState.invalid}
      helperText={fieldState.error?.message}
    />
  )
}
