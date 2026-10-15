import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { assistantText, parseAgUiStream } from '../helpers/agui.js'
import { withChatLock, withLock } from '../helpers/chatLock.js'
import { DashboardPage } from '../pages/DashboardPage.js'

/**
 * The night's upkeep (MAG-159, extends MAG-99 and MAG-17).
 *
 * Two conversations of the day: the owner moves to Rennes, then says « finalement, je m'installe à Lyon ».
 * The encoding pass writes the first fiche and doubts the second (it decides nothing); the night then reads
 * the second conversation, corrects the fiche — Lyon wins, Rennes stays, dated and marked replaced — renames
 * it after what it now says, keeps its account, and says all of it in `journal.md`. The model is the fake
 * (13-memory-night-*, 14-memory-night-*) and the bucket the simulated one.
 *
 * The stack's clock barely ticks in CI (MAG-234): the journey asks the agent to run the encoding and then the
 * night at an instant past their delays, for this user only (`POST /e2e/memory/{user}/encode` and `/night`).
 */

const MEMORY_LOCK = 'memory-folder'
const TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'
const headers = { 'X-E2E-Token': TOKEN, 'Content-Type': 'application/json' }

const RENNES_SENTENCE = "J'ai emménagé à Rennes, je suis arrivé hier."
const LYON_SENTENCE = "Finalement, je m'installe à Lyon, le contrat a changé."
const OLD_FICHE = 'Lieu de vie/Le propriétaire habite à Rennes.md'
const NEW_FICHE = 'Lieu de vie/Le propriétaire habite à Lyon.md'
const RENNES = 'Le propriétaire habite à Rennes'
const LYON = 'Le propriétaire habite à Lyon'

interface State {
  local: string[]
}

interface NightAccount {
  status: string
  calls: number
  report: {
    cartes: string[]
    corrigees: string[]
    renommees: { de: string; a: string }[]
    questions: { chemin: string; question: string }[]
  }
}

const encode = (path: string): string => path.split('/').map(encodeURIComponent).join('/')

async function call<T>(api: APIRequestContext, method: 'get' | 'post' | 'delete', path: string): Promise<T> {
  const response = await api[method](`/agent/e2e/memory/${path}`, { headers })
  expect(response.status(), `${method.toUpperCase()} ${path}`).toBe(200)
  return (await response.json()) as T
}

