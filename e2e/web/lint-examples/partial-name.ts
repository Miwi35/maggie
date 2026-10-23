import type { APIRequestContext } from '@playwright/test'
import { waitForIndexed } from '../helpers/api.js'

// Refused by `no-restricted-syntax` (checked by `check.mjs`, never run): « Gratin » also
// matches the rows of the seed and of the previous run. Both forms below are caught.
export async function partialName(api: APIRequestContext, name: string): Promise<void> {
  await waitForIndexed<{ summary: string }>(api, '/api/meals', (m) => m.summary.includes(name))
  await waitForIndexed<{ summary?: string }>(api, '/api/meals', (m) => String(m.summary).includes(name))
}
