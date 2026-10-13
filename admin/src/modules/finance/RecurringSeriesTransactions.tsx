import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import LinkOffIcon from '@mui/icons-material/LinkOff'
import { useCreatePath, useDataProvider, useNotify } from 'react-admin'
import { Link } from 'react-router-dom'
import { Amount } from './AmountField'
import { formatDay, seriesIri } from './recurringOperations'
import { useRecurringLink } from './useRecurringOperations'
import type { RaRecord } from 'react-admin'

/** The API caps a page at 100; a series is read whole, page after page. */
const PAGE_SIZE = 100

interface SeriesTransaction extends RaRecord {
  label: string
  bookedAt: string
  amountCents: number
  currency: string
  recurringOccurrenceOn: string | null
  recurringSource: 'auto' | 'manual' | null
}

const READ_ERROR = 'Impossible de lire les transactions de cette série'

/**
 * The transactions a series recognised, newest first (MAG-438): what the
 * owner opens to check a pattern or a counterparty. `reloadKey` changes when
 * the page hears a transaction move, so a line attached elsewhere appears
 * without a reload.
 */
export const RecurringSeriesTransactions = ({
  seriesId,
  currency,
  reloadKey,
}: {
  seriesId: string
  currency: string
  reloadKey: number
}) => {
  const dataProvider = useDataProvider()
  const createPath = useCreatePath()
  const notify = useNotify()
  const { detach, saving } = useRecurringLink()
  const [transactions, setTransactions] = useState<SeriesTransaction[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let current = true

    const readAll = async () => {
      const rows: SeriesTransaction[] = []
      for (let page = 1; ; page += 1) {
        const result = await dataProvider.getList<SeriesTransaction>('transactions', {
          filter: { recurringOperation: seriesIri(seriesId) },
          sort: { field: 'bookedAt', order: 'DESC' },
          pagination: { page, perPage: PAGE_SIZE },
        })
        rows.push(...result.data)
        if (result.data.length < PAGE_SIZE || rows.length >= (result.total ?? 0)) return rows
      }
    }

    readAll().then(
      (rows) => {
        if (!current) return
        setTransactions(rows)
        setError(null)
      },
      () => current && setError(READ_ERROR),
    )

    return () => {
      current = false
    }
  }, [dataProvider, seriesId, reloadKey, attempt])

  const onDetach = useCallback(
    async (transaction: SeriesTransaction) => {
      if (!(await detach(String(transaction.id)))) {
        notify('Impossible de détacher cette transaction', { type: 'error' })

        return
      }
      setTransactions((rows) => rows?.filter((row) => row.id !== transaction.id) ?? null)
      notify(`« ${transaction.label} » n’est plus rattachée à la série`, { type: 'info' })
    },
    [detach, notify],
  )

  if (error) {
    return (
      <Alert
        severity="error"
        action={
          <Button color="inherit" size="small" onClick={() => setAttempt((n) => n + 1)}>
            Réessayer
          </Button>
        }
      >
        {error}
      </Alert>
    )
  }
  if (transactions === null) return <LinearProgress aria-label="Chargement des transactions" />
  if (transactions.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Aucune transaction reconnue pour l’instant
      </Typography>
    )
  }

  return (
    <Table size="small" aria-label="Transactions de la série">
      <TableHead>
        <TableRow>
          <TableCell>Date</TableCell>
          <TableCell>Libellé bancaire</TableCell>
          <TableCell align="right">Montant</TableCell>
          <TableCell>Échéance couverte</TableCell>
          <TableCell>Rattachement</TableCell>
          <TableCell />
        </TableRow>
      </TableHead>
      <TableBody>
        {transactions.map((transaction) => (
          <TableRow key={String(transaction.id)}>
            <TableCell>{formatDay(transaction.bookedAt)}</TableCell>
            <TableCell>
              <Link to={createPath({ resource: 'transactions', type: 'edit', id: transaction.id })}>
                {transaction.label}
              </Link>
            </TableCell>
            <TableCell align="right">
              <Amount cents={transaction.amountCents} currency={transaction.currency || currency} signed />
            </TableCell>
            <TableCell>
              {transaction.recurringOccurrenceOn ? formatDay(transaction.recurringOccurrenceOn) : '—'}
            </TableCell>
            <TableCell>
              <Chip
                size="small"
                variant="outlined"
                label={transaction.recurringSource === 'manual' ? 'Manuel' : 'Automatique'}
              />
            </TableCell>
            <TableCell align="right">
              <Tooltip title="Détacher de la série">
                <span>
                  <IconButton
                    size="small"
                    aria-label={`Détacher ${transaction.label} de la série`}
                    disabled={saving}
                    onClick={() => onDetach(transaction)}
                  >
                    <LinkOffIcon fontSize="small" />
                  </IconButton>
                </span>
              </Tooltip>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
