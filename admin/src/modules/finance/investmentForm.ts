import { percentToBasisPoints } from './placementForm'
import { eurosToCents } from './projectForm'
import type { InvestmentInput, Project } from './projectsApi'

/** The investment form's values, checks and conversions (MAG-557). */

export interface InvestmentFormValues {
  /** As typed: « 18 000 ». */
  price: string
  downPayment: string
  /** The monthly rent, as typed. */
  rent: string
  /** The loan's annual rate in percent, as typed: « 3,5 ». */
  rate: string
  /** The loan's duration in months, as typed. */
  months: string
  hasResale: boolean
  /** `YYYY-MM`, as a month input gives it. */
  resaleMonth: string
  resaleNet: string
  /** Bare id of the project the proceeds are set aside for; '' for none. */
  resaleTarget: string
}

export type InvestmentFormField = 'price' | 'downPayment' | 'rent' | 'rate' | 'months' | 'resaleMonth' | 'resaleNet'
export type InvestmentFormErrors = Partial<Record<InvestmentFormField, string>>

/** The API refuses a rate above 50 %. */
export const MAX_RATE_PERCENT = 50
export const MAX_LOAN_MONTHS = 600

const decimal = (cents: number): string => String(cents / 100).replace('.', ',')

export const emptyValues = (project: Project): InvestmentFormValues => ({
  price: decimal(project.amountCents),
  downPayment: '',
  rent: '',
  rate: '',
  months: '',
  hasResale: false,
  resaleMonth: '',
  resaleNet: '',
  resaleTarget: '',
})

/** The form for an investment already set; the loan's rate and duration come once its conditions are read. */
export const valuesOf = (project: Project): InvestmentFormValues => {
  const investment = project.investment
  if (!investment) return emptyValues(project)

  return {
    price: decimal(investment.priceCents),
    downPayment: decimal(investment.downPaymentCents),
    rent: decimal(investment.monthlyRentCents),
    rate: '',
    months: '',
    hasResale: investment.resale !== null,
    resaleMonth: investment.resale?.month ?? '',
    resaleNet: investment.resale ? decimal(investment.resale.netAmountCents) : '',
    resaleTarget: investment.resale?.targetProjectId ?? '',
  }
}

/** What the loan's stored conditions give to the form: the rate in percent, the duration in months. */
export const loanFieldsOf = (conditions: Record<string, unknown>): Pick<InvestmentFormValues, 'rate' | 'months'> => ({
  rate: typeof conditions.annualRateBasisPoints === 'number' ? decimal(conditions.annualRateBasisPoints) : '',
  months: typeof conditions.durationMonths === 'number' ? String(conditions.durationMonths) : '',
})

/** What is borrowed: the price less the down payment, null while either is not an amount. */
export const borrowedCents = (values: InvestmentFormValues): number | null => {
  const price = eurosToCents(values.price)
  const down = eurosToCents(values.downPayment)

  return price === null || down === null || down > price ? null : price - down
}

/**
 * What the API would refuse, said before it is sent. `needsLoan`: the project has no loan yet, so the
 * conditions of the one to take are asked for; with a loan, leaving both empty keeps its conditions.
 */
export const validateInvestment = (values: InvestmentFormValues, needsLoan: boolean): InvestmentFormErrors => {
  const errors: InvestmentFormErrors = {}
  const price = eurosToCents(values.price)
  const down = eurosToCents(values.downPayment)
  const rent = eurosToCents(values.rent)

  if (price === null || price <= 0) errors.price = 'Indiquez un prix supérieur à 0.'
  if (down === null) errors.downPayment = "Indiquez l'apport en euros (0 s'il n'y en a pas)."
  else if (price !== null && down > price) errors.downPayment = "L'apport dépasse le prix."
  if (rent === null) errors.rent = 'Indiquez le loyer mensuel en euros (0 sans loyer).'

  const borrowed = borrowedCents(values)
  if (borrowed !== null && borrowed > 0) {
    const typed = values.rate.trim() !== '' || values.months.trim() !== ''
    if (needsLoan || typed) {
      const rate = percentToBasisPoints(values.rate)
      if (rate === null || rate > MAX_RATE_PERCENT * 100) errors.rate = `Indiquez un taux de 0 à ${MAX_RATE_PERCENT} %.`
      const months = Number(values.months)
      if (!/^\d+$/.test(values.months.trim()) || months < 1 || months > MAX_LOAN_MONTHS) {
        errors.months = `Indiquez une durée de 1 à ${MAX_LOAN_MONTHS} mois.`
      }
    }
  }

  if (values.hasResale) {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(values.resaleMonth)) errors.resaleMonth = 'Choisissez le mois de la revente.'
    const net = eurosToCents(values.resaleNet)
    if (net === null || net <= 0) errors.resaleNet = 'Indiquez le net de la revente, supérieur à 0.'
  }

  return errors
}

/** The body of `PUT …/investment`; call it on values that passed `validateInvestment`. */
export const toInvestmentInput = (values: InvestmentFormValues): InvestmentInput => {
  const borrowed = borrowedCents(values) ?? 0
  const rate = percentToBasisPoints(values.rate)
  const months = Number(values.months.trim())
  const withLoan = borrowed > 0 && rate !== null && /^\d+$/.test(values.months.trim())

  return {
    priceCents: eurosToCents(values.price) ?? 0,
    downPaymentCents: eurosToCents(values.downPayment) ?? 0,
    ...(withLoan ? { loan: { type: 'amortizing_fixed' as const, annualRateBasisPoints: rate, durationMonths: months } } : {}),
    monthlyRentCents: eurosToCents(values.rent) ?? 0,
    ...(values.hasResale
      ? {
          resale: {
            month: values.resaleMonth,
            netAmountCents: eurosToCents(values.resaleNet) ?? 0,
            ...(values.resaleTarget ? { targetProjectId: values.resaleTarget } : {}),
          },
        }
      : {}),
  }
}
