import { describe, test, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TaskBravo, TaskBravoBanner } from './TaskBravo'
import type { DayTask } from './dayPlan'
import type { Reward } from './reward'

const REWARD: Reward = {
  id: '01RW',
  subjectType: 'task',
  subjectId: '01BANK',
  level: 'standard',
  surprise: null,
  messageKey: 'doux.9',
  message: 'Coché. Ta journée prend forme.',
  star: true,
  earnedAt: '2026-10-15T08:00:00+00:00',
}

const NEXT: DayTask = {
  id: '01CANTEEN',
  title: 'Payer la cantine',
  estimatedMinutes: 15,
  firstStep: null,
  completedAt: null,
  dueDate: null,
  postponedSince: null,
  urgency: 'normal',
  criticality: 'low',
}

const props = (extra: Partial<Parameters<typeof TaskBravo>[0]> = {}) => ({
  title: 'Appeler la banque',
  reward: REWARD,
  done: 3,
  total: 5,
  next: NEXT,
  animate: true,
  onNext: vi.fn(),
  onPause: vi.fn(),
  ...extra,
})

describe('TaskBravo (MAG-508)', () => {
  test('draws the task done, the message, the day as five segments and the next task with its minutes', () => {
    render(<TaskBravo {...props()} />)

    const dialog = screen.getByRole('dialog', { name: 'Appeler la banque · faite' })
    expect(dialog).toHaveTextContent('Coché. Ta journée prend forme.')
    expect(dialog).toHaveTextContent("3 sur 5 aujourd'hui")
    expect(within(dialog).getAllByTestId('bravo-segment').map((s) => s.dataset.done)).toEqual(['true', 'true', 'true', 'false', 'false'])
    expect(within(dialog).getByRole('button', { name: 'Suivante : Payer la cantine · 15 min' })).toBeInTheDocument()
    expect(within(dialog).getByTestId('bravo-circle')).toBeInTheDocument()
    expect(within(dialog).queryByTestId('bravo-shooting-star')).toBeNull()
  })

  test('« Suivante » and « Pause méritée » hand back to the day', async () => {
    const p = props()
    render(<TaskBravo {...p} />)

    await userEvent.click(screen.getByRole('button', { name: /^Suivante/ }))
    expect(p.onNext).toHaveBeenCalledTimes(1)
    await userEvent.click(screen.getByRole('button', { name: 'Pause méritée' }))
    expect(p.onPause).toHaveBeenCalledTimes(1)
  })

  test('the day done has no next task, only the pause', () => {
    render(<TaskBravo {...props({ next: null, done: 5 })} />)

    expect(screen.queryByRole('button', { name: /^Suivante/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Pause méritée' })).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toHaveTextContent("5 sur 5 aujourd'hui")
  })

  test('a task out of the day shows no day bar', () => {
    render(<TaskBravo {...props({ done: 0, total: 0, next: null })} />)

    expect(screen.queryAllByTestId('bravo-segment')).toHaveLength(0)
  })

  test('a surprise has its shooting star; the animations can be off', () => {
    render(<TaskBravo {...props({ reward: { ...REWARD, level: 'surprise', surprise: 'fullDay' }, animate: false })} />)

    expect(screen.getByTestId('bravo-shooting-star')).toBeInTheDocument()
    expect(screen.getByTestId('bravo')).toHaveAttribute('data-animated', 'false')
  })

  test('the banner says the task done and its message, and nothing without a reward', () => {
    const { rerender } = render(<TaskBravoBanner title="Arroser" reward={REWARD} onClose={vi.fn()} />)

    expect(screen.getByRole('status')).toHaveTextContent('Arroser · faite — Coché. Ta journée prend forme.')

    rerender(<TaskBravoBanner title="Arroser" reward={null} onClose={vi.fn()} />)
    expect(screen.queryByText(/Coché/)).toBeNull()
  })
})
