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

/**
 * What an investment brings on the Gantt (MAG-557), months `YYYY-MM`: the rent from the month after the
 * purchase until the resale, the resale and where its proceeds go. A month is null when it falls past the
 * planning (or, for [rentUntilMonth], when the box is still rented at its end).
 */
@Serializable
data class PlannedInvestment(
    val monthlyRentCents: Int = 0,
    val monthlyBalanceCents: Int = 0,
    val rentFromMonth: String? = null,
    val rentUntilMonth: String? = null,
    val resaleMonth: String? = null,
    val resaleNetCents: Int? = null,
    val resaleTargetProjectId: String? = null,
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
    /** Set for an investment (MAG-557). */
    val investment: PlannedInvestment? = null,
    /** The month the last resale that funds this project lands: it is not bought before. Null when no resale funds it. */
    val resaleFromMonth: String? = null,
)

/**
 * One month of the planning (`YYYY-MM`): [capacityCents] is what the salary can give the projects,
 * [salaryCents] / [savingsCents] / [capitalCents] what funds them by source; [isOverCapacity] when
 * the salary goes past that capacity. [rentCents] is the rent the investments bring that month (it is part
 * of the capacity) and [resaleCents] the net proceeds of the resales that land in it (MAG-557).
 */
@Serializable
data class PlanningMonth(
    val month: String = "",
    val capacityCents: Int = 0,
    val salaryCents: Int = 0,
    val savingsCents: Int = 0,
    val capitalCents: Int = 0,
    val isOverCapacity: Boolean = false,
    val rentCents: Int = 0,
    val resaleCents: Int = 0,
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
