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
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { Title, useNotify } from 'react-admin'
import { formatCents } from './accountTypes'
import { Amount } from './AmountField'
import { formatDay } from './recurringOperations'
import { useSavingsAdvances } from './useSavingsAdvances'
import type { RepaymentProposal, SavingsAdvance, SavingsAdvancesData } from './useSavingsAdvances'

const UNPROPOSED_REASONS: Record<string, string> = {
  insufficient_balance: 'Le solde du compte courant ne suffit pas.',
  no_checking_account: 'Aucun compte courant ouvert.',
}

/** Open advances first, then the settled ones, each in the order the API gave them. */
const openFirst = (advances: SavingsAdvance[]): SavingsAdvance[] => [
  ...advances.filter((advance) => !advance.isClosed),
  ...advances.filter((advance) => advance.isClosed),
]

/**
 * What the proposal for an advance lets the owner do: « Remettre » records the
 * counter-transfer. An advance without a proposal has no action — its reason, when
 * the API gives one, is what stands in its place.
 */
const AdvanceAction = ({
  advance,
  data,
  givingBack,
  onGiveBack,
}: {
  advance: SavingsAdvance
  data: SavingsAdvancesData
  givingBack: string | null
  onGiveBack: (proposal: RepaymentProposal) => void
}) => {
  if (advance.isClosed) {
    return <Chip size="small" label="Soldée" />
  }

  const proposal = data.proposals.find((candidate) => candidate.advanceId === advance.id)
  if (!proposal) {
    const reason = data.unproposed.find((item) => item.advanceId === advance.id)?.reason

    return reason ? (
      <Typography variant="caption" color="text.secondary">
        {UNPROPOSED_REASONS[reason] ?? ''}
      </Typography>
    ) : null
  }

  return (
    <Stack direction="row" spacing={1} alignItems="center" justifyContent="flex-end">
      {proposal.isPartial && (
        <Typography variant="caption" color="text.secondary">
          Partiel : {formatCents(proposal.amountCents, proposal.currency)}
        </Typography>
      )}
      <Button variant="contained" size="small" disabled={givingBack !== null} onClick={() => onGiveBack(proposal)}>
        Remettre
      </Button>
    </Stack>
  )
}

/**
 * « Avances d'épargne »: what was taken from the savings while the salary was late,
 * what is left to give back, and a « Remettre » on each open advance the API proposes
 * a counter-transfer for. Reads, and re-reads whenever a transaction moves.
 */
export const SavingsAdvancesSection = () => {
  const { data, loading, error, retry, givingBack, giveBack } = useSavingsAdvances()
  const notify = useNotify()

  const onGiveBack = async (proposal: RepaymentProposal) => {
    const result = await giveBack(proposal)
    notify(
      {
        ok: 'Transaction enregistrée : l’avance est soldée',
        debit_failed: "Impossible d'enregistrer la transaction",
        credit_failed:
          "Le débit est enregistré, pas le crédit : ajoutez la transaction du compte d'épargne à la main avant de recommencer",
      }[result],
      { type: result === 'ok' ? 'info' : 'error' },
    )
  }

  return (
    <Card data-testid="treasury-section-advances">
      <CardContent>
        <Stack direction="row" spacing={2} alignItems="baseline" sx={{ mb: 1, flexWrap: 'wrap' }}>
          <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>
            Avances d'épargne
          </Typography>
          {data && (
            <Stack direction="row" spacing={1} alignItems="baseline">
              <Typography variant="body2" color="text.secondary">
                Reste à rendre
              </Typography>
              <Typography variant="h6" component="p" data-testid="savings-total-outstanding">
                {formatCents(data.totalOutstandingCents)}
              </Typography>
            </Stack>
          )}
        </Stack>

        {loading && !data && <LinearProgress />}

        {error && (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" onClick={retry}>
                Réessayer
              </Button>
            }
          >
            {error}
          </Alert>
        )}

        {data &&
          (data.advances.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Aucune avance en cours.
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
                  <TableCell align="right" />
                </TableRow>
              </TableHead>
              <TableBody>
                {openFirst(data.advances).map((advance) => (
                  <TableRow key={advance.id} data-testid="savings-advance" data-source={advance.sourceAccountName}>
                    <TableCell>{advance.sourceAccountName}</TableCell>
                    <TableCell>{formatDay(advance.openedOn)}</TableCell>
                    <TableCell align="right">
                      <Amount cents={advance.amountCents} />
                    </TableCell>
                    <TableCell align="right">
                      <Amount cents={advance.repaidCents} />
                    </TableCell>
                    <TableCell align="right">
                      <Amount cents={advance.outstandingCents} bold={!advance.isClosed} />
                    </TableCell>
                    <TableCell align="right">
                      <AdvanceAction advance={advance} data={data} givingBack={givingBack} onGiveBack={onGiveBack} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ))}
      </CardContent>
    </Card>
  )
}

export const SavingsAdvancesPage = () => (
  <Box>
    <Title title="Avances d'épargne" />
    <SavingsAdvancesSection />
  </Box>
)
