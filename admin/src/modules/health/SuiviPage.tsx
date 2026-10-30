import { useMemo, useState } from 'react'
import { useListContext, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { BODY_MEASUREMENT_TOPICS, BODY_MEASUREMENTS } from './bodyMeasurementsApi'
import type { BodyMeasurement } from './bodyMeasurementsApi'
import { SuiviMeasurementForm } from './SuiviMeasurementForm'
import { SuiviWeighingsCard } from './SuiviWeighingsCard'
import { SuiviWeightCard } from './SuiviWeightCard'
import { columns, DEFAULT_PERIOD, PERIOD_LABELS, PERIODS, readStart, weighingsOf } from './suiviSeries'
import type { Period } from './suiviSeries'

/** Two weighings a day over a year and its trend days, and room to spare: the plan's cap per page. */
const MAX_READ = 1000

interface PeriodPickerProps {
  period: Period
  onChange: (period: Period) => void
}

/** « 7 j · 30 j · 90 j · 1 an », one of them checked. */
const PeriodPicker = ({ period, onChange }: PeriodPickerProps) => (
  <Box role="radiogroup" aria-label="Période" sx={{ display: 'flex', gap: '2px', p: '3px', borderRadius: 2.5, bgcolor: 'background.default' }}>
    {PERIODS.map((candidate) => {
      const checked = candidate === period

      return (
        <Box
          key={candidate}
          component="button"
          type="button"
          role="radio"
          aria-checked={checked}
          onClick={() => onChange(candidate)}
          sx={(theme) => ({
            appearance: 'none',
            border: 0,
            m: 0,
            font: 'inherit',
            cursor: 'pointer',
            height: 30,
            px: 1.75,
            borderRadius: 2,
            fontSize: 13,
            fontVariantNumeric: 'tabular-nums',
            bgcolor: checked ? theme.palette.veilleuse.raised : 'transparent',
            color: checked ? theme.palette.text.primary : theme.palette.veilleuse.caption,
          })}
        >
          {PERIOD_LABELS[candidate]}
        </Box>
      )
    })}
  </Box>
)

interface BoardProps {
  period: Period
  onPeriod: (period: Period) => void
  now: Date
}

const SuiviBoard = ({ period, onPeriod, now }: BoardProps) => {
  const { data, isPending } = useListContext<BodyMeasurement>()
  // null: the last column, today's.
  const [selected, setSelected] = useState<number | null>(null)
  // The weighing being edited is kept as it was opened: an event must not rewrite what is being typed.
  const [form, setForm] = useState<{ measurement?: BodyMeasurement } | null>(null)

  const measurements = useMemo(() => data ?? [], [data])
  const cols = useMemo(() => columns(measurements, period, now), [measurements, period, now])
  const weighings = useMemo(() => weighingsOf([...measurements], period, now), [measurements, period, now])
  const current = selected === null || selected >= cols.length ? cols.length - 1 : selected

  return (
    <Box data-testid="suivi-board" sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, py: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center' }}>
        <PeriodPicker
          period={period}
          onChange={(next) => {
            setSelected(null)
            onPeriod(next)
          }}
        />
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(0, 2fr) minmax(0, 1fr)' }, gridTemplateRows: { md: '360px' }, gap: 1.75, minWidth: 0 }}>
        <SuiviWeightCard cols={cols} withDots={period <= 30} selected={current} onSelect={setSelected} />
        <SuiviWeighingsCard
          weighings={weighings}
          loaded={!isPending}
          onAdd={() => setForm({})}
          onEdit={(measurement) => setForm({ measurement })}
        />
      </Box>
      <SuiviMeasurementForm open={form !== null} measurement={form?.measurement} onClose={() => setForm(null)} />
    </Box>
  )
}

/**
 * Santé › Suivi (MAG-706): the period, the Poids card and the « Pesées » of
 * the period, on the mockup's grid. The heading is the layout's. The read
 * covers the period and the six days before it, which the trend of its first
 * day averages.
 */
export const SuiviPage = () => {
  const notify = useNotify()
  const [now] = useState(() => new Date())
  const [period, setPeriod] = useState<Period>(DEFAULT_PERIOD)
  const after = useMemo(() => readStart(period, now).toISOString(), [period, now])

  const live = useMemo<LiveConfig>(
    () => ({
      topics: BODY_MEASUREMENT_TOPICS,
      required: ['measuredAt', 'source'],
      accept: (row) =>
        row.id.startsWith('/api/body-measurements/') && new Date(String(row.measuredAt)).getTime() >= new Date(after).getTime(),
    }),
    [after],
  )
  const queryOptions = useMemo(
    () => ({ onError: () => notify('Erreur : les pesées n’ont pas pu être chargées.', { type: 'error' }) }),
    [notify],
  )

  return (
    <LiveList
      resource={BODY_MEASUREMENTS}
      live={live}
      filter={{ 'measuredAt[after]': after }}
      sort={{ field: 'measuredAt', order: 'DESC' }}
      perPage={MAX_READ}
      disableSyncWithLocation
      actions={false}
      pagination={false}
      empty={false}
      queryOptions={queryOptions}
    >
      <SuiviBoard period={period} onPeriod={setPeriod} now={now} />
    </LiveList>
  )
}
