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
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import { Link } from 'react-router-dom'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { shortDay, TreasuryChart } from './TreasuryChart'
import { TREASURY_TOPICS, useTreasuryOutlook } from './useTreasuryOutlook'
import type {
  TreasuryAdvice,
  TreasuryOutlook,
  TreasurySaveResult,
  TreasurySettingsChange,
  TreasurySettingsState,
} from './useTreasuryOutlook'

const dayCount = (days: number) => `${days} jour${days > 1 ? 's' : ''}`

/** « Salaire en retard de 30 jours » — the headline, only when the salary is late. */
export const delayHeadline = (delayDays: number) => `Salaire en retard de ${dayCount(delayDays)}`

type Save = (change: TreasurySettingsChange) => Promise<TreasurySaveResult>

const SalaryDayForm = ({ initial, onSave, intro }: { initial: number | null; onSave: Save; intro: string }) => {
  const [value, setValue] = useState(initial === null ? '' : String(initial))
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const day = Number(value)
    if (value.trim() === '' || !Number.isInteger(day) || day < 1 || day > 31) {
      setError('Indiquez un jour du mois, de 1 à 31.')

      return
    }
    const result = await onSave({ salaryExpectedDay: day })
    setError(result.ok ? null : result.message)
  }

  return (
    <Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {intro}
      </Typography>
      <Stack direction="row" spacing={2} alignItems="flex-start">
        <TextField
          type="number"
          size="small"
          label="Jour du mois"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          error={error !== null}
          helperText={error ?? 'De 1 à 31 ; un mois plus court prend son dernier jour.'}
          slotProps={{ htmlInput: { min: 1, max: 31 } }}
          sx={{ width: 180 }}
        />
        <Button variant="outlined" onClick={submit} sx={{ mt: 0.25 }}>
          Enregistrer le jour
        </Button>
      </Stack>
    </Box>
  )
}

const SalaryCard = ({ outlook }: { outlook: TreasuryOutlook }) => {
  const { salary } = outlook

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        {salary.isLate && (
          <Alert severity="warning" data-testid="salary-delay" sx={{ mb: 1 }}>
            {delayHeadline(salary.delayDays)}
          </Alert>
        )}
        <Stack direction="row" spacing={2} alignItems="center" sx={{ flexWrap: 'wrap' }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            Salaire
          </Typography>
          {salary.isReceived && <Chip size="small" color="success" label="Salaire reçu" />}
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {salary.expectedOn
            ? `${salary.isLate ? 'Attendu depuis le' : 'Attendu le'} ${shortDay(salary.expectedOn)}`
            : 'Date attendue inconnue'}
          {salary.referenceCents !== null && ` — repère de ${formatCents(salary.referenceCents)}`}
        </Typography>
        {salary.isLate && salary.shortfallCents > 0 && (
          <Typography variant="body2" color="text.secondary">
            Il manque {formatCents(salary.shortfallCents)} par rapport au repère.
          </Typography>
        )}
        <Typography variant="body2" sx={{ mt: 1 }}>
          Solde des comptes courants : <strong>{formatCents(outlook.balanceCents)}</strong>
        </Typography>
      </CardContent>
    </Card>
  )
}

const OverdraftCard = ({ outlook }: { outlook: TreasuryOutlook }) => {
  const { overdraft } = outlook
  const noProjection = outlook.projection.length === 0

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>
          Solde projeté
        </Typography>
        {overdraft.isForecast && overdraft.onDate ? (
          <Alert severity="error" data-testid="overdraft-forecast" sx={{ mb: 2 }}>
            Découvert prévu le {shortDay(overdraft.onDate)} — solde le plus bas :{' '}
            <strong>{formatCents(overdraft.worstBalanceCents)}</strong>
          </Alert>
        ) : (
          <Alert severity="success" variant="outlined" sx={{ mb: 2 }}>
            Aucun découvert prévu jusqu'au prochain salaire.
          </Alert>
        )}
        {noProjection ? (
          <Typography variant="body2" color="text.secondary">
            Aucun jour à projeter : le salaire est attendu aujourd'hui.
          </Typography>
        ) : (
          <>
            <TreasuryChart projection={outlook.projection} limitCents={overdraft.limitCents} />
            <ProjectionTable outlook={outlook} />
          </>
        )}
      </CardContent>
    </Card>
  )
}

