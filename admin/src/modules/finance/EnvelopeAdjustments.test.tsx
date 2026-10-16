import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EnvelopeAdjustments } from './EnvelopeAdjustments'
import type { AdjustmentLine, EnvelopeAdjustments as Payload } from './useEnvelopeAdjustments'

vi.mock('../../hooks/useMercure', () => ({ useMercure: () => undefined }))

const line = (overrides: Partial<AdjustmentLine>): AdjustmentLine => ({
  envelopeId: 'e',
  categoryId: 'c',
  categoryName: 'X',
  categoryPath: 'X',
  currency: 'EUR',
  amountCents: 10000,
  actualCents: 10000,
  targetEnvelopeId: 't',
  targetAmountCents: 10000,
  suggestion: null,
  ...overrides,
})

const LOISIRS = line({
  envelopeId: 'e-loisirs',
  categoryId: 'c-loisirs',
  categoryName: 'Loisirs',
  categoryPath: 'Loisirs',
  amountCents: 15000,
  actualCents: 20000,
  targetAmountCents: 15000,
  suggestion: { proposedCents: 20000, direction: 'raise', reason: 'Dépassée trois mois de suite', seasonal: null },
})
const COURSES = line({ categoryId: 'c-courses', categoryName: 'Courses', categoryPath: 'Courses', amountCents: 40000, actualCents: 39000, targetAmountCents: 40000 })
const CADEAUX = line({
  categoryId: 'c-cadeaux',
  categoryName: 'Cadeaux',
  categoryPath: 'Cadeaux',
  amountCents: 7000,
  actualCents: 7000,
  targetAmountCents: 7000,
  suggestion: { proposedCents: 12000, direction: 'raise', reason: 'Plus chère chaque octobre', seasonal: { fromMonth: 10, toMonth: 11 } },
})

const payload = (envelopes: AdjustmentLine[]): Payload => ({
  month: '2026-09',
  targetMonth: '2026-10',
  suggestionCount: envelopes.filter((e) => e.suggestion).length,
  envelopes,
})

const fetchMock = vi.fn()
let served: Payload
let postAnswer: () => Promise<Partial<Response>>

const posts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
const postedBody = (index = 0) => JSON.parse(posts()[index][1].body as string)

const row = async (category: string) =>
  (await screen.findAllByTestId('adjustment-row')).find((r) => r.dataset.category === category) as HTMLElement

