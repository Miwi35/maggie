import type { BilanOrigin, BilanStatus } from './bilanStatusApi'

export const BILAN_STEPS = ['journee', 'finance', 'sante', 'consignes', 'demain'] as const
export type BilanStep = (typeof BILAN_STEPS)[number]

export const STEP_LABELS: Record<BilanStep, string> = {
  journee: 'Journée',
  finance: 'Finance',
  sante: 'Santé',
  consignes: 'Consignes',
  demain: 'Demain',
}

export const STEP_HEADINGS: Record<BilanStep, string> = {
  journee: 'Votre journée',
  finance: 'Vos comptes',
  sante: 'Votre santé',
  consignes: 'Vos consignes',
  demain: 'Demain',
}

/** What Maggie says on a step that has nothing of its own to say: local, never the model's. */
export const DEFAULT_SAY: Record<BilanStep, string> = {
  journee: 'Voyons votre journée.',
  finance: 'Voilà où en sont vos comptes aujourd’hui.',
  sante: 'Et votre santé ? Votre moral, sur 10 ? Votre fatigue ?',
  consignes: 'Des consignes pour demain ? Je vous écoute.',
  demain: 'Voici ce qui vous attend demain.',
}

export type TaskChoice = 'tomorrow' | 'later'

export interface BilanInstruction {
  id: string
  text: string
  kind: 'reminder' | 'task' | 'instruction'
  targetId?: string | null
}

/** `answers` of the status: the steps write them, unknown keys stay untouched. */
export type BilanAnswers = Record<string, unknown> & {
  tasks?: { taskId: string; choice: TaskChoice }[]
  mood?: number
  fatigue?: number
}

/** A bilan in progress. `state` is the status' object as it stands: a change of step rewrites it untouched. */
export interface BilanSession {
  day: string
  step: BilanStep
  state: Record<string, unknown>
}

export const BILAN_ORIGIN_KIND = 'day'

export const originOf = (session: BilanSession): BilanOrigin => ({ kind: BILAN_ORIGIN_KIND, id: session.day })

export const stateOf = (session: BilanSession): Record<string, unknown> => ({ ...session.state, step: session.step })

export const isStep = (value: unknown): value is BilanStep => BILAN_STEPS.includes(value as BilanStep)

export const newSession = (day: string): BilanSession => ({ day, step: 'journee', state: {} })

/** The bilan a status holds, null when it is not one this version reads. */
export const sessionOf = (status: BilanStatus): BilanSession | null => {
  if (status.origin.kind !== BILAN_ORIGIN_KIND || !/^\d{4}-\d{2}-\d{2}$/.test(status.origin.id)) return null
  const step = status.state?.step
  if (!isStep(step)) return null

  return { day: status.origin.id, step, state: status.state }
}

/** Still resumable: started for today or a later day. */
export const resumable = (status: BilanStatus | null, today: string): BilanSession | null => {
  const session = status ? sessionOf(status) : null

  return session && session.day >= today ? session : null
}

export const answersOf = (session: BilanSession): BilanAnswers => {
  const answers = session.state.answers

  return typeof answers === 'object' && answers !== null && !Array.isArray(answers) ? (answers as BilanAnswers) : {}
}

/** `answers.tasks` as `taskId → choice`. */
export const taskChoices = (session: BilanSession): Record<string, TaskChoice> => {
  const tasks = answersOf(session).tasks
  if (!Array.isArray(tasks)) return {}

  return Object.fromEntries(tasks.filter((entry) => entry?.taskId && (entry.choice === 'tomorrow' || entry.choice === 'later')).map((entry) => [entry.taskId, entry.choice]))
}

/** The tasks answers with `taskId` set to `choice`; null takes it back. */
export const withTaskChoice = (session: BilanSession, taskId: string, choice: TaskChoice | null): BilanAnswers => {
  const choices = { ...taskChoices(session) }
  if (choice === null) delete choices[taskId]
  else choices[taskId] = choice

  return { tasks: Object.entries(choices).map(([id, value]) => ({ taskId: id, choice: value })) }
}

export const answerInt = (session: BilanSession, key: 'mood' | 'fatigue'): number | null => {
  const value = answersOf(session)[key]

  return typeof value === 'number' && Number.isInteger(value) ? value : null
}

export const instructionsOf = (session: BilanSession): BilanInstruction[] => {
  const list = session.state.instructions

  return Array.isArray(list) ? list.filter((item): item is BilanInstruction => typeof item?.id === 'string' && typeof item?.text === 'string') : []
}

export const stepIndex = (step: BilanStep): number => BILAN_STEPS.indexOf(step)
