import { describe, expect, test } from 'vitest'
import { borrowedCents, emptyValues, loanFieldsOf, toInvestmentInput, validateInvestment, valuesOf } from './investmentForm'
import type { InvestmentFormValues } from './investmentForm'
import type { Project, ProjectInvestment } from './projectsApi'

const project = (investment: ProjectInvestment | null = null): Project => ({
  id: 'BOX',
  name: 'Box à louer',
  amountCents: 1_800_000,
  currency: 'EUR',
  wellbeing: 'medium',
  wellbeingScore: 2,
  position: 0,
  dateMode: 'none',
  targetOn: null,
  dependsOn: [],
  note: null,
  unmetDependencies: [],
  fundingSources: [],
  salaryCents: 1_800_000,
  investment,
})

const INVESTMENT: ProjectInvestment = {
  priceCents: 1_800_000,
  downPaymentCents: 300_000,
  loanId: 'L1',
  loanPaymentCents: 14_000,
  monthlyRentCents: 12_050,
  monthlyBalanceCents: -1_950,
  resale: { month: '2028-06', netAmountCents: 1_000_000, targetProjectId: 'CAR' },
}

const filled = (overrides: Partial<InvestmentFormValues> = {}): InvestmentFormValues => ({
  ...emptyValues(project()),
  downPayment: '3 000',
  rent: '120,50',
  rate: '3,5',
  months: '120',
  ...overrides,
})

describe('investmentForm', () => {
  test('a new investment starts from the project amount, everything else empty', () => {
    expect(emptyValues(project())).toEqual({
      price: '18000',
      downPayment: '',
      rent: '',
      rate: '',
      months: '',
      hasResale: false,
      resaleMonth: '',
      resaleNet: '',
      resaleTarget: '',
    })
  })

  test('an investment already set fills the form, the resale with it', () => {
    expect(valuesOf(project(INVESTMENT))).toEqual({
      price: '18000',
      downPayment: '3000',
      rent: '120,5',
      rate: '',
      months: '',
      hasResale: true,
      resaleMonth: '2028-06',
      resaleNet: '10000',
      resaleTarget: 'CAR',
    })
    expect(valuesOf(project({ ...INVESTMENT, resale: null })).hasResale).toBe(false)
  })

  test('reads the loan conditions into a rate in percent and a duration in months', () => {
    expect(loanFieldsOf({ annualRateBasisPoints: 350, durationMonths: 120 })).toEqual({ rate: '3,5', months: '120' })
    expect(loanFieldsOf({})).toEqual({ rate: '', months: '' })
  })

  test('borrows the price less the down payment, nothing while either is not an amount', () => {
    expect(borrowedCents(filled())).toBe(1_500_000)
    expect(borrowedCents(filled({ downPayment: 'abc' }))).toBeNull()
    expect(borrowedCents(filled({ downPayment: '20 000' }))).toBeNull()
  })

  test('accepts a complete investment, with or without a loan to describe', () => {
    expect(validateInvestment(filled(), true)).toEqual({})
    expect(validateInvestment(filled({ downPayment: '18000', rate: '', months: '' }), true)).toEqual({})
    expect(validateInvestment(filled({ rate: '', months: '' }), false)).toEqual({})
  })

  test('says what is missing before anything is sent', () => {
    const errors = validateInvestment(filled({ price: '0', downPayment: '', rent: 'x' }), true)

    expect(errors.price).toBe('Indiquez un prix supérieur à 0.')
    expect(errors.downPayment).toMatch(/Indiquez l'apport/)
    expect(errors.rent).toMatch(/Indiquez le loyer mensuel/)
  })

  test('refuses a down payment above the price', () => {
    expect(validateInvestment(filled({ downPayment: '20 000' }), true).downPayment).toBe("L'apport dépasse le prix.")
  })

  test('asks for the loan conditions when the project has no loan yet, and checks them when typed', () => {
    expect(validateInvestment(filled({ rate: '', months: '' }), true)).toMatchObject({
      rate: 'Indiquez un taux de 0 à 50 %.',
      months: 'Indiquez une durée de 1 à 600 mois.',
    })
    expect(validateInvestment(filled({ rate: '60', months: '601' }), false)).toMatchObject({ rate: expect.any(String), months: expect.any(String) })
  })

  test('asks for the resale month and net when a resale is planned', () => {
    const errors = validateInvestment(filled({ hasResale: true }), true)

    expect(errors.resaleMonth).toBe('Choisissez le mois de la revente.')
    expect(errors.resaleNet).toBe('Indiquez le net de la revente, supérieur à 0.')
    expect(validateInvestment(filled({ hasResale: true, resaleMonth: '2028-06', resaleNet: '10 000' }), true)).toEqual({})
  })

  test('builds the body of PUT …/investment, in cents and basis points', () => {
    expect(toInvestmentInput(filled({ hasResale: true, resaleMonth: '2028-06', resaleNet: '10 000', resaleTarget: 'CAR' }))).toEqual({
      priceCents: 1_800_000,
      downPaymentCents: 300_000,
      loan: { type: 'amortizing_fixed', annualRateBasisPoints: 350, durationMonths: 120 },
      monthlyRentCents: 12_050,
      resale: { month: '2028-06', netAmountCents: 1_000_000, targetProjectId: 'CAR' },
    })
  })

  test('leaves out the loan when nothing is borrowed or its conditions are empty, and the target when none is chosen', () => {
    expect(toInvestmentInput(filled({ downPayment: '18 000' }))).not.toHaveProperty('loan')
    expect(toInvestmentInput(filled({ rate: '', months: '' }))).not.toHaveProperty('loan')
    const input = toInvestmentInput(filled({ hasResale: true, resaleMonth: '2028-06', resaleNet: '5 000' }))
    expect(input.resale).toEqual({ month: '2028-06', netAmountCents: 500_000 })
  })
})
