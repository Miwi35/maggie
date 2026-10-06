import { useCallback, useEffect, useRef, useState } from 'react'
import { AGENT_STREAMS, agentTopic, getStoredUserId } from './agentTopics'
import { mercureUrl } from './mercureUrl'

const APPROVALS_URL = '/agent/approvals'
const MERCURE_URL = import.meta.env.VITE_MERCURE_PUBLIC_URL || 'http://maggie.local/.well-known/mercure'

export type ApprovalStatus = 'pending' | 'approved' | 'denied' | 'expired' | 'failed'

/** An action the policy holds back until the user answers it — the agent's `PendingAction.to_dict()`. */
export interface Approval {
  id: string
  toolName: string
  arguments: Record<string, unknown>
  status: ApprovalStatus
  result: string | null
  createdAt: string
  expiresAt: string
  decidedAt?: string | null
  contextId?: string | null
}

export interface ApprovalItem extends Approval {
  /** The answer being sent: the card is « en cours » until the POST returns. */
  busy?: 'approve' | 'deny'
  error?: string
}

export const APPROVAL_ERRORS = {
  network: "Impossible d'envoyer ta réponse. Réessaie.",
  alreadyDecided: 'Cette action a déjà été traitée.',
} as const

function getAuthHeaders(): Record<string, string> {
  const token = localStorage.getItem('token')
  return token ? { Authorization: `Bearer ${token}` } : {}
}

function isApproval(value: unknown): value is Approval {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v.id === 'string' && typeof v.toolName === 'string' && typeof v.status === 'string'
}

/** Whether the 24 h window is over, even if the scheduler has not yet published `expired`. */
export function isOverdue(approval: Pick<Approval, 'expiresAt'>, now: number = Date.now()): boolean {
  const expiresAt = Date.parse(approval.expiresAt)
  return !Number.isNaN(expiresAt) && expiresAt <= now
}

/** Waiting for an answer, and still answerable. */
export function isAwaitingAnswer(approval: ApprovalItem, now: number = Date.now()): boolean {
  return approval.status === 'pending' && !isOverdue(approval, now)
}

/**
 * The user's pending approvals: loaded on mount, then kept current by the
 * `approvals` Mercure stream. A card stays in the list once answered, so the
 * thread shows how it ended (validée, refusée, échouée, expirée).
 */
export function useApprovals() {
  const [approvals, setApprovals] = useState<ApprovalItem[]>([])
  const approvalsRef = useRef<ApprovalItem[]>([])

  const commit = useCallback((next: ApprovalItem[]) => {
    approvalsRef.current = next
    setApprovals(next)
  }, [])

  // Merge, keeping what only this tab knows (busy, error): Mercure delivers the
  // `approved` claim before the result, while the POST is still in flight.
  const upsert = useCallback(
    (incoming: Approval, patch: Partial<ApprovalItem> = {}) => {
      const current = approvalsRef.current
      const exists = current.some((a) => a.id === incoming.id)
      commit(
        exists
          ? current.map((a) =>
              a.id === incoming.id
                ? { ...a, ...incoming, ...(incoming.status !== 'pending' && { error: undefined }), ...patch }
                : a,
            )
          : [...current, { ...incoming, ...patch }],
      )
    },
    [commit],
  )

  const patchOne = useCallback(
    (id: string, patch: Partial<ApprovalItem>) => {
      commit(approvalsRef.current.map((a) => (a.id === id ? { ...a, ...patch } : a)))
    },
    [commit],
  )

  useEffect(() => {
    let cancelled = false
    void (async () => {
      try {
        const res = await fetch(`${APPROVALS_URL}?status=pending`, { headers: getAuthHeaders() })
        if (!res.ok || cancelled) return
        const data: unknown = await res.json()
        if (!Array.isArray(data) || cancelled) return
        data.filter(isApproval).forEach((a) => upsert(a))
      } catch {
        // The cards are an addition to the chat: no list is not an error to show.
      }
    })()
    return () => {
      cancelled = true
    }
  }, [upsert])

  useEffect(() => {
    const userId = getStoredUserId()
    if (!userId) return
    const url = mercureUrl(MERCURE_URL, [agentTopic(AGENT_STREAMS.approvals, userId)])

    const eventSource = new EventSource(url.toString(), { withCredentials: true })
    eventSource.onmessage = (event) => {
      try {
        const data: unknown = JSON.parse(event.data)
        if (isApproval(data)) upsert(data)
      } catch {
        // Ignore malformed messages
      }
    }
    return () => eventSource.close()
  }, [upsert])

  const answer = useCallback(
    async (id: string, decision: 'approve' | 'deny') => {
      patchOne(id, { busy: decision, error: undefined })
      try {
        const res = await fetch(`${APPROVALS_URL}/${id}/${decision}`, { method: 'POST', headers: getAuthHeaders() })
        if (res.ok) {
          const settled: unknown = await res.json()
          if (isApproval(settled)) upsert(settled, { busy: undefined, error: undefined })
          else patchOne(id, { busy: undefined })
          return
        }
        if (res.status === 410) {
          patchOne(id, { status: 'expired', busy: undefined, error: undefined })
        } else if (res.status === 404) {
          commit(approvalsRef.current.filter((a) => a.id !== id))
        } else if (res.status === 409) {
          // Someone answered first (another tab, the scheduler): Mercure brings the real status.
          patchOne(id, { busy: undefined, error: APPROVAL_ERRORS.alreadyDecided })
        } else {
          patchOne(id, { busy: undefined, error: APPROVAL_ERRORS.network })
        }
      } catch {
        patchOne(id, { busy: undefined, error: APPROVAL_ERRORS.network })
      }
    },
    [commit, patchOne, upsert],
  )

  const approve = useCallback((id: string) => answer(id, 'approve'), [answer])
  const deny = useCallback((id: string) => answer(id, 'deny'), [answer])

  return { approvals, approve, deny }
}
