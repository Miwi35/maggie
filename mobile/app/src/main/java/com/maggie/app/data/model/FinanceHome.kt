package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The Finance card of the Accueil (MAG-565): `GET /api/home/finance`. The week and the month come from
 * the same rhythm as the Finance Dashboard's rings, so both tell the same figures.
 */
@Serializable
data class FinanceHomeState(
    val week: FinanceHomeRing = FinanceHomeRing(),
    val month: FinanceHomeRing = FinanceHomeRing(),
    /** Lines the review still has to qualify; the card shows a pill only above zero. */
    val toQualifyCount: Int = 0,
    /** The first project of the current scenario's planning; null with none. */
    val nextProject: FinanceHomeProject? = null,
    /** Accounts and envelopes the user has: with neither, the card is not drawn (MAG-594). */
    val accountCount: Int = 0,
    val envelopeCount: Int = 0,
) {
    val isEmpty: Boolean get() = accountCount == 0 && envelopeCount == 0
}

@Serializable
data class FinanceHomeRing(
    /** `on_track` or `above`. */
    val status: String = "on_track",
    val label: String = "",
    val budgetCents: Int = 0,
    val remainingCents: Int = 0,
    val expectedRemainingCents: Int = 0,
) {
    /** The ring as the dashboard's ring helpers read it. */
    fun toRhythm() = Rhythm(
        budgetCents = budgetCents,
        remainingCents = remainingCents,
        expectedRemainingCents = expectedRemainingCents,
        status = status,
        label = label,
    )
}

@Serializable
data class FinanceHomeProject(
    val projectId: String,
    val name: String,
    val amountCents: Int,
    val gatheredCents: Int,
    /** `YYYY-MM`. */
    val purchaseMonth: String,
)

/** « n à qualifier », the pill of the lines the review is waiting for. */
fun FinanceHomeState.toQualifyLabel(): String = "$toQualifyCount à qualifier"
