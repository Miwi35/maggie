package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.FinanceTimeline
import com.maggie.app.data.model.TimelineEvent
import com.maggie.app.data.model.FinanceTimelineLine
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.independenceMonthLabel
import kotlin.math.roundToInt

/** The event the frise ends on: the month the rentes cover the train de vie. */
const val TIMELINE_INDEPENDENCE_TYPE = "independence_reached"

/** Grey of « matelas »: what a colour the API sends badly written falls back to. */
private const val FALLBACK_COLOR = 0xFFABA6B8.toInt()

/** `#RRGGBB` as an opaque ARGB int; the neutral grey when the API's colour cannot be read. */
fun timelineCategoryColor(hex: String): Int {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return FALLBACK_COLOR
    val rgb = digits.toIntOrNull(16) ?: return FALLBACK_COLOR
    return 0xFF000000.toInt() or rgb
}

/**
 * Where [month] falls on the frise's axis: 0 is the first month, `horizon - 1` the last. A month
 * outside the horizon is pulled to the nearest edge rather than drawn off the track.
 */
fun timelineMonthIndex(startMonth: String, month: String, horizon: Int): Int =
    (monthNumber(month) - monthNumber(startMonth)).coerceIn(0, maxOf(horizon - 1, 0))

data class FriseLegendItem(val key: String, val label: String, val color: Int)

/** One event as the screen draws it; its [type] feeds the UI tag, so a journey finds « Indépendance ». */
data class FriseEvent(
    val id: String,
    val type: String,
    val monthIndex: Int,
    val monthLabel: String,
    val label: String,
    val sublabel: String?,
    val color: Int,
    val categoryLabel: String,
    /** The figures of the event in words, or null when it carries none. */
    val figures: String?,
) {
    val isIndependence: Boolean get() = type == TIMELINE_INDEPENDENCE_TYPE
}

/** One scenario's row of the frise: « Suivi » is the one the Dashboard follows, « Comparé » the other. */
data class FriseLine(
    val role: String,
    val name: String,
    /** « Date N · décembre 2045 », or the plain fact that N is not reached within the horizon. */
    val nLabel: String,
    val nIndex: Int?,
    val events: List<FriseEvent>,
    val isCompared: Boolean,
)

data class Frise(
    val startMonth: String,
    val horizon: Int,
    val assumptions: String,
    val legend: List<FriseLegendItem>,
    val lines: List<FriseLine>,
    /** The API's sentence about the gap of N, as it is. */
    val difference: String?,
)

/** The frise to draw from the API's answer: events in the order of their month, coloured by category. */
fun friseOf(timeline: FinanceTimeline): Frise {
    val categories = timeline.categories.associateBy { it.key }
    val horizon = timeline.horizonMonths

    fun lineOf(line: FinanceTimelineLine, isCompared: Boolean) = FriseLine(
        role = if (isCompared) "Comparé" else "Suivi",
        name = line.name,
        nLabel = line.nDate?.let { "Date N · ${independenceMonthLabel(it)}" } ?: "Date N non atteinte d'ici 20 ans",
        nIndex = line.nDate?.let { timelineMonthIndex(timeline.startMonth, it, horizon) },
        events = line.events
            .sortedBy { it.month }
            .map { friseEvent(it, timeline.startMonth, horizon, categories[it.category]?.label ?: it.category, categories[it.category]?.color) },
        isCompared = isCompared,
    )

    return Frise(
        startMonth = timeline.startMonth,
        horizon = horizon,
        assumptions = timeline.assumptions,
        legend = timeline.categories.map { FriseLegendItem(it.key, it.label, timelineCategoryColor(it.color)) },
        lines = listOfNotNull(
            lineOf(timeline.current, isCompared = false),
            timeline.compared?.let { lineOf(it, isCompared = true) },
        ),
        difference = timeline.difference?.sentence?.takeIf { it.isNotBlank() },
    )
}

private fun friseEvent(event: TimelineEvent, start: String, horizon: Int, categoryLabel: String, color: String?) = FriseEvent(
    id = event.id,
    type = event.type,
    monthIndex = timelineMonthIndex(start, event.month, horizon),
    monthLabel = independenceMonthLabel(event.month),
    label = event.label,
    sublabel = event.sublabel?.takeIf { it.isNotBlank() },
    color = color?.let(::timelineCategoryColor) ?: FALLBACK_COLOR,
    categoryLabel = categoryLabel,
    figures = eventFigures(event),
)

private fun euros(amount: Double) = formatCents((amount * 100).roundToInt())

/** The amount, the change of the monthly budget and the move of N, each said once, and only when the event has it. */
internal fun eventFigures(event: TimelineEvent): String? {
    val parts = buildList {
        event.amountEur?.let { add(euros(it)) }
        event.monthlyImpactEur?.let { add("budget mensuel ${if (it > 0) "+" else ""}${euros(it)}") }
        event.nImpactMonths?.let { add(nImpactSentence(it)) }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun nImpactSentence(months: Int): String = when {
    months == 0 -> "date N inchangée"
    months < 0 -> "date N ${-months} mois plus tôt"
    else -> "date N $months mois plus tard"
}
