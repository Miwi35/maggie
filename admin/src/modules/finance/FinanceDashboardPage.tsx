import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import { Title } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { CurrentStateTab } from './CurrentStateTab'
import { UpcomingTab } from './UpcomingTab'
import { useFinanceDashboard } from './useFinanceDashboard'
import { FINANCE_HOME_TOPICS } from './useUpcomingProjection'

export type DashboardTab = 'current' | 'upcoming'

/**
 * The finance home, in two sub-views. « État courant »: the day of the month,
 * the review when it is due, the rings, the next project, the month's spending
 * and the three cards Comptes, Budget, Planification, each opening its menu;
 * one read (`/api/finance/dashboard`) feeds all of it, and one Mercure refresh
 * reloads all of it. « À venir »: the week and the month after this one.
 */
export const FinanceDashboardPage = ({ initialTab = 'current' }: { initialTab?: DashboardTab }) => {
  const now = new Date()
  const [tab, setTab] = useState<DashboardTab>(initialTab)
  const { dashboard, loading, error, refresh } = useFinanceDashboard(now.getFullYear(), now.getMonth() + 1)

  useMercure(FINANCE_HOME_TOPICS, () => {
    refresh()
  })

  return (
    <>
      <Title title="Finance" />

      <Tabs
        value={tab}
        onChange={(_, value: DashboardTab) => setTab(value)}
        sx={{ mb: 2 }}
        aria-label="Vue du tableau de bord"
      >
        <Tab value="current" label="État courant" data-testid="dashboard-tab-current" />
        <Tab value="upcoming" label="À venir" data-testid="dashboard-tab-upcoming" />
      </Tabs>

      {tab === 'upcoming' && <UpcomingTab />}

      {tab === 'current' && (
        <>
          {error && (
            <Alert
              severity="error"
              sx={{ mb: 2 }}
              action={
                <Button color="inherit" size="small" onClick={() => { refresh() }}>
                  Réessayer
                </Button>
              }
            >
              Le tableau de bord n'a pas pu être chargé.
            </Alert>
          )}

          {loading && !dashboard && <LinearProgress />}

          {dashboard && <CurrentStateTab dashboard={dashboard} now={now} />}
        </>
      )}
    </>
  )
}
