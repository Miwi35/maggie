package com.maggie.app.data.model

/**
 * The Finance card of the Accueil (MAG-565), composed from `GET /api/finance/dashboard` (MAG-592): the week and
 * the month are the Finance Dashboard's own rings, so both tell the same figures.
 */
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

    companion object {
        /** The card from the dashboard of the current month; a dashboard sent without scores draws empty rings. */
        fun from(dashboard: FinanceDashboard): FinanceHomeState = FinanceHomeState(
            week = FinanceHomeRing.from(dashboard.scores?.week?.rhythm ?: Rhythm()),
            month = FinanceHomeRing.from(dashboard.scores?.month?.rhythm ?: Rhythm()),
            toQualifyCount = dashboard.todo.toSortCount,
            nextProject = dashboard.nextProject?.let {
                FinanceHomeProject(
                    projectId = it.projectId,
                    name = it.name,
                    amountCents = it.amountCents,
                    gatheredCents = it.gatheredCents,
                    purchaseMonth = it.purchaseMonth,
                )
            } ?: dashboard.projectBeyondHorizon?.let {
                FinanceHomeProject(
                    projectId = it.projectId,
                    name = it.name,
                    amountCents = it.amountCents,
                    gatheredCents = it.gatheredCents,
                    purchaseMonth = null,
                    horizonMonths = it.horizonMonths,
                )
            },
            accountCount = dashboard.tiles.accounts.accounts.size,
            envelopeCount = dashboard.tiles.budget.envelopes.size,
        )
    }
}

data class FinanceHomeRing(
    /** `on_track`, `above` or `incomplete`. */
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

    companion object {
        fun from(rhythm: Rhythm) = FinanceHomeRing(
            status = rhythm.status,
            label = rhythm.label,
            budgetCents = rhythm.budgetCents,
            remainingCents = rhythm.remainingCents,
            expectedRemainingCents = rhythm.expectedRemainingCents,
        )
    }
}

data class FinanceHomeProject(
    val projectId: String,
    val name: String,
    val amountCents: Int,
    val gatheredCents: Int,
    /** `YYYY-MM`; null when the planning lands it beyond [horizonMonths] (e.g. while the cushion is built). */
    val purchaseMonth: String? = null,
    val horizonMonths: Int = 36,
)

/** « n à qualifier », the pill of the lines the review is waiting for. */
fun FinanceHomeState.toQualifyLabel(): String = "$toQualifyCount à qualifier"

/**
 * The status of the card's one rhythm pill: `above` as soon as the week or the month runs above its rhythm,
 * `on_track` otherwise; null when neither has an envelope (no pace to tell).
 */
fun FinanceHomeState.rhythmStatus(): String? {
    val paced = listOf(week, month).filter { it.budgetCents > 0 }
    if (paced.isEmpty()) return null
    return if (paced.any { it.status == "above" }) "above" else "on_track"
}

/**
 * The right of the next project's line, as the mock-up writes it: « 740 / 1 200 € · mars 2027 »; [monthLabel]
 * names the purchase month, and a project beyond the horizon says so in its place.
 */
fun FinanceHomeProject.progressLabel(monthLabel: (String) -> String): String {
    val gathered = formatCentsRounded(gatheredCents).substringBeforeLast('\u00A0')
    val month = purchaseMonth?.let(monthLabel) ?: "au-delà de $horizonMonths mois"
    return "$gathered / ${formatCentsRounded(amountCents)} · $month"
}
