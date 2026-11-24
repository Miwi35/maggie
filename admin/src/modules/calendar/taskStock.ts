import { addDays } from '../../dates'
import type { TaskGroup } from './TaskList'
import type { ViewTask } from './taskViews'

const rankOf = (task: ViewTask): number => (task.urgency === 'critical' ? 0 : task.urgency === 'high' ? 1 : 2)

/** A task and the subtasks that follow it. */
const blocksOf = (tasks: ViewTask[]): ViewTask[][] => {
  const blocks: ViewTask[][] = []
  for (const task of tasks) {
    if (task.parent && blocks.length > 0) blocks[blocks.length - 1].push(task)
    else blocks.push([task])
  }

  return blocks
}

/**
 * The « Tâches » view: « À venir » and « Un jour » grouped. « Cette semaine » holds what is planned (else due)
 * up to this Sunday, « Plus tard » what is after, « Sans date » the « Un jour » tasks and the undated ones.
 * In a group, Critique then Haute then the rest, the API's order kept between equals.
 */
export function groupStock(upcoming: ViewTask[], someday: ViewTask[], today: string): TaskGroup[] {
  const weekday = new Date(`${today}T00:00:00Z`).getUTCDay()
  const sunday = addDays(today, (7 - weekday) % 7)
  const thisWeek: ViewTask[][] = []
  const later: ViewTask[][] = []
  const undated: ViewTask[][] = []

  for (const block of blocksOf(upcoming)) {
    const date = block[0].scheduledDate ?? block[0].dueDate
    if (!date) undated.push(block)
    else if (date <= sunday) thisWeek.push(block)
    else later.push(block)
  }
  undated.push(...blocksOf(someday))

  const groups: [string, ViewTask[][]][] = [
    ['Cette semaine', thisWeek],
    ['Plus tard', later],
    ['Sans date', undated],
  ]

  return groups
    .filter(([, blocks]) => blocks.length > 0)
    .map(([label, blocks]) => ({
      label,
      tasks: blocks
        .map((block, index) => ({ block, index }))
        .sort((a, b) => rankOf(a.block[0]) - rankOf(b.block[0]) || a.index - b.index)
        .flatMap(({ block }) => block),
    }))
}
