import { useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { buildRruleString, rruleToFrenchText, RRule } from './recurrenceUtils'

interface RecurrencePickerProps {
  value: string | null
  onChange: (rrule: string | null) => void
  eventStartDate: Date | null
}

type EndType = 'never' | 'count' | 'until'

const FREQ_OPTIONS = [
  { value: -1, label: 'Ne se répète pas' },
  { value: RRule.DAILY, label: 'Tous les jours' },
  { value: RRule.WEEKLY, label: 'Toutes les semaines' },
  { value: RRule.MONTHLY, label: 'Tous les mois' },
  { value: RRule.YEARLY, label: 'Tous les ans' },
]

const DAY_BUTTONS = [
  { value: 0, label: 'L' },
  { value: 1, label: 'M' },
  { value: 2, label: 'M' },
  { value: 3, label: 'J' },
  { value: 4, label: 'V' },
  { value: 5, label: 'S' },
  { value: 6, label: 'D' },
]

// JS Date.getDay() → rrule weekday: Sun=0→6, Mon=1→0, …, Sat=6→5
const jsToRruleDay = (jsDay: number): number => (jsDay === 0 ? 6 : jsDay - 1)

const toLocalDate = (d: Date): string => {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export const RecurrencePicker = ({ value, onChange, eventStartDate }: RecurrencePickerProps) => {
  const [freq, setFreq] = useState<number>(-1)
  const [interval, setInterval_] = useState(1)
  const [byweekday, setByweekday] = useState<number[]>([])
  const [endType, setEndType] = useState<EndType>('never')
  const [count, setCount] = useState(10)
  const [until, setUntil] = useState('')

  // Reset state when value is cleared externally (dialog reopen)
  useEffect(() => {
    if (value === null) {
      setFreq(-1)
      setInterval_(1)
      setByweekday(eventStartDate ? [jsToRruleDay(eventStartDate.getDay())] : [])
      setEndType('never')
      setCount(10)
      setUntil('')
    }
  }, [value, eventStartDate])

  // Default weekday when start date changes and no days selected
  useEffect(() => {
    if (eventStartDate && byweekday.length === 0) {
      setByweekday([jsToRruleDay(eventStartDate.getDay())])
    }
  }, [eventStartDate]) // eslint-disable-line react-hooks/exhaustive-deps

  // Build and emit RRULE
  useEffect(() => {
    if (freq === -1) {
      if (value !== null) onChange(null)
      return
    }

    const opts: Parameters<typeof buildRruleString>[0] = {
      freq,
      interval,
    }

    if (freq === RRule.WEEKLY && byweekday.length > 0) {
      opts.byweekday = [...byweekday].sort()
    }

    if (endType === 'count' && count > 0) {
      opts.count = count
    } else if (endType === 'until' && until) {
      opts.until = new Date(until)
    }

    const rrule = buildRruleString(opts)
    if (rrule !== value) onChange(rrule)
  }, [freq, interval, byweekday, endType, count, until]) // eslint-disable-line react-hooks/exhaustive-deps

  const freqLabel = useMemo(() => {
    if (freq === RRule.DAILY) return 'jours'
    if (freq === RRule.WEEKLY) return 'semaines'
    if (freq === RRule.MONTHLY) return 'mois'
    if (freq === RRule.YEARLY) return 'ans'
    return ''
  }, [freq])

  const summary = useMemo(() => {
    if (freq === -1 || !value) return null
    return rruleToFrenchText(value)
  }, [freq, value])

  const handleDayToggle = (_: React.MouseEvent, newDays: number[]) => {
    if (newDays.length > 0) setByweekday(newDays)
  }

  return (
    <Box>
      {/* Frequency */}
      <TextField
        label="Récurrence"
        value={freq}
        onChange={(e) => setFreq(Number(e.target.value))}
        select
        fullWidth
        size="small"
      >
        {FREQ_OPTIONS.map((opt) => (
          <MenuItem key={opt.value} value={opt.value}>
            {opt.label}
          </MenuItem>
        ))}
      </TextField>

      {freq !== -1 && (
        <Box sx={{ mt: 2, display: 'flex', flexDirection: 'column', gap: 2 }}>
          {/* Interval */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
              {freq === RRule.WEEKLY ? 'Toutes les' : 'Tous les'}
            </Typography>
            <TextField
              type="number"
              value={interval}
              onChange={(e) => setInterval_(Math.max(1, Number(e.target.value)))}
              size="small"
              slotProps={{ htmlInput: { min: 1, max: 99, style: { width: 50, textAlign: 'center' } } }}
            />
            <Typography variant="body2">{freqLabel}</Typography>
          </Box>

          {/* Weekly day toggles */}
          {freq === RRule.WEEKLY && (
            <ToggleButtonGroup
              value={byweekday}
              onChange={handleDayToggle}
              size="small"
              sx={{ '& .MuiToggleButton-root': { width: 36, height: 36, fontSize: '0.8rem' } }}
            >
              {DAY_BUTTONS.map((day) => (
                <ToggleButton key={day.value} value={day.value}>
                  {day.label}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
          )}

          {/* End condition */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <TextField
              label="Se termine"
              value={endType}
              onChange={(e) => setEndType(e.target.value as EndType)}
              select
              size="small"
              sx={{ minWidth: 140 }}
            >
              <MenuItem value="never">Jamais</MenuItem>
              <MenuItem value="count">Après</MenuItem>
              <MenuItem value="until">Le</MenuItem>
            </TextField>

            {endType === 'count' && (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <TextField
                  type="number"
                  value={count}
                  onChange={(e) => setCount(Math.max(1, Number(e.target.value)))}
                  size="small"
                  slotProps={{ htmlInput: { min: 1, max: 999, style: { width: 60, textAlign: 'center' } } }}
                />
                <Typography variant="body2">occurrences</Typography>
              </Box>
            )}

            {endType === 'until' && (
              <TextField
                type="date"
                value={until}
                onChange={(e) => setUntil(e.target.value)}
                size="small"
                slotProps={{
                  inputLabel: { shrink: true },
                  htmlInput: { min: eventStartDate ? toLocalDate(eventStartDate) : undefined },
                }}
              />
            )}
          </Box>

          {/* Summary */}
          {summary && (
            <Typography variant="caption" color="text.secondary">
              {summary}
            </Typography>
          )}
        </Box>
      )}
    </Box>
  )
}
