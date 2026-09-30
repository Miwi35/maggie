import type { APIRequestContext } from '@playwright/test'

/**
 * Talking to the API from a journey — to set a test up, or to assert on the
 * database rather than on what the screen says.
 */

/** API Platform 4 puts collection members under `member`, not `hydra:member`. */
export function hydraMembers<T = Record<string, unknown>>(body: unknown): T[] {
  if (typeof body === 'object' && body !== null && Array.isArray((body as { member?: unknown }).member)) {
    return (body as { member: T[] }).member
  }

  return []
}

export async function getCollection<T = Record<string, unknown>>(
  api: APIRequestContext,
  path: string,
): Promise<T[]> {
  const response = await api.get(path, { headers: { Accept: 'application/ld+json' } })

  if (!response.ok()) {
    throw new Error(`GET ${path} answered ${response.status()}: ${await response.text()}`)
  }

  return hydraMembers<T>(await response.json())
}

/**
 * Polls a collection until a member matches.
 *
 * Every indexable entity is written through Doctrine, dispatched to RabbitMQ
 * and only then indexed — and the collection endpoints are served from
 * Elasticsearch, which refreshes on its own schedule. So a row exists before it
 * is findable, and reading once is how a working feature gets reported as
 * broken. This is the wait the e2e standard requires of every journey.
 *
 * The seed is exempt: `task e2e:seed` reindexes synchronously and refreshes.
 */
export async function waitForIndexed<T = Record<string, unknown>>(
  api: APIRequestContext,
  path: string,
  match: (member: T) => boolean,
  options: { timeout?: number; what?: string } = {},
): Promise<T> {
  const timeout = options.timeout ?? 30_000
  const deadline = Date.now() + timeout
  let lastSeen = 0

  for (;;) {
    const members = await getCollection<T>(api, path)
    lastSeen = members.length
    const found = members.find(match)

    if (found) {
      return found
    }

    if (Date.now() >= deadline) {
      throw new Error(
        `${options.what ?? 'A row'} never became findable on ${path} within ${timeout}ms ` +
          `(${lastSeen} member(s) returned). Is the worker running, and is the entity indexable?`,
      )
    }

    await new Promise((resolve) => setTimeout(resolve, 250))
  }
}
