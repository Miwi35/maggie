import { apiUrl, headers } from './taskViews'

/** What helped, a closed list in the order the evening shows it (MAG-500). « Faire avec Maggie » comes with her. */
export const HELPED = ['start_small', 'morning', 'timer'] as const
export type Helped = (typeof HELPED)[number]

export const HELPED_LABELS: Record<Helped, string> = {
  start_small: 'Commencer petit',
  morning: 'Préparer le matin',
  timer: 'Le minuteur',
}

/** `GET /api/day-reviews/{date}`: what helped, and when « Bonne nuit » closed it. */
export interface DayReview {
  id: string
  date: string
  helped: Helped[]
  reviewedAt: string | null
}

const reviewError = async (response: Response): Promise<Error> => {
  const body = (await response.json().catch(() => ({}))) as { error?: string }

  return new Error(body.error ?? `Bilan : ${response.status}`)
}

const read = (body: Partial<DayReview>, date: string): DayReview => ({
  id: body.id ?? '',
  date: body.date ?? date,
  helped: (body.helped ?? []).filter((value): value is Helped => (HELPED as readonly string[]).includes(value)),
  reviewedAt: body.reviewedAt ?? null,
})

/** The review of `date` (`YYYY-MM-DD`), created empty by the API on first read. */
export async function fetchDayReview(date: string): Promise<DayReview> {
  const response = await fetch(`${apiUrl}/day-reviews/${date}`, { headers: headers({ Accept: 'application/json' }) })
  if (!response.ok) {
    throw await reviewError(response)
  }

  return read((await response.json()) as Partial<DayReview>, date)
}

/** « Bonne nuit »: records what helped, the whole list, and closes the review. */
export async function saveDayReview(date: string, helped: Helped[]): Promise<DayReview> {
  const response = await fetch(`${apiUrl}/day-reviews/${date}`, {
    method: 'PUT',
    headers: headers({ Accept: 'application/json', 'Content-Type': 'application/json' }),
    body: JSON.stringify({ helped }),
  })
  if (!response.ok) {
    throw await reviewError(response)
  }

  return read((await response.json()) as Partial<DayReview>, date)
}

/** Sets or clears a task's due day (`YYYY-MM-DD`) — one merge-patch. « Mettre de côté » clears it. */
export async function setTaskDueDate(id: string, dueDate: string | null): Promise<void> {
  const response = await fetch(`${apiUrl}/tasks/${id.split('/').pop()}`, {
    method: 'PATCH',
    headers: headers({ 'Content-Type': 'application/merge-patch+json' }),
    body: JSON.stringify({ dueDate }),
  })
  if (!response.ok) {
    throw new Error(`Tâche : ${response.status}`)
  }
}

/** The three ways out of a task left, in one gesture: tomorrow's five, the reserve, set aside. */
export type Outcome = 'tomorrow' | 'later' | 'aside'

/** `ids` with `id` back at the index it held in `original`, among those still there. */
export function restoreAt(ids: string[], id: string, original: string[]): string[] {
  if (ids.includes(id)) return ids
  const before = original.slice(0, original.indexOf(id)).filter((other) => ids.includes(other))
  const at = before.length === 0 ? 0 : ids.indexOf(before[before.length - 1]) + 1

  return [...ids.slice(0, at), id, ...ids.slice(at)]
}
