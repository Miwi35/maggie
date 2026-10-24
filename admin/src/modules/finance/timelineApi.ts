/**
 * The 20-year frise (MAG-554): the events of the current scenario, and of one
 * compared scenario beside it, month by month up to independence.
 * `GET /api/finance/timeline[?compare=<scenarioId|auto-rapid|auto-balanced|auto-quality>]`,
 * and `GET /api/finance/scenarios` for the scenarios one can compare with.
 * Category keys, labels and colours are served by the API: nothing here names one.
 */

export type TimelineEventType =
  | 'project_start'
  | 'project_purchase'
  | 'debt_end'
  | 'early_repayment'
  | 'placement_start'
  | 'placement_milestone'
  | 'independence_milestone'
  | 'independence_reached'
  | 'cushion_complete'
  | 'cushion_recharging'

export interface TimelineCategory {
  key: string
  label: string
  color: string
}

export interface TimelineEvent {
  id: string
  /** `YYYY-MM`. */
  month: string
  type: TimelineEventType
  /** One of the served categories' keys. */
  category: string
  label: string
  sublabel: string | null
  /** Euros, positive. */
  amount_eur: number | null
  /** Euros: negative when the event asks for a payment, positive when it frees one. */
  monthly_impact_eur: number | null
  /** Months the event moves N (negative: sooner). */
  n_impact_months: number | null
  detail: Record<string, unknown>
}

export interface TimelineLine {
  /** Null on the « Planning sans scénario »: nobody is current. */
  scenarioId: string | null
  name: string
  /** `YYYY-MM`, null when N is not reached within the horizon. */
  nDate: string | null
  events: TimelineEvent[]
}

export interface Timeline {
  startMonth: string
  horizonMonths: number
  assumptions: string
  categories: TimelineCategory[]
  current: TimelineLine
  compared: TimelineLine | null
  difference: { months: number | null; sentence: string } | null
}

/** A scenario one may compare with, as the list endpoint summarises it. */
export interface ScenarioChoice {
  id: string
  name: string
  /** Automatic scenarios are proposed by the API, not written by the user. */
  automatic: boolean
  current: boolean
}

/** A read the API refused or could not answer; `status` 0 when the network failed. */
export class TimelineError extends Error {
  readonly status: number

  constructor(status: number) {
    super(`The frise answered ${status}`)
    this.status = status
  }
}

export const TIMELINE_PATH = '/api/finance/timeline'
export const SCENARIOS_PATH = '/api/finance/scenarios'

const get = async (path: string): Promise<unknown> => {
  const token = localStorage.getItem('token')
  let response: Response
  try {
    response = await fetch(path, {
      headers: { Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    })
  } catch {
    throw new TimelineError(0)
  }
  if (!response.ok) throw new TimelineError(response.status)

  return response.json()
}

export const fetchTimeline = async (compareId: string | null): Promise<Timeline> =>
  (await get(compareId === null ? TIMELINE_PATH : `${TIMELINE_PATH}?compare=${encodeURIComponent(compareId)}`)) as Timeline

export const fetchScenarioChoices = async (): Promise<ScenarioChoice[]> => {
  const body = (await get(SCENARIOS_PATH)) as { scenarios?: Record<string, unknown>[] }

  return (body.scenarios ?? []).map((raw) => ({
    id: String(raw.id),
    name: String(raw.name ?? ''),
    automatic: raw.kind === 'auto' || String(raw.id).startsWith('auto-'),
    current: raw.current === true,
  }))
}