describe('EnvelopeAdjustments', () => {
  beforeEach(() => {
    served = payload([LOISIRS, COURSES, CADEAUX])
    postAnswer = async () => ({ ok: true, json: async () => ({ success: true, applied: [] }) })
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'POST' ? postAnswer() : Promise.resolve({ ok: true, json: async () => served }),
    )
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('reads the month asked for and shows the actual and the next amount of each envelope', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)

    const loisirs = await row('Loisirs')
    expect(fetchMock.mock.calls[0][0]).toBe('/api/finance/envelope-adjustments?month=2026-09')
    expect(within(loisirs).getByTestId('adjustment-actual')).toHaveTextContent(/200,00.*150,00/)
    expect(within(loisirs).getByTestId('adjustment-amount')).toHaveTextContent('150,00')
    expect(screen.getByRole('heading', { name: /Octobre 2026/ })).toBeInTheDocument()
  })

  test('shows a suggestion with its reason on an unfit envelope only, and the season when there is one', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)

    const loisirs = await row('Loisirs')
    expect(within(loisirs).getByTestId('adjustment-suggestion')).toHaveTextContent(/Relever à 200,00/)
    expect(within(loisirs).getByText('Dépassée trois mois de suite')).toBeInTheDocument()
    expect(within(loisirs).queryByText(/saison/)).not.toBeInTheDocument()

    const courses = await row('Courses')
    expect(within(courses).queryByTestId('adjustment-suggestion')).not.toBeInTheDocument()
    expect(within(courses).queryByRole('button', { name: /Appliquer/ })).not.toBeInTheDocument()

    const cadeaux = await row('Cadeaux')
    expect(within(cadeaux).getByTestId('adjustment-suggestion')).toHaveTextContent(/saison oct.–nov./)
  })

  test('writes nothing until a gesture asks for it', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    const loisirs = await row('Loisirs')

    await userEvent.click(within(loisirs).getByRole('button', { name: 'Augmenter Loisirs' }))

    expect(posts()).toHaveLength(0)
  })

  test('applies one suggestion with its amount, and marks it applied', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    const loisirs = await row('Loisirs')

    await userEvent.click(within(loisirs).getByRole('button', { name: 'Appliquer pour Loisirs' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(posts()[0][0]).toBe('/api/finance/envelope-adjustments')
    expect(postedBody()).toEqual({ month: '2026-09', adjustments: [{ categoryId: 'c-loisirs', amountCents: 20000, seasonal: false }] })
    expect(await within(loisirs).findByTestId('adjustment-applied')).toBeInTheDocument()
    // The list is read again after the write.
    expect(fetchMock.mock.calls.filter(([, init]) => !init?.method)).toHaveLength(2)
  })

  test('flags a seasonal suggestion as seasonal', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    const cadeaux = await row('Cadeaux')

    await userEvent.click(within(cadeaux).getByRole('button', { name: 'Appliquer pour Cadeaux' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(postedBody().adjustments).toEqual([{ categoryId: 'c-cadeaux', amountCents: 12000, seasonal: true }])
  })

  test('« Tout appliquer » posts every suggestion in one call', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    await row('Loisirs')

    await userEvent.click(screen.getByRole('button', { name: 'Tout appliquer (2)' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(postedBody().adjustments).toEqual([
      { categoryId: 'c-loisirs', amountCents: 20000, seasonal: false },
      { categoryId: 'c-cadeaux', amountCents: 12000, seasonal: true },
    ])
    expect(await screen.findByRole('button', { name: 'Tout appliquer (0)' })).toBeDisabled()
  })

  test('± moves the amount locally and « Enregistrer » posts it', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    const courses = await row('Courses')
    expect(screen.getByRole('button', { name: 'Enregistrer (0)' })).toBeDisabled()

    await userEvent.click(within(courses).getByRole('button', { name: 'Augmenter Courses' }))
    await userEvent.click(within(courses).getByRole('button', { name: 'Augmenter Courses' }))
    await userEvent.click(within(courses).getByRole('button', { name: 'Diminuer Courses' }))

    expect(within(courses).getByTestId('adjustment-amount')).toHaveTextContent('405,00')
    expect(posts()).toHaveLength(0)

    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer (1)' }))

    await waitFor(() => expect(posts()).toHaveLength(1))
    expect(postedBody()).toEqual({ month: '2026-09', adjustments: [{ categoryId: 'c-courses', amountCents: 40500, seasonal: false }] })
  })

  test('the slider moves the amount too', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    const courses = await row('Courses')

    const slider = within(courses).getByRole('slider', { name: 'Montant de Courses' })
    act(() => slider.focus())
    await userEvent.keyboard('{ArrowRight}')

    expect(within(courses).getByTestId('adjustment-amount')).toHaveTextContent('405,00')
  })

  test('stepping back to the stored amount leaves nothing to save', async () => {
    render(<EnvelopeAdjustments month="2026-09" />)
    const courses = await row('Courses')

    await userEvent.click(within(courses).getByRole('button', { name: 'Augmenter Courses' }))
    await userEvent.click(within(courses).getByRole('button', { name: 'Diminuer Courses' }))

    expect(screen.getByRole('button', { name: 'Enregistrer (0)' })).toBeDisabled()
  })

  test('a refusal shows the API message and marks nothing applied', async () => {
    postAnswer = async () => ({ ok: false, status: 400, json: async () => ({ error: 'Each adjustment needs a categoryId' }) })
    render(<EnvelopeAdjustments month="2026-09" />)
    const loisirs = await row('Loisirs')

    await userEvent.click(within(loisirs).getByRole('button', { name: 'Appliquer pour Loisirs' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Each adjustment needs a categoryId')
    expect(within(loisirs).queryByTestId('adjustment-applied')).not.toBeInTheDocument()
    expect(within(loisirs).getByRole('button', { name: 'Appliquer pour Loisirs' })).toBeEnabled()
  })

  test('a network failure shows a message, keeps the manual changes and marks nothing applied', async () => {
    postAnswer = () => Promise.reject(new Error('offline'))
    render(<EnvelopeAdjustments month="2026-09" />)
    const courses = await row('Courses')

    await userEvent.click(within(courses).getByRole('button', { name: 'Augmenter Courses' }))
    await userEvent.click(screen.getByRole('button', { name: 'Enregistrer (1)' }))

    expect(await screen.findByRole('alert')).toHaveTextContent("Impossible d'enregistrer les montants.")
    expect(within(courses).getByTestId('adjustment-amount')).toHaveTextContent('405,00')
    expect(screen.getByRole('button', { name: 'Enregistrer (1)' })).toBeEnabled()
  })

  test('says so when the list cannot be read', async () => {
    fetchMock.mockImplementation(() => Promise.resolve({ ok: false, json: async () => ({ error: 'boom' }) }))
    render(<EnvelopeAdjustments month="2026-09" />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Impossible de lire les enveloppes.')
  })
})
