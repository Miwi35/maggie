import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithTheme } from '../../test/renderWithTheme'
import { PeriodPlanStep } from './PeriodPlanStep'
import type { PeriodPlan, PlanCategory } from './usePeriodPlan'
import type { PlanningPeriodRef } from './useReviewProgress'

const QUARTER: PlanningPeriodRef = { key: '2027-Q2', level: 'quarter', from: '2027-04', to: '2027-06', months: 3 }
const YEAR: PlanningPeriodRef = { key: '2027', level: 'year', from: '2027-01', to: '2027-12', months: 12 }
const SOURCE: PlanningPeriodRef = { key: '2027-Q1', level: 'quarter', from: '2027-01', to: '2027-03', months: 3 }

const category = (overrides: Partial<PlanCategory>): PlanCategory => ({
  categoryId: 'c',
  categoryName: 'X',
  currency: 'EUR',
  lastYear: { consumedCents: 0 },
  envelopeCents: null,
  decidedCents: 0,
  suggestedCents: 0,
  suggestedMonthlyCents: 0,
  plannedEvents: [],
  ...overrides,
})

const COURSES = category({
  categoryId: 'c-courses',
  categoryName: 'Courses',
  lastYear: { consumedCents: 120000 },
  suggestedCents: 120000,
  suggestedMonthlyCents: 40000,
})
const LOISIRS = category({
  categoryId: 'c-loisirs',
  categoryName: 'Loisirs',
  lastYear: { consumedCents: 30000 },
  envelopeCents: 36000,
  suggestedCents: 30000,
  suggestedMonthlyCents: 10000,
})
const SILENT = category({ categoryId: 'c-silent', categoryName: 'Silencieuse' })

const plan = (period: PlanningPeriodRef, categories: PlanCategory[]): PeriodPlan => ({
  period,
  sourcePeriod: period.level === 'year' ? { ...YEAR, key: '2026', from: '2026-01', to: '2026-12' } : SOURCE,
  totalSuggestedCents: 0,
  totalEnvelopedCents: 0,
  categories,
})

const fetchMock = vi.fn()
let served: PeriodPlan
let postAnswer: () => Promise<Partial<Response>>
let getFails = false

const posts = () => fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
const postedBody = () => JSON.parse(posts()[0][1].body as string)

