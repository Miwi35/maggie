import { describe, test, expect } from 'vitest'
import { currentMonthKey, eventTitle, reviewTitle } from './reviewActivity'

describe('reviewTitle', () => {
  test.each([
    ['2026-10-25', "Revue d'octobre"],
    ['2026-11-25', 'Revue de novembre'],
    ['2027-04-25', "Revue d'avril"],
    ['2027-08-25', "Revue d'août"],
    ['2027-02-25', 'Revue de février'],
    ['2027-01-25', 'Revue de janvier'],
    ['2027-03-25', 'Revue de fin de trimestre'],
    ['2027-06-25', 'Revue de fin de semestre'],
    ['2027-09-25', 'Revue de fin de trimestre'],
    ['2026-12-25', "Revue de fin d'année"],
  ])('%s → %s', (day, title) => {
    expect(reviewTitle(day)).toBe(title)
  })

  test('reads an instant in the local month', () => {
    expect(reviewTitle(new Date(2026, 9, 25, 20, 0))).toBe("Revue d'octobre")
  })
})

describe('eventTitle', () => {
  test('names the review appointment after its month, whatever its summary', () => {
    expect(eventTitle('Revue du mois', 'finance-review', '2026-12-25')).toBe("Revue de fin d'année")
  })

  test('leaves any other event its own summary', () => {
    expect(eventTitle('Dentiste', null, '2026-12-25')).toBe('Dentiste')
    expect(eventTitle('Dentiste', undefined, '2026-12-25')).toBe('Dentiste')
  })
})

test('currentMonthKey pads the month', () => {
  expect(currentMonthKey(new Date(2026, 2, 9))).toBe('2026-03')
})
