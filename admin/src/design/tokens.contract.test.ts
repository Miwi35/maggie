import { readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

import { TOKENS, chartColor, contextStateColor, criticalityColor } from './tokens'

/**
 * `tokens.ts` against `design/tokens.json`, which the mobile theme reads too
 * (MAG-39).
 *
 * The mirror is hand-written — a generated module would make the Vite build
 * depend on a file outside `admin/` — so this is what keeps the two platforms
 * on one identity. Same shape as `useMercure.contract.test.ts`: read the file,
 * do not restate it, and **fail** rather than skip when it cannot be found. A
 * contract test that passes because it found nothing is worse than no test.
 */

/** The repository root, wherever this suite runs from: the checkout under CI, or
 * `task wt:test:admin`, which mounts `admin/` as `/app` and `design/` as `/design`. */
function tokenFile(): string {
  let candidate = resolve(process.cwd())
  for (;;) {
    const file = join(candidate, 'design', 'tokens.json')
    try {
      statSync(file)
      return file
    } catch {
      const parent = dirname(candidate)
      if (parent === candidate) break
      candidate = parent
    }
  }
  throw new Error(
    `No design/tokens.json found above ${process.cwd()}. It is the source of the ` +
      'design system (agent-os/standards/global/design-system.md); ' +
      '`task wt:test:admin` mounts it, so a failure here means the mount is gone.',
  )
}

const source = JSON.parse(readFileSync(tokenFile(), 'utf8')) as Record<string, unknown>

describe('the design tokens', () => {
  it('mirror design/tokens.json exactly, group by group', () => {
    // Compared as a whole rather than key by key: a group *added* to the source
    // and forgotten here has to fail too, and `toEqual` on the whole object is
    // the only assertion that catches that.
    expect(JSON.parse(JSON.stringify(TOKENS))).toEqual(source)
  })

  it('name a signal for every criticality the API can send', () => {
    // The chip's colour; an unknown criticality must still draw something.
    expect(criticalityColor('low')).toBe(TOKENS.signal.success)
    expect(criticalityColor('medium')).toBe(TOKENS.signal.warning)
    expect(criticalityColor('high')).toBe(TOKENS.signal.danger)
    expect(criticalityColor('critical')).toBe(TOKENS.signal.critical)
    expect(criticalityColor('whatever-comes-next')).toBe(TOKENS.signal.success)
  })

  it('name a signal for every state a conversation thread can be in', () => {
    expect(contextStateColor('active')).toBe(TOKENS.signal.success)
    expect(contextStateColor('dormant')).toBe(TOKENS.signal.warning)
    expect(contextStateColor('closed')).toBe(TOKENS.signal.neutral)
    expect(contextStateColor('whatever-comes-next')).toBe(TOKENS.signal.neutral)
  })

  it('number the chart slots the way a legend does', () => {
    expect(chartColor(1, 'light')).toBe('#2A78D6')
    expect(chartColor(2, 'dark')).toBe('#D95926')
  })

  it('ask only for font weights admin/index.html loads', () => {
    // The link element loads `wght@400;500;600;700`. A weight outside that set
    // is synthesised by the browser — which is what made `h5` faux-bold until
    // MAG-39 — so the token file may not hold one either.
    // Vitest runs from `admin/` — the checkout's, or `/app` under `task wt:test:admin`.
    const link = readFileSync(join(process.cwd(), 'index.html'), 'utf8')
    const loaded = /Gabarito:wght@([\d;]+)/.exec(link)?.[1].split(';').map(Number)

    expect(loaded, 'admin/index.html no longer loads Gabarito by weight').toBeDefined()
    expect(loaded).toEqual(Object.values(TOKENS.typography.weight))
  })
})
