import { readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'

/** A response recorded in `api/contract/responses/`, found from wherever the suite runs (the checkout, or `task wt:test:admin`). */
export const recordedResponse = <T>(name: string): T => {
  let candidate = resolve(process.cwd())
  for (;;) {
    const file = join(candidate, 'api', 'contract', 'responses', name)
    try {
      statSync(file)

      return JSON.parse(readFileSync(file, 'utf8')) as T
    } catch {
      const parent = dirname(candidate)
      if (parent === candidate) throw new Error(`No api/contract/responses/${name} above ${process.cwd()}: UPDATE_CONTRACT=1 task wt:test:api -- --testsuite Contract`)
      candidate = parent
    }
  }
}
