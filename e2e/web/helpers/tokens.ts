import { readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'

export interface Tokens {
  surface: Record<'light' | 'dark', { background: string; text: string }>
  typography: { family: string; weight: Record<string, number> }
}

/** `design/tokens.json`, from `/e2e` in the container or from the checkout. */
export function readTokens(): Tokens {
  let candidate = resolve(process.cwd())
  for (;;) {
    const file = join(candidate, 'design', 'tokens.json')
    try {
      statSync(file)

      return JSON.parse(readFileSync(file, 'utf8')) as Tokens
    } catch {
      const parent = dirname(candidate)
      if (parent === candidate) break
      candidate = parent
    }
  }
  throw new Error(
    `No design/tokens.json found above ${process.cwd()}. docker-compose.e2e.yml mounts it ` +
      'at /design for the journeys — a failure here means the mount is gone.',
  )
}

export const TOKENS = readTokens()
