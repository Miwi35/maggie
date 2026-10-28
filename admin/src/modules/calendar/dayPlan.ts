import { apiUrl, headers } from './taskViews'

/** The day holds five tasks at most: a longer list discourages (MAG-497). */
export const DAY_SLOTS = 5

/** A task as the day sends it: only what the day draws. */
export interface DayTask {
  id: string
  title: string
  estimatedMinutes: number | null
  firstStep: string | null
  completedAt: string | null
  dueDate: string | null
  /** `YYYY-MM-DD`: the day it has been put off since; null when it is not. */
  postponedSince: string | null
  urgency: string | null
  criticality: string
}

export interface DaySlot {
  /** 1 to 5, in order. */
  position: number
  type: 'task'
  task: DayTask
}

/** `GET /api/day-plans/{date}`: the slots, the first one not done, and the reserve by score. */
export interface DayPlan {
  id: string
  date: string
  confirmedAt: string | null
  slots: DaySlot[]
  now: DaySlot | null
  reserve: DayTask[]
  done: number
  total: number
}

const dayError = async (response: Response): Promise<Error> => {
  const body = (await response.json().catch(() => ({}))) as { error?: string }

  return new Error(body.error ?? `Journée : ${response.status}`)
}

/** The day of `date` (`YYYY-MM-DD`), created empty by the API on first read. */
export async function fetchDayPlan(date: string): Promise<DayPlan> {
  const response = await fetch(`${apiUrl}/day-plans/${date}`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) {
    throw await dayError(response)
  }

  return (await response.json()) as DayPlan
}

/**
 * Replaces the whole list of the day's tasks, in order; the API answers the day as it stands.
 * `confirm: false` writes without settling the day — the evening's « Demain » (MAG-500),
 * so the morning still opens and keeps it.
 */
export async function saveDayPlan(date: string, taskIds: string[], confirm = true): Promise<DayPlan> {
  const response = await fetch(`${apiUrl}/day-plans/${date}`, {
    method: 'PUT',
    headers: headers({ Accept: 'application/json', 'Content-Type': 'application/json' }),
    body: JSON.stringify({ slots: taskIds.map((taskId) => ({ type: 'task', taskId })), ...(confirm ? {} : { confirm }) }),
  })
  if (!response.ok) {
    throw await dayError(response)
  }

  return (await response.json()) as DayPlan
}

/** Why a task is proposed, in the order of the rule's tiers (MAG-498). */
export type ProposalReason = 'postponed' | 'dueToday' | 'urgent' | 'dueTomorrow' | 'quick' | 'whenever'

export const REASON_LABELS: Record<ProposalReason, string> = {
  postponed: 'reportée',
  dueToday: 'à faire aujourd’hui',
  urgent: 'urgent',
  dueTomorrow: 'pour demain',
  quick: 'rapide',
  whenever: 'quand tu veux',
}

/** A task the morning proposes, with why and the minutes it counts for (15 when unknown). */
export interface ProposalEntry {
  reason: ProposalReason
  minutes: number
  task: DayTask
}

export interface ProposalSlot extends ProposalEntry {
  position: number
  type: 'task'
}

/** `GET /api/day-plans/{date}/proposal`: five tasks at most and the rest by rank; it writes nothing. */
export interface DayProposal {
  date: string
  slots: ProposalSlot[]
  reserveRanked: ProposalEntry[]
  totalMinutes: number
  /** The agenda's free time of the day, from the morning to 21:00. */
  freeMinutes: number
}

/** The morning's proposal for `date` (`YYYY-MM-DD`). */
export async function fetchDayProposal(date: string): Promise<DayProposal> {
  const response = await fetch(`${apiUrl}/day-plans/${date}/proposal`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) {
    throw await dayError(response)
  }
  const body = (await response.json()) as Partial<DayProposal>

  return {
    date: body.date ?? date,
    slots: body.slots ?? [],
    reserveRanked: body.reserveRanked ?? [],
    totalMinutes: body.totalMinutes ?? 0,
    freeMinutes: body.freeMinutes ?? 0,
  }
}

