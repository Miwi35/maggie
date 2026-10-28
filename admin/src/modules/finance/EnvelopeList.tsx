import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import TuneIcon from '@mui/icons-material/Tune'
import { useNotify, useRefresh } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { EnvelopeAdjustments } from './EnvelopeAdjustments'
import { BUDGET_TOPICS } from './useEnvelopeAdjustments'
import { EnvelopeTreeScreen } from './EnvelopeTreeScreen'
import type { BudgetView } from './EnvelopeTreeScreen'
import { PeriodPicker } from './PeriodPicker'
import { ToQualifyPill } from './ToQualifyPill'
import { useBudgetStatus } from './useBudgetStatus'
import { useRollOverEnvelopes } from './useRollOverEnvelopes'

/** Whether a Mercure message is about a record the editor or the tree reads through react-admin. */
const touchesRecords = (data?: string): boolean => {
  try {
    const id = String((JSON.parse(data ?? '{}') as { '@id'?: string })['@id'] ?? '')

    return id.startsWith('/api/envelopes/') || id.startsWith('/api/categories/')
  } catch {
    return true
  }
}

/**
 * Finance → Budget, one of its two views: « Mois », the envelope tree with its
 * gauges, or « Hors budget », the annual and one-off envelopes. One period and
 * one status for the toolbar and the view: the tree edits what the gauges show.
 */
const BudgetPage = ({ view }: { view: BudgetView }) => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { status, loading, refresh } = useBudgetStatus(year, month)
  const refreshRecords = useRefresh()
  const notify = useNotify()
  const { rollOver, running } = useRollOverEnvelopes()
  const [adjusting, setAdjusting] = useState(false)

  useMercure(BUDGET_TOPICS, (data) => {
    refresh()
    if (touchesRecords(data)) refreshRecords()
  })

  const onRollOver = async () => {
    const previous = new Date(year, month - 2, 1)
    const result = await rollOver({
      fromYear: previous.getFullYear(),
      fromMonth: previous.getMonth() + 1,
      year,
      month,
    })
    if (result === null) {
      notify('Impossible de reconduire les enveloppes', { type: 'error' })

      return
    }
    notify(
      result.created > 0
        ? `${result.created} enveloppe(s) reconduite(s), ${result.skipped} déjà en place`
        : 'Rien à reconduire : les enveloppes sont déjà en place',
      { type: 'info' },
    )
    refresh()
    refreshRecords()
  }

  return (
    <>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        alignItems={{ xs: 'stretch', sm: 'center' }}
        flexWrap="wrap"
        useFlexGap
        sx={{ mb: 2 }}
      >
        <PeriodPicker year={year} month={month} onYearChange={setYear} onMonthChange={setMonth} />
        {view === 'month' && (
          <>
            <Button size="small" startIcon={<ContentCopyIcon />} onClick={onRollOver} disabled={running}>
              Reconduire le mois précédent
            </Button>
            <Button
              size="small"
              variant={adjusting ? 'contained' : 'outlined'}
              startIcon={<TuneIcon />}
              aria-pressed={adjusting}
              onClick={() => setAdjusting((on) => !on)}
            >
              Ajuster
            </Button>
          </>
        )}
        <Box sx={{ ml: { sm: 'auto' } }}>
          <ToQualifyPill />
        </Box>
      </Stack>
      {view === 'month' && adjusting && (
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <EnvelopeAdjustments key={`${year}-${month}`} month={`${year}-${String(month).padStart(2, '0')}`} />
          </CardContent>
        </Card>
      )}
      <EnvelopeTreeScreen year={year} month={month} view={view} status={status} loading={loading} refresh={refresh} />
    </>
  )
}

/** « Mois »: the envelope tree of the period. */
export const EnvelopeList = () => <BudgetPage view="month" />

/** « Hors budget »: the annual and one-off envelopes, apart from the month's budget. */
export const EnvelopeOutside = () => <BudgetPage view="outside" />
