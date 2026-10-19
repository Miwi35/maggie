import { apiUrl, headers } from './taskViews'

/** A rare surprise, « étoile filante » (MAG-508). */
export type RewardSurprise = 'fullDay' | 'monthRecord' | 'waited'

/** `GET /api/rewards/task/{id}`: the bravo a tick laid down, its message from a written bank. */
export interface Reward {
  id: string
  subjectType: string
  subjectId: string
  level: 'standard' | 'surprise' | 'palier'
  surprise: RewardSurprise | null
  messageKey: string
  message: string
  /** What a palier celebrates, the chapter's title; null for a task. */
  title: string | null
  star: boolean
  earnedAt: string
}

/** The reward a Mercure message carries; null for anything else (a task, a day) published on the same stream. */
export function parseReward(data?: string): Reward | null {
  if (!data) return null
  try {
    const body = JSON.parse(data) as Partial<Reward> & { '@id'?: string }
    if (!body['@id']?.startsWith('/api/rewards/') || typeof body.message !== 'string' || typeof body.level !== 'string') return null

    return { id: body['@id'].split('/').pop() ?? '', title: null, surprise: null, ...body } as Reward
  } catch {
    return null
  }
}

/** How a task done is cheered here: no vibration in a browser. */
export interface RewardCheer {
  animation: boolean
  sound: boolean
}

export const DEFAULT_CHEER: RewardCheer = { animation: true, sound: true }

/** The little sound, generated for Maggie and distinct from her chime. */
export const BRAVO_SOUND = `${import.meta.env.BASE_URL.replace(/\/$/, '')}/maggie_bravo.ogg`

/** The reward of a task just ticked (or of a saga chapter: `subjectType`); null when it has none (yet). */
export async function fetchReward(subjectId: string, subjectType = 'task'): Promise<Reward | null> {
  const id = subjectId.split('/').pop()
  const response = await fetch(`${apiUrl}/rewards/${subjectType}/${id}`, { headers: headers({ Accept: 'application/json' }) })
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`Récompense : ${response.status}`)

  return (await response.json()) as Reward
}

/** The settings of the cheer; all on when they cannot be read, as they are by default. */
export async function fetchRewardCheer(): Promise<RewardCheer> {
  const response = await fetch(`${apiUrl}/task-settings/me`, { headers: headers({ Accept: 'application/json' }) }).catch(
    () => null,
  )
  if (!response?.ok) return DEFAULT_CHEER
  const body = (await response.json().catch(() => ({}))) as { rewardAnimation?: unknown; rewardSound?: unknown }

  return { animation: body.rewardAnimation !== false, sound: body.rewardSound !== false }
}

/** Plays the bravo once; a browser that refuses (no gesture yet, no codec) stays silent. */
export function playBravoSound(): void {
  try {
    const audio = new Audio(BRAVO_SOUND)
    audio.volume = 0.6
    void audio.play()?.catch(() => undefined)
  } catch {
    // No audio here: the bravo is still shown.
  }
}
