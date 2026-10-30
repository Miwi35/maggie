import { ThemeProvider } from '@mui/material/styles'
import { vi } from 'vitest'
import { veilleuseLightTheme } from '../../theme'
import { liveListSuite } from '../../test/mercure'
import { SuiviPage } from './SuiviPage'
import { formatDay } from './suiviSeries'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

/** Days ago at 07:00: inside the default 30 days, each a different date in the « Pesées » table. */
const daysAgo = (days: number) => {
  const at = new Date()
  at.setHours(7, 0, 0, 0)
  at.setDate(at.getDate() - days)

  return at
}

const listedAt = daysAgo(3)
const createdAt = daysAgo(5)
const updatedAt = daysAgo(8)

// A weighing Maggie records (manage_body_measurements) shows without a reload.
liveListSuite({
  name: 'SuiviPage',
  screen: () => (
    <ThemeProvider theme={veilleuseLightTheme}>
      <SuiviPage />
    </ThemeProvider>
  ),
  resource: 'body-measurements',
  topic: '/api/body-measurements/{id}',
  listed: {
    id: '/api/body-measurements/01LISTED',
    measuredAt: listedAt.toISOString(),
    weightKg: 72.4,
    bodyFatPercent: 21.3,
    waistCm: null,
    source: 'manual',
    externalId: null,
    excluded: false,
  },
  listedText: formatDay(listedAt),
  created: {
    payload: { measuredAt: createdAt.toISOString(), weightKg: 71.8, bodyFatPercent: null, waistCm: null, source: 'manual', externalId: null, excluded: false },
    text: formatDay(createdAt),
  },
  updated: { payload: { measuredAt: updatedAt.toISOString() }, text: formatDay(updatedAt) },
})
