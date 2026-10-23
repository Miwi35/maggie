import { centsToEuros, eurosToCents } from './scenariosApi'
import type { Scenario, ScenarioInput } from './scenariosApi'

/** One early repayment as the editor shows it: a loan, a month, an amount in euros or the whole capital. */
export interface RepaymentRow {
  /** Local key of the row. */
  key: string
  /** Kept when the repayment is already stored. */
  id?: string
  loanId: string
  /** `YYYY-MM`. */
  month: string
  total: boolean
  euros: string
}

export interface ScenarioFormValues {
  name: string
  /** Bare project ids in priority order: the projects the scenario keeps. */
  projectIds: string[]
  repayments: RepaymentRow[]
  placementIds: string[]
  /** Euros typed; empty leaves the saving to the plan. */
  saving: string
}

export type ScenarioFormErrors = Partial<Record<'name' | 'repayments' | 'saving', string>>

let rows = 0

export const newRepaymentRow = (): RepaymentRow => ({ key: `new-${++rows}`, loanId: '', month: '', total: false, euros: '' })

/** A new scenario starts from every project, in the user's order. */
export const emptyValues = (projectIds: string[]): ScenarioFormValues => ({
  name: '',
  projectIds,
  repayments: [],
  placementIds: [],
  saving: '',
})

export const valuesOf = (scenario: Scenario, knownProjectIds: string[]): ScenarioFormValues => ({
  name: scenario.name,
  projectIds: scenario.projectIds.filter((id) => knownProjectIds.includes(id)),
  repayments: scenario.repayments.map((repayment, index) => ({
    key: repayment.id ?? `stored-${index}`,
    id: repayment.id,
    loanId: repayment.loanId,
    month: repayment.month,
    total: repayment.amountCents === null,
    euros: centsToEuros(repayment.amountCents),
  })),
  placementIds: scenario.placementIds,
  saving: centsToEuros(scenario.monthlySaving),
})

export const validate = (values: ScenarioFormValues): ScenarioFormErrors => {
  const errors: ScenarioFormErrors = {}
  if (values.name.trim() === '') errors.name = 'Donnez un nom au scénario.'
  if (values.saving.trim() !== '' && Number.isNaN(eurosToCents(values.saving))) errors.saving = 'Indiquez un montant positif en euros.'
  const incomplete = values.repayments.some(
    (row) => row.loanId === '' || !/^\d{4}-\d{2}$/.test(row.month) || (!row.total && !((eurosToCents(row.euros) ?? 0) > 0)),
  )
  if (incomplete) errors.repayments = 'Chaque remboursement anticipé demande un prêt, un mois et un montant (ou le capital restant).'

  return errors
}

export const toInput = (values: ScenarioFormValues): ScenarioInput => ({
  name: values.name.trim(),
  projectIds: values.projectIds,
  repayments: values.repayments.map((row) => ({
    ...(row.id ? { id: row.id } : {}),
    loanId: row.loanId,
    month: row.month,
    amountCents: row.total ? null : eurosToCents(row.euros),
  })),
  placementIds: values.placementIds,
  monthlySaving: eurosToCents(values.saving),
})
