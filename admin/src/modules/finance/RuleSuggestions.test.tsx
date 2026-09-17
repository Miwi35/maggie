import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { RuleSuggestions } from './RuleSuggestions'

const SUGGESTIONS = [
  {
    pattern: 'CARREFOUR DAC VL',
    occurrences: 4,
    totalCents: -14515,
    direction: 'debit',
    categoryId: 'cat-food',
    categoryName: 'Nourriture',
    samples: ['PAIEMENT PAR CARTE X9633 MP*CARREFOUR DAC VL 04/08'],
  },
  {
    pattern: 'SAS JARDIS',
    occurrences: 3,
    totalCents: -7512,
    direction: 'debit',
    categoryId: null,
    categoryName: null,
    samples: ['Sas Jardis'],
  },
]

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({
      data: [
        { id: '/api/categories/cat-food', name: 'Nourriture' },
        { id: '/api/categories/cat-fuel', name: 'Essence' },
      ],
      total: 2,
    })) as unknown as DataProvider['getList'],
})

const fetchMock = vi.fn()

const renderPanel = () =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <RuleSuggestions />
      <Notification />
    </AdminContext>,
  )

describe('RuleSuggestions', () => {
  beforeEach(() => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      Promise.resolve({
        ok: true,
        json: async () =>
          init?.method === 'POST' ? { created: 1, categorized: 4 } : { suggestions: SUGGESTIONS },
      } as Response),
    )
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('each recurring merchant is offered with what it costs', async () => {
    renderPanel()

    expect(await screen.findByText('CARREFOUR DAC VL')).toBeInTheDocument()
    expect(screen.getByText('SAS JARDIS')).toBeInTheDocument()
    expect(screen.getByText('-145,15 €')).toBeInTheDocument()
  })

  test('only a merchant with a guessed heading starts ticked', async () => {
    renderPanel()
    await screen.findByText('CARREFOUR DAC VL')

    const boxes = screen.getAllByRole('checkbox')

    // The guess is an offer; the unguessed one is a question.
    expect(boxes[0]).toBeChecked()
    expect(boxes[1]).not.toBeChecked()
  })

  test('creating sends only what is kept, and says what it changed', async () => {
    renderPanel()
    await screen.findByText('CARREFOUR DAC VL')

    await userEvent.click(screen.getByRole('button', { name: /Créer 1 règle/ }))

    await waitFor(() => {
      const posted = fetchMock.mock.calls.find(
        ([, init]) => (init as RequestInit | undefined)?.method === 'POST',
      )
      expect(posted).toBeDefined()
      expect(JSON.parse((posted?.[1] as RequestInit).body as string)).toEqual({
        rules: [{ pattern: 'CARREFOUR DAC VL', categoryId: 'cat-food', direction: 'debit' }],
      })
    })

    expect(await screen.findByText(/4 opération\(s\) rangée\(s\)/)).toBeInTheDocument()
  })

  test('nothing to propose is said plainly', async () => {
    fetchMock.mockResolvedValue({ ok: true, json: async () => ({ suggestions: [] }) } as Response)

    renderPanel()

    expect(await screen.findByText(/Rien à proposer/)).toBeInTheDocument()
  })
})
