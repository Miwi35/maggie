import { describe, test, expect } from 'vitest'
import { kindHelp, provenanceSentence, senseOfAmount } from './qualificationKinds'

describe('senseOfAmount', () => {
  test('the sense is the sign of the amount', () => {
    expect(senseOfAmount(-2500)).toBe('out')
    expect(senseOfAmount(2500)).toBe('in')
    expect(senseOfAmount(0)).toBe('unknown')
    expect(senseOfAmount(null)).toBe('unknown')
  })
})

describe('kindHelp', () => {
  test('a repayment is « je rembourse » on a debit, « on me rembourse » on a credit', () => {
    expect(kindHelp('repayment', 'out')).toMatch(/je rembourse/)
    expect(kindHelp('repayment', 'in')).toMatch(/on me rembourse/)
    expect(kindHelp('repayment', 'unknown')).toMatch(/selon le signe du montant/)
  })

  test('every kind says the sense comes from the amount', () => {
    for (const kind of ['none', 'saving', 'investment', 'loan', 'repayment'] as const) {
      expect(kindHelp(kind, 'unknown')).toMatch(/montant/)
    }
  })
})

describe('provenanceSentence', () => {
  test('says who qualified the line', () => {
    expect(provenanceSentence('manual')).toBe('à la main')
    expect(provenanceSentence('series', 'Crédit auto')).toBe('posée par la série Crédit auto')
    expect(provenanceSentence('rule', null, 'TOTALENERGIES')).toBe('posée par la règle TOTALENERGIES')
  })

  test('says nothing for a line nobody qualified', () => {
    expect(provenanceSentence(null)).toBeNull()
    expect(provenanceSentence(undefined)).toBeNull()
  })
})
