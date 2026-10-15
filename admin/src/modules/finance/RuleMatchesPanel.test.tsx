import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { RuleMatchesPanel } from './RuleMatchesPanel'
import type { RulePreviewCriteria } from './useCategorizationRulePreview'

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({ data: [{ id: '/api/categories/1', name: 'Alimentation' }], total: 1 })) as unknown as DataProvider['getList'],
})

const CRITERIA: RulePreviewCriteria = {
  labelPattern: 'CARREFOUR',
  matchType: 'contains',
  direction: 'debit',
  minAmountCents: null,
  maxAmountCents: null,
  categoryId: '1',
  priority: 0,
  isActive: true,
  ruleId: null,
}

const MATCH = {
  transactionId: 't1',
  label: 'CARREFOUR MARKET 123',
  amountCents: -4250,
  bookedAt: '2026-10-02',
  currentCategoryId: null,
  wouldChange: true,
}

const fetchMock = vi.fn()
const respond = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body } as Response)
const renderPanel = (criteria: RulePreviewCriteria, onChangeCount?: (n: number | null) => void) => {
  const ui = (c: RulePreviewCriteria) => (
    <AdminContext dataProvider={dataProvider}>
      <RuleMatchesPanel criteria={c} onChangeCount={onChangeCount} />
    </AdminContext>
  )
  const view = render(ui(criteria))

  return { rerender: (c: RulePreviewCriteria) => view.rerender(ui(c)) }
}

describe('RuleMatchesPanel', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('lists the transactions the given criteria catch, and what would change', async () => {
    fetchMock.mockImplementation(() => respond(true, { total: 1, changeCount: 1, matches: [MATCH] }))
    const onChangeCount = vi.fn()

    renderPanel(CRITERIA, onChangeCount)

    expect(await screen.findByText('CARREFOUR MARKET 123')).toBeInTheDocument()
    expect(screen.getByText('Sans catégorie → Alimentation')).toBeInTheDocument()
    expect(JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string)).toMatchObject({
      labelPattern: 'CARREFOUR',
      direction: 'debit',
      categoryId: '1',
    })
    await waitFor(() => expect(onChangeCount).toHaveBeenLastCalledWith(1), { timeout: 5000 })
  })

  test('the list follows the pattern when the criteria change', async () => {
    fetchMock.mockImplementation((_url: string, init: RequestInit) => {
      const { labelPattern } = JSON.parse(init.body as string) as { labelPattern: string }

      return respond(true, {
        total: 1,
        changeCount: 0,
        matches: [{ ...MATCH, transactionId: labelPattern, label: `LIGNE ${labelPattern}`, wouldChange: false }],
      })
    })
    const { rerender } = renderPanel(CRITERIA)
    expect(await screen.findByText('LIGNE CARREFOUR')).toBeInTheDocument()

    rerender({ ...CRITERIA, labelPattern: 'LECLERC' })

    expect(await screen.findByText('LIGNE LECLERC')).toBeInTheDocument()
    expect(screen.queryByText('LIGNE CARREFOUR')).not.toBeInTheDocument()
  })

  test('without a pattern it asks for one and calls nothing', () => {
    renderPanel({ ...CRITERIA, labelPattern: '' })

    expect(screen.getByText(/Saisissez le texte à reconnaître/)).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('an API refusal is shown as it came', async () => {
    fetchMock.mockImplementation(() => respond(false, { error: 'Motif invalide.' }))

    renderPanel(CRITERIA)

    expect(await screen.findByText(/Motif invalide\./)).toBeInTheDocument()
  })
})
