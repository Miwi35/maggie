import { describe, test, expect } from 'vitest'
import { delayLabel, durationLabel } from './nextProject'

describe('durationLabel', () => {
  test.each([
    [36, '3 ans'],
    [24, '2 ans'],
    [16, '1 an et 4 mois'],
    [13, '1 an et 1 mois'],
    [12, '1 an'],
    [8, '8 mois'],
    [1, '1 mois'],
    [0, '0 mois'],
    [40, '3 ans et 4 mois'],
  ])('%i months reads « %s »', (months, label) => {
    expect(durationLabel(months)).toBe(label)
  })
})

describe('delayLabel', () => {
  test.each([
    [1, "d'un mois"],
    [3, 'de 3 mois'],
    [12, "d'un an"],
    [14, 'de 1 an et 2 mois'],
  ])('%i months reads « %s »', (months, label) => {
    expect(delayLabel(months)).toBe(label)
  })
})
