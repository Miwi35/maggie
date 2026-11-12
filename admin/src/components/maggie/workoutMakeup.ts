/** The make-ups Maggie proposes for a missed session (MAG-772), shared by the interruption and the bell. */

export type MakeupOption = 'softer' | 'shorter' | 'tomorrow'

export interface WorkoutMakeup {
  softerMinutes: number
  shorterMinutes: number
  status: string
}

/** The words of the choices, in this order, whatever order `actions` holds. */
const ORDER: MakeupOption[] = ['softer', 'shorter', 'tomorrow']

/** The `workout_makeup` notification's `relatedEntityIri`: the proposal's ULID, or null. */
export function workoutMakeupId(iri: string | null | undefined): string | null {
  return (iri && /^\/api\/workout-makeups\/([0-9A-Za-z-]{1,64})$/.exec(iri)?.[1]) || null
}

/** The options the notification offers, in the display order; unknown keys are dropped. */
export function makeupOptions(actions: unknown): MakeupOption[] {
  if (!Array.isArray(actions)) return []
  return ORDER.filter((option) => actions.includes(option))
}

/** « 45 min, plus doux » · « 20 min, même intensité » · « Demain »; without the minutes yet, the words alone. */
export function makeupLabel(option: MakeupOption, makeup: WorkoutMakeup | null): string {
  if (option === 'tomorrow') return 'Demain'
  if (option === 'softer') return makeup ? `${makeup.softerMinutes} min, plus doux` : 'Plus doux'
  return makeup ? `${makeup.shorterMinutes} min, même intensité` : 'Même intensité'
}

function headers(): Record<string, string> {
  const token = localStorage.getItem('token')
  return {
    Accept: 'application/json',
    'Content-Type': 'application/json',
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  }
}

/** The proposal as it stands, or null when it cannot be read. */
export async function fetchWorkoutMakeup(id: string): Promise<WorkoutMakeup | null> {
  try {
    const response = await fetch(`/api/workout-makeups/${encodeURIComponent(id)}`, { headers: headers() })
    if (!response.ok) return null
    const body = await response.json()
    return typeof body?.softerMinutes === 'number' && typeof body?.shorterMinutes === 'number' ? body : null
  } catch {
    return null
  }
}

/** `chosen`: planned; `settled`: already chosen or overtaken (409); `failed`: nothing went. */
export async function chooseWorkoutMakeup(id: string, option: MakeupOption): Promise<'chosen' | 'settled' | 'failed'> {
  try {
    const response = await fetch(`/api/workout-makeups/${encodeURIComponent(id)}/choose`, {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ option }),
    })
    if (response.ok) return 'chosen'
    return response.status === 409 ? 'settled' : 'failed'
  } catch {
    return 'failed'
  }
}