describe('PeriodPlanStep', () => {
  beforeEach(() => {
    served = plan(QUARTER, [COURSES, LOISIRS, SILENT])
    getFails = false
    postAnswer = async () => ({ ok: true, json: async () => ({ success: true }) })
    fetchMock.mockImplementation(async (_url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return postAnswer()
      if (getFails) return { ok: false, json: async () => ({}) }

      return { ok: true, json: async () => served }
    })
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('reads the plan of the period and proposes one line per category that has something', async () => {
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)

    const rows = await screen.findAllByTestId('plan-row')
    expect(rows.map((row) => row.dataset.category)).toEqual(['Courses', 'Loisirs'])
    expect(fetchMock).toHaveBeenCalledWith('/api/finance/annual-plan?period=2027-Q2', expect.anything())
    expect(screen.getByText(/avril à juin 2027/)).toBeInTheDocument()
    // Proposed per month from the quarter behind; an envelope already set shows its own monthly amount.
    expect(screen.getByLabelText('Montant par mois Courses')).toHaveValue(400)
    expect(screen.getByLabelText('Montant par mois Loisirs')).toHaveValue(120)
    expect(rows[1]).toHaveAttribute('data-set', 'true')
  })

  test('applies the proposed plan in one gesture, leaving alone the envelope already set', async () => {
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)
    await screen.findAllByTestId('plan-row')
    expect(screen.getByTestId('plan-count')).toHaveTextContent('1 ligne à écrire')

    await userEvent.click(screen.getByRole('button', { name: 'Appliquer le plan' }))

    expect(await screen.findByTestId('plan-applied')).toBeInTheDocument()
    expect(posts()).toHaveLength(1)
    expect(posts()[0][0]).toBe('/api/finance/annual-plan')
    expect(postedBody()).toEqual({
      period: '2027-Q2',
      items: [{ kind: 'monthly', categoryId: 'c-courses', amountCents: 40000, currency: 'EUR' }],
    })
  })

  test('an edited amount and a one-off envelope are part of the plan', async () => {
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)
    await screen.findAllByTestId('plan-row')

    const courses = screen.getByLabelText('Montant par mois Courses')
    await userEvent.clear(courses)
    await userEvent.type(courses, '425.5')
    const loisirs = screen.getByLabelText('Montant par mois Loisirs')
    await userEvent.clear(loisirs)
    await userEvent.type(loisirs, '90')
    await userEvent.type(screen.getByLabelText('Nom'), 'Escapade')
    await userEvent.type(screen.getByLabelText('Montant'), '450')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))
    expect(within(screen.getByTestId('plan-one-offs')).getByTestId('plan-one-off')).toHaveTextContent('Escapade')

    await userEvent.click(screen.getByRole('button', { name: 'Appliquer le plan' }))

    await screen.findByTestId('plan-applied')
    expect(postedBody().items).toEqual([
      { kind: 'monthly', categoryId: 'c-courses', amountCents: 42550, currency: 'EUR' },
      { kind: 'monthly', categoryId: 'c-loisirs', amountCents: 9000, currency: 'EUR' },
      { kind: 'one_off', name: 'Escapade', amountCents: 45000, currency: 'EUR' },
    ])
  })

  test('a one-off envelope can be taken back before applying', async () => {
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)
    await screen.findAllByTestId('plan-row')
    await userEvent.type(screen.getByLabelText('Nom'), 'Escapade')
    await userEvent.type(screen.getByLabelText('Montant'), '450')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    await userEvent.click(screen.getByRole('button', { name: 'Retirer Escapade' }))

    expect(screen.queryByTestId('plan-one-off')).not.toBeInTheDocument()
    expect(screen.getByTestId('plan-count')).toHaveTextContent('1 ligne à écrire')
  })

  test('a year is planned with annual envelopes', async () => {
    served = plan(YEAR, [category({ categoryId: 'c-courses', categoryName: 'Courses', suggestedCents: 480000, suggestedMonthlyCents: 40000 })])
    renderWithTheme(<PeriodPlanStep period={YEAR} />)

    expect(await screen.findByLabelText('Montant par an Courses')).toHaveValue(4800)
    await userEvent.click(screen.getByRole('button', { name: 'Appliquer le plan' }))

    await screen.findByTestId('plan-applied')
    expect(postedBody()).toEqual({
      period: '2027',
      items: [{ kind: 'annual', categoryId: 'c-courses', amountCents: 480000, currency: 'EUR' }],
    })
  })

  test('a refused plan says which line and that nothing was written, and can be applied again', async () => {
    postAnswer = async () => ({
      ok: false,
      json: async () => ({ error: 'Category not found.', index: 1 }),
    })
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)
    await screen.findAllByTestId('plan-row')
    await userEvent.type(screen.getByLabelText('Nom'), 'Escapade')
    await userEvent.type(screen.getByLabelText('Montant'), '450')
    await userEvent.click(screen.getByRole('button', { name: 'Ajouter' }))

    await userEvent.click(screen.getByRole('button', { name: 'Appliquer le plan' }))

    const alert = await screen.findByTestId('plan-error')
    expect(alert).toHaveTextContent('Ligne « Escapade » refusée : Category not found. Rien n\'a été écrit.')
    expect(screen.queryByTestId('plan-applied')).not.toBeInTheDocument()
    // The lines are still there, to be corrected.
    expect(screen.getByTestId('plan-one-off')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Appliquer le plan' })).toBeEnabled()
  })

  test('a plan the network lost says so', async () => {
    postAnswer = async () => {
      throw new Error('offline')
    }
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)
    await screen.findAllByTestId('plan-row')

    await userEvent.click(screen.getByRole('button', { name: 'Appliquer le plan' }))

    expect(await screen.findByTestId('plan-error')).toHaveTextContent("Le plan n'a pas pu être appliqué. Rien n'a été écrit.")
  })

  test('nothing to propose says so, and the apply button waits for a line', async () => {
    served = plan(QUARTER, [SILENT])
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)

    expect(await screen.findByTestId('plan-empty')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Appliquer le plan' })).toBeDisabled()
  })

  test('a plan that cannot be read says so and can be read again', async () => {
    getFails = true
    renderWithTheme(<PeriodPlanStep period={QUARTER} />)

    expect(await screen.findByText("Le plan n'a pas pu être chargé.")).toBeInTheDocument()
    getFails = false
    await userEvent.click(screen.getByRole('button', { name: 'Réessayer' }))

    await waitFor(() => expect(screen.getAllByTestId('plan-row')).toHaveLength(2))
  })
})
