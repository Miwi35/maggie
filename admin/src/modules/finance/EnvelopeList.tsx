import { useState } from 'react'
import { useRefresh } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { BudgetStatusPanel } from './BudgetStatusPanel'
import { EnvelopeTreeScreen } from './EnvelopeTreeScreen'
import { useBudgetStatus } from './useBudgetStatus'

// Spends move the gauges, envelopes and categories move the tree itself.
const BUDGET_TOPICS = ['/api/envelopes/{id}', '/api/transactions/{id}', '/api/categories/{id}']

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
      <EnvelopeTreeScreen year={year} month={month} status={status} loading={loading} refresh={refresh} />
    </>
  )
}
