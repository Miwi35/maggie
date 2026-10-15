import { ONE_OFF, toEnvelopePayload } from './budgetModes'
import { CATEGORY_MAX_DEPTH, categoryTree } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import type { BudgetLine, BudgetStatus, OneOffBudgetLine } from './useBudgetStatus'

/** A row of the tree: a category, and the monthly envelope that budgets it this month, if any. */
export interface EnvelopeTreeRow<T extends CategoryNode = CategoryNode> {
  category: T
  /** 1 for a main category, 3 at most. */
  depth: number
  line: BudgetLine | null
}

/** An envelope listed apart from the month's budget: an annual one of the year, or a one-off event. */
export interface OutsideEntry {
  /** The envelope's id as the budget status gives it (bare ULID). */
  id: string
  kind: 'annual' | 'one_off'
  /** The category's name, or the event's name. */
  label: string
  line: BudgetLine | OneOffBudgetLine
}

/** What the editor shows: a category of the tree, an envelope listed apart, or a one-off to create. */
export type Selection =
  | { kind: 'category'; categoryId: string }
  | { kind: 'envelope'; envelopeId: string }
  | { kind: 'new-one-off' }

/** The last segment of an IRI (`/api/categories/01ABC` → `01ABC`); an id without slash is returned as is. */
export const bareId = (id: string | number): string => String(id).split('/').pop() ?? String(id)

export const categoryIri = (id: string | number): string => {
  const value = String(id)

  return value.startsWith('/') ? value : `/api/categories/${value}`
}

export const envelopeIri = (id: string | number): string => {
  const value = String(id)

  return value.startsWith('/') ? value : `/api/envelopes/${value}`
}

/**
 * The categories as a three-level tree, each with the monthly envelope that
 * budgets it in the period (null when it has none). Nothing is computed here:
 * the consumption of a mother is the API's cumulative one.
 */
export function buildEnvelopeTree<T extends CategoryNode>(
  categories: T[],
  status: Pick<BudgetStatus, 'budgets'> | null,
): EnvelopeTreeRow<T>[] {
  const lines = new Map((status?.budgets ?? []).map((line) => [line.categoryId, line]))

  return categoryTree(categories).map(({ category, depth }) => ({
    category,
    depth: Math.min(depth, CATEGORY_MAX_DEPTH),
    line: lines.get(bareId(category.id)) ?? null,
  }))
}

/** The annual envelopes of the year, then the one-off ones, as « Hors budget mensuel » lists them. */
export function outsideEntries(status: BudgetStatus | null): OutsideEntry[] {
  const annual = status?.outsideMonthlyBudget?.annual ?? []
  const oneOff = status?.outsideMonthlyBudget?.oneOff ?? []

  return [
    ...annual.map((line): OutsideEntry => ({ id: line.id, kind: 'annual', label: line.categoryName, line })),
    ...oneOff.map((line): OutsideEntry => ({ id: line.id, kind: 'one_off', label: line.name, line })),
  ]
}

/** Every envelope the status lists, whatever its place on the screen. */
function allLines(status: BudgetStatus | null): Array<BudgetLine | OneOffBudgetLine> {
  return [
    ...(status?.budgets ?? []),
    ...(status?.outsideMonthlyBudget?.annual ?? []),
    ...(status?.outsideMonthlyBudget?.oneOff ?? []),
  ]
}

/** The envelope of the period with this id (bare ULID or IRI), or null once it is gone. */
export function findLine(status: BudgetStatus | null, id: string): BudgetLine | OneOffBudgetLine | null {
  const wanted = bareId(id)

  return allLines(status).find((line) => line.id === wanted) ?? null
}

/** The envelopes directly below a mother, in the same mode and period: the ruler shares her amount between them. */
export function daughtersOf(status: BudgetStatus | null, mother: BudgetLine): BudgetLine[] {
  const siblings = mother.mode === 'annual' ? status?.outsideMonthlyBudget?.annual : status?.budgets

  return (siblings ?? []).filter((line) => line.parentEnvelopeId === mother.id)
}

/** The monthly envelope of a category in the period: what its node edits. */
export function monthlyLineOf(status: BudgetStatus | null, categoryId: string): BudgetLine | null {
  const wanted = bareId(categoryId)

  return (status?.budgets ?? []).find((line) => line.categoryId === wanted) ?? null
}

/** Where the editor goes once an envelope is saved: its node for a monthly one, its entry apart otherwise. */
export function selectionOfEnvelope(envelope: { id: string | number; mode?: string; category?: string | null }): Selection {
  return envelope.mode === 'monthly' && envelope.category
    ? { kind: 'category', categoryId: bareId(envelope.category) }
    : { kind: 'envelope', envelopeId: bareId(envelope.id) }
}

export function sameSelection(a: Selection | null, b: Selection | null): boolean {
  if (!a || !b || a.kind !== b.kind) return false
  if (a.kind === 'category') return a.categoryId === (b as typeof a).categoryId
  if (a.kind === 'envelope') return a.envelopeId === (b as typeof a).envelopeId

  return true
}

export type ConsumptionState = 'overspent' | 'overcommitted' | 'ok'

export const consumptionState = (line: Pick<BudgetLine, 'isOverspent' | 'isOvercommitted'>): ConsumptionState =>
  line.isOverspent ? 'overspent' : line.isOvercommitted ? 'overcommitted' : 'ok'

/**
 * What the API accepts for the period on screen: the editor never asks for a
 * year or a month, it takes the page's own, and an annual envelope carries none.
 */
export function envelopePayload(values: Record<string, unknown>, year: number, month: number): Record<string, unknown> {
  if (values.mode === ONE_OFF) {
    return toEnvelopePayload<Record<string, unknown>>({ currency: 'EUR', ...values })
  }

  return toEnvelopePayload<Record<string, unknown>>({
    currency: 'EUR',
    ...values,
    year: values.year ?? year,
    month: values.mode === 'annual' ? null : (values.month ?? month),
  })
}
