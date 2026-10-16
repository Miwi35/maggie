import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FinanceScoresCard, FinanceScoresSection } from './FinanceScoresSection'
import { scores } from './scoresFixture'

const answer = (body: unknown, ok = true) => ({ ok, status: ok ? 200 : 500, json: () => Promise.resolve(body) })

describe('FinanceScoresSection', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  test('draws the rings and the curve once the scores are loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(scores())))

    render(<FinanceScoresSection year={2020} month={2} />)

    expect(await screen.findAllByTestId('score-ring')).toHaveLength(2)
    expect(screen.getByTestId('rhythm-curve')).toBeInTheDocument()
    expect(screen.getByTestId('debit-marker')).toHaveAttribute('data-label', 'Loyer')
  })

  test('says so when the API fails, and retries on demand', async () => {
    const user = userEvent.setup()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValueOnce(answer({}, false)).mockResolvedValue(answer(scores())),
    )

    render(<FinanceScoresSection year={2020} month={2} />)

    expect(await screen.findByText("Les scores n'ont pas pu être chargés.")).toBeInTheDocument()
    expect(screen.queryByTestId('score-ring')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await screen.findAllByTestId('score-ring')).toHaveLength(2)
    expect(screen.queryByText("Les scores n'ont pas pu être chargés.")).not.toBeInTheDocument()
  })

  test('reloads when the host page bumps the refresh token, and not otherwise', async () => {
    const fetchMock = vi.fn().mockResolvedValue(answer(scores()))
    vi.stubGlobal('fetch', fetchMock)

    const { rerender } = render(<FinanceScoresSection year={2020} month={2} refreshToken={0} />)
    await screen.findAllByTestId('score-ring')

    rerender(<FinanceScoresSection year={2020} month={2} refreshToken={0} />)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    rerender(<FinanceScoresSection year={2020} month={2} refreshToken={1} />)

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
  })

  test('switches the curve between the week and the month', async () => {
    const user = userEvent.setup()
    const data = scores()
    data.week.rhythm.debits = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(answer(data)))

    render(<FinanceScoresSection year={2020} month={2} />)
    expect(await screen.findByTestId('debit-marker')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Semaine' }))

    expect(screen.queryByTestId('debit-marker')).not.toBeInTheDocument()
  })

  test('the card draws the scores it is given, without fetching', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const data = scores()
    data.week.rhythm.debits = []

    render(<FinanceScoresCard scores={data} />)

    expect(screen.getAllByTestId('score-ring')).toHaveLength(2)
    expect(screen.getByTestId('debit-marker')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Semaine' }))
    expect(screen.queryByTestId('debit-marker')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  test('the card offers a retry only when the host gives one', async () => {
    const user = userEvent.setup()
    const retry = vi.fn()

    const { rerender } = render(<FinanceScoresCard scores={null} error />)
    expect(screen.queryByRole('button', { name: 'Réessayer' })).not.toBeInTheDocument()

    rerender(<FinanceScoresCard scores={null} error onRetry={retry} />)
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))
    expect(retry).toHaveBeenCalledTimes(1)
  })
})
