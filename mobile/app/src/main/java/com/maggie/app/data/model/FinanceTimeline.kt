package com.maggie.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A category of the frise with the colour every channel draws it in (`#RRGGBB`). */
@Serializable
data class TimelineCategory(
    val key: String = "",
    val label: String = "",
    val color: String = "",
)

/**
 * One event of a scenario's frise (MAG-554), on [month] (`YYYY-MM`). Amounts are in euros; the
 * snake_case names are the API's own, read here by name: a bare `amountEur` would silently stay null.
 */
@Serializable
data class TimelineEvent(
    val id: String = "",
    val month: String = "",
    val type: String = "",
    val category: String = "",
    val label: String = "",
    val sublabel: String? = null,
    @SerialName("amount_eur") val amountEur: Double? = null,
    @SerialName("monthly_impact_eur") val monthlyImpactEur: Double? = null,
    /** Months the event moves the date N (negative: sooner); null when N is not reached or it does not weigh on it. */
    @SerialName("n_impact_months") val nImpactMonths: Int? = null,
)

/** One scenario drawn on the frise: [scenarioId] is null for the planning that follows no scenario. */
@Serializable
data class FinanceTimelineLine(
    val scenarioId: String? = null,
    val name: String = "",
    /** `YYYY-MM`; null when independence is not reached within the horizon. */
    val nDate: String? = null,
    val events: List<TimelineEvent> = emptyList(),
)

/** The gap of N between the two scenarios, in the API's own words. */
@Serializable
data class TimelineDifference(
    val months: Int? = null,
    val sentence: String = "",
)

/**
 * `GET /api/finance/timeline[?compare=<scenario id>]` (MAG-554): the events of the followed scenario
 * month by month over [horizonMonths] months from [startMonth], and — when asked — of a second one,
 * with the [assumptions] the figures rest on. Facts only: no scenario is called better.
 */
@Serializable
data class FinanceTimeline(
    val startMonth: String = "",
    val horizonMonths: Int = 240,
    val assumptions: String = "",
    val categories: List<TimelineCategory> = emptyList(),
    val current: FinanceTimelineLine = FinanceTimelineLine(),
    val compared: FinanceTimelineLine? = null,
    val difference: TimelineDifference? = null,
)
