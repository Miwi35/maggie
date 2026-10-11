import { coverageDir, coverageEnabled, foldCoverageParts } from './fixtures/coverage.js'

/**
 * Folds the admin coverage of each spec into one file (`E2E_COVERAGE=1` only):
 * the tests of a spec run on several workers, each writing a part of its own,
 * and the contract wants one file per journey. See `fixtures/coverage.ts`.
 */
export default async function globalTeardown(): Promise<void> {
  if (!coverageEnabled()) {
    return
  }

  const specs = await foldCoverageParts()
  console.log(`[e2e coverage] admin: ${specs} spec(s) written to ${coverageDir()}/raw/admin`)
  if (0 === specs) {
    console.warn('[e2e coverage] admin: no coverage recorded — is the browser Chromium, and did any test open a page?')
  }
}
