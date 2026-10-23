import type { FundingAccount, FundingKind, FundingSource, FundingSourceInput, Project } from './projectsApi'

/** The funding panel's values, conversions and figures (MAG-525). */

/** One press of « + » or « − »: 100 €. */
export const STEP_CENTS = 10_000

export const KIND_LABELS: Record<FundingKind, string> = {
  savings: 'Économies',
  capital: 'Capital',
  loan: 'Emprunt',
  resale: 'Revente',
}

/** Words used in a row's summary: « économies + emprunt + salaire ». */
const SUMMARY_WORDS: Record<FundingKind, string> = { savings: 'économies', capital: 'capital', loan: 'emprunt', resale: 'revente' }

const KIND_ORDER: FundingKind[] = ['savings', 'capital', 'loan', 'resale']

export interface SourceValues {
  kind: FundingKind
  amountCents: number
  /** Bare id of the account; '' for a loan. */
  account: string
  /** Bare id of the simulated placement a capital source leaves (MAG-556); absent or '' for none. */
  placement?: string
  /** The conditions of a loan source; absent for the others, or while they could not be read. */
  loan?: Record<string, unknown>
  /** Bare id of the investment a `resale` source comes from (MAG-557): read-only, never sent back. */
  sourceProject?: string
}

export interface FundingValues {
  sources: SourceValues[]
}

/** The conditions a loan source carries to the API: the loan's rate and its details. */
const LOAN_CONDITION_KEYS = [
  'annualRateBasisPoints',
  'type',
  'durationMonths',
  'aprBasisPoints',
  'firstPaymentOn',
  'periodicity',
  'referenceIndex',
  'marginBasisPoints',
  'rateCapBasisPoints',
  'rateFloorBasisPoints',
  'steps',
  'deferralMonths',
  'insuranceMonthlyCents',
  'insuranceRateBasisPoints',
  'feesCents',
  'earlyRepaymentAllowed',
  'prepaymentPenaltyMode',
  'prepaymentPenaltyFixedCents',
  'minPartialRepaymentCents',
  'partialRepaymentEffect',
  'noticeDays',
] as const

/** What a stored loan or a form holds of the conditions, without the empty ones. */
export const loanConditionsOf = (record: Record<string, unknown>): Record<string, unknown> => {
  const conditions: Record<string, unknown> = {}
  for (const key of LOAN_CONDITION_KEYS) {
    const value = record[key]
    if (value !== null && value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0)) conditions[key] = value
  }

  return conditions
}

export const valuesOfProject = (project: Project, conditions: Record<string, unknown> | null): FundingValues => ({
  sources: project.fundingSources.map((source) => ({
    kind: source.kind,
    amountCents: source.amountCents,
    account: source.accountId ?? '',
    ...(source.placementId ? { placement: source.placementId } : {}),
    ...(source.kind === 'loan' && conditions ? { loan: loanConditionsOf(conditions) } : {}),
    ...(source.kind === 'resale' ? { sourceProject: source.sourceProjectId ?? '' } : {}),
  })),
})

/**
 * The whole list `PUT …/funding` takes. A source brought to 0 is a source taken
 * off; the resale of an investment is not the owner's to set here (it follows the
 * investment) and stays out; a loan whose conditions could not be read is sent without them, the API
 * then keeps the ones it has.
 */
export const toFundingInputs = (values: FundingValues): FundingSourceInput[] =>
  values.sources
    .filter((source) => source.amountCents > 0 && source.kind !== 'resale')
    .map((source): FundingSourceInput => {
      if (source.kind === 'loan') {
        const conditions = source.loan ? loanConditionsOf(source.loan) : undefined

        return { kind: 'loan', amountCents: source.amountCents, ...(conditions ? { loan: conditions } : {}) }
      }

      return {
        kind: source.kind as FundingSourceInput['kind'],
        amountCents: source.amountCents,
        account: source.account,
        ...(source.kind === 'capital' && source.placement ? { placement: source.placement } : {}),
      }
    })

export const sourcesTotal = (sources: Pick<FundingSource, 'amountCents'>[]): number =>
  sources.reduce((sum, source) => sum + (Number.isFinite(source.amountCents) ? source.amountCents : 0), 0)

/** « Le reste »: what the salary pays. Never below 0. */
export const remainder = (amountCents: number, sources: Pick<FundingSource, 'amountCents'>[]): number =>
  Math.max(0, amountCents - sourcesTotal(sources))

/** « économies + emprunt + salaire »: the kinds present, then the salary when it pays something. */
export const sourcesSummary = (project: Pick<Project, 'fundingSources' | 'salaryCents'>): string => {
  const words = KIND_ORDER.filter((kind) => project.fundingSources.some((source) => source.kind === kind)).map(
    (kind) => SUMMARY_WORDS[kind],
  )
  if (project.salaryCents > 0 || words.length === 0) words.push('salaire')

  return words.join(' + ')
}

export interface Segment {
  key: string
  label: string
  cents: number
  kind: FundingKind | 'salary'
}

/** The bar's segments: each source, then what is left to the salary. */
export const segmentsOf = (amountCents: number, sources: SourceValues[]): Segment[] => {
  const segments: Segment[] = sources
    .filter((source) => source.amountCents > 0)
    .map((source, index) => ({ key: `${source.kind}-${index}`, label: KIND_LABELS[source.kind], cents: source.amountCents, kind: source.kind }))
  const rest = remainder(amountCents, sources)
  if (rest > 0) segments.push({ key: 'salary', label: 'Salaire', cents: rest, kind: 'salary' })

  return segments
}

/** The accounts a kind of source can draw on: never the cushion. */
export const accountsFor = (kind: FundingKind, accounts: FundingAccount[]): FundingAccount[] => {
  if (kind === 'capital') return accounts.filter((account) => account.type === 'investment')
  if (kind === 'savings') return accounts.filter((account) => (account.type === 'savings' || account.type === 'checking') && !account.cushion)

  return []
}
