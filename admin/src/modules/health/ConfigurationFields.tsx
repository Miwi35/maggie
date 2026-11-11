import { useId, useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import InputBase from '@mui/material/InputBase'
import NativeSelect from '@mui/material/NativeSelect'
import type { Theme } from '@mui/material/styles'
import { parseFigure } from './healthProfileApi'

/** The mockup's `.in`: a 40px field on the raised surface. */
const inputSx = (theme: Theme) => ({
  height: 40,
  borderRadius: 2.5,
  bgcolor: theme.palette.veilleuse.raised,
  px: 1.5,
  fontSize: 15,
  color: theme.palette.text.primary,
  fontVariantNumeric: 'tabular-nums',
  width: '100%',
  '& input, & select': { p: 0 },
}) as const

/** The mockup's `.field`: its label over the field. */
export const Field = ({ id, label, children }: { id: string; label: string; children: ReactNode }) => (
  <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75, minWidth: 0 }}>
    <Box component="label" htmlFor={id} sx={{ fontSize: 13, color: 'text.secondary' }}>
      {label}
    </Box>
    {children}
  </Box>
)

interface FigureFieldProps {
  label: string
  value: number | null
  format: (value: number) => string
  /** What follows the figure in the field: « ans », « kg », « / jour ». */
  unit: string
  onSave: (value: number) => Promise<boolean>
  /** A number field the arrows step through (the entered need: by 50 within 1 000..5 000); a text field otherwise. */
  stepper?: { min: number; max: number; step: number }
}

/**
 * A figure, saved when the field is left (or on Enter), not on each keystroke.
 * What is typed stays shown until the API answers, then the field shows what
 * the API kept — the previous value when it refused. Nothing typed, or not a
 * figure: the field goes back to the saved value, nothing is sent.
 */
export const FigureField = ({ label, value, format, unit, onSave, stepper }: FigureFieldProps) => {
  const id = useId()
  const [draft, setDraft] = useState<string | null>(null)
  const shown = draft ?? (value === null ? '' : format(value))

  const commit = () => {
    if (draft === null) return
    const figure = parseFigure(draft)
    if (figure === null || figure === value) {
      setDraft(null)
      return
    }
    void onSave(figure).finally(() => setDraft(null))
  }

  return (
    <Field id={id} label={label}>
      <InputBase
        id={id}
        value={shown}
        placeholder="—"
        onChange={(event) => setDraft(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') commit()
        }}
        type={stepper ? 'number' : 'text'}
        inputProps={stepper ?? { inputMode: 'decimal' }}
        endAdornment={
          <Box component="span" sx={{ ml: 0.5, flex: 'none' }}>
            {unit}
          </Box>
        }
        sx={inputSx}
      />
    </Field>
  )
}

interface SelectFieldProps<T extends string | number> {
  label: string
  value: T | null
  options: readonly (readonly [T, string])[]
  onChange: (value: T) => void
  testId?: string
}

/** A choice among a few, saved as soon as it is picked; « — » until one is. */
export const SelectField = <T extends string | number>({ label, value, options, onChange, testId }: SelectFieldProps<T>) => {
  const id = useId()

  return (
    <Field id={id} label={label}>
      <NativeSelect
        id={id}
        value={value === null ? '' : String(value)}
        onChange={(event) => {
          const picked = options.find(([candidate]) => String(candidate) === event.target.value)
          if (picked) onChange(picked[0])
        }}
        input={<InputBase sx={inputSx} inputProps={testId ? { 'data-testid': testId } : undefined} />}
      >
        {value === null && (
          <option value="" disabled>
            —
          </option>
        )}
        {options.map(([candidate, text]) => (
          <option key={String(candidate)} value={String(candidate)}>
            {text}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}

const TIME = /^([01]\d|2[0-3]):[0-5]\d$/

/** The mockup's `.in` box around fields drawn as one, as « 08:00 – 20:00 ». */
export const FieldBox = ({ labelledBy, children }: { labelledBy: string; children: ReactNode }) => (
  <Box role="group" aria-labelledby={labelledBy} sx={(theme) => ({ ...inputSx(theme), display: 'flex', alignItems: 'center', gap: 0.75 })}>
    {children}
  </Box>
)

/**
 * A time, saved when the field is left (or on Enter) and only as a whole
 * `HH:MM`: a browser's time field is valid after each keystroke, so saving on
 * change would send every step (as Réglages › Tâches).
 */
export const TimeInput = ({
  name,
  value,
  onSave,
  id,
  testId,
}: {
  name: string
  value: string
  onSave: (time: string) => Promise<boolean>
  id?: string
  testId?: string
}) => {
  const [draft, setDraft] = useState<string | null>(null)

  const commit = () => {
    if (draft === null) return
    if (!TIME.test(draft) || draft === value) {
      setDraft(null)
      return
    }
    void onSave(draft).finally(() => setDraft(null))
  }

  return (
    <InputBase
      type="time"
      value={draft ?? value}
      onChange={(event) => setDraft(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') commit()
      }}
      id={id}
      inputProps={{ 'aria-label': name, ...(testId && { 'data-testid': testId }) }}
      sx={{
        fontSize: 15,
        fontVariantNumeric: 'tabular-nums',
        flexShrink: 0,
        // As wide as the time it shows, « 08:00 » or « 08:00 AM » by the browser's locale.
        '& input': { p: 0, fieldSizing: 'content' },
        // The picker icon would take the room of the digits (« 08: »): the time is typed, as on the mockup.
        '& input::-webkit-calendar-picker-indicator': { display: 'none' },
      }}
    />
  )
}

/** A time with its label over it, in the field's box (« Début de la fenêtre » of the fast, MAG-770). */
export const TimeField = ({ label, value, onSave, testId }: { label: string; value: string; onSave: (time: string) => Promise<boolean>; testId?: string }) => {
  const id = useId()

  return (
    <Field id={id} label={label}>
      <Box sx={(theme) => ({ ...inputSx(theme), display: 'flex', alignItems: 'center' })}>
        <TimeInput id={id} name={label} value={value} onSave={onSave} testId={testId} />
      </Box>
    </Field>
  )
}
