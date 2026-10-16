import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import { UpcomingEnvelopes } from './UpcomingEnvelopes'
import { UpcomingRemaining } from './UpcomingRemaining'
import { UpcomingWindow } from './UpcomingWindow'
import { monthLabel, windowRange } from './upcomingLabels'
import { useUpcomingProjection } from './useUpcomingProjection'

/**
 * Dashboard › À venir: the week and the month after the current ones, read
 * from what is entered, then the next month's envelopes as adjusted and what
 * that leaves toward the projects. One read feeds all of it.
 */
export const UpcomingTab = () => {
  const { projection, loading, error, refresh } = useUpcomingProjection()

  return (
    <Stack spacing={2} data-testid="upcoming-tab">
      {error && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => { void refresh() }}>
              Réessayer
            </Button>
          }
        >
          Les prévisions n'ont pas pu être chargées.
        </Alert>
      )}

      {loading && !projection && <LinearProgress />}

      {projection && (
        <>
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
            <UpcomingWindow
              testId="upcoming-week"
              title="La semaine suivante"
              subtitle={windowRange(projection.week)}
              window={projection.week}
            />
            <UpcomingWindow
              testId="upcoming-month"
              title="Le mois suivant"
              subtitle={monthLabel(projection.month.month)}
              window={projection.month}
            />
          </Stack>

          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
            <UpcomingEnvelopes month={projection.month} />
            <UpcomingRemaining month={projection.month} />
          </Stack>
        </>
      )}
    </Stack>
  )
}
