import { useState } from 'react'
import Button from '@mui/material/Button'
import EventRepeatIcon from '@mui/icons-material/EventRepeat'
import { useNotify } from 'react-admin'
import { RecurringOperationDialog } from './RecurringOperationDialog'
import { RecurringCatchUpPreview } from './RecurringCatchUp'
import { describeHistory, idOf, labelPatternFrom } from './recurringOperations'
import { useAttachRecurringOperations } from './useRecurringOperations'
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

/**
 * « En faire une opération récurrente » on a line that belongs to no series:
 * the form opens filled from it, then the history of the same payee is offered
 * for attachment — rehearsed first, like every catch-up.
 */
export const MakeRecurringButton = ({ record, onChanged }: { record: RaRecord; onChanged?: () => void }) => {
  const [open, setOpen] = useState(false)
  const [preview, setPreview] = useState<{ report: AttachReport; seriesId: string; name: string } | null>(null)
  const { attach, running } = useAttachRecurringOperations()
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
      onChanged?.()

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
    onChanged?.()
  }

  return (
    <>
      <Button size="small" startIcon={<EventRepeatIcon />} onClick={() => setOpen(true)}>
        En faire une opération récurrente
      </Button>
      {open && <RecurringOperationDialog initial={initial} onClose={() => setOpen(false)} onSaved={afterSave} />}
      {preview && (
        <RecurringCatchUpPreview
          report={preview.report}
          title="Rattacher l’historique à cette série"
          intro={historyIntro(preview.report, preview.seriesId, preview.name)}
          running={running}
          onClose={() => {
            setPreview(null)
            onChanged?.()
          }}
          onConfirm={confirm}
        />
      )}
    </>
  )
}
