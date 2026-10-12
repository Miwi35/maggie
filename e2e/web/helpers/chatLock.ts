import { mkdir, rm, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/**
 * One run at a time under a named lock, across the workers of a shard.
 *
 * The chat lock: journeys that talk to Maggie share the second account, and the agent builds
 * the history from the user's stored messages: a message sent while another
 * run of that account is still open lands in the same turn ("J'ai terminé mes
 * courses\nPlanifie la recette…"), and the fake LLM answers the older scenario.
 * Two workers sending at once is all it takes. Other names guard other
 * account-wide state, such as the default agenda.
 */
const lockPath = (name: string): string => join(tmpdir(), `maggie-e2e-${name}.lock`)
const STALE_MS = 90_000
const GIVE_UP_MS = 150_000

const pause = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms))

async function acquire(lock: string): Promise<void> {
  const giveUpAt = Date.now() + GIVE_UP_MS

  for (;;) {
    try {
      await mkdir(lock)
      return
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error
    }

    // A worker killed mid-run leaves its lock behind.
    const age = await stat(lock).then(
      (found) => Date.now() - found.mtimeMs,
      () => 0,
    )
    if (age > STALE_MS) await rm(lock, { recursive: true, force: true })

    if (Date.now() > giveUpAt) throw new Error(`another run held ${lock} for ${GIVE_UP_MS} ms`)
    await pause(100)
  }
}

export async function withLock<T>(name: string, run: () => Promise<T>): Promise<T> {
  const lock = lockPath(name)
  await acquire(lock)
  try {
    return await run()
  } finally {
    await rm(lock, { recursive: true, force: true })
  }
}

export const withChatLock = <T>(run: () => Promise<T>): Promise<T> => withLock('chat', run)
