import type { BudgetLine } from './useBudgetStatus'

export interface NestedBudgetLine {
  line: BudgetLine
  /** 1 for an envelope with none above it. */
  depth: number
  /** The category as seen from the envelope above it: « Bars › Obsydian » under Loisirs. */
  label: string
}

/**
 * The envelopes in tree order — each followed by the envelopes nested in it.
 * A line whose mother is not in the list stays at the top.
 */
export function nestBudgetLines(lines: BudgetLine[]): NestedBudgetLine[] {
  const byId = new Map(lines.map((line) => [line.id, line]))
  const children = new Map<string | null, BudgetLine[]>()
  for (const line of lines) {
    const key = line.parentEnvelopeId && byId.has(line.parentEnvelopeId) ? line.parentEnvelopeId : null
    children.set(key, [...(children.get(key) ?? []), line])
  }

  const rows: NestedBudgetLine[] = []
  const walk = (key: string | null, depth: number, mother?: BudgetLine) => {
    for (const line of children.get(key) ?? []) {
      const prefix = mother ? `${mother.categoryPath} › ` : ''
      const label = prefix && line.categoryPath.startsWith(prefix)
        ? line.categoryPath.slice(prefix.length)
        : line.categoryPath || line.categoryName
      rows.push({ line, depth, label })
      walk(line.id, depth + 1, line)
    }
  }
  walk(null, 1)

  return rows
}
