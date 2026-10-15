import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import LinearProgress from '@mui/material/LinearProgress'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { Amount } from './AmountField'
import { formatDay } from './recurringOperations'
import { useRecurringOperationPreview } from './useRecurringOperationPreview'
import type { RecurringPreviewCriteria } from './useRecurringOperationPreview'

/**
 * What a series would recognise, live: the transactions that match the
 * identity and the amount typed so far, before anything is written.
 */
export const RecurringMatchesPanel = ({ criteria }: { criteria: RecurringPreviewCriteria }) => {
  const { status, result, error } = useRecurringOperationPreview(criteria)

  return (
    <Box aria-busy={status === 'loading'} aria-label="Transactions reconnues" role="region" sx={{ width: '100%' }}>
      <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
        Transactions reconnues
      </Typography>

      {status === 'idle' && (
        <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
          Saisissez un montant pour voir les transactions que la série reconnaîtrait.
        </Alert>
      )}

      {status === 'error' && (
        <Alert severity="warning" variant="outlined" sx={{ mt: 1 }}>
          {error} Vous pouvez quand même enregistrer la série.
        </Alert>
      )}

      {status === 'loading' && !result && <LinearProgress sx={{ mt: 1 }} />}

      {result && result.total === 0 && (
        <Alert severity="info" variant="outlined" sx={{ mt: 1 }}>
          Aucune transaction reconnue
        </Alert>
      )}

      {result && result.total > 0 && (
        <>
          {status === 'loading' && <LinearProgress sx={{ mt: 1 }} />}
          <Typography variant="body2" sx={{ my: 1 }}>
            {result.total} transaction(s) reconnue(s)
            {result.total > result.matches.length && ` — ${result.matches.length} premières sur ${result.total}`}
          </Typography>
          <Box sx={{ overflowX: 'auto', maxHeight: 240 }}>
            <Table size="small" stickyHeader aria-label="Aperçu des transactions reconnues">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Libellé</TableCell>
                  <TableCell align="right">Montant</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {result.matches.map((match) => (
                  <TableRow key={match.transactionId}>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDay(match.bookedAt)}</TableCell>
                    <TableCell>{match.label}</TableCell>
                    <TableCell align="right">
                      <Amount cents={match.amountCents} signed />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        </>
      )}
    </Box>
  )
}
