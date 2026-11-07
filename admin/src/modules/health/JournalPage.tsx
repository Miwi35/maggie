import { useCallback, useMemo, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import Box from '@mui/material/Box'
import { useMercure } from '../../hooks/useMercure'
import { energyRange, parisDay } from './dailyEnergySeries'
import { HEALTH_DAY_TOPICS, patchHealthDay, useHealthDay, useHealthDays } from './healthDaysApi'
import type { HealthDayPatch } from './healthDaysApi'
import { JournalDaysTable } from './JournalDaysTable'
import { JournalEntryCard } from './JournalEntryCard'
import { PeriodPicker } from './PeriodPicker'
import { DEFAULT_PERIOD } from './suiviSeries'
import type { Period } from './suiviSeries'

const SCORE_ERROR = 'Erreur : la note du jour n’a pas été enregistrée.'
const NOTE_ERROR = 'Erreur : la note n’a pas été enregistrée.'

/**
 * Santé › Journal (MAG-750): the days of the period on the left, the chosen
 * day on the right — mood and fatigue, the note, the day's figures. A score
 * is written as soon as it is clicked; a write here or elsewhere (Maggie, the
 * phone) reads the journal again.
 */
export const JournalPage = () => {
  const notify = useNotify()
  const [now] = useState(() => new Date())
  const today = useMemo(() => parisDay(now), [now])
  const [period, setPeriod] = useState<Period>(DEFAULT_PERIOD)
  const [day, setDay] = useState(today)
  const { from, to } = useMemo(() => energyRange(period, now), [period, now])

  const days = useHealthDays(from, to)
  const entry = useHealthDay(day)
  const { refresh: refreshDays } = days
  const { refresh: refreshEntry, replace: replaceEntry } = entry
  // Only the last write's answer is shown: an earlier one arriving late would undo it.
  const writes = useRef(0)

  const refreshAll = useCallback(() => {
    refreshDays()
    refreshEntry()
  }, [refreshDays, refreshEntry])

  // The journal is served by Doctrine: an event may be read back at once.
  useMercure(HEALTH_DAY_TOPICS, refreshAll)

  const patch = useCallback(
    async (change: HealthDayPatch): Promise<boolean> => {
      const write = ++writes.current
      const result = await patchHealthDay(day, change)
      if (result.ok) {
        if (write === writes.current) replaceEntry(result.day)
        refreshDays()

        return true
      }
      notify('note' in change ? NOTE_ERROR : SCORE_ERROR, { type: 'error' })
      refreshAll()

      return false
    },
    [day, notify, refreshAll, refreshDays, replaceEntry],
  )

  return (
    <Box data-testid="journal-board" sx={{ display: 'flex', flexDirection: 'column', gap: 1.75, px: 2, py: 1 }}>
      <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
        <PeriodPicker period={period} onChange={setPeriod} />
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', md: 'minmax(0, 3fr) minmax(0, 2fr)' }, gap: 1.75, alignItems: 'start', minWidth: 0 }}>
        <JournalDaysTable state={days} today={today} selected={day} onSelect={setDay} />
        <JournalEntryCard day={day} today={today} entry={entry} onDay={setDay} onPatch={patch} />
      </Box>
    </Box>
  )
}
