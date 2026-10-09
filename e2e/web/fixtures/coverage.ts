import { mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import type { BrowserContext, Page, TestInfo } from '@playwright/test'
import { executedBitmap, executedLines, indexScript, repoPathOf } from './coverage-lines.js'
import type { IndexedScript } from './coverage-lines.js'
import { journeyId, journeySlug } from './journey.js'

/**
 * The admin's JS coverage, per spec (« Sélection e2e par couverture », part B).
 *
 * Off unless `E2E_COVERAGE=1` — the nightly. Then every page a test opens
 * records Chromium's precise coverage; when the page goes away its scripts are
 * mapped back to `admin/src` through the bundle's source maps (the e2e admin
 * build emits them under the same flag, see `admin/vite.config.ts`), and the
 * lines are written as one part file per test. `global-teardown.ts` folds the
 * parts of a spec into `e2e/coverage/raw/admin/<slug>.json`:
 *
 *     {"journey": "e2e/web/tests/chat.spec.ts", "files": {"admin/src/…": [12, 13, …]}}
 *
 * A line any test of the spec ran belongs to the spec.
 */

export const coverageEnabled = (): boolean => '1' === process.env.E2E_COVERAGE

/** `e2e/coverage/` — `/coverage` in the container, which mounts it there. */
export function coverageDir(): string {
  return process.env.E2E_COVERAGE_DIR ?? fileURLToPath(new URL('../../coverage', import.meta.url))
}

/** The parts of the specs, before `global-teardown.ts` folds them. */
const partsDir = () => path.join(coverageDir(), 'parts', 'admin')
/** The contract's output. */
const rawDir = () => path.join(coverageDir(), 'raw', 'admin')

/** Where the bundle is served, and where it sits in the repository (`base` and `outDir` in vite.config.ts). */
const ADMIN_BASE_PATH = '/admin/'
const ADMIN_DIST_IN_REPO = 'admin/dist'
const KEEP_PREFIX = 'admin/src/'

/**
 * A bundle's decoded map, once per worker and per URL: Vite hashes the file
 * names, so a URL is one content. `null` when the script is not the admin's or
 * has no usable map.
 */
const scripts = new Map<string, Promise<IndexedScript | null>>()
const warned = new Set<string>()

function warnOnce(key: string, message: string): void {
  if (!warned.has(key)) {
    warned.add(key)
    console.warn(`[e2e coverage] ${message}`)
  }
}

async function loadScript(url: string, source: string): Promise<IndexedScript | null> {
  const scriptPath = new URL(url).pathname
  if (!scriptPath.startsWith(ADMIN_BASE_PATH) || !scriptPath.endsWith('.js')) {
    return null
  }

  const reference = /\/\/# sourceMappingURL=(\S+)\s*$/.exec(source)?.[1]
  if (!reference || reference.startsWith('data:')) {
    warnOnce('no-map', `${scriptPath} points at no source map: was the admin built with E2E_COVERAGE=1 (task e2e:admin:build)?`)

    return null
  }

  const mapUrl = new URL(reference, url)
  let map
  try {
    const response = await fetch(mapUrl)
    map = response.ok ? await response.json() : null
  } catch {
    map = null
  }
  if (!map || !Array.isArray(map.sources)) {
    warnOnce(`map:${mapUrl}`, `no source map at ${mapUrl}: its lines are not counted.`)

    return null
  }

  const mapDir = path.posix.join(ADMIN_DIST_IN_REPO, path.posix.dirname(mapUrl.pathname.slice(ADMIN_BASE_PATH.length)))

  return indexScript(source, map, (mapSource) => repoPathOf(mapDir, mapSource, KEEP_PREFIX))
}

/** The coverage of one test: the pages it opened, and the lines they ran. */
export class AdminCoverage {
  private readonly pages = new Set<Page>()
  private readonly lines = new Map<string, Set<number>>()

  constructor(private readonly enabled: boolean = coverageEnabled()) {}

  /** Starts recording a page. Before its first navigation, or what that navigation ran is lost. */
  async watch(page: Page): Promise<void> {
    // `page.coverage` is Chromium's alone; every project here is Chromium, but
    // a Firefox one added tomorrow must run, uncounted, rather than throw.
    if (!this.enabled || !page.coverage) {
      return
    }
    // Navigations are kept: a journey goes from screen to screen with `goto`.
    await page.coverage.startJSCoverage({ resetOnNavigation: false, reportAnonymousScripts: false })
    this.pages.add(page)
  }

  /** Stops recording a page and maps what it ran. Before the page closes: a closed page has nothing to give. */
  async collect(page: Page): Promise<void> {
    if (!this.pages.delete(page)) {
      return
    }

    let entries
    try {
      entries = await page.coverage.stopJSCoverage()
    } catch (error) {
      warnOnce(`stop:${String(error)}`, `a page closed before its coverage was read: ${String(error)}`)

      return
    }

    for (const entry of entries) {
      if (!entry.source) {
        continue
      }
      let script = scripts.get(entry.url)
      if (!script) {
        script = loadScript(entry.url, entry.source)
        scripts.set(entry.url, script)
      }
      const indexed = await script
      if (indexed) {
        executedLines(indexed, executedBitmap(entry.source.length, entry.functions), this.lines)
      }
    }
  }

  /** Collects every recorded page of a context, then closes it. */
  async closeContext(context: BrowserContext): Promise<void> {
    await Promise.all(context.pages().map((page) => this.collect(page)))
    await context.close()
  }

  /** Writes this test's part: what its pages ran, under its spec's slug. */
  async write(testInfo: TestInfo): Promise<void> {
    if (!this.enabled) {
      return
    }
    await Promise.all([...this.pages].map((page) => this.collect(page)))

    const journey = journeyId(testInfo)
    const dir = path.join(partsDir(), journeySlug(journey))
    const name = `${testInfo.testId}-${testInfo.project.name}-${testInfo.retry}.json`.replace(/[^\w.-]/g, '_')
    await mkdir(dir, { recursive: true })
    await writeFile(path.join(dir, name), JSON.stringify({ journey, files: toJson(this.lines) }))
  }
}

function toJson(lines: Map<string, Set<number>>): Record<string, number[]> {
  const files: Record<string, number[]> = {}
  for (const file of [...lines.keys()].sort()) {
    files[file] = [...(lines.get(file) ?? [])].sort((a, b) => a - b)
  }

  return files
}

interface RawCoverage {
  journey: string
  files: Record<string, number[]>
}

/**
 * Folds the parts of each spec into its raw file, and removes them.
 *
 * Merged with a raw file already there rather than replacing it: a spec split
 * across two runs on the same disk (a retry run, `--last-failed`) keeps the
 * lines of both. Shards on separate machines each write their own file — the
 * union across them is the map builder's job (part C).
 */
export async function foldCoverageParts(): Promise<number> {
  let specs: string[]
  try {
    specs = await readdir(partsDir())
  } catch {
    return 0
  }

  await mkdir(rawDir(), { recursive: true })
  for (const slug of specs) {
    const merged = new Map<string, Set<number>>()
    let journey = ''
    const add = (part: RawCoverage) => {
      journey = part.journey
      for (const [file, lines] of Object.entries(part.files)) {
        const set = merged.get(file) ?? new Set<number>()
        lines.forEach((line) => set.add(line))
        merged.set(file, set)
      }
    }

    const target = path.join(rawDir(), `${slug}.json`)
    try {
      add(JSON.parse(await readFile(target, 'utf8')) as RawCoverage)
    } catch {
      // No earlier run: nothing to merge with.
    }
    for (const part of await readdir(path.join(partsDir(), slug))) {
      add(JSON.parse(await readFile(path.join(partsDir(), slug, part), 'utf8')) as RawCoverage)
    }
    await writeFile(target, `${JSON.stringify({ journey, files: toJson(merged) })}\n`)
  }
  await rm(partsDir(), { recursive: true, force: true })

  return specs.length
}
