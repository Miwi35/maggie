import { expect, test } from '@playwright/test'

test('probe: a red journey in one shard', async ({ page }) => {
  await page.goto('/')
  expect(1).toBe(2)
})
