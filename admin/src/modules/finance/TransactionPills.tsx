import type { MouseEvent, ReactElement } from 'react'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import EventRepeatIcon from '@mui/icons-material/EventRepeat'
import SwapHorizIcon from '@mui/icons-material/SwapHoriz'
import UndoIcon from '@mui/icons-material/Undo'
import LabelOffIcon from '@mui/icons-material/LabelOff'
import GroupsIcon from '@mui/icons-material/Groups'
import { useCreatePath, useGetOne } from 'react-admin'
import { useNavigate } from 'react-router-dom'
import type { RaRecord } from 'react-admin'
import { transferLabel } from './TransferBadge'
import { isBetweenAccounts } from './useTransactionTransfer'

/** `shared` is typed and drawn but nothing emits it yet: the sharing lot (MAG-451) feeds it. */
export type PillKind = 'internal' | 'rejected' | 'series' | 'shared' | 'to_sort'

export interface TransactionPill {
  kind: PillKind
  label: string
}

export const SERIES_FALLBACK_NAME = 'Opération récurrente'
export const TO_SORT_LABEL = 'À trier'

/**
 * The one line of pills a transaction wears in the lists: what the line is
 * (a transfer, a rejection), the series it belongs to, and whether it still
 * waits for a category. `seriesName` completes « Série · … » once it is read.
 */
export function transactionPills(record: RaRecord, seriesName?: string): TransactionPill[] {
  const pills: TransactionPill[] = []
  const transferKind = record.transferKind as string | null | undefined
  const label = transferLabel(transferKind, record.amountCents as number | undefined)

  if (label !== null) {
    pills.push({ kind: isBetweenAccounts(transferKind) ? 'internal' : 'rejected', label })
  }
  if (record.recurringOperation) {
    pills.push({ kind: 'series', label: seriesName ? `Série · ${seriesName}` : 'Série' })
  }
  if (label === null && !record.category) {
    pills.push({ kind: 'to_sort', label: TO_SORT_LABEL })
  }

  return pills
}

const PILL_STYLE: Record<PillKind, { color: 'info' | 'warning' | 'primary' | 'secondary' | 'default'; icon: ReactElement }> = {
  internal: { color: 'info', icon: <SwapHorizIcon /> },
  rejected: { color: 'warning', icon: <UndoIcon /> },
  series: { color: 'primary', icon: <EventRepeatIcon /> },
  shared: { color: 'secondary', icon: <GroupsIcon /> },
  to_sort: { color: 'default', icon: <LabelOffIcon /> },
}

/** « Série · Flixo »: the name is the series' own, read from the API. */
const useSeriesName = (record: RaRecord) => {
  const iri = record.recurringOperation as string | null | undefined
  const { data } = useGetOne('recurring_operations', { id: iri ?? '' }, { enabled: Boolean(iri) })

  return iri ? ((data?.label as string | undefined) ?? SERIES_FALLBACK_NAME) : undefined
}

/**
 * The pills of a line, in the list. A click opens the sheet, like the row
 * itself: taking a line off its series or out of a marking is done there.
 */
export const TransactionPills = ({ record }: { record: RaRecord }) => {
  const seriesName = useSeriesName(record)
  const createPath = useCreatePath()
  const navigate = useNavigate()
  const pills = transactionPills(record, seriesName)

  if (pills.length === 0) return null

  const open = (event: MouseEvent) => {
    event.stopPropagation()
    navigate(createPath({ resource: 'transactions', type: 'edit', id: record.id }))
  }

  return (
    <Box component="span" sx={{ display: 'inline-flex', flexWrap: 'wrap', gap: 0.5, ml: 1, verticalAlign: 'middle' }}>
      {pills.map((pill) => (
        <Chip
          key={pill.kind}
          size="small"
          variant="outlined"
          color={PILL_STYLE[pill.kind].color}
          icon={PILL_STYLE[pill.kind].icon}
          label={pill.label}
          data-pill={pill.kind}
          onClick={open}
        />
      ))}
    </Box>
  )
}
