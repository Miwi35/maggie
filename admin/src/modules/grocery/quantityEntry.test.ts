import { describe, expect, test } from 'vitest'
import {
  adjustLabel,
  chosenPackagings,
  coverage,
  draftForLine,
  draftPayload,
  initialDraft,
  isDraftValid,
  packagingsFor,
  packagingsForNeed,
  readable,
  shortfallMessage,
  suggestions,
  summaryText,
  toBase,
  withNeed,
} from './quantityEntry'

const pasta = { packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g', restockQuantity: 2 }
const jam = { packagingUnit: 'jar', packagingSize: null, packagingSizeUnit: null }
const courgettes = { packagingUnit: null, defaultUnit: 'piece' }

describe('initialDraft', () => {
  test('a packaged product opens in packagings, filled with its automatic restock', () => {
    expect(initialDraft(pasta)).toMatchObject({ mode: 'packagings', packagings: '2', source: 'restock' })
  })

  test('without restock quantity, one packaging, not marked as prefilled', () => {
    expect(initialDraft({ ...pasta, restockQuantity: null })).toMatchObject({ mode: 'packagings', packagings: '1', source: 'default' })
    expect(initialDraft({ ...pasta, restockQuantity: 0 })).toMatchObject({ packagings: '1', source: 'default' })
  })

  test('a product without packaging opens on an empty quantity in its usual unit', () => {
    expect(initialDraft(courgettes)).toMatchObject({ mode: 'quantity', quantity: '', unit: 'piece', source: 'default' })
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

const rice = { packagingUnit: 'pack', packagingSize: 500, packagingSizeUnit: 'g', restockQuantity: 2 }
const need800 = { quantity: 800, unit: 'g' }

describe("the week's need fills the packagings (MAG-573)", () => {
  test('the restock wins over the need', () => {
    expect(initialDraft(rice, need800)).toMatchObject({ mode: 'packagings', packagings: '2', source: 'restock' })
  })

  test('without restock, the need is rounded up to packagings', () => {
    const noRestock = { ...rice, restockQuantity: null }
    expect(initialDraft(noRestock, need800)).toMatchObject({ packagings: '2', source: 'need' })
    expect(initialDraft(noRestock, { quantity: 1.2, unit: 'kg' })).toMatchObject({ packagings: '3', source: 'need' })
    expect(initialDraft(noRestock, { quantity: 1000, unit: 'g' })).toMatchObject({ packagings: '2' })
  })

  test('a need in another unit than the pack is ignored: one packaging', () => {
    expect(initialDraft({ ...rice, restockQuantity: null }, { quantity: 2, unit: 'piece' })).toMatchObject({ packagings: '1', source: 'default' })
  })

  test("a need read late fills an untouched draft, never a restock or the owner's choice", () => {
    const noRestock = { ...rice, restockQuantity: null }
    expect(withNeed(initialDraft(noRestock), noRestock, need800)).toMatchObject({ packagings: '2', source: 'need' })
    expect(withNeed(initialDraft(rice), rice, { quantity: 3000, unit: 'g' })).toMatchObject({ packagings: '2', source: 'restock' })
    const typed = { ...initialDraft(noRestock), packagings: '5', source: 'user' as const }
    expect(withNeed(typed, noRestock, need800)).toBe(typed)
    expect(withNeed(initialDraft(noRestock), noRestock, null)).toMatchObject({ packagings: '1' })
  })
})

describe('draftForLine keeps what was asked', () => {
  test('the wanted quantity is ready for Quantité', () => {
    expect(draftForLine(3, rice, { quantity: 1400, unit: 'g' })).toMatchObject({ mode: 'packagings', packagings: '3', quantity: '1400', unit: 'g', source: 'user' })
  })
})

describe('toBase and packagingsForNeed', () => {
  test('kilos and litres go back to grams and millilitres', () => {
    expect(toBase(1.4, 'kg')).toEqual({ quantity: 1400, unit: 'g' })
    expect(toBase(2, 'cl')).toEqual({ quantity: 20, unit: 'ml' })
    expect(toBase(2, 'piece')).toEqual({ quantity: 2, unit: 'piece' })
  })

  test('the need rounds up to whole packagings, null when it cannot be compared', () => {
    expect(packagingsForNeed(rice, need800)).toBe(2)
    expect(packagingsForNeed(rice, { quantity: 1.2, unit: 'kg' })).toBe(3)
    expect(packagingsForNeed(rice, { quantity: 100, unit: 'g' })).toBe(1)
    expect(packagingsForNeed(rice, { quantity: 1, unit: 'l' })).toBeNull()
    expect(packagingsForNeed({ ...rice, packagingSize: null }, need800)).toBeNull()
  })
})

describe('coverage', () => {
  test('packagings cover the need when they hold at least as much', () => {
    expect(coverage({ ...initialDraft(rice), packagings: '2' }, rice, need800)).toEqual({ covered: true, packagingsToCover: 2 })
    expect(coverage({ ...initialDraft(rice), packagings: '1' }, rice, need800)).toEqual({ covered: false, packagingsToCover: 2 })
  })

  test('the week asks for more than the restock: 1,2 kg needs 3 packs', () => {
    expect(coverage(initialDraft(rice), rice, { quantity: 1200, unit: 'g' })).toEqual({ covered: false, packagingsToCover: 3 })
  })

  test('a quantity is counted in the packagings it makes', () => {
    const draft = { ...initialDraft(rice), mode: 'quantity' as const, quantity: '1', unit: 'kg' }
    expect(chosenPackagings(draft, rice)).toBe(2)
    expect(coverage(draft, rice, need800)?.covered).toBe(true)
    expect(coverage({ ...draft, quantity: '300', unit: 'g' }, rice, need800)?.covered).toBe(false)
  })

  test('nothing to say without need, pack content or valid choice', () => {
    expect(coverage(initialDraft(rice), rice, null)).toBeNull()
    expect(coverage(initialDraft(rice), { ...rice, packagingSize: null }, need800)).toBeNull()
    expect(coverage({ ...initialDraft(rice), packagings: '' }, rice, need800)).toBeNull()
    expect(coverage(initialDraft(courgettes), courgettes, need800)).toBeNull()
  })

  test('the alert and its button speak French', () => {
    expect(shortfallMessage({ quantity: 1200, unit: 'g' })).toBe('Ne couvre pas les besoins de la semaine (1,2 kg pour les recettes)')
    expect(adjustLabel(rice, 3)).toBe('Ajuster à 3 paquets')
    expect(adjustLabel(rice, 1)).toBe('Ajuster à 1 paquet')
  })
})

describe('summaryText', () => {
  test('what is wanted, what is for the recipes, the packagings', () => {
    expect(summaryText({ ...initialDraft(rice), packagings: '2' }, rice, need800)).toBe('1 kg (dont 800 g pour les recettes) · 2 paquets')
    expect(summaryText({ ...initialDraft(rice), packagings: '3' }, rice, { quantity: 1200, unit: 'g' })).toBe('1,5 kg (dont 1,2 kg pour les recettes) · 3 paquets')
  })

  test('no « dont » when the choice is below the need, nor without need', () => {
    expect(summaryText({ ...initialDraft(rice), packagings: '1' }, rice, need800)).toBe('500 g · 1 paquet')
    expect(summaryText({ ...initialDraft(rice), packagings: '2' }, rice, null)).toBe('1 kg · 2 paquets')
  })

  test('a quantity keeps what was typed, with its packagings', () => {
    const draft = { ...initialDraft(rice), mode: 'quantity' as const, quantity: '1,4', unit: 'kg' }
    expect(summaryText(draft, rice, need800)).toBe('1,4 kg (dont 800 g pour les recettes) · 3 paquets')
  })

  test('a pack without content is only counted; nothing without a valid choice', () => {
    expect(summaryText({ ...initialDraft(jam), packagings: '2' }, jam, null)).toBe('2 bocaux')
    expect(summaryText({ ...initialDraft(rice), packagings: '' }, rice, null)).toBeNull()
    expect(summaryText(initialDraft(courgettes), courgettes, null)).toBeNull()
  })
})

describe('suggestions', () => {
  test('the need then the restock in base units', () => {
    expect(suggestions(rice, need800)).toEqual([
      { kind: 'need', label: '800 g', quantity: 800, unit: 'g' },
      { kind: 'restock', label: '1 kg', quantity: 1000, unit: 'g' },
    ])
  })

  test('a restock equal to the need is offered once; nothing without packaging', () => {
    expect(suggestions(rice, { quantity: 1000, unit: 'g' }).map((one) => one.kind)).toEqual(['need'])
    expect(suggestions(courgettes, need800)).toEqual([])
    expect(suggestions(jam, null)).toEqual([])
  })
})

describe('readable', () => {
  test('thousands become kilos and litres', () => {
    expect(readable(800, 'g')).toBe('800 g')
    expect(readable(1200, 'g')).toBe('1,2 kg')
    expect(readable(1500, 'ml')).toBe('1,5 l')
  })
})
