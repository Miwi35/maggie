import { describe, expect, test } from 'vitest'
import { bilanBlockOf, bilanMoment } from './bilanBlock'

describe('bilanBlock', () => {
  test('reads the bilan block', () => {
    const block = { type: 'bilan', day: '2026-10-26', route: 'maggie://activity/bilan' }
    expect(bilanBlockOf({ blocks: [{ type: 'digest' }, block] })).toEqual(block)
    expect(bilanBlockOf({ blocks: [{ type: 'digest' }] })).toBeNull()
    expect(bilanBlockOf({ blocks: null })).toBeNull()
    expect(bilanBlockOf(undefined)).toBeNull()
  })

  test('the moment says the short day and five steps', () => {
    expect(bilanMoment('2026-10-26')).toBe('lun. 26 oct. · 5 étapes')
  })
})
