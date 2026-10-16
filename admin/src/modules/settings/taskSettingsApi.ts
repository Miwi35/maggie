import { useCallback, useEffect, useRef, useState } from 'react'
import { useNotify } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'

/**
 * Réglages › Tâches (MAG-407): what the two pages share — the settings read
 * from and saved to `GET|PUT /api/task-settings/me` (the phone reads the same),
 * saved on every change, reverted to the server's values when a save fails.
 */

export const API_PATH = '/api/task-settings/me'
export const PREVIEW_PATH = '/api/task-settings/me/preview'
const MERCURE_TOPICS = ['/api/task_settings/{id}']

export const URGENCIES = ['critical', 'high', 'normal'] as const
export type Urgency = (typeof URGENCIES)[number]

export const LEVEL_GROUPS = ['timePressure', 'criticality', 'priority'] as const
export type LevelGroup = (typeof LEVEL_GROUPS)[number]

export interface UrgencySchedule {
  daysBefore: number[]
  dueDay: boolean
  overdueEveryDays: number | null
}

export interface SettingsValues {
  orderWeights: Record<string, Record<string, number>>
  orderLevels: Record<LevelGroup, number>
  alertSchedule: Record<Urgency, UrgencySchedule>
  morningTime: string
  /** After it, the first visit of Tâches opens the evening's review (MAG-500). */
  eveningTime: string
}

export interface TaskSettingsResponse extends SettingsValues {
  id: string
  defaults: SettingsValues
}

/** What a PUT may carry: only the fields that change. */
export interface SettingsPatch {
  orderLevels?: Partial<Record<LevelGroup, number>>
  alertSchedule?: Partial<Record<Urgency, Partial<UrgencySchedule>>>
  morningTime?: string
  eveningTime?: string
}

export const SAVE_ERROR = "Impossible d'enregistrer les réglages des tâches"

export function apiFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

/**
 * The settings, loaded and kept in step with Mercure; `save` applies a change
 * at once, sends it, and falls back to the last server values if it is refused.
 * Saves go one after the other, so the last change is the one that stays.
 */
export function useTaskSettings(onReload?: () => void) {
  const notify = useNotify()
  const [settings, setSettings] = useState<TaskSettingsResponse | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [saved, setSaved] = useState(false)
  const server = useRef<TaskSettingsResponse | null>(null)
  const queue = useRef<Promise<unknown>>(Promise.resolve())
  /**
   * Saves sent and not answered yet, and whether a value is being moved
   * (a slider held): until both settle, the screen keeps what the user did —
   * a server answer would undo the changes still on their way (as on the phone).
   */
  const pending = useRef(0)
  const editing = useRef(false)

  /** Reads the settings; true when the order levels changed since the last known values. */
  const load = useCallback(async (): Promise<boolean> => {
    try {
      const res = await apiFetch(API_PATH)
      if (!res.ok) throw new Error(String(res.status))
      const data = (await res.json()) as TaskSettingsResponse
      const levelsChanged = JSON.stringify(data.orderLevels) !== JSON.stringify(server.current?.orderLevels)
      server.current = data
      if (pending.current === 0 && !editing.current) setSettings(data)
      setLoadFailed(false)
      return levelsChanged
    } catch {
      setLoadFailed(true)
      return false
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  // A change made elsewhere (the phone, Maggie) shows up here. The echo of
  // our own save brings no new levels, so it does not reload the preview twice.
  const handleMercure = useCallback(async () => {
    if ((await load()) && pending.current === 0) onReload?.()
  }, [load, onReload])

  useMercure(MERCURE_TOPICS, handleMercure)

  /** Shows a value before it is saved (a slider being dragged). */
  const update = useCallback((change: (s: TaskSettingsResponse) => TaskSettingsResponse) => {
    editing.current = true
    setSettings((s) => (s ? change(s) : s))
  }, [])

  const save = useCallback(
    (patch: SettingsPatch, change?: (s: TaskSettingsResponse) => TaskSettingsResponse): Promise<boolean> => {
      if (change) setSettings((s) => (s ? change(s) : s))
      editing.current = false
      pending.current += 1
      setSaved(false)

      const send = async (): Promise<boolean> => {
        try {
          const res = await apiFetch(API_PATH, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(patch),
          })
          if (res.ok) {
            const data = (await res.json()) as TaskSettingsResponse
            server.current = data
            pending.current -= 1
            if (pending.current === 0 && !editing.current) {
              setSettings(data)
              setSaved(true)
            }
            return true
          }
          const body = await res.json().catch(() => null)
          notify(res.status === 400 && typeof body?.error === 'string' ? body.error : SAVE_ERROR, { type: 'error' })
        } catch {
          notify(SAVE_ERROR, { type: 'error' })
        }
        pending.current -= 1
        setSettings(server.current)
        return false
      }

      const run = queue.current.then(send)
      queue.current = run
      return run
    },
    [notify],
  )

  return { settings, loadFailed, saved, save, update }
}
