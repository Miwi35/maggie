import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import { useMercure } from '../../hooks/useMercure'
import { AccountsTile } from './AccountsTile'
import { BudgetTile } from './BudgetTile'
import { FinanceScoresCard } from './FinanceScoresSection'
import { NextProjectCard } from './NextProjectCard'
import { PeriodPicker } from './PeriodPicker'
import { PlanningTile } from './PlanningTile'
import { ToQualifyPill } from './ToQualifyPill'
import { UpcomingTab } from './UpcomingTab'
import { useFinanceDashboard } from './useFinanceDashboard'
import { FINANCE_HOME_TOPICS } from './useUpcomingProjection'

/**
 * The finance home, in two tabs. « État courant »: the two rings, the month's
 * curve against its rhythm, the next project, then three tiles — Comptes,
 * Budget, Planification — each opening its menu; one read
 * (`/api/finance/dashboard`) feeds all of it, and one Mercure refresh reloads
 * all of it. « À venir »: the week and the month after this one.
 */
export const FinanceDashboardPage = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const [tab, setTab] = useState<'current' | 'upcoming'>('current')
  const { dashboard, loading, error, refresh } = useFinanceDashboard(year, month)

  useMercure(FINANCE_HOME_TOPICS, () => {
    refresh()
  })

  return (
    <>
      <Title title="Finance" />

      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        alignItems={{ xs: 'stretch', sm: 'center' }}
        sx={{ mb: 2 }}
      >
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Vue d'ensemble
        </Typography>
        {tab === 'current' && (
          <PeriodPicker
            year={year}
            month={month}
            onYearChange={setYear}
            onMonthChange={setMonth}
          />
        )}
      </Stack>

      <Tabs
        value={tab}
        onChange={(_, value: 'current' | 'upcoming') => setTab(value)}
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

          <ToQualifyPill />

          {loading && !dashboard && <LinearProgress />}

          {dashboard && (
            <>
              <FinanceScoresCard scores={dashboard.scores} />

              <NextProjectCard />

              <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
                <AccountsTile tile={dashboard.tiles.accounts} />
                <BudgetTile tile={dashboard.tiles.budget} />
                <PlanningTile tile={dashboard.tiles.planning} />
              </Stack>
            </>
          )}
        </>
      )}
    </>
  )
}
