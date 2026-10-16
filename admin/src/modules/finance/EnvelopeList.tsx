import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import TuneIcon from '@mui/icons-material/Tune'
import { useRefresh } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { BudgetStatusPanel } from './BudgetStatusPanel'
import { EnvelopeAdjustments } from './EnvelopeAdjustments'
import { BUDGET_TOPICS } from './useEnvelopeAdjustments'
import { EnvelopeTreeScreen } from './EnvelopeTreeScreen'
import { ToQualifyPill } from './ToQualifyPill'
import { useBudgetStatus } from './useBudgetStatus'

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
 * Finance → Budgets: the period's gauges, then the envelope tree to configure
 * them. One period for both: the tree edits the envelope the gauges show.
 */
export const EnvelopeList = () => {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const { status, loading, refresh } = useBudgetStatus(year, month)
  const refreshRecords = useRefresh()
  const [scoresToken, setScoresToken] = useState(0)
  const [adjusting, setAdjusting] = useState(false)

  useMercure(BUDGET_TOPICS, (data) => {
    refresh()
    setScoresToken((token) => token + 1)
    if (touchesRecords(data)) refreshRecords()
  })

  return (
    <>
      <BudgetStatusPanel
        year={year}
        month={month}
        onYearChange={setYear}
        onMonthChange={setMonth}
        status={status}
        loading={loading}
        refresh={refresh}
        scoresToken={scoresToken}
      />
      <ToQualifyPill />
      <Box sx={{ display: 'flex', mb: 2 }}>
        <Button
          size="small"
          variant={adjusting ? 'contained' : 'outlined'}
          startIcon={<TuneIcon />}
          aria-pressed={adjusting}
          onClick={() => setAdjusting((on) => !on)}
        >
          Ajuster
        </Button>
      </Box>
      {adjusting && (
        <Card sx={{ mb: 2 }}>
          <CardContent>
            <EnvelopeAdjustments key={`${year}-${month}`} month={`${year}-${String(month).padStart(2, '0')}`} />
          </CardContent>
        </Card>
      )}
      <EnvelopeTreeScreen year={year} month={month} status={status} loading={loading} refresh={refresh} />
    </>
  )
}
