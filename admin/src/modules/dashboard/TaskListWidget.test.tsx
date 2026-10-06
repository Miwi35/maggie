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

const renderWidget = (tasks: DashboardTask[], loading = false) =>
  render(<TaskListWidget tasks={tasks} loading={loading} onToggleDone={onToggleDone} />)

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
})
