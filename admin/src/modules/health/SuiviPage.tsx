import { useMemo, useState } from 'react'
import { useListContext, useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { BODY_MEASUREMENT_TOPICS, BODY_MEASUREMENTS } from './bodyMeasurementsApi'
import type { BodyMeasurement } from './bodyMeasurementsApi'
import { useDailyEnergy } from './dailyEnergyApi'
import { energyRange } from './dailyEnergySeries'
import { useHealthDays } from './healthDaysApi'
import { PeriodPicker } from './PeriodPicker'
import { SuiviBalanceBanner } from './SuiviBalanceBanner'
import { SuiviEnergyCard } from './SuiviEnergyCard'
import { SuiviMeasurementForm } from './SuiviMeasurementForm'
import { SuiviMoodCard } from './SuiviMoodCard'
import { SuiviStepsCard } from './SuiviStepsCard'
import { SuiviWeighingsCard } from './SuiviWeighingsCard'
import { SuiviWeightCard } from './SuiviWeightCard'
import { columns, DEFAULT_PERIOD, readStart, weighingsOf } from './suiviSeries'
import type { Period } from './suiviSeries'

/** Two weighings a day over a year and its trend days, and room to spare: the plan's cap per page. */
const MAX_READ = 1000

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
  // null: the whole period for « Pas », today for « Énergie » and « Moral · fatigue ».
  const [energyDay, setEnergyDay] = useState<number | null>(null)
  const [form, setForm] = useState<{ measurement?: BodyMeasurement } | null>(null)

  const measurements = useMemo(() => data ?? [], [data])
  const cols = useMemo(() => columns(measurements, period, now), [measurements, period, now])
  const weighings = useMemo(() => weighingsOf([...measurements], period, now), [measurements, period, now])
  const { from, to } = useMemo(() => energyRange(period, now), [period, now])
  const energy = useDailyEnergy(from, to)
  // Read once, as the energy: the page's one stream is the weighings' (MAG-473).
  const journal = useHealthDays(from, to)
  const current = selected === null || selected >= cols.length ? cols.length - 1 : selected

  return (
    <Box data-testid="suivi-board" sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, py: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1.5, flexWrap: 'wrap' }}>
        <SuiviBalanceBanner />
        <Box sx={{ flex: 1 }} />
        <PeriodPicker
          period={period}
          onChange={(next) => {
            setSelected(null)
            setEnergyDay(null)
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
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.75, minWidth: 0 }}>
        <SuiviStepsCard state={energy} today={to} selected={energyDay} onSelect={setEnergyDay} />
        <SuiviEnergyCard state={energy} today={to} selected={energyDay} onSelect={setEnergyDay} />
        <SuiviMoodCard state={journal} from={from} to={to} selected={energyDay} onSelect={setEnergyDay} />
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
