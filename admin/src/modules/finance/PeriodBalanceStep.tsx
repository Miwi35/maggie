import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { EnvelopeBars } from './EnvelopeBars'
import { PeriodCurve } from './PeriodCurve'
import { PERIOD_WORDS, periodLabel } from './reviewActivity'
import { SatisfactionRing } from './SatisfactionRing'
import { usePeriodBalance } from './usePeriodBalance'
import type { PeriodProject } from './usePeriodBalance'

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

const ProjectList = ({ title, projects, testId }: { title: string; projects: PeriodProject[]; testId: string }) => (
  <Box data-testid={testId}>
    <Typography variant="body2" sx={{ fontWeight: 600 }}>
      {title} · {projects.length}
    </Typography>
    {projects.map((project) => (
      <Typography key={project.id} variant="caption" color="text.secondary" component="div" data-testid="period-project">
        {project.label} — {formatCents(project.amountCents, project.currency)} · {project.month}
      </Typography>
    ))}
  </Box>
)

/**
 * The review that closes a quarter, a semester or a year, step « Bilan de la période » (MAG-462):
 * the months against each other, each envelope over the whole period, what was put
 * aside, how much of what was judged was worth it, and the planned expenses done or postponed.
 */
export const PeriodBalanceStep = ({ month }: { month: string }) => {
  const { balance, error, retry } = usePeriodBalance(month)

  if (error) {
    return (
      <Alert severity="error" action={<Button color="inherit" size="small" onClick={retry}>Réessayer</Button>}>
        Le bilan de la période n'a pas pu être chargé.
      </Alert>
    )
  }
  if (!balance) return <LinearProgress aria-label="Chargement" />

  const { period, totals, savings, projects } = balance
  const share = savings.plannedCents ? Math.min(100, (savings.savedCents / savings.plannedCents) * 100) : 0

  return (
    <Stack spacing={2} data-testid="review-period-balance" data-period={period.key}>
      <Typography variant="body2" color="text.secondary">
        Bilan {PERIOD_WORDS[period.level].of} · {periodLabel(period)}
      </Typography>

      <Card data-testid="period-totals">
        <CardContent>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems="center">
            <Stack spacing={0.5} sx={{ flex: '0 0 220px' }}>
              <Typography variant="body2">Budgété : {formatCents(totals.budgetCents)}</Typography>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                Dépensé : {formatCents(totals.spentCents)}
              </Typography>
              <Typography variant="body2">Épargné : {formatCents(totals.savedCents)}</Typography>
            </Stack>
            <Box sx={{ flex: 1, width: '100%' }}>
              <PeriodCurve months={balance.months} />
            </Box>
          </Stack>
        </CardContent>
      </Card>

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
        <Section title="Enveloppes" testId="period-envelopes">
          {balance.envelopes.length === 0 ? (
            <Typography variant="body2" color="text.secondary" data-testid="period-envelopes-empty">
              Aucune enveloppe sur la période.
            </Typography>
          ) : (
            <EnvelopeBars envelopes={balance.envelopes.map(({ categoryId, ...rest }) => ({ envelopeId: categoryId, ...rest }))} />
          )}
        </Section>
        <Section title="Satisfaction" testId="period-satisfaction">
          <SatisfactionRing satisfaction={{ ...balance.satisfaction, unratedCount: 0 }} />
        </Section>
      </Stack>

      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
        <Section title="Épargne" testId="period-savings">
          <LinearProgress
            variant="determinate"
            value={share}
            color={savings.plannedCents !== null && savings.savedCents >= savings.plannedCents ? 'success' : 'primary'}
            aria-label="Épargné face à la capacité prévue"
            sx={{ height: 10, borderRadius: 5, mb: 0.5 }}
          />
          <Typography variant="body2" data-testid="period-savings-text">
            {savings.plannedCents === null
              ? `${formatCents(savings.savedCents)} épargnés · capacité inconnue`
              : `${formatCents(savings.savedCents)} épargnés sur ${formatCents(savings.plannedCents)} prévus`}
          </Typography>
        </Section>
        <Section title="Projets" testId="period-projects">
          {projects.done.length + projects.postponed.length === 0 ? (
            <Typography variant="body2" color="text.secondary" data-testid="period-projects-empty">
              Aucune dépense prévue sur la période.
            </Typography>
          ) : (
            <Stack spacing={1.5}>
              <ProjectList title="Réalisés" projects={projects.done} testId="period-projects-done" />
              <ProjectList title="Décalés" projects={projects.postponed} testId="period-projects-postponed" />
            </Stack>
          )}
        </Section>
      </Stack>
    </Stack>
  )
}
