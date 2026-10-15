package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Envelope(
    val id: String,
    val mode: String = "monthly",
    val amountCents: Int = 0,
    val currency: String = "EUR",
    // Null for a one-off envelope, which has neither period nor category (MAG-414).
    val year: Int? = null,
    val month: Int? = null,
    // IRI of the category budgeted; null for a one-off envelope.
    val category: String? = null,
    // What a one-off envelope is for (« Hellfest 2026 »); the API omits it on the others.
    val name: String? = null,
    // A monthly envelope's amounts for stretches of the year; amountCents is the rest (MAG-415).
    val seasonalAmounts: List<Season> = emptyList(),
) {
    val isOneOff: Boolean get() = mode == ONE_OFF_MODE
}

/**
 * A stretch of the year a monthly envelope is budgeted differently (MAG-415):
 * Cinéma 45 € from November to February. It may cross the new year (11 → 2).
 */
@Serializable
data class Season(
    val fromMonth: Int,
    val toMonth: Int,
    val amountCents: Int,
) {
    /** The months it covers, in order: 11 → 2 is 11, 12, 1, 2. */
    val months: List<Int>
        get() = generateSequence(fromMonth) { if (it == toMonth) null else it % 12 + 1 }
            .take(12)
            .toList()
}

private val MONTH_SHORT = listOf(
    "janv.", "févr.", "mars", "avr.", "mai", "juin",
    "juil.", "août", "sept.", "oct.", "nov.", "déc.",
)

/** « saison nov.–févr. », or « saison juil. » for a single month. */
fun seasonLabel(season: Season): String {
    val from = MONTH_SHORT.getOrNull(season.fromMonth - 1) ?: season.fromMonth.toString()
    val to = MONTH_SHORT.getOrNull(season.toMonth - 1) ?: season.toMonth.toString()
    return if (season.fromMonth == season.toMonth) "saison $from" else "saison $from–$to"
}

/** The first month two seasons share, or null: the API refuses overlapping seasons (400). */
fun overlappingMonth(seasons: List<Season>): Int? {
    val taken = mutableSetOf<Int>()
    for (season in seasons) {
        for (month in season.months) {
            if (!taken.add(month)) return month
        }
    }
    return null
}

/** An event (Hellfest 2026): a name and an amount, spends attributed to it by hand. */
const val ONE_OFF_MODE = "one_off"

fun envelopeIri(id: String): String = "/api/envelopes/$id"

/** What weighs on an envelope, whatever its mode: the gauge reads only this. */
interface EnvelopeConsumption {
    val amountCents: Int
    val currency: String
    val spentCents: Int
    val committedCents: Int
    val plannedCents: Int
    val toArbitrateCents: Int
    val consumedCents: Int
    val remainingCents: Int
    val availableCents: Int
    val isOverspent: Boolean
    val isOvercommitted: Boolean
}

/** One category budget of a period, with what has already been spent on it. */
@Serializable
data class BudgetLine(
    val id: String,
    val categoryId: String,
    val categoryName: String,
    val categoryPath: String = "",
    val parentEnvelopeId: String? = null,
    val mode: String = "monthly",
    override val amountCents: Int = 0,
    override val currency: String = "EUR",
    val year: Int = 0,
    val month: Int? = null,
    // The season budgeting this month: amountCents is then its amount (MAG-415).
    val season: Season? = null,
    override val spentCents: Int = 0,
    override val committedCents: Int = 0,
    override val plannedCents: Int = 0,
    override val toArbitrateCents: Int = 0,
    override val consumedCents: Int = 0,
    override val remainingCents: Int = 0,
    override val availableCents: Int = 0,
    override val isOverspent: Boolean = false,
    override val isOvercommitted: Boolean = false,
    /** Past this share of the budget, a bank sync makes Maggie interrupt (MAG-49). */
    val alertThresholdPercent: Int = DEFAULT_ALERT_THRESHOLD_PERCENT,
) : EnvelopeConsumption

/** A one-off envelope's line: every spend ever attributed to it, whatever its date. */
@Serializable
data class OneOffBudgetLine(
    val id: String,
    val name: String,
    val mode: String = ONE_OFF_MODE,
    override val amountCents: Int = 0,
    override val currency: String = "EUR",
    override val spentCents: Int = 0,
    override val committedCents: Int = 0,
    override val plannedCents: Int = 0,
    override val toArbitrateCents: Int = 0,
    override val consumedCents: Int = 0,
    override val remainingCents: Int = 0,
    override val availableCents: Int = 0,
    override val isOverspent: Boolean = false,
    override val isOvercommitted: Boolean = false,
) : EnvelopeConsumption

/** The envelopes kept apart from the month's budget: none of them counts in its totals. */
@Serializable
data class OutsideMonthlyBudget(
    val annual: List<BudgetLine> = emptyList(),
    val oneOff: List<OneOffBudgetLine> = emptyList(),
) {
    val isEmpty: Boolean get() = annual.isEmpty() && oneOff.isEmpty()
}

