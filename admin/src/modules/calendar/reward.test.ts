import { describe, test, expect } from 'vitest'
import { parseReward } from './reward'

const PALIER = {
  '@id': '/api/rewards/01RW',
  subjectType: 'saga',
  subjectId: '01CHAPTER',
  level: 'palier',
  messageKey: 'palier-doux.1',
  message: 'Un chapitre entier. Prends le temps de le savourer.',
  title: 'Chapitre 1 · Le départ',
  star: false,
  earnedAt: '2026-10-19T08:00:00+00:00',
}

describe('parseReward (MAG-509)', () => {
  test('reads the reward a Mercure message carries, its id from the IRI', () => {
    const reward = parseReward(JSON.stringify(PALIER))

    expect(reward).toMatchObject({ id: '01RW', level: 'palier', title: 'Chapitre 1 · Le départ', star: false, surprise: null })
  })

  test.each([
    ['nothing', undefined],
    ['text that is not JSON', 'not json'],
    ['a task published on the same stream', JSON.stringify({ '@id': '/api/tasks/01T', title: 'Arroser' })],
    ['an empty update', '{}'],
    ['a reward without its message', JSON.stringify({ '@id': '/api/rewards/01RW', level: 'standard' })],
  ])('ignores %s', (_, data) => {
    expect(parseReward(data)).toBeNull()
  })
})
