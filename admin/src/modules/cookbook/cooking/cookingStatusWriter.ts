import type { CookingOrigin } from './cookingSession'
import { deleteCookingStatus, fetchCookingStatus, putCookingStatus } from './cookingStatusApi'
import type { CookingStatus } from './cookingStatusApi'

interface Pending {
  origin: CookingOrigin
  state: unknown
}

/**
 * Writes the activity's state to the API in order: never two requests in flight, and while one is
 * out only the latest state follows. A failure is dropped, the next change writes again.
 */
export const createStatusWriter = (put: typeof putCookingStatus = putCookingStatus, remove: typeof deleteCookingStatus = deleteCookingStatus) => {
  let chain: Promise<void> = Promise.resolve()
  let pending: Pending | null = null

  const flush = async () => {
    const next = pending
    pending = null
    if (next) await put(next.origin, next.state).catch(() => undefined)
  }

  return {
    save: (origin: CookingOrigin, state: unknown): void => {
      const queued = pending !== null
      pending = { origin, state }
      if (!queued) chain = chain.then(flush)
    },
    /** The activity is over or starts again: what waits is dropped, then the status is removed. */
    clear: (): void => {
      pending = null
      chain = chain.then(() => remove().catch(() => undefined))
    },
    /** Resolves once everything asked so far has been sent. */
    settled: (): Promise<void> => chain,
  }
}

export type StatusWriter = ReturnType<typeof createStatusWriter>

/** The one writer of the page: a status read after leaving waits for what the activity just wrote. */
export const statusWriter: StatusWriter = createStatusWriter()

export const readCookingStatus = async (signal?: AbortSignal): Promise<CookingStatus | null> => {
  await statusWriter.settled()

  return fetchCookingStatus(signal)
}
