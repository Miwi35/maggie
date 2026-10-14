import { describe, test, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { criticalityColor } from '../../design/tokens'
import { TaskListWidget } from './TaskListWidget'
import type { DashboardTask } from './TaskListWidget'

/**
 * The dashboard's task list (MAG-39).
 *
 * The widget's own job is the chip: a task's criticality is read as a colour
 * before it is read as a word, and that colour is the `criticality` mapping of
 * `design/tokens.json` — the same one the phone's `TaskConstants.kt` resolves.
 * The hexes are never written here, they are asked of `criticalityColor()`, so
 * a change of the mapping moves the expectation with it.
 */

const TASKS: DashboardTask[] = [
  { id: '/api/tasks/01LOW', title: 'Arroser les plantes', criticality: 'low' },
  { id: '/api/tasks/01MEDIUM', title: 'Appeler le garage', criticality: 'medium' },
  { id: '/api/tasks/01HIGH', title: 'Déclarer les impôts', criticality: 'high' },
  { id: '/api/tasks/01CRITICAL', title: 'Payer le loyer', criticality: 'critical' },
]

const onToggleDone = vi.fn()

const renderWidget = (tasks: DashboardTask[], loading = false, extra: { ordered?: boolean; error?: boolean } = {}) =>
  render(<TaskListWidget tasks={tasks} loading={loading} onToggleDone={onToggleDone} {...extra} />)

/** `#4CAF50` as `rgb(76, 175, 80)` — what jsdom computes off the emotion rule. */
const asRgb = (hex: string): string => {
  const [r, g, b] = [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16))

  return `rgb(${r}, ${g}, ${b})`
}

/** The chip is the criticality label; its colour sits on the MUI root, not the text span. */
const chipFor = (criticality: string): HTMLElement => {
  const chip = screen.getByText(criticality).closest('.MuiChip-root') as HTMLElement | null
  expect(chip, `no chip for "${criticality}"`).not.toBeNull()

  return chip!
}

