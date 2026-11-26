import { describe, expect, test } from 'vitest'
import { energyLabel, filterIngredients, pieceLabel, valuesPills, valuesState } from './ingredientTable'

const COURGETTE = { id: '1', name: 'Courgette', ciqualAlimCode: '20020', kcalPer100g: 40 }
const OIGNON = { id: '2', name: 'Oignon jaune', ciqualAlimCode: null, kcalPer100g: null }
const MAISON = { id: '3', name: 'Pâté maison', ciqualAlimCode: null, kcalPer100g: 250 }

describe('ingredientTable', () => {
  test('valuesState reads ciqual, none or nothing', () => {
    expect(valuesState(COURGETTE)).toBe('ciqual')
    expect(valuesState(OIGNON)).toBe('none')
    expect(valuesState(MAISON)).toBeNull()
  })

  test('valuesPills counts each state', () => {
    expect(valuesPills([COURGETTE, OIGNON, MAISON])).toEqual([
      { key: 'all', label: 'Tous', count: 3 },
      { key: 'none', label: 'Sans valeurs', count: 1 },
      { key: 'ciqual', label: 'Ciqual', count: 1 },
    ])
  })

  test('filterIngredients ignores accents and case', () => {
    const all = [COURGETTE, OIGNON, MAISON]
    expect(filterIngredients(all, 'OIGNON', 'all')).toEqual([OIGNON])
    expect(filterIngredients(all, 'pate', 'all')).toEqual([MAISON])
    expect(filterIngredients(all, '', 'none')).toEqual([OIGNON])
    expect(filterIngredients(all, '', 'ciqual')).toEqual([COURGETTE])
  })

  test('energyLabel and pieceLabel write « 40 kcal », « ≈ 100 g » and « — »', () => {
    expect(energyLabel(40.2)).toBe('40 kcal')
    expect(energyLabel(null)).toBe('—')
    expect(pieceLabel(100)).toBe('≈ 100 g')
    expect(pieceLabel(undefined)).toBe('—')
  })
})