const ProjectionTable = ({ outlook }: { outlook: TreasuryOutlook }) => (
  <TableContainer sx={{ maxHeight: 320, mt: 2 }}>
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
              <TableCell>
                {day.movements.length === 0
                  ? '—'
                  : day.movements
                      .map((m) => `${m.label}${m.source === 'recurring' ? ' (récurrent)' : ''} (${formatCents(m.amountCents)})`)
                      .join(', ')}
              </TableCell>
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

const AdviceCard = ({ outlook }: { outlook: TreasuryOutlook }) => {
  const { advice, outstandingAdvancesCents } = outlook

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>
          Avance conseillée
        </Typography>
        {advice ? (
          <Box data-testid="advice">
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
                Entame le matelas, il se reconstituera quand tu rendras l'avance.
              </Alert>
            )}

            {advice.alternatives.length > 0 && (
              <Box sx={{ mt: 1 }}>
                <Typography variant="subtitle2">Le reste, depuis</Typography>
                {advice.alternatives.map((alternative) => (
                  <Typography key={alternative.accountId} variant="body2" color="text.secondary">
                    {alternative.accountName} — {formatCents(alternative.amountCents)}
                    {alternative.isCushion && ' (matelas : entame le matelas, il se reconstituera quand tu rendras l\'avance)'}
                  </Typography>
                ))}
              </Box>
            )}

            {advice.uncoveredCents > 0 && (
              <Typography variant="body2" color="error" sx={{ mt: 1 }}>
                Il manque encore {formatCents(advice.uncoveredCents)} : l'épargne ne suffit pas.
              </Typography>
            )}

            {advice.sourceAccountId && (
              <Button component={Link} to={advanceFormLink(advice, outlook.today)} variant="contained" sx={{ mt: 2 }}>
                Préparer l'avance
              </Button>
            )}
          </Box>
        ) : (
          <Typography variant="body2" color="text.secondary">
            {outlook.salary.isReceived
              ? 'Le salaire est arrivé : aucune avance à prévoir.'
              : 'Aucune avance à prévoir : le solde reste au-dessus de la limite.'}
          </Typography>
        )}

        {outstandingAdvancesCents > 0 && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
            Déjà avancé et pas encore rendu : {formatCents(outstandingAdvancesCents)} reste à rendre.
          </Typography>
        )}
      </CardContent>
    </Card>
  )
}

const OverdraftLimitForm = ({ outlook, onSave }: { outlook: TreasuryOutlook; onSave: Save }) => {
  const limitCents = outlook.overdraft.limitCents
  const [value, setValue] = useState(String(limitCents / 100))
  const [seenLimit, setSeenLimit] = useState(limitCents)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  if (seenLimit !== limitCents) {
    setSeenLimit(limitCents)
    setValue(String(limitCents / 100))
  }

  const submit = async () => {
    setSaved(false)
    const euros = Number(value)
    if (value.trim() === '' || Number.isNaN(euros)) {
      setError('Indiquez un montant en euros.')

      return
    }
    if (euros < 0) {
      setError('Le découvert autorisé ne peut pas être négatif : indiquez 0 s\'il n\'y en a pas.')

      return
    }
    const result = await onSave({ overdraftLimitCents: Math.round(euros * 100) })
    setError(result.ok ? null : result.message)
    setSaved(result.ok)
  }

  return (
    <Stack direction="row" spacing={2} alignItems="flex-start">
      <TextField
        type="number"
        size="small"
        label="Découvert autorisé (€)"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        error={error !== null}
        helperText={error ?? (saved ? 'Découvert autorisé enregistré.' : '0 : tout solde négatif est un découvert.')}
        sx={{ width: 260 }}
      />
      <Button variant="outlined" onClick={submit} sx={{ mt: 0.25 }}>
        Enregistrer le découvert
      </Button>
    </Stack>
  )
}

const SettingsCard = ({
  outlook,
  settings,
  onSave,
}: {
  outlook: TreasuryOutlook
  settings: TreasurySettingsState
  onSave: Save
}) => (
  <Card>
    <CardContent>
      <Typography variant="h6" sx={{ mb: 2 }}>
        Réglages
      </Typography>
      <Stack spacing={3}>
        <OverdraftLimitForm outlook={outlook} onSave={onSave} />
        {outlook.salary.isConfigured && (
          <SalaryDayForm
            key={settings.salaryExpectedDay ?? 'none'}
            initial={settings.salaryExpectedDay}
            onSave={onSave}
            intro="Le jour du mois où le salaire est attendu : au-delà, il est en retard."
          />
        )}
      </Stack>
    </CardContent>
  </Card>
)

export const TreasuryPage = () => {
  const { outlook, settings, loading, error, refresh, save } = useTreasuryOutlook()

  useMercure(TREASURY_TOPICS, refresh)

  return (
    <>
      <Title title="Trésorerie" />
      {loading && !outlook && <LinearProgress />}
      {error && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={refresh}>
              Réessayer
            </Button>
          }
        >
          {error}
        </Alert>
      )}
      {outlook && !outlook.salary.isConfigured && (
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>
              Quel jour le salaire est-il attendu ?
            </Typography>
            <SalaryDayForm
              initial={null}
              onSave={save}
              intro="Sans ce jour, Maggie ne sait pas si le salaire est en retard : rien n'est annoncé tant qu'il manque."
            />
          </CardContent>
        </Card>
      )}
      {outlook && outlook.salary.isConfigured && (
        <>
          <SalaryCard outlook={outlook} />
          <OverdraftCard outlook={outlook} />
          <AdviceCard outlook={outlook} />
        </>
      )}
      {outlook && settings && <SettingsCard outlook={outlook} settings={settings} onSave={save} />}
    </>
  )
}
