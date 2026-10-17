import { describe, test, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { TransactionPills, transactionPills } from './TransactionPills'

const ORDINARY = {
  id: '/api/transactions/01TX',
  label: 'Courses',
  amountCents: -2000,
  transferKind: 'none',
  category: '/api/categories/01C',
  recurringOperation: null,
}

describe('transactionPills', () => {
  test('a movement between the owner’s accounts is « internal », named by its purpose', () => {
    expect(transactionPills({ ...ORDINARY, transferKind: 'internal' })).toEqual([{ kind: 'internal', label: 'Virement interne' }])
    expect(transactionPills({ ...ORDINARY, transferKind: 'advance' })).toEqual([{ kind: 'internal', label: 'Avance d’épargne' }])
    expect(transactionPills({ ...ORDINARY, transferKind: 'repayment' })[0]).toEqual({
      kind: 'internal',
      label: 'Remboursement d’avance',
    })
    expect(transactionPills({ ...ORDINARY, transferKind: 'funding' })[0]).toEqual({ kind: 'internal', label: 'Approvisionnement' })
  })

  test('a rejection is « rejected »: « Rejeté » on the debit, « Rejet » on the credit', () => {
    expect(transactionPills({ ...ORDINARY, transferKind: 'rejected', amountCents: -8000 })).toEqual([
      { kind: 'rejected', label: 'Rejeté' },
    ])
    expect(transactionPills({ ...ORDINARY, transferKind: 'rejected', amountCents: 8000 })).toEqual([
      { kind: 'rejected', label: 'Rejet' },
    ])
  })

  test('a line of a series is « series », named once the series is read', () => {
    const record = { ...ORDINARY, recurringOperation: '/api/recurring_operations/01S' }

    expect(transactionPills(record)).toEqual([{ kind: 'series', label: 'Série' }])
    expect(transactionPills(record, 'Flixo')).toEqual([{ kind: 'series', label: 'Série · Flixo' }])
  })

  test('« to_sort » is only for an ordinary line with no category', () => {
    expect(transactionPills({ ...ORDINARY, category: null })).toEqual([{ kind: 'to_sort', label: 'À trier' }])
    expect(transactionPills({ ...ORDINARY, category: undefined, transferKind: undefined })).toEqual([
      { kind: 'to_sort', label: 'À trier' },
    ])
    expect(transactionPills(ORDINARY)).toEqual([])
    expect(transactionPills({ ...ORDINARY, category: null, transferKind: 'internal' }).map((p) => p.kind)).toEqual(['internal'])
    expect(transactionPills({ ...ORDINARY, category: null, transferKind: 'rejected' }).map((p) => p.kind)).toEqual(['rejected'])
  })

  test('a shared spend is « Partagée » (MAG-451), after its series', () => {
    expect(transactionPills({ ...ORDINARY, myShareCents: 20000 })).toEqual([{ kind: 'shared', label: 'Partagée' }])
    expect(transactionPills({ ...ORDINARY, myShareCents: 0 }).map((p) => p.kind)).toEqual(['shared'])
    expect(transactionPills({ ...ORDINARY, myShareCents: null })).toEqual([])
    expect(
      transactionPills({ ...ORDINARY, myShareCents: 500, recurringOperation: '/api/recurring_operations/01S' }).map((p) => p.kind),
    ).toEqual(['series', 'shared'])
  })

  test('a line of a series with no category is both, in the order of the row', () => {
    const record = { ...ORDINARY, category: null, recurringOperation: '/api/recurring_operations/01S' }

    expect(transactionPills(record).map((p) => p.kind)).toEqual(['series', 'to_sort'])
  })

  test('« shared » is never emitted yet', () => {
    const records = [
      ORDINARY,
      { ...ORDINARY, category: null },
      { ...ORDINARY, transferKind: 'internal' },
      { ...ORDINARY, transferKind: 'rejected' },
      { ...ORDINARY, recurringOperation: '/api/recurring_operations/01S' },
      { ...ORDINARY, shared: true, sharedWith: ['x'] },
    ]

    for (const record of records) {
      expect(transactionPills(record).map((p) => p.kind)).not.toContain('shared')
    }
  })
})

const LocationState = () => <pre data-testid="location">{useLocation().pathname}</pre>

const renderPills = (record: Record<string, unknown>) => {
  const getOne = vi.fn(async () => ({ data: { id: '/api/recurring_operations/01S', label: 'Abonnement Flixo' } }))
  render(
    <MemoryRouter initialEntries={['/list']}>
      <AdminContext dataProvider={testDataProvider({ getOne: getOne as unknown as DataProvider['getOne'] })}>
        <Routes>
          <Route path="/list" element={<TransactionPills record={{ ...ORDINARY, ...record }} />} />
          <Route path="*" element={<LocationState />} />
        </Routes>
      </AdminContext>
    </MemoryRouter>,
  )

  return getOne
}

describe('TransactionPills', () => {
  test('a line with nothing to say wears no pill and reads no series', () => {
    const getOne = renderPills({})

    expect(screen.queryByText(/./)).not.toBeInTheDocument()
    expect(getOne).not.toHaveBeenCalled()
  })

  test('draws the pills of the line, the series under its own name', async () => {
    renderPills({ category: null, transferKind: 'none', recurringOperation: '/api/recurring_operations/01S' })

    expect(await screen.findByText('Série · Abonnement Flixo')).toBeInTheDocument()
    expect(screen.getByText('À trier')).toBeInTheDocument()
  })

  test('a transfer reads its label and is not « À trier »', () => {
    renderPills({ category: null, transferKind: 'internal' })

    expect(screen.getByText('Virement interne')).toBeInTheDocument()
    expect(screen.queryByText('À trier')).not.toBeInTheDocument()
  })

  test('a click on a pill opens the sheet of the line, and the row does not hear it', async () => {
    const rowClick = vi.fn()
    const user = userEvent.setup()
    render(
      <MemoryRouter initialEntries={['/list']}>
        <AdminContext dataProvider={testDataProvider()}>
          <Routes>
            <Route
              path="/list"
              element={
                <div onClick={rowClick}>
                  <TransactionPills record={{ ...ORDINARY, category: null }} />
                </div>
              }
            />
            <Route path="*" element={<LocationState />} />
          </Routes>
        </AdminContext>
      </MemoryRouter>,
    )

    await user.click(screen.getByText('À trier'))

    expect(await screen.findByTestId('location')).toHaveTextContent('/transactions/%2Fapi%2Ftransactions%2F01TX')
    expect(rowClick).not.toHaveBeenCalled()
  })
})
