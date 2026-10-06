/**
 * One turn with Maggie at a time, for the whole stack (MAG-211).
 *
 * The conversation is **shared state scoped to the account**, not to the test:
 * the chat routes store the message, then build the history the model is sent
 * out of the user's last messages — the thread's own, plus a short global
 * window across every thread (`agent/app/llm/history.py`). So two journeys
 * talking to Maggie as the same account at the same time each get the other's
 * question in their own context, and the fake model resolves its scenario on
 * the last thing the user said: an errand's « le magasin … est fermé » sitting
 * next to a meal plan's « Planifie la recette … » makes `move_to_fallback` run
 * on a journey about meals. That is run 37466997420 — the two sentences were
 * merged into one user turn, and `43-grocery-fallback.yaml` is read before
 * `47-meal-plan.yaml`.
 *
 * `chat.spec.ts` already says this in its own file, with
 * `test.describe.configure({ mode: 'serial' })`, for exactly this reason. What
 * it cannot do is reach the three *other* files that talk to Maggie as the
 * shopper account — `grocery-errand`, `meals-grocery`, `recipes-ciqual` — and
 * Playwright has no serial group that spans files. This is that group: a lock
 * every worker of the stack takes before it starts a turn and drops when the
 * run has finished.
 *
 * Not keyed by account, deliberately. A key is one more thing a journey can
 * get wrong — and a miskeyed lock is silent, which is the failure mode this
 * exists to remove. The cost of serialising the owner's turns against the
 * shopper's is a few seconds per shard, because the lock is held for the round
 * trip alone and nothing else of a test.
 *
 * The directory is the lock: `mkdir` is atomic and fails with `EEXIST`, which
 * is what makes this work between processes — Playwright workers are separate
 * ones, and `/tmp` is the journeys container's own, so two stacks on the same
 * machine never see each other's.
 */

import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const LOCK = join(tmpdir(), 'maggie-e2e-chat-turn.lock')

const POLL_MS = 25

/**
 * How long a turn may wait for its predecessor.
 *
 * Above `ChatPanel.submit`'s own 60s wait on the stream and below Playwright's
 * 90s test timeout, so a turn that is genuinely stuck is reported as that
 * rather than as a `press('Enter')` that never returned. A turn takes a second
 * or two, and the queue is at most `workers - 1` deep: reaching this means
 * something ahead of you hung, not that the suite is busy.
 */
const WAIT_TIMEOUT_MS = 75_000

/** Past this, the holder is a crashed worker rather than a slow one, and its lock is swept. */
const STALE_MS = 120_000

/**
 * The queue inside one worker process.
 *
 * The directory below only arbitrates *between* processes: two turns started
 * concurrently in the same one — a journey awaiting both halves of a `Promise.all`
 * — would both find the lock theirs and talk over each other. So they line up
 * here first, and only the head of the queue reaches for the directory.
 *
 * A turn nested in another turn would therefore wait for itself. Nothing nests
 * today, and the alternative — a re-entrant counter — lets the concurrent case
 * through, which is the one a journey can reach by accident.
 */
let queue: Promise<unknown> = Promise.resolve()

/**
 * Runs `turn` with nobody else talking to Maggie.
 *
 * `what` names the caller in the timeout message and in the lock file, so a
 * stuck turn says which journey is holding the conversation.
 */
export function withMaggieToItself<T>(what: string, turn: () => Promise<T>): Promise<T> {
  const mine = queue.then(() => exclusively(what, turn))

  // Swallowed on the queue only: a turn that failed must not fail the one behind
  // it, while `mine` keeps the rejection for its own caller.
  queue = mine.then(
    () => undefined,
    () => undefined,
  )

  return mine
}

async function exclusively<T>(what: string, turn: () => Promise<T>): Promise<T> {
  const mine = await acquire(what)

  try {
    return await turn()
  } finally {
    await release(mine)
  }
}

/** Takes the lock and returns the name written in it, which is the proof of ownership. */
async function acquire(what: string): Promise<string> {
  const deadline = Date.now() + WAIT_TIMEOUT_MS
  const mine = `${what} (pid ${process.pid}, ${deadline})`

  for (;;) {
    try {
      await mkdir(LOCK)
    } catch (error) {
      if ('EEXIST' !== (error as NodeJS.ErrnoException).code) {
        throw error
      }

      if (await abandoned()) {
        // Swept rather than waited on: a worker killed mid-turn would otherwise
        // hold the conversation for the rest of the run. Two workers deciding
        // that at once is harmless — one `rm` wins, one `mkdir` wins.
        await rm(LOCK, { recursive: true, force: true })
        continue
      }

      if (Date.now() >= deadline) {
        throw new Error(
          `Waited ${WAIT_TIMEOUT_MS / 1_000}s for « ${await holder()} » to finish its turn with Maggie, ` +
            `before starting ${what}. One of the chat journeys is stuck, not this one.`,
        )
      }

      await new Promise((resume) => setTimeout(resume, POLL_MS))
      continue
    }

    await writeFile(join(LOCK, 'holder'), mine)

    return mine
  }
}

/**
 * Drops the lock, but only while it is still ours.
 *
 * A turn that outlived {@link STALE_MS} has been swept and the lock belongs to
 * whoever took it next; removing it then would hand the conversation to a third
 * turn while the second is still mid-run. Rare by construction — no turn waits
 * longer than `ChatPanel.submit`'s own 60s — and silent if it ever happened,
 * which is why it is checked rather than reasoned about.
 */
async function release(mine: string): Promise<void> {
  if (mine !== (await holder())) {
    return
  }

  await rm(LOCK, { recursive: true, force: true })
}

async function abandoned(): Promise<boolean> {
  try {
    const { mtimeMs } = await stat(LOCK)

    return Date.now() - mtimeMs > STALE_MS
  } catch {
    // Gone between the EEXIST and the stat: free, not abandoned.
    return false
  }
}

async function holder(): Promise<string> {
  try {
    return await readFile(join(LOCK, 'holder'), 'utf8')
  } catch {
    return 'another journey'
  }
}
