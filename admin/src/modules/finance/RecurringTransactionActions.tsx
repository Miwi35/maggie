import { useState } from 'react'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import EventRepeatIcon from '@mui/icons-material/EventRepeat'
import LinkOffIcon from '@mui/icons-material/LinkOff'
import { useGetOne, useListContext, useNotify } from 'react-admin'
import { RecurringOperationDialog } from './RecurringOperationDialog'
import { RecurringCatchUpPreview } from './RecurringCatchUp'
import { describeHistory, idOf, labelPatternFrom } from './recurringOperations'
import { useAttachRecurringOperations, useRecurringLink } from './useRecurringOperations'
import type { RaRecord } from 'react-admin'
import type { RecurringFormValues } from './RecurringOperationDialog'
import type { AttachReport } from './recurringOperations'

/** The last day of its month: the series then counts from the month's end, not from this day. */
const isLastDayOfMonth = (iso: string) => {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number)

  return new Date(Date.UTC(y, m, 0)).getUTCDate() === d
}

const ofSeries = (seriesId: string) => (line: { recurringOperationId: string | null }) => line.recurringOperationId === seriesId

/** The pass covers the whole history: say how much of it is the new series'. */
const historyIntro = (report: AttachReport, seriesId: string, name: string) => {
  const ours = report.attachments.filter(ofSeries(seriesId))
  const others = report.attachments.length - ours.length

  return `${describeHistory(name, ours)}${others > 0 ? ` Le rattrapage rattache aussi ${others} transaction(s) d’autres séries.` : ''}`
}

/** « Série · Flixo » on a line attached to a recurring operation, with the way to take it off. */
export const RecurringBadge = ({ record }: { record: RaRecord }) => {
  const iri = record.recurringOperation as string | null | undefined
  const { data: series } = useGetOne('recurring_operations', { id: iri ?? '' }, { enabled: Boolean(iri) })
  const { detach, saving } = useRecurringLink()
  const { refetch } = useListContext()
  const notify = useNotify()

  if (!iri) return null

  const name = (series?.label as string | undefined) ?? 'Opération récurrente'
  const onDetach = async (event: React.MouseEvent) => {
    event.stopPropagation()
    if (!(await detach(String(record.id)))) {
      notify('Impossible de détacher cette transaction', { type: 'error' })

      return
    }
    notify(`« ${record.label as string} » n’est plus rattachée à « ${name} »`, { type: 'info' })
    refetch()
  }

  return (
    <Chip
      size="small"
      color="primary"
      variant="outlined"
      icon={<EventRepeatIcon />}
      label={`Série · ${name}`}
      sx={{ ml: 1 }}
      onDelete={saving ? undefined : onDetach}
      deleteIcon={
        <Tooltip title="Détacher de la série">
          <LinkOffIcon aria-label={`Détacher ${record.label as string} de la série`} />
        </Tooltip>
      }
    />
  )
}

/**
 * « En faire une opération récurrente » on a line that belongs to no series:
 * the form opens filled from it, then the history of the same payee is offered
 * for attachment — rehearsed first, like every catch-up.
 */
export const MakeRecurringButton = ({ record }: { record: RaRecord }) => {
  const [open, setOpen] = useState(false)
  const [preview, setPreview] = useState<{ report: AttachReport; seriesId: string; name: string } | null>(null)
  const { attach, running } = useAttachRecurringOperations()
  const { refetch } = useListContext()
  const notify = useNotify()

  if (record.recurringOperation) return null

  const bookedAt = String(record.bookedAt).slice(0, 10)
  const counterpartyName = (record.counterpartyName as string | null | undefined) ?? ''
  const name = counterpartyName || (record.label as string)
  const initial: Partial<RecurringFormValues> = {
    label: name,
    counterpartyName,
    // Most banks name no payee: the label then recognises the series.
    labelPattern: counterpartyName === '' ? labelPatternFrom(record.label as string) : '',
    referenceAmountCents: record.amountCents as number,
    account: record.account as string,
    category: (record.category as string | null | undefined) ?? '',
    anchorOn: bookedAt,
    dayRule: isLastDayOfMonth(bookedAt) ? 'last_day_of_month' : 'fixed_day',
  }

  const afterSave = async (saved: RaRecord) => {
    setOpen(false)
    const seriesId = idOf(String(saved.id))
    const report = await attach(true)
    const ours = report?.attachments.filter(ofSeries(seriesId)) ?? []
    if (report === null || ours.length === 0) {
      notify('Opération récurrente créée', { type: 'info' })
      refetch()

      return
    }
    setPreview({ report, seriesId, name: typeof saved.label === 'string' ? saved.label : name })
  }

  const confirm = async () => {
    const report = await attach(false)
    setPreview(null)
    if (report === null) {
      notify('Impossible de rattacher l’historique', { type: 'error' })

      return
    }
    notify(`Opération récurrente créée, ${report.attached} transaction(s) rattachée(s)`, { type: 'info' })
    refetch()
  }

  return (
    <>
      <Tooltip title="En faire une opération récurrente">
        <IconButton
          size="small"
          aria-label={`En faire une opération récurrente : ${record.label as string}`}
          onClick={(event) => {
            event.stopPropagation()
            setOpen(true)
          }}
        >
          <EventRepeatIcon fontSize="small" />
        </IconButton>
      </Tooltip>
      {/* Dialogs render in a portal, but React still bubbles their clicks to the row, whose click opens the edit page. */}
      <span onClick={(event) => event.stopPropagation()}>
        {open && <RecurringOperationDialog initial={initial} onClose={() => setOpen(false)} onSaved={afterSave} />}
        {preview && (
          <RecurringCatchUpPreview
            report={preview.report}
            title="Rattacher l’historique à cette série"
            intro={historyIntro(preview.report, preview.seriesId, preview.name)}
            running={running}
            onClose={() => {
              setPreview(null)
              refetch()
            }}
            onConfirm={confirm}
          />
        )}
      </span>
    </>
  )
}

