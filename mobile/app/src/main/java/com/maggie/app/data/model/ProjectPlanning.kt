package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** What the safety cushion is doing in the planning: it comes before any purchase. */
object CushionPlanState {
    const val BUILDING = "building"
    const val COMPLETE = "complete"
    const val RECHARGING = "recharging"
}

/** The loan a project would take out (`YYYY-MM`): it starts the month after the purchase. */
@Serializable
data class PlannedLoan(
    val startMonth: String = "",
    /** Null when the loan outlives the 36 months. */
    val endMonth: String? = null,
    val monthlyPaymentCents: Int = 0,
)

/** One project of the planning, in priority order. Months are `YYYY-MM`. */
@Serializable
data class PlannedProject(
    val projectId: String,
    val name: String,
    val position: Int = 0,
    val wellbeing: String = ProjectWellbeing.MEDIUM.key,
    val amountCents: Int = 0,
    val dateMode: String = ProjectDateMode.NONE.key,
    val targetOn: String? = null,
    val savingFromMonth: String = "",
    /** Null when the purchase falls past the horizon. */
    val purchaseMonth: String? = null,
    val gatheredCents: Int = 0,
    val loan: PlannedLoan? = null,
    val isBeyondHorizon: Boolean = false,
    val isDeadlineMissed: Boolean = false,
    val delayMonths: Int = 0,
    val unmetDependencies: List<String> = emptyList(),
)

/**
 * One month of the planning (`YYYY-MM`): [capacityCents] is what the salary can give the projects,
 * [salaryCents] / [savingsCents] / [capitalCents] what funds them by source; [isOverCapacity] when
 * the salary goes past that capacity.
 */
@Serializable
data class PlanningMonth(
    val month: String = "",
    val capacityCents: Int = 0,
    val salaryCents: Int = 0,
    val savingsCents: Int = 0,
    val capitalCents: Int = 0,
    val isOverCapacity: Boolean = false,
)

@Serializable
data class PlannedCushion(
    val state: String = CushionPlanState.COMPLETE,
    /** Null when already complete, or not complete within the horizon. */
    val completeMonth: String? = null,
)

/**
 * `GET /api/finance/project-planning` (MAG-526): the projects over the next [horizonMonths] months,
 * computed from the saving capacity and never stored. [startMonth] is the current month, month 0.
 * With no known income there is nothing to draw: [isIncomeKnown] is false and the Gantt stays out.
 */
@Serializable
data class ProjectPlanning(
    val startMonth: String = "",
    val horizonMonths: Int = 36,
    val currency: String = "EUR",
    val isIncomeKnown: Boolean = false,
    val cushion: PlannedCushion = PlannedCushion(),
    val months: List<PlanningMonth> = emptyList(),
    val projects: List<PlannedProject> = emptyList(),
)
