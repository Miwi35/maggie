import type { APIRequestContext } from '@playwright/test'
import { test, expect } from '../fixtures/index.js'
import { withLock } from '../helpers/chatLock.js'
import { HomePage } from '../pages/HomePage.js'

/**
 * The habits the night reads in the owner's data (MAG-18, extends MAG-99 and MAG-159).
 *
 * The owner keeps an agenda « Concerts » with five shows of stoner, psychedelic and doom bands. The weekly look
 * of the night reads the app's data through MCP (the real server, the real database), asks the model — the fake
 * (14-memory-night-habits-*) — what they show, and writes the answer as a fiche: provenance `observé`, the
 * period it was read over, and each show as its proof. The model cites the shows by the keys the code gave
 * them; the proof lines are the code's own, never the model's words. A second night the same week reads
 * nothing again.
 *
 * An account of its own: nothing else writes its agenda or its memory.
 */

test.use({ journeyAccount: 'empty' })

const MEMORY_LOCK = 'memory-folder'
const TOKEN = process.env.E2E_LOGIN_TOKEN ?? 'e2e-login-token'
const headers = { 'X-E2E-Token': TOKEN, 'Content-Type': 'application/json' }
const JSON_LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

const BANDS = ['Black Wizards', 'Moundrag', 'Red Sun Atacama', 'Kadavar', 'Slift']
const TASTES = 'Le propriétaire aime le stoner, le psyché, le doom et le heavy rock des années 70'

interface Night {
  status: string
  report: { habitudes?: { chemin: string; preuves: number }[] }
}

const encode = (path: string): string => path.split('/').map(encodeURIComponent).join('/')

async function call<T>(api: APIRequestContext, method: 'get' | 'post' | 'delete', path: string): Promise<T> {
  const response = await api[method](`/agent/e2e/memory/${path}`, { headers })
  expect(response.status(), `${method.toUpperCase()} ${path}`).toBe(200)
  return (await response.json()) as T
}

const daysAgo = (days: number): string => new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10)

test('the night reads the concerts of the agenda and writes the musical tastes as an observed fiche, with its proofs', async ({
  page,
  api,
  session,
}) => {
  await withLock(MEMORY_LOCK, async () => {
    const user = session.user.id
    const inBucket = async (path: string): Promise<string> =>
      (await call<{ text: string }>(api, 'get', `bucket/${user}/${encode(path)}`)).text
    const push = async (): Promise<void> => {
      expect((await call<{ ok: boolean }>(api, 'post', `${user}/sync`)).ok).toBe(true)
    }
    const files = async (): Promise<string[]> => (await call<{ local: string[] }>(api, 'get', `${user}/state`)).local

    await call(api, 'delete', user)
    await new HomePage(page).open()

    // Given an agenda « Concerts » with five shows, two weeks apart, over the last ten weeks,
    const agenda = await api.post('/api/agendas', {
      headers: JSON_LD,
      data: { name: 'Concerts', color: '#6d4c41', timeZone: 'Europe/Paris' },
    })
    expect(agenda.status()).toBe(201)
    const agendaId = ((await agenda.json()) as { id: string }).id
    const days = BANDS.map((_, index) => daysAgo(70 - index * 14))
    for (const [index, band] of BANDS.entries()) {
      const created = await api.post('/api/events', {
        headers: JSON_LD,
        data: {
          summary: band,
          startAt: `${days[index]}T19:00:00+00:00`,
          endAt: `${days[index]}T21:00:00+00:00`,
          agenda: `/api/agendas/${agendaId}`,
        },
      })
      expect(created.status()).toBe(201)
    }

    // When the night passes,
    const night = await call<Night>(api, 'post', `${user}/night`)
    await push()

    // Then it is done, and its account says one habit was read, on five proofs.
    expect(night.status).toBe('done')
    expect(night.report.habitudes).toHaveLength(1)
    expect(night.report.habitudes?.[0].preuves).toBe(BANDS.length)

    // And the fiche exists, in provenance `observé`, sourced on the agenda and the period it was read over,
    const [path] = (await files()).filter((f) => f.startsWith('Goûts/'))
    expect(path).toBe(`Goûts/${TASTES}.md`)
    const fiche = await inBucket(path)
    const known = fiche.slice(fiche.indexOf("## Ce qu'on sait"))
    expect(known).toMatch(
      new RegExp(`- ${TASTES} — observé · app : agenda « Concerts », sur la période du \\d{4}-\\d{2}-\\d{2} au \\d{4}-\\d{2}-\\d{2}`),
    )

    // And each show is a proof the code wrote from the data, on its own day — not the model's words.
    for (const [index, band] of BANDS.entries()) {
      expect(known).toMatch(
        new RegExp(`- agenda « Concerts » : ${band} \\(\\S+ ${days[index]}[^)]*\\) — observé · app : agenda « Concerts », ${days[index]}`),
      )
    }

    // And the journal says it in a line.
    expect(await inBucket('journal.md')).toMatch(/entretien nocturne du .*1 habitude\(s\) lue\(s\) dans les données/)

    // A second night the same week reads the data no more: the same files, the same fiche.
    const before = await files()
    const second = await call<Night>(api, 'post', `${user}/night`)
    await push()
    expect(second.status).toBe('done')
    expect(second.report.habitudes ?? []).toEqual([])
    expect(await files()).toEqual(before)
    expect(await inBucket(path)).toBe(fiche)

    await call(api, 'delete', user)
  })
})
