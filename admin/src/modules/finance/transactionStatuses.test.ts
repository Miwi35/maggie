import { describe, test, expect } from 'vitest'
import { TRANSACTION_STATUS_CHOICES, TRANSACTION_STATUS_LABELS } from './transactionStatuses'

describe('transactionStatuses', () => {
  test('every choice has a matching label', () => {
    for (const choice of TRANSACTION_STATUS_CHOICES) {
      expect(TRANSACTION_STATUS_LABELS[choice.id]).toBe(choice.name)
    }
  })

  test('covers the four API transaction statuses', () => {
    expect(TRANSACTION_STATUS_CHOICES.map((c) => c.id)).toEqual([
      'spent',
      'committed',
      'planned',
      'to_arbitrate',
    ])
  })
})
