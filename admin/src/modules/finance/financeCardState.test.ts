import { expect, test } from 'vitest'
import { composeFinanceState } from './financeCardState'
import { dashboardFixture, nextProjectFixture } from './dashboardFixture'
import { period, rhythm, scores } from './scoresFixture'

test('the rings are the rhythm of the week and of the month of the dashboard', () => {
  const state = composeFinanceState(
    dashboardFixture({
      scores: scores({
        week: period({ rhythm: rhythm({ budgetCents: 7000, remainingCents: 4500, expectedRemainingCents: 4000 }) }),
        month: period({ rhythm: rhythm({ status: 'above', label: 'Au-dessus du rythme', remainingCents: -100 }) }),
      }),
    }),
  )

  expect(state.week).toEqual({ status: 'on_track', label: 'Dans le rythme', budgetCents: 7000, remainingCents: 4500, expectedRemainingCents: 4000 })
  expect(state.month).toMatchObject({ status: 'above', remainingCents: -100 })
})

test('the card keeps only what it draws of the next project, and counts the lines to qualify', () => {
  const state = composeFinanceState(dashboardFixture({ nextProject: nextProjectFixture({ name: 'Vélo', purchaseMonth: '2099-10' }) }))

  expect(state.toQualifyCount).toBe(3)
  expect(state.nextProject).toEqual({ projectId: 'p1', name: 'Vélo', amountCents: 120000, gatheredCents: 74000, purchaseMonth: '2099-10' })
})

test('has no next project when the dashboard has none', () => {
  expect(composeFinanceState(dashboardFixture({ nextProject: null })).nextProject).toBeNull()
})

test('counts the accounts and the envelopes of the month', () => {
  const state = composeFinanceState(dashboardFixture())

  expect(state.accountCount).toBe(2)
  expect(state.envelopeCount).toBe(3)
})
