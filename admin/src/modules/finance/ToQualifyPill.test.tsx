import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { AdminContext, testDataProvider } from 'react-admin'
import { MemoryRouter } from 'react-router-dom'
import { ToQualifyPill } from './ToQualifyPill'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => undefined }))

const fetchMock = vi.fn()

const answer = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body }) as Promise<Response>

const renderPill = () =>
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()}>
        <ToQualifyPill />
      </AdminContext>
    </MemoryRouter>,
  )

describe('ToQualifyPill', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('says how many lines wait and what they weigh, and links to the page', async () => {
    fetchMock.mockImplementation(() => answer(true, { total: 3, totalCents: 5799, transactions: [] }))
    renderPill()

    const pill = await screen.findByRole('link', { name: /À qualifier · 3 · 57,99/ })

    expect(pill).toHaveAttribute('href', '/finance/review?state=unrecognised&period=all')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/finance/transactions/to-sort?limit=1')
  })

  test('shows nothing when no line waits', async () => {
    fetchMock.mockImplementation(() => answer(true, { total: 0, totalCents: 0, transactions: [] }))
    renderPill()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  test('stays out of the way when the count cannot be read', async () => {
    fetchMock.mockImplementation(() => answer(false, { error: 'Service indisponible' }))
    renderPill()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})
