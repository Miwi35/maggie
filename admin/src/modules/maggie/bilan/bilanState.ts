import { answersOf, taskChoices, withTaskChoice } from './bilanSession'
import type { BilanSession } from './bilanSession'
import type { BilanStatus } from './bilanStatusApi'

/** A task Maggie chose at the creation of the bilan (`tomorrowTasks`) or keeps in reserve (`tomorrowSpares`). */
export interface PlannedTask {
  taskId: string
  title: string
  reason?: string
}

const plannedList = (value: unknown): PlannedTask[] =>
  Array.isArray(value)
    ? value
        .filter((entry): entry is Record<string, unknown> => typeof entry?.taskId === 'string')
        .map((entry) => ({
          taskId: entry.taskId as string,
          title: typeof entry.title === 'string' ? entry.title : '',
          ...(typeof entry.reason === 'string' && entry.reason ? { reason: entry.reason } : {}),
        }))
    : []

export const tomorrowTasks = (session: BilanSession): PlannedTask[] => plannedList(session.state.tomorrowTasks)

export const tomorrowSpares = (session: BilanSession): PlannedTask[] => plannedList(session.state.tomorrowSpares)

/** The tasks left « Demain » at the Journée step, in the order they were answered. */
export const leftForTomorrow = (session: BilanSession): string[] =>
  Object.entries(taskChoices(session))
    .filter(([, choice]) => choice === 'tomorrow')
    .map(([taskId]) => taskId)

/** What Demain lists: the tasks left « Demain » at Journée first, then the ones Maggie chose. The title of the first is read from the task. */
export const plannedForTomorrow = (session: BilanSession): (PlannedTask & { fromJournee: boolean })[] => {
  const left = leftForTomorrow(session)
  const chosen = tomorrowTasks(session)
  const known = new Map(chosen.map((task) => [task.taskId, task]))

  return [
    ...left.map((taskId) => ({ taskId, title: known.get(taskId)?.title ?? '', ...(known.get(taskId)?.reason ? { reason: known.get(taskId)?.reason } : {}), fromJournee: true })),
    ...chosen.filter((task) => !left.includes(task.taskId)).map((task) => ({ ...task, fromJournee: false })),
  ]
}

/**
 * ⇄: the first spare takes the place of `taskId`, which goes last in the reserve. A task that came from Journée
 * is put off instead (its choice becomes `later`). Null without a spare. The result is a patch of the status' state.
 */
export const withSwap = (session: BilanSession, taskId: string, title?: string): Record<string, unknown> | null => {
  const [spare, ...rest] = tomorrowSpares(session)
  if (!spare) return null
  const chosen = tomorrowTasks(session)
  const index = chosen.findIndex((task) => task.taskId === taskId)
  const out: PlannedTask = { taskId, title: title || chosen[index]?.title || '' }
  const remaining = chosen.filter((task) => task.taskId !== taskId)
  const tasks = index === -1 ? [...remaining, spare] : [...remaining.slice(0, index), spare, ...remaining.slice(index)]
  const patch: Record<string, unknown> = { tomorrowTasks: tasks, tomorrowSpares: [...rest, out] }
  if (taskChoices(session)[taskId] === 'tomorrow') patch.answers = { ...answersOf(session), ...withTaskChoice(session, taskId, 'later') }

  return patch
}

/** A status of the day without `step` is a bilan Maggie prepared: it has not been opened yet. */
export const isPrepared = (status: BilanStatus | null, today: string): status is BilanStatus =>
  status !== null && status.origin.kind === 'day' && status.origin.id === today && typeof status.state?.step !== 'string'

export const answeredTrips = (session: BilanSession): string[] => {
  const trips = answersOf(session).trips

  return Array.isArray(trips) ? trips.filter((key): key is string => typeof key === 'string') : []
}

/** The patch of the state with the trip question `key` added to `answers.trips`, or taken off. */
export const withTripAnswer = (session: BilanSession, key: string, answered = true): Record<string, unknown> => {
  const others = answeredTrips(session).filter((entry) => entry !== key)

  return { answers: { ...answersOf(session), trips: (answered ? [...others, key] : others).sort() } }
}
