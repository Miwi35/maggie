import { useState } from 'react'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { consumedPercent } from './budgetModes'
import { DailyScoreBanner } from './DailyScoreBanner'
import { MonthlyFlowsChart } from './MonthlyFlowsChart'
import { PeriodPicker } from './PeriodPicker'
import { useFinanceDashboard } from './useFinanceDashboard'
import type { FinanceDashboard } from './useFinanceDashboard'

const DASHBOARD_TOPICS = [
  '/api/transactions/{id}',
  '/api/accounts/{id}',
  '/api/envelopes/{id}',
]

const BalanceCard = ({ dashboard }: { dashboard: FinanceDashboard }) => (
  <Card sx={{ flex: '1 1 320px' }}>
    <CardContent>
      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
        Solde de tous les comptes
      </Typography>
      <Typography variant="h5" sx={{ my: 0.5 }}>
        {formatCents(dashboard.balance.totalCents)}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        dont {formatCents(dashboard.balance.cushionCents)} de matelas —{' '}
        {formatCents(dashboard.balance.availableCents)} disponibles
      </Typography>

      {dashboard.balance.accounts.map((account) => (
        <Stack key={account.id} direction="row" justifyContent="space-between" sx={{ mt: 0.5 }}>
          <Typography variant="caption" color="text.secondary">
            {account.name}
            {account.isCushion && ' · matelas'}
          </Typography>
          <Typography variant="caption">
            {formatCents(account.balanceCents, account.currency)}
          </Typography>
        </Stack>
      ))}
    </CardContent>
  </Card>
)

const CapacityCard = ({ dashboard }: { dashboard: FinanceDashboard }) => {
  const { savingCapacity: capacity } = dashboard

  return (
    <Card sx={{ flex: '1 1 320px' }}>
      <CardContent>
        <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
          Capacité d'épargne nette
        </Typography>
        {capacity.isIncomeKnown ? (
          <>
            <Typography variant="h5" sx={{ my: 0.5 }}>
              {formatCents(capacity.netCapacityCents)}
              <Typography component="span" variant="body2" color="text.secondary">
                {' '}
                / mois
              </Typography>
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {formatCents(capacity.monthlyNetIncomeCents)} de revenu −{' '}
              {formatCents(capacity.loanPaymentsCents)} de mensualités −{' '}
              {formatCents(capacity.estimatedLifestyleCents)} de train de vie
            </Typography>
          </>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Renseignez votre revenu net dans le matelas de sécurité pour l'obtenir.
          </Typography>
        )}
      </CardContent>
    </Card>
  )
}

const TopPostsCard = ({ dashboard }: { dashboard: FinanceDashboard }) => (
  <Card sx={{ flex: '1 1 320px' }}>
    <CardContent>
      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
        Principaux postes du mois
      </Typography>
      {dashboard.topPosts.length === 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Aucune dépense sur ce mois.
        </Typography>
      )}
      {dashboard.topPosts.map((post) => (
        <Stack
          key={post.categoryId ?? 'uncategorized'}
          direction="row"
          justifyContent="space-between"
          sx={{ mt: 1 }}
        >
          <Typography variant="body2">{post.categoryName ?? 'Non catégorisé'}</Typography>
          <Typography variant="body2">
            {formatCents(post.spentCents)}
            <Typography
              component="span"
              variant="caption"
              color={post.changeCents > 0 ? 'warning.main' : 'success.main'}
              sx={{ ml: 1 }}
            >
              {post.changeCents > 0 ? '+' : ''}
              {formatCents(post.changeCents)}
            </Typography>
          </Typography>
        </Stack>
      ))}
    </CardContent>
  </Card>
)

const BudgetGaugesCard = ({ dashboard }: { dashboard: FinanceDashboard }) => (
  <Card sx={{ flex: '1 1 320px' }}>
    <CardContent>
      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
        Enveloppes
      </Typography>
      {dashboard.budgets.length === 0 && (
        <Typography variant="body2" color="text.secondary">
          Aucune enveloppe sur ce mois.
        </Typography>
      )}
      {dashboard.budgets.map((line) => (
        <Box key={line.id} sx={{ mb: 1.5 }}>
          <Stack direction="row" justifyContent="space-between">
            <Typography variant="caption">{line.categoryName}</Typography>
            <Typography variant="caption" color={line.isOverspent ? 'error.main' : 'text.secondary'}>
              {formatCents(line.consumedCents, line.currency)} /{' '}
              {formatCents(line.amountCents, line.currency)}
            </Typography>
          </Stack>
          <LinearProgress
            variant="buffer"
            value={consumedPercent(line.consumedCents, line.amountCents)}
            valueBuffer={consumedPercent(line.consumedCents + line.plannedCents, line.amountCents)}
            color={line.isOverspent ? 'error' : line.isOvercommitted ? 'warning' : 'primary'}
            sx={{ height: 6, borderRadius: 3 }}
          />
        </Box>
      ))}
    </CardContent>
  </Card>
)

export const FinanceDashboardPage = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { dashboard, loading, refresh } = useFinanceDashboard(year, month)

  useMercure(DASHBOARD_TOPICS, () => { refresh() })

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
        <PeriodPicker
          year={year}
          month={month}
          onYearChange={setYear}
          onMonthChange={setMonth}
        />
      </Stack>

      {loading && !dashboard && <LinearProgress />}

      {dashboard && (
        <>
          <DailyScoreBanner year={year} month={month} />

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, mb: 2 }}>
            <BalanceCard dashboard={dashboard} />
            <CapacityCard dashboard={dashboard} />
          </Stack>

          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
                Recettes et dépenses sur douze mois
              </Typography>
              <MonthlyFlowsChart flows={dashboard.monthlyFlows} />
            </CardContent>
          </Card>

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2 }}>
            <TopPostsCard dashboard={dashboard} />
            <BudgetGaugesCard dashboard={dashboard} />
          </Stack>
        </>
      )}
    </>
  )
}
