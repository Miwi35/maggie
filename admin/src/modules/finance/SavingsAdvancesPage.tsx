import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title, useNotify } from 'react-admin'
import { formatCents } from './accountTypes'
import { Amount } from './AmountField'
import { formatDay } from './recurringOperations'
import { useSavingsAdvances } from './useSavingsAdvances'
import { isValidSalaryDay, useTreasurySettings } from './useTreasurySettings'
import type { RepaymentProposal, SalaryDelay, SavingsAdvance, SavingsAdvancesData } from './useSavingsAdvances'

const UNPROPOSED_REASONS: Record<string, string> = {
  insufficient_balance: 'Le solde du compte courant ne suffit pas.',
  no_checking_account: 'Aucun compte courant ouvert.',
}

const SalaryDayCard = ({
  delay,
  saving,
  onSave,
}: {
  delay: SalaryDelay
  saving: boolean
  onSave: (day: number | null) => void
}) => {
  const [value, setValue] = useState(delay.salaryExpectedDay === null ? '' : String(delay.salaryExpectedDay))
  const parsed = value.trim() === '' ? null : Number(value)
  const invalid = parsed !== null && !isValidSalaryDay(parsed)
  const unchanged = parsed === delay.salaryExpectedDay

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>
          Jour de salaire attendu
        </Typography>

        {delay.salaryExpectedDay === null ? (
          <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
            Quel jour du mois attendez-vous votre salaire ? Tant que vous ne l'avez pas indiqué, aucun retard n'est
            annoncé.
          </Alert>
        ) : !delay.isConfigured ? (
          <Alert severity="info" variant="outlined" sx={{ mb: 2 }}>
            Aucun salaire de référence : le retard ne peut pas être calculé.
          </Alert>
        ) : delay.isLate ? (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Salaire en retard de {delay.delayDays} jour{delay.delayDays > 1 ? 's' : ''} — il manque{' '}
            {formatCents(delay.shortfallCents)}.
          </Alert>
        ) : (
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Salaire attendu le {delay.salaryExpectedDay} de chaque mois : pas de retard.
          </Typography>
        )}

        <Stack direction="row" spacing={2} alignItems="flex-start" sx={{ flexWrap: 'wrap' }}>
          <TextField
            type="number"
            size="small"
            label="Jour du mois"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            error={invalid}
            helperText={invalid ? 'Un jour de 1 à 31.' : 'Laisser vide pour ne plus suivre le retard.'}
            slotProps={{ htmlInput: { min: 1, max: 31 } }}
            sx={{ width: 200 }}
          />
          <Button variant="contained" disabled={saving || invalid || unchanged} onClick={() => onSave(parsed)}>
            Enregistrer
          </Button>
        </Stack>
      </CardContent>
    </Card>
  )
}

const OutstandingSummary = ({ data }: { data: SavingsAdvancesData }) => (
  <Card sx={{ mb: 2 }}>
    <CardContent>
      <Typography variant="h6">Reste à rendre</Typography>
      <Typography variant="h4" data-testid="savings-total-outstanding" sx={{ my: 1 }}>
        {formatCents(data.totalOutstandingCents)}
      </Typography>

      {data.bySource.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Aucune avance en cours : rien à remettre sur vos comptes d'épargne.
        </Typography>
      ) : (
        data.bySource.map((source) => (
          <Stack
            key={source.sourceAccountId}
            data-testid="savings-source"
            data-source={source.sourceAccountName}
            direction="row"
            spacing={2}
            sx={{ py: 0.5 }}
          >
            <Typography variant="body2" sx={{ flexGrow: 1 }}>
              {source.sourceAccountName}
              <Typography component="span" variant="body2" color="text.secondary">
                {' '}
                — {source.openCount} avance{source.openCount > 1 ? 's' : ''} ouverte{source.openCount > 1 ? 's' : ''}
              </Typography>
            </Typography>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {formatCents(source.outstandingCents)}
            </Typography>
          </Stack>
        ))
      )}
    </CardContent>
  </Card>
)

