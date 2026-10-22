import { describe, expect, test } from 'vitest'
import { conversionHint, draftForLine, draftPayload, initialDraft, isDraftValid, packagingsFor } from './quantityEntry'

const pasta = { packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g', restockQuantity: 2 }
const jam = { packagingUnit: 'jar', packagingSize: null, packagingSizeUnit: null }
const courgettes = { packagingUnit: null, defaultUnit: 'piece' }

describe('initialDraft', () => {
  test('a packaged product opens in packagings, filled with its automatic restock', () => {
    expect(initialDraft(pasta)).toMatchObject({ mode: 'packagings', packagings: '2', prefilled: true })
  })

  test('without restock quantity, one packaging, not marked as prefilled', () => {
    expect(initialDraft({ ...pasta, restockQuantity: null })).toMatchObject({ mode: 'packagings', packagings: '1', prefilled: false })
    expect(initialDraft({ ...pasta, restockQuantity: 0 })).toMatchObject({ packagings: '1', prefilled: false })
  })

  test('a product without packaging opens on an empty quantity in its usual unit', () => {
    expect(initialDraft(courgettes)).toMatchObject({ mode: 'quantity', quantity: '', unit: 'piece', prefilled: false })
    expect(initialDraft(null)).toMatchObject({ mode: 'quantity', quantity: '', unit: '' })
  })
})

describe('the unit kept for Quantité mode', () => {
  test('a packaged product leaves the usual unit, else the one its packaging is measured in, ready to type a quantity', () => {
    expect(initialDraft(pasta)).toMatchObject({ unit: 'g' })
    expect(initialDraft({ ...pasta, defaultUnit: 'kg' })).toMatchObject({ unit: 'kg' })
    expect(draftForLine(2, pasta)).toMatchObject({ unit: 'g' })
  })
})

describe('draftForLine', () => {
  test('a packaged line is edited in packagings, at least one', () => {
    expect(draftForLine(3, pasta)).toMatchObject({ mode: 'packagings', packagings: '3' })
    expect(draftForLine(null, pasta)).toMatchObject({ packagings: '1' })
  })
})

describe('draftPayload', () => {
  test('sends the packagings alone in Conditionnement mode', () => {
    expect(draftPayload({ ...initialDraft(pasta), packagings: '2' })).toEqual({ packagings: 2 })
  })

  test('sends the quantity and its unit alone in Quantité mode', () => {
    expect(draftPayload({ ...initialDraft(pasta), mode: 'quantity', quantity: '300', unit: 'g' })).toEqual({ quantity: 300, unit: 'g' })
  })

  test('an empty quantity sends nothing to count', () => {
    expect(draftPayload(initialDraft(courgettes))).toEqual({ quantity: null, unit: 'piece' })
  })
})

describe('isDraftValid', () => {
  test('packagings must be a whole number of at least one', () => {
    for (const text of ['', '0', '-1', '1.5', 'deux']) {
      expect(isDraftValid({ ...initialDraft(pasta), packagings: text })).toBe(false)
    }
    expect(isDraftValid({ ...initialDraft(pasta), packagings: '4' })).toBe(true)
  })

  test('a quantity may be left empty but not be zero or negative', () => {
    expect(isDraftValid(initialDraft(courgettes))).toBe(true)
    expect(isDraftValid({ ...initialDraft(courgettes), quantity: '0' })).toBe(false)
    expect(isDraftValid({ ...initialDraft(courgettes), quantity: '2' })).toBe(true)
  })
})

describe('packagingsFor — the API rule', () => {
  test('rounds up to the whole packaging, within a family', () => {
    expect(packagingsFor(pasta, 300, 'g')).toBe(1)
    expect(packagingsFor(pasta, 1, 'kg')).toBe(2)
    expect(packagingsFor(pasta, 1.2, 'kg')).toBe(3)
  })

  test('the packaging unit counts as it is, and an unconvertible unit as one', () => {
    expect(packagingsFor(pasta, 2, 'pack')).toBe(2)
    expect(packagingsFor(pasta, 3, '')).toBe(3)
    expect(packagingsFor(pasta, 3, 'piece')).toBe(1)
    expect(packagingsFor(jam, 300, 'g')).toBe(1)
  })
})

describe('conversionHint', () => {
  test('packagings show their content', () => {
    expect(conversionHint({ ...initialDraft(pasta), packagings: '2' }, pasta)).toBe('= 1 kg')
    expect(conversionHint({ ...initialDraft(pasta), packagings: '1' }, pasta)).toBe('= 500 g')
  })

  test('a quantity shows the packagings to buy', () => {
    const draft = { ...initialDraft(pasta), mode: 'quantity' as const, quantity: '300', unit: 'g' }
    expect(conversionHint(draft, pasta)).toBe('= 1 paquet de 500 g à acheter')
    expect(conversionHint({ ...draft, quantity: '1', unit: 'kg' }, pasta)).toBe('= 2 paquets de 500 g à acheter')
  })

  test('nothing to convert without packaging, content or quantity', () => {
    expect(conversionHint(initialDraft(courgettes), courgettes)).toBeNull()
    expect(conversionHint(initialDraft(jam), jam)).toBeNull()
    expect(conversionHint({ ...initialDraft(pasta), mode: 'quantity' }, pasta)).toBeNull()
  })
})
