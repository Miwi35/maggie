import { stripDeliveryTags } from '../../../components/chat/deliveryTags'
import type { VoiceAttachment } from '../../../components/chat/VoiceBubble'

const MESSAGES_URL = '/agent/messages'

/** The `digest` block of a message (`blocks` of `GET /agent/messages`). */
export interface DigestBlock {
  type: 'digest'
  /** `YYYY-MM-DD`: the day the digest is for. */
  day: string
  text: string
  expiresAt: string
  openedAt: string | null
}

/** A message of Maggie's as the digest screen reads it. */
export interface DigestMessage {
  id: string
  content: string
  audio?: VoiceAttachment | null
  blocks?: ({ type: string } & Partial<DigestBlock>)[] | null
}

/** The day's weather (`GET /api/weather`); every figure may be missing. */
export interface Weather {
  date?: string | null
  minC?: number | null
  maxC?: number | null
  rainMm?: number | null
  snowCm?: number | null
  rainProbability?: number | null
  windKmh?: number | null
  alerts?: string[]
  condition?: string | null
}

const headers = (body = false): HeadersInit => {
  const token = localStorage.getItem('token')

  return { Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}), ...(token ? { Authorization: `Bearer ${token}` } : {}) }
}

export const digestBlockOf = (message: DigestMessage | null | undefined): DigestBlock | null =>
  (message?.blocks?.find((block) => block.type === 'digest') as DigestBlock | undefined) ?? null

/** The message `id`, read out of its context; null when the server does not return it. */
export async function fetchDigestMessage(id: string, signal?: AbortSignal): Promise<DigestMessage | null> {
  const url = new URL(`${MESSAGES_URL}/context`, window.location.origin)
  url.searchParams.set('around', id)
  const response = await fetch(url.toString(), { signal, headers: headers() })
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`GET ${MESSAGES_URL}/context answered ${response.status}`)
  const body = (await response.json()) as { messages?: DigestMessage[] } | null

  return body?.messages?.find((message) => message.id === id) ?? null
}

/** The latest message of the digest screen; null when there is none. */
export async function fetchLatestDigest(signal?: AbortSignal): Promise<DigestMessage | null> {
  const response = await fetch(`${MESSAGES_URL}?screen=digest&limit=1`, { signal, headers: headers() })
  if (!response.ok) throw new Error(`GET ${MESSAGES_URL} answered ${response.status}`)
  const body = (await response.json()) as DigestMessage[] | null

  return body?.[0] ?? null
}

/** Marks the digest opened; idempotent on the server. */
export async function markDigestOpened(id: string): Promise<void> {
  const response = await fetch(`${MESSAGES_URL}/${encodeURIComponent(id)}/digest/opened`, { method: 'POST', headers: headers(true) })
  if (!response.ok) throw new Error(`POST digest/opened answered ${response.status}`)
}

/** The weather of `date`; null when the user has no city (404). */
export async function fetchWeather(date: string, signal?: AbortSignal): Promise<Weather | null> {
  const response = await fetch(`/api/weather?date=${encodeURIComponent(date)}`, { signal, headers: headers() })
  if (response.status === 404) return null
  if (!response.ok) throw new Error(`GET /api/weather answered ${response.status}`)

  return (await response.json()) as Weather
}

const CONDITIONS: Record<string, string> = {
  clear: 'Ensoleillé',
  partly_cloudy: 'Éclaircies',
  cloudy: 'Nuageux',
  fog: 'Brouillard',
  drizzle: 'Bruine',
  rain: 'Pluie',
  showers: 'Averses',
  snow: 'Neige',
  storm: 'Orages',
}

/** « Éclaircies »… and « 4 mm de pluie » from 1 mm, « 3 cm de neige » from 1 cm — snow wins; null when unknown. */
export function weatherLabels(weather: Weather): { condition: string | null; quantity: string | null } {
  const snow = weather.snowCm ?? 0
  const rain = weather.rainMm ?? 0

  return {
    condition: weather.condition ? (CONDITIONS[weather.condition] ?? null) : null,
    quantity: snow >= 1 ? `${Math.trunc(snow)} cm de neige` : rain >= 1 ? `${Math.trunc(rain)} mm de pluie` : null,
  }
}

/** The small text of the digest, without voice tags. */
export const digestWord = (block: DigestBlock): string => stripDeliveryTags(block.text).trim()

const capitalised = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1)

const clock = (ms: number): string => {
  const seconds = Math.max(0, Math.floor(ms / 1000))

  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

/** « Mardi 27 octobre · 1:12 » (long) or « mar. 27 oct. · 1:12 » (short). */
export function digestMoment(day: string, durationMs: number, form: 'long' | 'short'): string {
  const [y, m, d] = day.split('-').map(Number)
  const date = new Date(y, m - 1, d)
  const text =
    form === 'long'
      ? capitalised(date.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' }))
      : date.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' })

  return `${text} · ${clock(durationMs)}`
}

/** « Mardi 27 octobre », the title of the screen. */
export const digestTitle = (day: string): string => digestMoment(day, 0, 'long').replace(/ · .*$/, '')
