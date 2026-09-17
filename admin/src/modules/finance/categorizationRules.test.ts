import { describe, test, expect } from 'vitest'
import {
  MATCH_TYPE_CHOICES,
  MATCH_TYPE_LABELS,
  DIRECTION_CHOICES,
  DIRECTION_LABELS,
  describeRuleScope,
} from './categorizationRules'

describe('categorizationRules', () => {
  test('every match type has a matching label', () => {
    for (const choice of MATCH_TYPE_CHOICES) {
      expect(MATCH_TYPE_LABELS[choice.id]).toBe(choice.name)
    }
  })

  test('covers the API match types and directions', () => {
    expect(MATCH_TYPE_CHOICES.map((c) => c.id)).toEqual(['contains', 'starts_with', 'equals'])
    expect(DIRECTION_CHOICES.map((c) => c.id)).toEqual(['any', 'debit', 'credit'])
    expect(DIRECTION_LABELS.debit).toBe('Dépense')
  })
})

describe('describeRuleScope', () => {
  test('is empty for a rule that narrows on nothing', () => {
    expect(describeRuleScope('any', null, null)).toBe('')
  })

  test('names the direction alone', () => {
    expect(describeRuleScope('debit', null, null)).toBe('Dépense')
  })

  test('renders a bounded amount range', () => {
    expect(describeRuleScope('debit', 1000, 5000)).toBe('Dépense · entre 10,00 € et 50,00 €')
  })

  test('renders open-ended bounds', () => {
    expect(describeRuleScope('any', 1500, null)).toBe('à partir de 15,00 €')
    expect(describeRuleScope('any', null, 2000)).toBe("jusqu'à 20,00 €")
  })
})
