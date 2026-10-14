import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { RecurrenceSection } from './RecurrenceSection'

const SERIES = [
  { id: '/api/recurring_operations/01FLIXO', label: 'Flixo' },
  { id: '/api/recurring_operations/01LOYER', label: 'Loyer' },
]

const LINE = { id: '/api/transactions/01TX', label: 'PRLV FLIXO', amountCents: -1349, bookedAt: '2026-10-12T00:00:00+00:00' }

const fetchMock = vi.fn()
let refuse = false
const getOne = vi.fn()

const patches = () =>
  fetchMock.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === 'PATCH')
    .map(([url, init]) => ({ url, body: JSON.parse((init as RequestInit).body as string) }))

const renderSection = (record: Record<string, unknown>) =>
  render(
    <AdminContext
      dataProvider={testDataProvider({
        getList: (() => Promise.resolve({ data: SERIES, total: SERIES.length })) as unknown as DataProvider['getList'],
        getOne: getOne as unknown as DataProvider['getOne'],
      })}
    >
      <RecurrenceSection record={record as never} />
      <Notification />
    </AdminContext>,
  )

describe('RecurrenceSection', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    refuse = false
    fetchMock.mockImplementation(() => Promise.resolve({ ok: !refuse, status: refuse ? 422 : 200, json: async () => ({}) } as Response))
    getOne.mockImplementation((resource: string) =>
      Promise.resolve({
        data:
          resource === 'recurring_operations'
            ? SERIES[0]
            : { ...LINE, recurringOperation: SERIES[1].id, recurringOccurrenceOn: '2026-10-12' },
      }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
    getOne.mockReset()
  })

  test('a line in a series names it, gives the due date and offers to detach it', async () => {
    renderSection({ ...LINE, recurringOperation: SERIES[0].id, recurringOccurrenceOn: '2026-10-12' })

    expect(await screen.findByText('Flixo')).toBeInTheDocument()
    expect(screen.getByText(/Échéance du/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Détacher de la série' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rattacher à cette série' })).not.toBeInTheDocument()
  })

  test('detaching acts at once, clears both series fields and offers to attach again', async () => {
    const user = userEvent.setup()
    renderSection({ ...LINE, recurringOperation: SERIES[0].id, recurringOccurrenceOn: '2026-10-12' })

    await user.click(await screen.findByRole('button', { name: 'Détacher de la série' }))

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(patches()[0]).toEqual({
      url: '/api/transactions/01TX',
      body: { recurringOperation: null, recurringOccurrenceOn: null },
    })
    expect(await screen.findByText(/n’est plus rattachée/)).toBeInTheDocument()
    expect(await screen.findByLabelText('Opération récurrente existante')).toBeInTheDocument()
  })

  test('a refused detach says so and keeps the series', async () => {
    refuse = true
    const user = userEvent.setup()
    renderSection({ ...LINE, recurringOperation: SERIES[0].id })

    await user.click(await screen.findByRole('button', { name: 'Détacher de la série' }))

    expect(await screen.findByText('Impossible de détacher cette transaction')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Détacher de la série' })).toBeInTheDocument()
  })

  test('a free line attaches to an existing series, written at once', async () => {
    const user = userEvent.setup()
    renderSection({ ...LINE, recurringOperation: null })
    expect(await screen.findByText(/n’appartient à aucune opération récurrente/)).toBeInTheDocument()
    const attach = await screen.findByRole('button', { name: 'Rattacher à cette série' })
    expect(attach).toBeDisabled()

    await user.click(await screen.findByLabelText('Opération récurrente existante'))
    await user.click(await screen.findByRole('option', { name: 'Loyer' }))
    await user.click(attach)

    await waitFor(() => expect(patches()).toHaveLength(1))
    expect(patches()[0].body).toEqual({ recurringOperation: '/api/recurring_operations/01LOYER' })
    expect(await screen.findByText(/est rattachée à « Loyer »/)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Détacher de la série' })).toBeInTheDocument()
  })

  test('a free line offers to make a series of it', async () => {
    renderSection({ ...LINE, recurringOperation: null })

    expect(await screen.findByRole('button', { name: 'En faire une opération récurrente' })).toBeInTheDocument()
  })

  test('a record that arrives fresher than the one the sheet opened on is followed', async () => {
    const view = (record: Record<string, unknown>) => (
      <AdminContext
        dataProvider={testDataProvider({
          getList: (() => Promise.resolve({ data: SERIES, total: SERIES.length })) as unknown as DataProvider['getList'],
          getOne: getOne as unknown as DataProvider['getOne'],
        })}
      >
        <RecurrenceSection record={record as never} />
      </AdminContext>
    )
    const { rerender } = render(view({ ...LINE, recurringOperation: null }))
    expect(await screen.findByText(/n’appartient à aucune opération récurrente/)).toBeInTheDocument()

    rerender(view({ ...LINE, recurringOperation: SERIES[0].id, recurringOccurrenceOn: '2026-10-12' }))

    expect(await screen.findByRole('button', { name: 'Détacher de la série' })).toBeInTheDocument()
  })
})
