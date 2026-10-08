import { SEARCH_INDEX_CONFIG } from '../../modules/search/searchConfig'

/** Where an interruption comes from — what its action does depends on it. */
export type InterruptionSource = 'proaction' | 'notification' | 'approval'

export interface NotificationLink {
  path: string
  label: string
}

interface Common {
  /** Unique across sources: `notification:01J…`. */
  id: string
  /** The source's own id: the notification's `@id`, the approval's id. */
  ref: string
  title?: string
  message: string
}

export type Interruption =
  | (Common & { source: 'proaction' })
  | (Common & { source: 'notification'; link: NotificationLink | null })
  | (Common & { source: 'approval' })

/** What one message of the shared feed means for the interruption queue. */
export type FeedEvent =
  | { kind: 'interrupt'; interruption: Interruption }
  /** Answered, read or deleted elsewhere, or expired: it must not be said any more. */
  | { kind: 'withdraw'; id: string }

const FINANCE_PATHS: Record<string, string> = {
  '': '/finance/dashboard',
  accounts: '/accounts',
  budgets: '/envelopes',
  categories: '/categories',
  rules: '/categorization_rules',
  'rule-suggestions': '/categorization_rules',
  banks: '/finance/banks',
  cushion: '/finance/cushion',
  loans: '/loans',
  review: '/finance/monthly-review',
}

const ENTITY_LABELS: Record<string, string> = {
  events: "Voir l'événement",
  tasks: 'Voir la tâche',
  grocery_items: 'Voir la liste de courses',
  recipes: 'Voir la recette',
}

/**
 * The admin screen a notification's `relatedEntityIri` opens, with the label of
 * the button that goes there — the same targets and words as the push on the phone.
 */
export function notificationLink(iri: string | null | undefined): NotificationLink | null {
  if (!iri) return null
  const path = iri.split(/[?#]/)[0]

  const entity = /^\/api\/([a-z_]+)\/([0-9A-Za-z-]{1,64})$/.exec(path)
  if (entity) {
    const [, resource] = entity
    if (resource === 'grocery_items') return { path: '/grocery', label: ENTITY_LABELS[resource] }
    const config = SEARCH_INDEX_CONFIG[resource]
    if (!config || !ENTITY_LABELS[resource]) return null
    return {
      path: config.getPath ? config.getPath(path) : `${config.basePath}/${encodeURIComponent(path)}/show`,
      label: ENTITY_LABELS[resource],
    }
  }

  const finance = /^\/finance(?:\/([a-z-]+))?\/?$/.exec(path)
  if (finance) {
    const screen = finance[1] ?? ''
    if (!(screen in FINANCE_PATHS)) return null
    return {
      path: FINANCE_PATHS[screen],
      label: screen === 'banks' ? 'Reconnecter la banque' : 'Ouvrir les finances',
    }
  }

  return null
}

/** A reminder stores how many minutes ahead it fires, not a sentence. */
function notificationMessage(type: string, body: unknown): string {
  if (typeof body !== 'string' || !body.trim()) return ''
  if (type === 'reminder' && /^\d+$/.test(body.trim())) return `Dans ${Number(body)} min`
  return body
}

const isText = (value: unknown): value is string => typeof value === 'string' && value.trim() !== ''

/**
 * Reads one message of the shared feed. Each source is recognised by its shape —
 * the feed also carries the chat's contexts, which are none of these.
 *
 * - a finished proaction with an answer (`/proactions/{userId}`);
 * - a notification just created and still unread (`/users/{userId}/api/notifications/{id}`):
 *   a reminder, a proaction, a task due, anything Maggie or the API raises on its own.
 *   The `approval` type is left to the approval itself, which can be answered;
 * - an action held until the user answers (`/approvals/{userId}`).
 */
export function readFeedMessage(raw: string): FeedEvent | null {
  let data: Record<string, unknown>
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed !== 'object' || parsed === null) return null
    data = parsed as Record<string, unknown>
  } catch {
    return null
  }

  const notificationRef = typeof data['@id'] === 'string' && data['@id'].startsWith('/api/notifications/') ? data['@id'] : null
  if (notificationRef) {
    // Read or deleted elsewhere (the bell, the phone, another tab): not worth saying any more.
    if (data.deleted || data.readAt) return { kind: 'withdraw', id: `notification:${notificationRef}` }
    if (data.type === 'approval' || !isText(data.title)) return null
    const type = typeof data.type === 'string' ? data.type : ''
    return {
      kind: 'interrupt',
      interruption: {
        source: 'notification',
        id: `notification:${notificationRef}`,
        ref: notificationRef,
        title: data.title,
        message: notificationMessage(type, data.body),
        link: notificationLink(typeof data.relatedEntityIri === 'string' ? data.relatedEntityIri : null),
      },
    }
  }

  if (typeof data.toolName === 'string' && typeof data.id === 'string') {
    if (data.status !== 'pending') return { kind: 'withdraw', id: `approval:${data.id}` }
    if (typeof data.expiresAt === 'string' && Date.parse(data.expiresAt) <= Date.now()) return null
    return {
      kind: 'interrupt',
      interruption: {
        source: 'approval',
        id: `approval:${data.id}`,
        ref: data.id,
        message: isText(data.summary) ? data.summary : data.toolName,
      },
    }
  }

  if (data.status === 'completed' && isText(data.response) && typeof data.id === 'string') {
    return {
      kind: 'interrupt',
      interruption: { source: 'proaction', id: `proaction:${data.id}`, ref: data.id, message: data.response },
    }
  }

  return null
}