describe('TaskListWidget', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  test('draws every task with its criticality, in the criticality’s own colour', () => {
    renderWidget(TASKS)

    for (const task of TASKS) {
      expect(screen.getByText(task.title)).toBeInTheDocument()
      expect(getComputedStyle(chipFor(task.criticality)).backgroundColor, task.criticality).toBe(
        asRgb(criticalityColor(task.criticality)),
      )
    }
  })

  test('falls back to the low colour for a criticality the API does not send yet', () => {
    renderWidget([{ id: '/api/tasks/01NEW', title: 'Ranger le garage', criticality: 'blocking' }])

    expect(getComputedStyle(chipFor('blocking')).backgroundColor).toBe(
      asRgb(criticalityColor('low')),
    )
  })

  test('reports the task that was ticked, as done', async () => {
    const user = userEvent.setup()
    renderWidget(TASKS)

    const row = screen.getByText('Payer le loyer').closest('li') as HTMLElement
    await user.click(within(row).getByRole('checkbox'))

    expect(onToggleDone).toHaveBeenCalledExactlyOnceWith('/api/tasks/01CRITICAL', true)
  })

  // A due date is a plain day since MAG-404, read as that day here.
  test('shows the due day as written', () => {
    renderWidget([{ id: '/api/tasks/01DUE', title: 'Payer la cantine', criticality: 'low', dueDate: '2026-03-20' }])

    expect(screen.getByText(/^20 mars/)).toBeInTheDocument()
  })

  test('says so when there is nothing to do', () => {
    renderWidget([])

    expect(screen.getByText('Aucune tâche')).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  test('shows skeletons rather than the list while the tasks load', () => {
    const { container } = renderWidget(TASKS, true)

    expect(container.querySelectorAll('.MuiSkeleton-root').length).toBeGreaterThan(0)
    expect(screen.queryByText('Payer le loyer')).toBeNull()
    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  // MAG-405: a late task says so in words and with an icon, never by colour alone.
  describe('a late task', () => {
    const LATE: DashboardTask[] = [
      { id: '/api/tasks/01LATE', title: 'Renvoyer le formulaire', criticality: 'low', dueDate: '2026-10-11', overdueDays: 2 },
      { id: '/api/tasks/01TODAY', title: 'Appeler le garage', criticality: 'critical', dueDate: '2026-10-13', overdueDays: null },
    ]

    test('reads « En retard de N j » with an icon, and its original due day', () => {
      renderWidget(LATE, false, { ordered: true })

      const row = screen.getByText('Renvoyer le formulaire').closest('li') as HTMLElement
      const badge = within(row).getByText('En retard de 2 j')
      expect(badge.querySelector('svg')).not.toBeNull()
      expect(within(row).getByText(/^prévue le 11 oct/)).toBeInTheDocument()
    })

    test('does not flag a task due today', () => {
      renderWidget(LATE, false, { ordered: true })

      const row = screen.getByText('Appeler le garage').closest('li') as HTMLElement
      expect(within(row).queryByText(/En retard/)).toBeNull()
      expect(within(row).getByText(/^13 oct/)).toBeInTheDocument()
    })

    test('keeps the given order when ordered, instead of sorting by criticality', () => {
      renderWidget(LATE, false, { ordered: true })

      const titles = screen.getAllByRole('listitem').map((li) => li.textContent ?? '')
      expect(titles[0]).toContain('Renvoyer le formulaire')
      expect(titles[1]).toContain('Appeler le garage')
    })

    test('sorts by score otherwise, and by due day at equal score', () => {
      renderWidget(LATE)

      const titles = screen.getAllByRole('listitem').map((li) => li.textContent ?? '')
      expect(titles[0]).toContain('Renvoyer le formulaire')
    })

    test('reports the late task that was ticked, as done', async () => {
      const user = userEvent.setup()
      renderWidget(LATE, false, { ordered: true })

      const row = screen.getByText('Renvoyer le formulaire').closest('li') as HTMLElement
      await user.click(within(row).getByRole('checkbox'))

      expect(onToggleDone).toHaveBeenCalledExactlyOnceWith('/api/tasks/01LATE', true)
    })

    test('drops the badge once the task is done', () => {
      renderWidget([{ ...LATE[0], completedAt: '2026-10-13T08:00:00Z' }], false, { ordered: true })

      expect(screen.queryByText(/En retard/)).toBeNull()
    })
  })

  // MAG-406: the urgency the API computes, on every row, and the order of its score.
  describe('the urgency and the order', () => {
    const SCORED: DashboardTask[] = [
      { id: '/api/tasks/01WATER', title: 'Arroser', criticality: 'low', dueDate: '2026-10-13', urgency: 'normal', orderScore: 55 },
      { id: '/api/tasks/01BOOK', title: 'Rendre le livre', criticality: 'low', dueDate: '2026-10-12', overdueDays: 1, urgency: 'high', orderScore: 67 },
      { id: '/api/tasks/01CANTEEN', title: 'Payer la cantine', criticality: 'high', dueDate: '2026-10-13', urgency: 'high', orderScore: 78 },
    ]

    test('draws the urgency of each task as a word with an icon', () => {
      renderWidget(SCORED)

      const urgencyOf = (title: string) =>
        within(screen.getByText(title).closest('li') as HTMLElement).getByTestId('urgency-badge')
      expect(urgencyOf('Payer la cantine')).toHaveTextContent('haute')
      expect(urgencyOf('Rendre le livre')).toHaveTextContent('haute')
      expect(urgencyOf('Arroser')).toHaveTextContent('normale')
      expect(urgencyOf('Arroser').querySelector('svg')).not.toBeNull()
      // The late task keeps its lateness beside its urgency.
      expect(within(screen.getByText('Rendre le livre').closest('li') as HTMLElement).getByText('En retard de 1 j')).toBeInTheDocument()
    })

    test('lists the highest score first', () => {
      renderWidget(SCORED)

      const titles = screen.getAllByRole('listitem').map((li) => li.textContent ?? '')
      expect(titles.map((title) => title.match(/Payer la cantine|Rendre le livre|Arroser/)?.[0])).toEqual([
        'Payer la cantine',
        'Rendre le livre',
        'Arroser',
      ])
    })

    test('ticking a task beside its urgency reports that task', async () => {
      const user = userEvent.setup()
      renderWidget(SCORED)

      await user.click(within(screen.getByText('Payer la cantine').closest('li') as HTMLElement).getByRole('checkbox'))

      expect(onToggleDone).toHaveBeenCalledExactlyOnceWith('/api/tasks/01CANTEEN', true)
    })

    test('draws no urgency when the API sent none', () => {
      renderWidget(TASKS)

      expect(screen.queryByTestId('urgency-badge')).toBeNull()
    })
  })

  test('says the tasks are unavailable instead of « Aucune tâche » on an error', () => {
    renderWidget([], false, { error: true })

    expect(screen.getByRole('alert')).toHaveTextContent('Tâches indisponibles')
    expect(screen.queryByText('Aucune tâche')).toBeNull()
  })
})
