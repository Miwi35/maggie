/** One notch of the slider: a whole euro. */
export const SPLIT_STEP_CENTS = 100

export interface SplitShares {
  /** What each daughter holds, in the order of the bar. */
  amounts: number[]
  /** What the daughters leave free in the mother. */
  freeCents: number
}

export const sharesOf = (amounts: number[], motherCents: number): SplitShares => ({
  amounts,
  freeCents: motherCents - amounts.reduce((sum, amount) => sum + amount, 0),
})

/**
 * Moves the boundary after daughter `index` by `deltaCents`: the daughter on
 * its left grows by what the neighbour on its right loses — the next daughter,
 * or the free part for the last handle. Neither can go below zero, so the
 * total never moves and never exceeds the mother.
 */
export function moveBoundary(shares: SplitShares, index: number, deltaCents: number): SplitShares {
  const neighbourIsFree = index === shares.amounts.length - 1
  const left = shares.amounts[index]
  const right = neighbourIsFree ? shares.freeCents : shares.amounts[index + 1]
  const delta = Math.max(-left, Math.min(right, deltaCents))

  const amounts = shares.amounts.map((amount, i) => {
    if (i === index) {
      return amount + delta
    }
    if (i === index + 1) {
      return amount - delta
    }

    return amount
  })

  return { amounts, freeCents: neighbourIsFree ? shares.freeCents - delta : shares.freeCents }
}

/** A pointer travel in pixels, as cents on the bar, snapped to the step. */
export function centsForTravel(travelPx: number, barWidthPx: number, motherCents: number): number {
  if (barWidthPx <= 0) {
    return 0
  }

  return Math.round((travelPx / barWidthPx) * motherCents / SPLIT_STEP_CENTS) * SPLIT_STEP_CENTS
}
