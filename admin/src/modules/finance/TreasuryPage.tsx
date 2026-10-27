import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import { Link } from 'react-router-dom'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { CushionSection } from './CushionPage'
import { PeerDebtsSection } from './PeerDebtsPage'
import { SavingsAdvancesSection } from './SavingsAdvancesPage'
import { movementText, shortDay, TreasuryChart, TreasuryFlowsChart } from './TreasuryChart'
import { TreasurySettingsDialog } from './TreasurySettingsDialog'
import { useCushionStatus } from './useCushionStatus'
import { TREASURY_HORIZONS, TREASURY_TOPICS, useTreasuryOutlook } from './useTreasuryOutlook'
import type { TreasuryAdvice, TreasuryOutlook } from './useTreasuryOutlook'

const dayCount = (days: number) => `${days} jour${days > 1 ? 's' : ''}`

/** « Salaire en retard de 30 jours » — the headline, only when the salary is late. */
export const delayHeadline = (delayDays: number) => `Salaire en retard de ${dayCount(delayDays)}`

const HorizonPicker = ({ months, onChange }: { months: number | null; onChange: (months: number | null) => void }) => (
  <ToggleButtonGroup
    exclusive
    size="small"
    value={months === null ? 'default' : String(months)}
    onChange={(_, value: string | null) => {
      if (value !== null) onChange(value === 'default' ? null : Number(value))
    }}
    aria-label="Horizon de la projection"
    sx={{ flexWrap: 'wrap' }}
  >
    {TREASURY_HORIZONS.map((horizon) => (
      <ToggleButton key={horizon.label} value={horizon.months === null ? 'default' : String(horizon.months)}>
        {horizon.label}
      </ToggleButton>
    ))}
  </ToggleButtonGroup>
)

/** The salary, when it is expected or late, and what the current accounts hold. */
const SalaryLine = ({ outlook }: { outlook: TreasuryOutlook }) => {
  const { salary } = outlook

  return (
    <Box sx={{ mb: 1 }}>
      {salary.isConfigured && (
        <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: 'wrap' }}>
          <Typography variant="body2" color="text.secondary">
            {salary.expectedOn
              ? `${salary.isLate ? 'Salaire attendu depuis le' : 'Salaire attendu le'} ${shortDay(salary.expectedOn)}`
              : 'Date attendue inconnue'}
            {salary.referenceCents !== null && ` — repère de ${formatCents(salary.referenceCents)}`}
          </Typography>
          {salary.isReceived && <Chip size="small" color="success" label="Salaire reçu" />}
        </Stack>
      )}
      {salary.isLate && salary.shortfallCents > 0 && (
        <Typography variant="body2" color="text.secondary">
          Il manque {formatCents(salary.shortfallCents)} par rapport au repère.
        </Typography>
      )}
      <Typography variant="body2">
        Solde des comptes courants : <strong>{formatCents(outlook.balanceCents)}</strong>
      </Typography>
    </Box>
  )
}

/** Opens the new-transaction form already filled in; nothing is saved until the owner does. */
export const advanceFormLink = (advice: TreasuryAdvice, today: string) => ({
  pathname: '/transactions/create',
  search: `?source=${encodeURIComponent(
    JSON.stringify({
      account: `/api/accounts/${advice.sourceAccountId}`,
      label: 'Avance vers le compte courant',
      amountCents: -advice.sourceAmountCents,
      bookedAt: today,
      status: 'spent',
      currency: 'EUR',
    }),
  )}`,
})

const Advice = ({ outlook, advice }: { outlook: TreasuryOutlook; advice: TreasuryAdvice }) => (
  <Box data-testid="advice" sx={{ mt: 3 }}>
    <Typography variant="subtitle2" component="h3" sx={{ mb: 0.5 }}>
      Avance conseillée
    </Typography>
    <Typography variant="body1">
      Avancer <strong>{formatCents(advice.amountCents)}</strong>
      {advice.sourceAccountName ? (
        <>
          {' '}
          depuis <strong>{advice.sourceAccountName}</strong>
          {advice.sourceAmountCents < advice.amountCents &&
            ` (${formatCents(advice.sourceAmountCents)} sur ce compte)`}
        </>
      ) : (
        ' — aucune épargne disponible pour le couvrir.'
      )}
    </Typography>

    {advice.isCushion && (
      <Alert severity="warning" data-testid="advice-cushion" sx={{ mt: 1 }}>
        Entame le matelas
      </Alert>
    )}

    {advice.alternatives.length > 0 && (
      <Box sx={{ mt: 1 }}>
        <Typography variant="subtitle2">Le reste, depuis</Typography>
        {advice.alternatives.map((alternative) => (
          <Typography key={alternative.accountId} variant="body2" color="text.secondary">
            {alternative.accountName} — {formatCents(alternative.amountCents)}
            {alternative.isCushion && ' (matelas)'}
          </Typography>
        ))}
      </Box>
    )}

    {advice.uncoveredCents > 0 && (
      <Typography variant="body2" color="error" sx={{ mt: 1 }}>
        Il manque encore {formatCents(advice.uncoveredCents)}.
      </Typography>
    )}

    {advice.sourceAccountId && (
      <Button component={Link} to={advanceFormLink(advice, outlook.today)} variant="contained" sx={{ mt: 2 }}>
        Préparer l'avance
      </Button>
    )}
  </Box>
)

