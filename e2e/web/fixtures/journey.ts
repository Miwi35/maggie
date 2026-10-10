import path from 'node:path'
import type { TestInfo } from '@playwright/test'

/**
 * Who is calling — the journey identity of « Sélection e2e par couverture ».
 *
 * Every request a spec makes carries `X-E2E-Journey: <id>`, the spec's path in
 * the repository (`e2e/web/tests/chat.spec.ts`). The API and the agent read it
 * to file the lines they ran under that journey; nothing else looks at it. It
 * is sent on every run, not only the nightly's: it costs one header, and a
 * header that only exists at night would break at night.
 */
export const JOURNEY_HEADER = 'X-E2E-Journey'

/** Where the specs live in the repository, whatever directory the runner mounted them at. */
const REPO_TESTS_DIR = 'e2e/web/tests'

/**
 * The spec's id: its path from the repository root.
 *
 * Built from `testDir`, not from the absolute path: in the container the specs
 * are mounted at `/e2e/tests`, which says nothing about where they sit in the
 * repository.
 */
export function journeyId(testInfo: Pick<TestInfo, 'file' | 'project'>): string {
  const relative = path.relative(testInfo.project.testDir, testInfo.file).split(path.sep).join('/')

  return `${REPO_TESTS_DIR}/${relative}`
}

/** The headers a request context of this spec carries. */
export function journeyHeaders(testInfo: Pick<TestInfo, 'file' | 'project'>): Record<string, string> {
  return { [JOURNEY_HEADER]: journeyId(testInfo) }
}

/** `e2e/web/tests/chat.spec.ts` → `e2e_web_tests_chat_spec_ts`: the file name of its coverage. */
export function journeySlug(id: string): string {
  return id.replace(/[/.]/g, '_')
}
