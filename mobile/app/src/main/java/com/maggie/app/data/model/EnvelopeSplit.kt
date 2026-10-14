package com.maggie.app.data.model

/** One notch of the ruler: a whole euro. */
const val SPLIT_STEP_CENTS = 100

/** How a mother is shared: what each daughter holds, in bar order, and what stays free. */
data class SplitShares(val amounts: List<Int>, val freeCents: Int)

fun splitSharesOf(amounts: List<Int>, motherCents: Int) =
    SplitShares(amounts, motherCents - amounts.sum())

/** The envelopes directly below a mother, for the same mode: what her ruler shares. */
fun daughtersOf(mother: BudgetLine, lines: List<BudgetLine>): List<BudgetLine> =
    lines.filter { it.parentEnvelopeId == mother.id && it.mode == mother.mode }

/**
 * Moves the boundary after daughter [index] by [deltaCents]: the daughter on its left
 * grows by what the neighbour on its right loses — the next daughter, or the free part
 * for the last boundary. Neither goes below zero, so the total never moves and never
 * exceeds the mother.
 */
fun SplitShares.moveBoundary(index: Int, deltaCents: Int): SplitShares {
    if (index !in amounts.indices) return this
    val neighbourIsFree = index == amounts.lastIndex
    val left = amounts[index]
    val right = if (neighbourIsFree) freeCents else amounts[index + 1]
    val delta = deltaCents.coerceIn(-left, right)

    val moved = amounts.mapIndexed { i, amount ->
        when (i) {
            index -> amount + delta
            index + 1 -> amount - delta
            else -> amount
        }
    }
    return SplitShares(moved, if (neighbourIsFree) freeCents - delta else freeCents)
}
