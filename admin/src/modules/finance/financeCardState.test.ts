import { expect, test } from 'vitest'
import { composeFinanceState } from './financeCardState'
import { dashboardFixture, nextProjectFixture } from './dashboardFixture'
import { period, rhythm, scores } from './scoresFixture'

test('the rings are what the week and the month of the dashboard spent and expected', () => {
  const state = composeFinanceState(
    dashboardFixture({
      scores: scores({
        week: period({ rhythm: rhythm({ spentCents: 4500, expectedSpentCents: 0, plannedCents: 0 }) }),
        month: period({ rhythm: rhythm({ spentCents: 120000, expectedSpentCents: 78000, plannedCents: 81000 }) }),
      }),
    }),
  )

  expect(state.week).toEqual({ spentCents: 4500, expectedSpentCents: 0, plannedCents: 0 })
  expect(state.month).toEqual({ spentCents: 120000, expectedSpentCents: 78000, plannedCents: 81000 })
})

test('the card keeps only what it draws of the next project, and counts the lines to qualify', () => {
  const state = composeFinanceState(dashboardFixture({ nextProject: nextProjectFixture({ name: 'Vélo', purchaseMonth: '2099-10' }) }))

  expect(state.toQualifyCount).toBe(3)
  expect(state.nextProject).toEqual({ projectId: 'p1', name: 'Vélo', amountCents: 120000, gatheredCents: 74000, purchaseMonth: '2099-10', horizonMonths: 36 })
})

test('has no next project when the dashboard has none', () => {
  expect(composeFinanceState(dashboardFixture({ nextProject: null })).nextProject).toBeNull()
})

test('names the first project of the planning when none lands inside the horizon', () => {
  const beyond = { projectId: 'p2', name: 'Salle de bain', amountCents: 900000, gatheredCents: 0, horizonMonths: 36 }
  const state = composeFinanceState(dashboardFixture({ nextProject: null, projectBeyondHorizon: beyond }))

  expect(state.nextProject).toEqual({ ...beyond, purchaseMonth: null })
})

test('counts the accounts and the envelopes of the month', () => {
  const state = composeFinanceState(dashboardFixture())

  expect(state.accountCount).toBe(2)
  expect(state.envelopeCount).toBe(3)
})
