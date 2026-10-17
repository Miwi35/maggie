import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { EnvelopeBars } from './EnvelopeBars'
import { PeriodPicker } from './PeriodPicker'
import { RhythmCurve } from './RhythmCurve'
import { SatisfactionRing } from './SatisfactionRing'
import { ScoreRing } from './ScoreRings'
import { useMonthBalance } from './useMonthBalance'
import type { BalanceSavings } from './useMonthBalance'
import type { TileCushion } from './useFinanceDashboard'

const BALANCE_TOPICS = ['/api/transactions/{id}', '/api/envelopes/{id}']

const Section = ({ title, testId, children }: { title: string; testId: string; children: React.ReactNode }) => (
  <Card data-testid={testId} sx={{ flex: '1 1 320px' }}>
    <CardContent>
      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
        {title}
      </Typography>
      {children}
    </CardContent>
  </Card>
)

const Savings = ({ savings }: { savings: BalanceSavings }) => {
  const { savedCents, plannedCents } = savings
  const share = plannedCents ? Math.min(100, (savedCents / plannedCents) * 100) : 0

  return (
    <Box>
      <LinearProgress
        variant="determinate"
        value={share}
        color={plannedCents !== null && savedCents >= plannedCents ? 'success' : 'primary'}
        aria-label="Épargné face à la capacité prévue"
        sx={{ height: 10, borderRadius: 5, mb: 0.5 }}
      />
      <Typography variant="body2" data-testid="savings-text">
        {plannedCents === null
          ? `${formatCents(savedCents)} épargnés · capacité inconnue`
          : `${formatCents(savedCents)} épargnés sur ${formatCents(plannedCents)} prévus`}
      </Typography>
    </Box>
  )
}

const Cushion = ({ cushion }: { cushion: TileCushion }) => {
  if (!cushion.isConfigured) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="cushion-text">
        Matelas non configuré.
      </Typography>
    )
  }

  return (
    <Box>
      <LinearProgress
        variant="determinate"
        value={Math.min(100, cushion.coveragePercent)}
        color={cushion.state === 'complete' ? 'success' : 'primary'}
        aria-label="Matelas de sécurité"
        sx={{ height: 10, borderRadius: 5, mb: 0.5 }}
      />
      <Typography variant="body2" data-testid="cushion-text">
        {formatCents(cushion.currentCents)} sur {formatCents(cushion.targetCents)} · {cushion.coveragePercent} %
      </Typography>
    </Box>
  )
}

/**
 * Dashboard › État courant (a tab of the finance home): the balance of a month in charts — the month's
 * ring and its curve against the rhythm, one bar per envelope, the
 * satisfaction ring, what was put aside, the cushion. Each figure has its
 * visual. The lines nobody qualified are reported apart: they belong to no
 * envelope yet.
 */
export const MonthBalanceTab = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { balance, loading, error, refresh } = useMonthBalance(year, month)

  useMercure(BALANCE_TOPICS, () => {
    refresh()
  })

  return (
    <>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        alignItems={{ xs: 'stretch', sm: 'center' }}
        sx={{ mb: 2 }}
      >
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
          Bilan du mois
        </Typography>
        <PeriodPicker year={year} month={month} onYearChange={setYear} onMonthChange={setMonth} />
      </Stack>

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
          Le bilan du mois n'a pas pu être chargé.
        </Alert>
      )}

      {loading && !balance && <LinearProgress />}

      {balance && (
        <Stack spacing={2} data-testid="month-balance" data-month={balance.month}>
          {balance.toQualify.count > 0 && (
            <Box sx={{ display: 'flex' }}>
              <Chip
                component={Link}
                to="/finance/to-sort"
                clickable
                color="warning"
                variant="outlined"
                label={`À qualifier · ${balance.toQualify.count} · ${formatCents(balance.toQualify.cents)}`}
                data-testid="balance-to-qualify"
              />
            </Box>
          )}

          <Card data-testid="balance-spending">
            <CardContent>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="center">
                <Box sx={{ flex: '0 0 220px' }}>
                  <ScoreRing period="month" title="Dépensé" score={balance.spending} />
                </Box>
                <Box sx={{ flex: 1, width: '100%' }}>
                  <RhythmCurve rhythm={balance.spending.rhythm} />
                </Box>
              </Stack>
            </CardContent>
          </Card>

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
            <Section title="Enveloppes" testId="balance-envelopes">
              <EnvelopeBars envelopes={balance.envelopes} />
            </Section>
            <Section title="Satisfaction" testId="balance-satisfaction">
              <SatisfactionRing satisfaction={balance.satisfaction} />
            </Section>
          </Stack>

          <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
            <Section title="Épargne" testId="balance-savings">
              <Savings savings={balance.savings} />
            </Section>
            <Section title="Matelas" testId="balance-cushion">
              <Cushion cushion={balance.cushion} />
            </Section>
          </Stack>
        </Stack>
      )}
    </>
  )
}