test('the night corrects a fiche that a later conversation contradicts, keeps the old wording, and the journal says why', async ({
  otherUser,
}) => {
  await withLock(MEMORY_LOCK, async () => {
    const { page, api, session } = otherUser
    const user = session.user.id
    const state = async (): Promise<State> => call<State>(api, 'get', `${user}/state`)
    const inBucket = async (path: string): Promise<string> =>
      (await call<{ text: string }>(api, 'get', `bucket/${user}/${encode(path)}`)).text
    const encodeNow = async (): Promise<number> => (await call<{ encoded: number }>(api, 'post', `${user}/encode`)).encoded
    const nightNow = async (): Promise<NightAccount> => call<NightAccount>(api, 'post', `${user}/night`)
    const push = async (): Promise<void> => {
      expect((await call<{ ok: boolean }>(api, 'post', `${user}/sync`)).ok).toBe(true)
    }
    const say = async (message: string): Promise<string> =>
      withChatLock(async () => {
        const body = await page.evaluate(
          async ({ text, token }) => {
            const response = await fetch('/agent/chat/stream', {
              method: 'POST',
              headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
              body: JSON.stringify({ message: text }),
            })
            return response.text()
          },
          { text: message, token: session.token },
        )
        return assistantText(parseAgUiStream(body))
      })

    await call(api, 'delete', user)
    await new DashboardPage(page).open()

    // Given two conversations of the day, the second contradicting a fiche the first gave birth to,
    expect(await say(RENNES_SENTENCE)).toContain('Rennes')
    const fichePresent = async (): Promise<boolean> => {
      await encodeNow()
      await push()
      return (await state()).local.includes(OLD_FICHE)
    }
    await expect.poll(fichePresent, { timeout: 60_000 }).toBe(true)
    expect(await say(LYON_SENTENCE)).toContain('Lyon')
    const twoCartes = async (): Promise<boolean> => {
      await encodeNow()
      await push()
      const files = (await state()).local.filter((f) => f.startsWith('Conversations/'))
      if (files.length < 2) return false
      const heads = await Promise.all(files.map(async (f) => (await inBucket(f)).split('# ')[0]))
      return heads.every((head) => head.includes('resume:'))
    }
    await expect.poll(twoCartes, { timeout: 60_000 }).toBe(true)
    const ficheBefore = await inBucket(OLD_FICHE)
    expect(ficheBefore).toContain(`- ${RENNES}`)
    expect(ficheBefore).not.toContain(LYON)

    // When the night passes,
    const account = await nightNow()
    await push()

    // Then it is done, and its account says what it changed.
    expect(account.status).toBe('done')
    expect(account.report.corrigees).toEqual([OLD_FICHE])
    expect(account.report.renommees).toEqual([{ de: OLD_FICHE, a: NEW_FICHE }])

    // And the fiche says Lyon, citing the conversation and the minute, and keeps Rennes dated and replaced.
    const files = (await state()).local
    expect(files).toContain(NEW_FICHE)
    expect(files).not.toContain(OLD_FICHE)
    const fiche = await inBucket(NEW_FICHE)
    // The frontmatter keeps the old name as an alias on purpose: only the body says what is true.
    const body = fiche.slice(fiche.indexOf("## Ce qu'on sait"))
    const [known, replaced] = body.split("Ce qui n'est plus vrai")
    expect(known).toMatch(new RegExp(`- ${LYON} — dit · Conversations/\\S.* · \\d{4}-\\d{2}-\\d{2} \\d{2}h\\d{2}`))
    expect(known).not.toContain(`- ${RENNES}`)
    expect(replaced).toMatch(new RegExp(`- ~~${RENNES}~~ — dit · .* — remplacé le \\d{4}-\\d{2}-\\d{2} : `))

    // And every conversation of the day has its carte, the messages below it untouched.
    const conversations = files.filter((f) => f.startsWith('Conversations/'))
    expect(conversations).toHaveLength(2)
    for (const path of conversations) {
      const text = await inBucket(path)
      expect(text.split('# ')[0]).toContain('resume:')
      expect(text.includes(RENNES_SENTENCE) || text.includes(LYON_SENTENCE)).toBe(true)
    }

    // And the journal lets one rebuild what the night changed and why.
    const journalSaysIt = async (): Promise<boolean> => {
      await push()
      return /entretien nocturne du \d{4}-\d{2}-\d{2} \(\d+ appel\(s\) au modèle\)/.test(await inBucket('journal.md'))
    }
    await expect.poll(journalSaysIt, { timeout: 15_000 }).toBe(true)
    const journal = await inBucket('journal.md')
    expect(journal).toContain(`\`${OLD_FICHE}\` → \`${NEW_FICHE}\``)
    expect(journal).toMatch(/modifié `Lieu de vie\/[^`]*` — entretien nocturne du/)
    expect(journal).toMatch(/fiche\(s\) corrigée\(s\)/)

    // A second night on the same day finds nothing new: the same files, no new line in the journal.
    const before = (await state()).local
    const second = await nightNow()
    await push()
    expect(second.status).toBe('done')
    expect((await state()).local).toEqual(before)
    expect((await inBucket('journal.md')).match(/entretien nocturne du \d{4}-\d{2}-\d{2} \(/g)).toHaveLength(
      journal.match(/entretien nocturne du \d{4}-\d{2}-\d{2} \(/g)!.length,
    )

    await call(api, 'delete', user)
  })
})
