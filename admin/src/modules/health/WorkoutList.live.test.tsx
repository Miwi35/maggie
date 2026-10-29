import { ThemeProvider } from '@mui/material/styles'
import { vi } from 'vitest'
import { veilleuseLightTheme } from '../../theme'
import { liveListSuite } from '../../test/mercure'
import { WorkoutList } from './WorkoutList'
import { formatShortDate } from './workoutFormat'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

/** Days ago at noon, never a Monday-agnostic constant: the texts are the short dates, which must differ. */
const daysAgo = (days: number) => {
  const at = new Date()
  at.setHours(12, 0, 0, 0)
  at.setDate(at.getDate() - days)

  return at
}

const listedAt = daysAgo(3)
const createdAt = daysAgo(5)
const updatedAt = daysAgo(8)

liveListSuite({
  name: 'WorkoutList',
  screen: () => (
    <ThemeProvider theme={veilleuseLightTheme}>
      <WorkoutList withChart={false} />
    </ThemeProvider>
  ),
  resource: 'workouts',
  topic: '/api/workouts/{id}',
  listed: {
    id: '/api/workouts/01RUN',
    type: 'run',
    title: 'Course',
    startedAt: listedAt.toISOString(),
    durationMinutes: 32,
    distanceMeters: 5400,
    kcal: 386,
    avgHeartRate: 152,
    feeling: 'good',
    notes: null,
    source: 'watch',
    externalId: null,
  },
  listedText: formatShortDate(listedAt),
  created: {
    payload: { type: 'bike', title: 'Vélo', startedAt: createdAt.toISOString(), durationMinutes: 65, distanceMeters: 22000, source: 'manual' },
    text: formatShortDate(createdAt),
  },
  updated: { payload: { startedAt: updatedAt.toISOString() }, text: formatShortDate(updatedAt) },
})
