import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EnvelopeSplitSlider } from './EnvelopeSplitSlider'
import type { BudgetLine } from './useBudgetStatus'

const save = vi.fn()
vi.mock('./useEnvelopeSplit', () => ({
  useEnvelopeSplit: () => ({ save, saving: false }),
}))

const line = (overrides: Partial<BudgetLine>): BudgetLine => ({
  id: 'x',
  categoryId: 'c',
  categoryName: 'X',
  categoryPath: 'X',
  parentEnvelopeId: null,
  mode: 'monthly',
  amountCents: 10000,
  currency: 'EUR',
  year: 2026,
  month: 10,
  spentCents: 0,
  committedCents: 0,
  plannedCents: 0,
  toArbitrateCents: 0,
  allocatedToChildrenCents: null,
  freeCents: null,
  consumedCents: 0,
  remainingCents: 0,
  availableCents: 0,
  isOverspent: false,
  isOvercommitted: false,
  ...overrides,
})

const loisirs = line({ id: 'loisirs', categoryName: 'Loisirs', amountCents: 30000, allocatedToChildrenCents: 15000, freeCents: 15000 })
const bars = line({ id: 'bars', categoryName: 'Bars', parentEnvelopeId: 'loisirs', amountCents: 10000 })
const cinema = line({ id: 'cinema', categoryName: 'Cinéma', parentEnvelopeId: 'loisirs', amountCents: 5000 })

const amountOf = (category: string) =>
  screen.getAllByTestId('split-amount').find((el) => el.getAttribute('data-category') === category)!

const renderSlider = (onSaved = vi.fn()) => {
  render(<EnvelopeSplitSlider mother={loisirs} daughters={[bars, cinema]} onSaved={onSaved} />)

  return onSaved
}

describe('EnvelopeSplitSlider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  test('shows a segment per daughter, the free part and the amounts', () => {
    renderSlider()

    expect(screen.getAllByTestId('split-segment').map((s) => s.getAttribute('data-category'))).toEqual([
      'Bars',
      'Cinéma',
      'libre',
    ])
    expect(amountOf('Bars')).toHaveTextContent(/Bars 100,00\s€/)
    expect(amountOf('Cinéma')).toHaveTextContent(/Cinéma 50,00\s€/)
    expect(amountOf('libre')).toHaveTextContent(/Libre 150,00\s€/)
    expect(screen.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' })).toBeInTheDocument()
    expect(screen.getByRole('slider', { name: 'Frontière entre Cinéma et libre' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })

  test('the keyboard moves a boundary, Shift by ten euros', async () => {
    const user = userEvent.setup()
    renderSlider()

    screen.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' }).focus()
    await user.keyboard('{Shift>}{ArrowRight}{ArrowRight}{/Shift}')

    expect(amountOf('Bars')).toHaveTextContent(/120,00\s€/)
    expect(amountOf('Cinéma')).toHaveTextContent(/30,00\s€/)
    expect(amountOf('libre')).toHaveTextContent(/150,00\s€/)
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeEnabled()
  })

  test('dragging a handle moves the boundary by the distance travelled', () => {
    renderSlider()
    const bar = screen.getByTestId('envelope-split').firstElementChild as HTMLElement
    bar.getBoundingClientRect = () => ({ width: 300 }) as DOMRect
    const handle = screen.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' })

    fireEvent.pointerDown(handle, { clientX: 100, pointerId: 1 })
    fireEvent.pointerMove(handle, { clientX: 120, pointerId: 1 })
    fireEvent.pointerUp(handle, { pointerId: 1 })
    // Further moves after the release change nothing.
    fireEvent.pointerMove(handle, { clientX: 200, pointerId: 1 })

    // 20 px of 300 px for 300 € = 20 €.
    expect(amountOf('Bars')).toHaveTextContent(/120,00\s€/)
    expect(amountOf('Cinéma')).toHaveTextContent(/30,00\s€/)
  })

  test('saves every daughter at once, then tells the screen', async () => {
    save.mockResolvedValue({ ok: true })
    const user = userEvent.setup()
    const onSaved = renderSlider()

    screen.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' }).focus()
    await user.keyboard('{Shift>}{ArrowRight}{ArrowRight}{/Shift}')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(save).toHaveBeenCalledWith('loisirs', [
      { envelopeId: 'bars', amountCents: 12000 },
      { envelopeId: 'cinema', amountCents: 3000 },
    ])
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
  })

  test('says the split was refused and goes back to the saved amounts', async () => {
    save.mockResolvedValue({ ok: false, refused: true })
    const user = userEvent.setup()
    const onSaved = renderSlider()

    screen.getByRole('slider', { name: 'Frontière entre Cinéma et libre' }).focus()
    await user.keyboard('{Shift>}{ArrowRight}{/Shift}')
    await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/rien n'a été modifié/)
    expect(amountOf('Cinéma')).toHaveTextContent(/50,00\s€/)
    expect(onSaved).not.toHaveBeenCalled()
  })

  test('cancel puts the saved amounts back', async () => {
    const user = userEvent.setup()
    renderSlider()

    screen.getByRole('slider', { name: 'Frontière entre Bars et Cinéma' }).focus()
    await user.keyboard('{ArrowRight}')
    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    expect(amountOf('Bars')).toHaveTextContent(/100,00\s€/)
    expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeDisabled()
  })
})
