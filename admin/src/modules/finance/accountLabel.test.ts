import { describe, expect, test } from 'vitest'
import { accountLabel } from './accountLabel'

describe('accountLabel', () => {
  test('reads bank, currency, then name', () => {
    expect(accountLabel({ bank: 'Boursorama', currency: 'EUR', name: 'Compte courant' })).toBe(
      'Boursorama · EUR · Compte courant',
    )
  })

  test('tells two accounts of the same name apart by their bank', () => {
    const labels = ['Boursorama', 'Crédit Agricole'].map((bank) =>
      accountLabel({ bank, currency: 'EUR', name: 'Compte courant' }),
    )

    expect(labels).toEqual(['Boursorama · EUR · Compte courant', 'Crédit Agricole · EUR · Compte courant'])
  })

  test.each([null, undefined, '', '  '])('leaves the bank out when it is %j', (bank) => {
    expect(accountLabel({ bank, currency: 'EUR', name: 'Livret' })).toBe('EUR · Livret')
  })

  test('keeps whatever is known when the rest is missing', () => {
    expect(accountLabel({ name: 'Livret' })).toBe('Livret')
    expect(accountLabel({})).toBe('')
  })
})
