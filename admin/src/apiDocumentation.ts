import { parseHydraDocumentation } from '@api-platform/api-doc-parser'
import type { Resource } from '@api-platform/api-doc-parser'
import { clearSession, getToken, sessionAwaitsNetwork } from './auth/session'

export const RETRY_DELAYS_MS = [1000, 2000, 4000, 8000]

type ParserFailure = { status?: number }

// The parser rejects with `{ api, error, response, status }`: no status means
// the request never got an answer (network down, API restarting).
function isTransient(failure: unknown): boolean {
  const status = (failure as ParserFailure | null)?.status
  return status === undefined || status >= 500 || status === 408 || status === 429
}

const getAuthHeaders = (): HeadersInit => {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

type Parsed = Awaited<ReturnType<typeof parseHydraDocumentation>>

/**
 * A rule is an operation without recurrence (MAG-478): `/categorization_rules`
 * is a second API resource on the RecurringOperation entity, and Hydra
 * describes one class per entity — the rules are missing from the
 * documentation. Without them the guessers crash the rule pages and the data
 * provider cannot serialise a rule. Same entity, same fields: the rules are
 * described as the operations are, at their own path, until the screens merge
 * (part 4).
 */
export function withRuleResource(parsed: Parsed): Parsed {
  const resources = parsed.api.resources ?? []
  const operations = resources.find(({ name }) => name === 'recurring_operations')
  if (!operations || resources.some(({ name }) => name === 'categorization_rules')) {
    return parsed
  }

  const rules: Resource = Object.assign(Object.create(Object.getPrototypeOf(operations)), operations, {
    name: 'categorization_rules',
    url: operations.url.replace(/recurring_operations$/, 'categorization_rules'),
    title: 'CategorizationRule',
  })
  resources.push(rules)

  return parsed
}

/**
 * The Hydra documentation is fetched once, when the admin loads. A deploy
 * restarts the API for a few seconds: a tab that loads (or reloads) in that
 * window must wait for it rather than land on "Cannot fetch API documentation"
 * (MAG-374).
 */
export async function fetchApiDocumentation(
  entrypointUrl: string,
  parse: typeof parseHydraDocumentation = parseHydraDocumentation,
  delaysMs: number[] = RETRY_DELAYS_MS,
) {
  for (let attempt = 0; ; attempt++) {
    try {
      return withRuleResource(await parse(entrypointUrl, { headers: getAuthHeaders }))
    } catch (error) {
      const status = (error as ParserFailure).status
      if ((status === 401 || status === 403) && !sessionAwaitsNetwork()) {
        clearSession()
        window.location.reload()
        throw error
      }
      if (attempt >= delaysMs.length || !isTransient(error)) {
        throw error
      }
      await wait(delaysMs[attempt])
    }
  }
}