const Proposals = ({
  data,
  givingBack,
  onGiveBack,
}: {
  data: SavingsAdvancesData
  givingBack: string | null
  onGiveBack: (proposal: RepaymentProposal) => void
}) => {
  const nameOf = (accountId: string) =>
    data.advances.find((advance) => advance.sourceAccountId === accountId)?.sourceAccountName ?? 'le compte d’épargne'

  if (data.proposals.length === 0 && data.unproposed.length === 0) {
    return null
  }

  return (
    <Card sx={{ mb: 2 }}>
      <CardContent>
        <Typography variant="h6">Contre-virements proposés</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          Faites le virement dans votre banque, puis cliquez sur « Je l'ai fait » : Maggie enregistre la transaction et
          l'avance se solde. Ce bouton ne fait aucun virement.
        </Typography>

        {data.proposals.map((proposal) => (
          <Stack
            key={proposal.advanceId}
            data-testid="savings-proposal"
            data-advance={proposal.advanceId}
            direction="row"
            spacing={2}
            alignItems="center"
            sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider', flexWrap: 'wrap' }}
          >
            <Box sx={{ flexGrow: 1, minWidth: 220 }}>
              <Typography variant="body2">
                Virer <strong>{formatCents(proposal.amountCents, proposal.currency)}</strong> vers{' '}
                {nameOf(proposal.toAccountId)}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {proposal.label}
                {proposal.isPartial && ' — réduit au solde disponible du compte courant'}
              </Typography>
            </Box>
            <Button
              variant="contained"
              size="small"
              disabled={givingBack !== null}
              onClick={() => onGiveBack(proposal)}
              aria-label={`Je l'ai fait : ${proposal.label}`}
            >
              Je l'ai fait
            </Button>
          </Stack>
        ))}

        {data.unproposed.map((item) => (
          <Alert key={item.advanceId} severity="warning" variant="outlined" sx={{ mt: 1 }}>
            {formatCents(item.amountCents)} à rendre à {item.sourceAccountName} ne peuvent pas être proposés.{' '}
            {UNPROPOSED_REASONS[item.reason] ?? ''}
          </Alert>
        ))}
      </CardContent>
    </Card>
  )
}

const AdvancesTable = ({ title, advances, empty }: { title: string; advances: SavingsAdvance[]; empty?: string }) => (
  <Card sx={{ mb: 2 }}>
    <CardContent>
      <Typography variant="h6" sx={{ mb: 1 }}>
        {title}
      </Typography>

      {advances.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          {empty}
        </Typography>
      ) : (
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Source</TableCell>
              <TableCell>Ouverte le</TableCell>
              <TableCell align="right">Avancé</TableCell>
              <TableCell align="right">Rendu</TableCell>
              <TableCell align="right">Reste à rendre</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {advances.map((advance) => (
              <TableRow key={advance.id} data-testid="savings-advance" data-source={advance.sourceAccountName}>
                <TableCell>{advance.sourceAccountName}</TableCell>
                <TableCell>{formatDay(advance.openedOn)}</TableCell>
                <TableCell>
                  <Amount cents={advance.amountCents} />
                </TableCell>
                <TableCell>
                  <Amount cents={advance.repaidCents} />
                </TableCell>
                <TableCell>
                  <Amount cents={advance.outstandingCents} bold={!advance.isClosed} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </CardContent>
  </Card>
)

export const SavingsAdvancesPage = () => {
  const { data, loading, error, retry, givingBack, giveBack } = useSavingsAdvances()
  const { saving, saveSalaryExpectedDay } = useTreasurySettings()
  const notify = useNotify()

  const onSaveDay = async (day: number | null) => {
    const ok = await saveSalaryExpectedDay(day)
    notify(ok ? 'Jour de salaire attendu enregistré' : "Impossible d'enregistrer le jour de salaire attendu", {
      type: ok ? 'info' : 'error',
    })
    if (ok) {
      retry()
    }
  }

  const onGiveBack = async (proposal: RepaymentProposal) => {
    const ok = await giveBack(proposal)
    notify(
      ok ? 'Transaction enregistrée : l’avance est soldée' : "Impossible d'enregistrer la transaction",
      { type: ok ? 'info' : 'error' },
    )
  }

  const open = data?.advances.filter((advance) => !advance.isClosed) ?? []
  const closed = data?.advances.filter((advance) => advance.isClosed) ?? []

  return (
    <>
      <Title title="Avances d'épargne" />
      {loading && !data && <LinearProgress />}

      {error && (
        <Alert
          severity="error"
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={retry}>
              Réessayer
            </Button>
          }
        >
          {error}
        </Alert>
      )}

      {data && (
        <>
          <SalaryDayCard
            key={data.salaryDelay.salaryExpectedDay ?? 'none'}
            delay={data.salaryDelay}
            saving={saving}
            onSave={onSaveDay}
          />
          <OutstandingSummary data={data} />
          <Proposals data={data} givingBack={givingBack} onGiveBack={onGiveBack} />
          <AdvancesTable title="Avances ouvertes" advances={open} empty="Aucune avance en cours." />
          {closed.length > 0 && <AdvancesTable title="Avances soldées" advances={closed} />}
        </>
      )}
    </>
  )
}
