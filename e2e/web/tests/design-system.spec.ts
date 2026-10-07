import { readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { test, expect } from '../fixtures/index.js'
import type { Locator } from '@playwright/test'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * The app's identity, in the browser (MAG-39).
 *
 * Carried by MAG-39 itself rather than by one of the module journeys, as
 * `responsive.spec.ts` is carried by MAG-38: the identity is not a module, it is
 * every screen at once, and the surface worth asserting — the shell the owner
 * reads every page through — belongs to no feature.
 *
 * The expected values are read from `design/tokens.json`, mounted read-only
 * into the Playwright container, so they are not written a fourth time: the
 * source, the two mirrors, and here. A deliberate change of identity is one
 * edit and this journey follows it.
 *
 * What this cannot assert, and why it is not a gap: **that the glyphs are
 * Gabarito**. The stack aborts every off-origin request, Google Fonts included
 * (`e2e/web/README.md`), so the browser falls back to Tahoma and what is
 * readable here is the *declared* stack and the weight asked for. The weight is
 * the half that mattered: radiant asked `h5` for 900, `index.html` loads up to
 * 700, and the browser synthesised the difference on every page title in the
 * admin.
 */

interface Tokens {
  surface: { light: { background: string; text: string } }
  typography: { family: string; weight: Record<string, number> }
}

/** `design/tokens.json`, from `/e2e` in the container or from the checkout. */
function readTokens(): Tokens {
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

const TOKENS = readTokens()

/**
 * `rgb(26, 26, 46)` as `#1A1A2E`, so a failure prints the token, not three
 * numbers — and the alpha beside it, because that is what tells a colour from
 * no colour at all.
 */
const asColor = (computed: string): { hex: string; alpha: number } => {
  const channels = (computed.match(/\d+(\.\d+)?/g) ?? []).map(Number)
  const [r, g, b] = channels

  return {
    hex: `#${[r, g, b].map((channel) => channel.toString(16).padStart(2, '0')).join('')}`.toUpperCase(),
    // `getComputedStyle` writes `rgb(…)`, with no fourth channel, when opaque.
    alpha: channels.length > 3 ? channels[3] : 1,
  }
}

/**
 * The element's *painted* background. An unpainted one computes to
 * `rgba(0, 0, 0, 0)`, and read as a hex alone that is an indistinguishable
 * black: the failure would read « #000000 instead of #F0F1F6 » when what
 * happened is that nothing painted the element at all.
 */
const backgroundOf = async (locator: Locator, what: string): Promise<string> => {
  const computed = await locator.evaluate((element) => getComputedStyle(element).backgroundColor)
  const { hex, alpha } = asColor(computed)

  expect(alpha, `${what} is transparent (${computed}) — nothing painted it`).toBe(1)

  return hex
}

test('the shell is drawn on the light surface, and writes its titles in Gabarito at a weight it loads', async ({
  page,
}) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()
  await dashboard.expectReady()

  // MUI's CssBaseline paints `body` with `background.default` and `text.primary`.
  const body = page.locator('body')
  expect(await backgroundOf(body, 'the page background')).toBe(
    TOKENS.surface.light.background.toUpperCase(),
  )
  expect(
    await body
      .evaluate((element) => getComputedStyle(element).color)
      .then((computed) => asColor(computed).hex),
  ).toBe(TOKENS.surface.light.text.toUpperCase())

  const title = await dashboard.heading.evaluate((element) => {
    const style = getComputedStyle(element)

    return { family: style.fontFamily, weight: style.fontWeight }
  })

  expect(title.family.split(',')[0].replace(/["']/g, '')).toBe(TOKENS.typography.family.split(',')[0])
  expect(Number(title.weight)).toBe(TOKENS.typography.weight.bold)
})

test('no text on the dashboard asks for a weight the page does not load', async ({ page }) => {
  const dashboard = new DashboardPage(page)
  await dashboard.open()
  await dashboard.expectReady()

  // `index.html` loads `wght@400;500;600;700`. Anything else is synthesised by
  // the browser — faux-bold, and nobody notices until a screenshot is compared.
  // Each weight is kept with the first element that asked for it, so a failure
  // in CI names the element to go and look at rather than a bare number.
  const asked = await page.evaluate(() => {
    const seen = new Map<number, string>()
    Array.from(document.querySelectorAll('body *')).forEach((element) => {
      if (!element.textContent?.trim()) return
      const weight = Number(getComputedStyle(element).fontWeight)
      if (!seen.has(weight)) {
        // `getAttribute`, not `className`: on an SVG node the latter is an
        // `SVGAnimatedString` and prints as `[object …]`.
        const names = element.getAttribute('class')?.trim()
        const classes = names ? `.${names.split(/\s+/).join('.')}` : ''
        seen.set(weight, `<${element.tagName.toLowerCase()}${classes}>`)
      }
    })

    return Array.from(seen, ([weight, where]) => ({ weight, where }))
  })

  const loaded = Object.values(TOKENS.typography.weight)
  expect(asked.length, 'no text was found on the dashboard at all').toBeGreaterThan(0)
  expect(asked.filter(({ weight }) => !loaded.includes(weight))).toEqual([])
})