/** 1. The projected balance: the curve, the horizon, the delay and the overdraft announced, the advance advised. */
const ProjectionSection = ({
  outlook,
  months,
  onHorizon,
}: {
  outlook: TreasuryOutlook
  months: number | null
  onHorizon: (months: number | null) => void
}) => {
  const { overdraft, salary, advice } = outlook

  return (
    <Card data-testid="treasury-section-chart">
      <CardContent>
        <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" sx={{ mb: 1, flexWrap: 'wrap' }}>
          <Typography variant="h6" component="h2">
            Solde projeté
          </Typography>
          <HorizonPicker months={months} onChange={onHorizon} />
        </Stack>

        {salary.isLate && (
          <Alert severity="warning" data-testid="salary-delay" sx={{ mb: 1 }}>
            {delayHeadline(salary.delayDays)}
          </Alert>
        )}
        <SalaryLine outlook={outlook} />
        {overdraft.isForecast && overdraft.onDate && (
          <Alert severity="info" data-testid="overdraft-forecast" sx={{ mb: 2 }}>
            Découvert prévu le {shortDay(overdraft.onDate)}
          </Alert>
        )}

        {outlook.projection.length > 0 && (
          <>
            <TreasuryChart projection={outlook.projection} limitCents={overdraft.limitCents} />
            <Typography variant="subtitle2" sx={{ mt: 3, mb: 1 }}>
              Entrées et sorties prévues, cumulées
            </Typography>
            <TreasuryFlowsChart projection={outlook.projection} />
          </>
        )}

        {advice && <Advice outlook={outlook} advice={advice} />}
      </CardContent>
    </Card>
  )
}

/** The day-by-day table of what is planned and the balance it leaves. */
export const ProjectionTable = ({ outlook }: { outlook: TreasuryOutlook }) => (
  <TableContainer sx={{ maxHeight: 320 }}>
    <Table size="small" stickyHeader aria-label="Solde projeté jour par jour">
      <TableHead>
        <TableRow>
          <TableCell>Jour</TableCell>
          <TableCell>Transactions prévues</TableCell>
          <TableCell align="right">Solde</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {outlook.projection.map((day) => {
          const inOverdraft = day.balanceCents < -outlook.overdraft.limitCents

          return (
            <TableRow key={day.date} data-testid="treasury-day" data-overdraft={inOverdraft ? 'true' : 'false'}>
              <TableCell>{shortDay(day.date)}</TableCell>
              <TableCell>{day.movements.length === 0 ? '—' : day.movements.map(movementText).join(', ')}</TableCell>
              <TableCell align="right">
                {formatCents(day.balanceCents)}
                {inOverdraft && <Chip size="small" color="error" variant="outlined" label="Découvert" sx={{ ml: 1 }} />}
              </TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  </TableContainer>
)

/** 5. The movements, day by day. */
const MovementsSection = ({ outlook }: { outlook: TreasuryOutlook }) => (
  <Card data-testid="treasury-section-movements">
    <CardContent>
      <Typography variant="h6" component="h2" sx={{ mb: 1 }}>
        Mouvements
      </Typography>
      {outlook.projection.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Aucun mouvement prévu
        </Typography>
      ) : (
        <ProjectionTable outlook={outlook} />
      )}
    </CardContent>
  </Card>
)

/**
 * « Trésorerie »: one page, no tabs — the projected balance, the cushion, the
 * savings advances, who owes what, the movements. The settings are behind « Régler ».
 */
export const TreasuryPage = () => {
  const [months, setMonths] = useState<number | null>(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const { outlook, settings, loading, error, refresh, save } = useTreasuryOutlook(months)
  const { status: cushion, refresh: refreshCushion, configure } = useCushionStatus()

  useMercure(TREASURY_TOPICS, () => {
    void refresh()
    void refreshCushion()
  })

  return (
    <>
      <Title title="Trésorerie" />
      <Stack spacing={2}>
        <Stack direction="row" justifyContent="flex-end">
          <Button variant="outlined" size="small" onClick={() => setSettingsOpen(true)}>
            Régler
          </Button>
        </Stack>

        {loading && !outlook && <LinearProgress />}
        {error && (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" onClick={refresh}>
                Réessayer
              </Button>
            }
          >
            {error}
          </Alert>
        )}

        {outlook && <ProjectionSection outlook={outlook} months={months} onHorizon={setMonths} />}
        {cushion && <CushionSection status={cushion} />}
        <SavingsAdvancesSection />
        <PeerDebtsSection />
        {outlook && <MovementsSection outlook={outlook} />}
      </Stack>

      <TreasurySettingsDialog
        open={settingsOpen}
        onClose={() => setSettingsOpen(false)}
        cushion={cushion}
        configureCushion={configure}
        settings={settings}
        save={save}
      />
    </>
  )
}
