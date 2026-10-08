import { describe, test, expect } from 'vitest'
import { packagingLabel, quantityLabel } from './packaging'

describe('packagingLabel', () => {
  test('says a pack with its content', () => {
    expect(packagingLabel('pack', 500, 'g')).toBe('paquet de 500 g')
  })

  test('says a jar of unknown content alone', () => {
    expect(packagingLabel('jar', null, null)).toBe('bocal')
  })

  test('writes decimals the French way', () => {
    expect(packagingLabel('bottle', 1.5, 'l')).toBe('bouteille de 1,5 l')
  })

  test('puts a counted content in the plural', () => {
    expect(packagingLabel('pack', 6, 'piece')).toBe('paquet de 6 pièces')
    expect(packagingLabel('pack', 1, 'piece')).toBe('paquet de 1 pièce')
  })

  test('is null without packaging', () => {
    expect(packagingLabel(null, null, null)).toBeNull()
    expect(packagingLabel(undefined, 500, 'g')).toBeNull()
  })
})

describe('quantityLabel', () => {
  test('counts packagings, in the plural from two', () => {
    expect(quantityLabel(1, 'pack')).toBe('1 paquet')
    expect(quantityLabel(2, 'jar')).toBe('2 bocaux')
  })

  test('leaves a measured unit alone', () => {
    expect(quantityLabel(300, 'g')).toBe('300 g')
    expect(quantityLabel(1.5, 'l')).toBe('1,5 l')
  })
})