const countedMinutes = (slots: ProposalSlot[]): number =>
  slots.reduce((sum, slot) => sum + (slot.task.completedAt == null ? slot.minutes : 0), 0)

/**
 * The proposal once slot `index` is swapped: the first of the reserve takes
 * its place (the position does not change) and the task it replaces goes to
 * the end of the reserve. Nothing changes without a reserve.
 */
export function swapProposal(proposal: DayProposal, index: number): DayProposal {
  const [next, ...rest] = proposal.reserveRanked
  const out = proposal.slots[index]
  if (!next || !out) return proposal

  const slots = proposal.slots.map((slot, i) => (i === index ? { ...slot, ...next } : slot))

  return {
    ...proposal,
    slots,
    reserveRanked: [...rest, { reason: out.reason, minutes: out.minutes, task: out.task }],
    totalMinutes: countedMinutes(slots),
  }
}

/** `55 min`, `2 h 30`, `1 h`. */
export function formatMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60

  return rest === 0 ? `${hours} h` : `${hours} h ${String(rest).padStart(2, '0')}`
}

/** The user's morning and evening times (`HH:MM`), each null when it cannot be read. */
export interface DayTimes {
  morningTime: string | null
  eveningTime: string | null
}

const time = (value: unknown): string | null =>
  typeof value === 'string' && /^\d{2}:\d{2}$/.test(value) ? value : null

export async function fetchDayTimes(): Promise<DayTimes> {
  const response = await fetch(`${apiUrl}/task-settings/me`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) return { morningTime: null, eveningTime: null }
  const body = (await response.json().catch(() => ({}))) as { morningTime?: unknown; eveningTime?: unknown }

  return { morningTime: time(body.morningTime), eveningTime: time(body.eveningTime) }
}

const reached = (now: Date, at: string): boolean => {
  const [hours, minutes] = at.split(':').map(Number)

  return now.getHours() * 60 + now.getMinutes() >= hours * 60 + minutes
}

/** The morning proposal is due once the morning time has come and the day is not confirmed yet. */
export function morningDue(now: Date, morningTime: string | null, confirmedAt: string | null): boolean {
  if (!morningTime || confirmedAt != null) return false

  return reached(now, morningTime)
}

/** The evening review is due once the evening time has come, on a day that holds tasks, until « Bonne nuit » (MAG-500). */
export function eveningDue(now: Date, eveningTime: string | null, total: number, reviewedAt: string | null): boolean {
  if (!eveningTime || total === 0 || reviewedAt != null) return false

  return reached(now, eveningTime)
}

let morningOffered = false

/** True the first time only: the morning opens once per load of the app, so leaving it unconfirmed does not loop. */
export function claimMorning(): boolean {
  if (morningOffered) return false
  morningOffered = true

  return true
}

export function resetMorningOffer(): void {
  morningOffered = false
  eveningOffered = false
}

let eveningOffered = false

/** True the first time only: « Plus tard » closes the evening for this load of the app. */
export function claimEvening(): boolean {
  if (eveningOffered) return false
  eveningOffered = true

  return true
}

/**
 * The day's list once `taskId` goes « plus tard » (MAG-499): last of the slots
 * not done. The done slots keep their place; only the not-done ones are
 * reordered, among the places they already held. Null when nothing changes —
 * the task is not in the day, is done, or is already the last one left.
 */
export function deferOnDay(slots: { id: string; done: boolean }[], taskId: string): string[] | null {
  const task = slots.find((slot) => slot.id === taskId)
  if (!task || task.done) return null

  const open = slots.filter((slot) => !slot.done).map((slot) => slot.id)
  if (open[open.length - 1] === taskId) return null

  const reordered = [...open.filter((id) => id !== taskId), taskId]
  let next = 0

  return slots.map((slot) => (slot.done ? slot.id : reordered[next++]))
}
