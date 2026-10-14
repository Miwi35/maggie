import { useCallback, useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import MenuItem from '@mui/material/MenuItem'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import LinkOffIcon from '@mui/icons-material/LinkOff'
import { useDataProvider, useGetList, useGetOne, useNotify } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { MakeRecurringButton } from './RecurringTransactionActions'
import { formatDay, idOf } from './recurringOperations'
import { useRecurringLink } from './useRecurringOperations'

interface SeriesLink {
  recurringOperation: string | null
  recurringOccurrenceOn: string | null
}

const linkOf = (record: RaRecord): SeriesLink => ({
  recurringOperation: (record.recurringOperation as string | null | undefined) || null,
  recurringOccurrenceOn: (record.recurringOccurrenceOn as string | null | undefined) || null,
})

/**
 * The « Récurrence » section: the series the line settles and its due date,
 * the way to take it off, or to attach it to a series — an existing one, or one
 * made from the line itself.
 *
 * Attaching and detaching act at once and are read back here, apart from the
 * form: « Enregistrer » never sends the series, so a stale value cannot undo them.
 */
export const RecurrenceSection = ({ record }: { record: RaRecord }) => {
  const dataProvider = useDataProvider()
  const notify = useNotify()
  const { attach, detach, saving } = useRecurringLink()
  const [link, setLink] = useState<SeriesLink>(() => linkOf(record))
  const { recurringOperation: recordSeries, recurringOccurrenceOn: recordDue } = linkOf(record)
  // The sheet may open on a copy of the line older than the series link, then receive the fresh one.
  useEffect(() => {
    setLink({ recurringOperation: recordSeries, recurringOccurrenceOn: recordDue })
  }, [recordSeries, recordDue])
  const [choice, setChoice] = useState('')

  const { data: series } = useGetOne(
    'recurring_operations',
    { id: link.recurringOperation ?? '' },
    { enabled: link.recurringOperation !== null },
  )
  const { data: seriesList = [] } = useGetList<RaRecord & { label: string }>('recurring_operations', {
    pagination: { page: 1, perPage: 100 },
  })
  const allSeries = [...seriesList].sort((a, b) => a.label.localeCompare(b.label, 'fr'))

  const reload = useCallback(async () => {
    try {
      const { data } = await dataProvider.getOne('transactions', { id: record.id })
      setLink(linkOf(data as RaRecord))
    } catch {
      // The sheet keeps what it knew; the next opening reads the line again.
    }
  }, [dataProvider, record.id])

  const lineLabel = record.label as string
  const name = (series?.label as string | undefined) ?? 'Opération récurrente'

  const onDetach = async () => {
    if (!(await detach(String(record.id)))) {
      notify('Impossible de détacher cette transaction', { type: 'error' })

      return
    }
    setLink({ recurringOperation: null, recurringOccurrenceOn: null })
    notify(`« ${lineLabel} » n’est plus rattachée à « ${name} »`, { type: 'info' })
  }

  const onAttach = async () => {
    const target = allSeries.find((s) => s.id === choice)
    if (!(await attach(String(record.id), idOf(choice), null))) {
      notify('Impossible de rattacher cette transaction', { type: 'error' })

      return
    }
    setChoice('')
    setLink({ recurringOperation: choice, recurringOccurrenceOn: null })
    notify(`« ${lineLabel} » est rattachée à « ${target?.label ?? 'cette série'} »`, { type: 'info' })
    void reload()
  }

  if (link.recurringOperation !== null) {
    return (
      <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, alignItems: 'flex-start' }}>
        <Typography variant="body2">
          Série : <strong>{name}</strong>
        </Typography>
        {link.recurringOccurrenceOn && (
          <Typography variant="body2" color="text.secondary">
            Échéance du {formatDay(link.recurringOccurrenceOn)}
          </Typography>
        )}
        <Button color="warning" startIcon={<LinkOffIcon />} disabled={saving} onClick={onDetach}>
          Détacher de la série
        </Button>
      </Box>
    )
  }

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, alignItems: 'flex-start' }}>
      <Typography variant="body2" color="text.secondary">
        Cette ligne n’appartient à aucune opération récurrente.
      </Typography>
      {allSeries.length > 0 && (
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            select
            size="small"
            label="Opération récurrente existante"
            value={choice}
            onChange={(event) => setChoice(event.target.value)}
            sx={{ minWidth: 260 }}
          >
            {allSeries.map((s) => (
              <MenuItem key={s.id} value={s.id as string}>
                {s.label}
              </MenuItem>
            ))}
          </TextField>
          <Button disabled={choice === '' || saving} onClick={onAttach}>
            Rattacher à cette série
          </Button>
        </Box>
      )}
      <MakeRecurringButton record={{ ...record, recurringOperation: null }} onChanged={() => void reload()} />
    </Box>
  )
}
