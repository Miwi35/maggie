package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The balance of one month (MAG-457): `GET /api/finance/month-balance?month=YYYY-MM`.
 *
 * The month's ring and curve ([spending]), one bar per root envelope, the satisfaction on what was
 * judged, what was put aside, the cushion, and the lines nobody qualified yet ([toQualify]), which
 * belong to no envelope and are reported apart.
 */
@Serializable
data class MonthBalance(
    val month: String = "",
    val isCurrentMonth: Boolean = false,
    val spending: PeriodScore = PeriodScore(),
    val envelopes: List<BalanceEnvelope> = emptyList(),
    val satisfaction: BalanceSatisfaction = BalanceSatisfaction(),
    val savings: BalanceSavings = BalanceSavings(),
    val cushion: TileCushion = TileCushion(),
    val toQualify: BalanceToQualify = BalanceToQualify(),
)

/** One root envelope; [status] is `held`, `close` or `over`, and [percent] is null for an envelope with no budget. */
@Serializable
data class BalanceEnvelope(
    val envelopeId: String,
    val name: String = "",
    val budgetCents: Int = 0,
    val consumedCents: Int = 0,
    val percent: Int? = null,
    val status: String = "held",
)

/** What was judged worth keeping against avoidable; [keptPercent] is null while nothing is judged, never a 0 %. */
@Serializable
data class BalanceSatisfaction(
    val judgedCents: Int = 0,
    val keptCents: Int = 0,
    // The superfluous amount.
    val avoidableCents: Int = 0,
    val unratedCount: Int = 0,
    val keptPercent: Int? = null,
)

/** What was put aside, against the planned saving capacity (null when no income is known). */
@Serializable
data class BalanceSavings(
    val savedCents: Int = 0,
    val plannedCents: Int? = null,
)

@Serializable
data class BalanceToQualify(
    val count: Int = 0,
    val cents: Int = 0,
)

/** The pill under the month: « À qualifier · 3 · 65,20 € », or null when the month has nothing to qualify. */
fun toQualifyLabel(toQualify: BalanceToQualify): String? =
    if (toQualify.count > 0) "À qualifier · ${toQualify.count} · ${formatCents(toQualify.cents)}" else null

/** The word of an envelope status, with the glyph that says it without the colour. */
fun envelopeStatusWord(status: String): String = when (status) {
    "over" -> "✕ Dépassée"
    "close" -> "▲ Proche"
    else -> "✓ Tenue"
}
