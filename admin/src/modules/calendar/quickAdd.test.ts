import { describe, expect, test } from 'vitest'
import { parseQuickAdd } from './quickAdd'

// 2026-10-28 is a Wednesday.
const TODAY = '2026-10-28'

describe('parseQuickAdd', () => {
  test.each([
    ['Appeler le garage demain', 'Appeler le garage', '2026-10-29'],
    ['Appeler le garage aujourd’hui', 'Appeler le garage', '2026-10-28'],
    ["Appeler le garage aujourd'hui", 'Appeler le garage', '2026-10-28'],
    ['Payer la cantine après-demain', 'Payer la cantine', '2026-10-30'],
    ['Payer la cantine apres-demain', 'Payer la cantine', '2026-10-30'],
    ['Réunion parents vendredi', 'Réunion parents', '2026-10-30'],
    ['Faire le point mercredi', 'Faire le point', '2026-11-04'],
    ['Faire le point lundi', 'Faire le point', '2026-11-02'],
    ['Appeler le garage pour demain', 'Appeler le garage', '2026-10-29'],
    ['  Appeler   le garage   DEMAIN ', 'Appeler le garage', '2026-10-29'],
  ])('reads %s', (input, title, dueDate) => {
    expect(parseQuickAdd(input, TODAY)).toEqual({ title, dueDate })
  })

  test.each(['Appeler le garage', 'Demain nous irons', 'demain', 'Acheter du pain  '])(
    'keeps %s as the title, with no day',
    (input) => {
      expect(parseQuickAdd(input, TODAY)).toEqual({ title: input.trim().replace(/\s+/g, ' ') })
    },
  )

  test('goes over a month end', () => {
    expect(parseQuickAdd('Rendre le livre demain', '2026-10-31').dueDate).toBe('2026-11-01')
  })
})
