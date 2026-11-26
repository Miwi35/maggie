import { describe, test, expect, vi, afterEach } from 'vitest'
import { postponedLabel, setTaskDone } from './taskViews'

// 2026-10-15 is a Thursday.
const TODAY = '2026-10-15'

describe('postponedLabel — « reportée depuis … », never a count of days late (MAG-497)', () => {
  test('nothing when the task is not put off, or only from today', () => {
    expect(postponedLabel(null, TODAY)).toBeNull()
    expect(postponedLabel(undefined, TODAY)).toBeNull()
    expect(postponedLabel(TODAY, TODAY)).toBeNull()
  })

  test('since yesterday', () => {
    expect(postponedLabel('2026-10-14', TODAY)).toBe('reportée depuis hier')
  })

  test('within the week, the weekday in lower case', () => {
    expect(postponedLabel('2026-10-13', TODAY)).toBe('reportée depuis mardi')
    expect(postponedLabel('2026-10-12', TODAY)).toBe('reportée depuis lundi')
    expect(postponedLabel('2026-10-09', TODAY)).toBe('reportée depuis vendredi')
  })

  test('a week or more, the date', () => {
    expect(postponedLabel('2026-10-08', TODAY)).toBe('reportée depuis le 8 oct.')
    expect(postponedLabel('2026-10-03', TODAY)).toBe('reportée depuis le 3 oct.')
  })
})

describe('setTaskDone (MAG-1033)', () => {
  afterEach(() => vi.unstubAllGlobals())

  test('answers the task the API sends back, nextOccurrence included', async () => {
    const body = { id: '01BIN', title: 'Sortir la poubelle', completedAt: '2026-10-15T08:00:00+00:00', nextOccurrence: '/api/tasks/01NEXT' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status: 200 })))

    await expect(setTaskDone('/api/tasks/01BIN', true)).resolves.toMatchObject({ nextOccurrence: '/api/tasks/01NEXT' })
  })
})