/** The threshold of an envelope that sets none — the API's default. */
const val DEFAULT_ALERT_THRESHOLD_PERCENT = 80

/**
 * What the threshold field holds: blank is « the default » (null), 1 to 99 is a
 * threshold, anything else is refused — 100 % always alerts anyway.
 */
sealed interface AlertThresholdInput {
    data class Valid(val percent: Int?) : AlertThresholdInput
    data object Invalid : AlertThresholdInput

    companion object {
        fun parse(text: String): AlertThresholdInput {
            val trimmed = text.trim().removeSuffix("%").trim()
            if (trimmed.isEmpty()) return Valid(null)
            val percent = trimmed.toIntOrNull() ?: return Invalid
            return if (percent in 1..99) Valid(percent) else Invalid
        }
    }
}

@Serializable
data class BudgetStatus(
    val year: Int = 0,
    val month: Int = 0,
    val totalBudgetedCents: Int = 0,
    val totalSpentCents: Int = 0,
    val totalCommittedCents: Int = 0,
    val totalPlannedCents: Int = 0,
    val totalConsumedCents: Int = 0,
    val totalRemainingCents: Int = 0,
    val totalAvailableCents: Int = 0,
    // The monthly envelopes only: the month's budget, which the totals add up.
    val budgets: List<BudgetLine> = emptyList(),
    val outsideMonthlyBudget: OutsideMonthlyBudget = OutsideMonthlyBudget(),
)

@Serializable
data class RollOverResult(
    val success: Boolean = false,
    val created: Int = 0,
    val skipped: Int = 0,
)

/** Human-readable French label for a budget mode code. */
fun budgetModeLabel(mode: String): String = when (mode) {
    "monthly" -> "Mensuel"
    "annual" -> "Annuel"
    ONE_OFF_MODE -> "Ponctuel"
    else -> mode
}

private val MONTH_NAMES = listOf(
    "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
    "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
)

/** French label of a month number, or the number itself when out of range. */
fun monthLabel(month: Int): String = MONTH_NAMES.getOrNull(month - 1) ?: month.toString()

/** Label of the period a budget covers: "Juillet 2026" or "Année 2026". */
fun budgetPeriodLabel(mode: String, year: Int, month: Int?): String =
    if (mode == "annual" || month == null) "Année $year" else "${monthLabel(month)} $year"

/**
 * What a monthly envelope's card says under its name: the period, and when a season budgets
 * the month, its amount and the season — « Novembre 2026 · 45,00 € · saison nov.–févr. ».
 */
fun budgetCaption(line: BudgetLine): String {
    val period = budgetPeriodLabel(line.mode, line.year, line.month)
    val season = line.season ?: return period
    return "$period · ${formatCents(line.amountCents, line.currency)} · ${seasonLabel(season)}"
}

/** Share of a budget already spent, in [0f, 1f], for a progress indicator. */
fun consumedFraction(spentCents: Int, amountCents: Int): Float {
    if (amountCents <= 0) return if (spentCents > 0) 1f else 0f
    return (spentCents.toFloat() / amountCents).coerceIn(0f, 1f)
}

/** What weighs on an envelope, spelled out: "250,00 € dépensés · 300,00 € engagés". */
fun budgetBreakdown(line: EnvelopeConsumption): String = listOfNotNull(
    line.spentCents.takeIf { it > 0 }?.let { "${formatCents(it, line.currency)} dépensés" },
    line.committedCents.takeIf { it > 0 }?.let { "${formatCents(it, line.currency)} engagés" },
    line.plannedCents.takeIf { it > 0 }?.let { "${formatCents(it, line.currency)} planifiés" },
    line.toArbitrateCents.takeIf { it > 0 }?.let { "${formatCents(it, line.currency)} à arbitrer" },
).joinToString(" · ")

/** An envelope placed in its tree: `depth` is 1 for an envelope with none above it. */
data class NestedBudgetLine(val line: BudgetLine, val depth: Int, val label: String)

/**
 * The envelopes in tree order — each followed by the envelopes nested in it, its label
 * the category as seen from the envelope above (« Bars › Obsydian » under Loisirs).
 * A line whose mother is not in the list stays at the top.
 */
fun nestBudgetLines(lines: List<BudgetLine>): List<NestedBudgetLine> {
    val ids = lines.map { it.id }.toSet()
    val children = lines.groupBy { line -> line.parentEnvelopeId?.takeIf { it in ids } }
    val rows = mutableListOf<NestedBudgetLine>()

    fun walk(key: String?, depth: Int, mother: BudgetLine?) {
        for (line in children[key].orEmpty()) {
            val prefix = mother?.let { "${it.categoryPath} › " }
            val label = if (prefix != null && line.categoryPath.startsWith(prefix)) {
                line.categoryPath.removePrefix(prefix)
            } else {
                line.categoryPath.ifEmpty { line.categoryName }
            }
            rows += NestedBudgetLine(line, depth, label)
            walk(line.id, depth + 1, line)
        }
    }
    walk(null, 1, null)

    return rows
}
