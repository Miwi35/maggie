package com.maggie.app.data.model

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * One line of the review queue (`GET /api/finance/review/queue`, MAG-456): a line nobody qualified
 * ([recognised] false, with Maggie's [suggestion]) or one an operation qualified that waits for its check
 * ([recognised] true, with its [categoryPath]). The names are the ones of `finance_review_queue.read`.
 */
@Serializable
data class ReviewQueueItem(
    val id: String,
    val label: String,
    val recognised: Boolean = false,
    val counterpartyName: String? = null,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val accountId: String? = null,
    val accountName: String? = null,
    val judgeable: Boolean = false,
    val retrospect: String = RETROSPECT_UNRATED,
    val suggestion: SortSuggestion? = null,
    val categoryId: String? = null,
    val categoryPath: String? = null,
    val qualifiedBy: String? = null,
)

/** `GET /api/finance/review/queue?month=YYYY-MM`: the lines to qualify first, then those to control. */
@Serializable
data class ReviewQueue(
    val month: String = "",
    val unrecognisedCount: Int = 0,
    val unrecognisedCents: Int = 0,
    val recognisedCount: Int = 0,
    val items: List<ReviewQueueItem> = emptyList(),
)

/**
 * A quarter (`2027-Q1`), a semester (`2027-S2`) or a year (`2027`), as the API describes it (MAG-462):
 * [from] and [to] are `YYYY-MM`, the first and the last month of the period.
 */
@Serializable
data class PlanningPeriodRef(
    val key: String = "",
    val level: String = REVIEW_LEVEL_QUARTER,
    val from: String = "",
    val to: String = "",
    val months: Int = 3,
)

/**
 * `GET|PUT /api/finance/review/progress`: where the review of [month] stands, on any device. A review
 * that closes a quarter, a semester or a year ([level]) also carries the [period] it closes and the
 * [nextPeriod] it plans (MAG-462); a plain month carries neither.
 */
@Serializable
data class ReviewProgress(
    val month: String = "",
    val step: String = REVIEW_STEP_REVIEW,
    val handledTransactionIds: List<String> = emptyList(),
    val updatedAt: String? = null,
    val level: String = REVIEW_LEVEL_MONTH,
    val period: PlanningPeriodRef? = null,
    val nextPeriod: PlanningPeriodRef? = null,
)

/** The body of `PUT /api/finance/review/progress`: [step] replaces, [handledTransactionIds] are added to the list. */
@Serializable
data class ReviewProgressUpdate(
    val month: String,
    val step: String? = null,
    val handledTransactionIds: List<String>? = null,
)

const val REVIEW_STEP_REVIEW = "review"
const val REVIEW_STEP_BALANCE = "balance"
const val REVIEW_STEP_ENVELOPES = "envelopes"
const val REVIEW_STEP_UPCOMING = "upcoming"
const val REVIEW_STEP_PROJECTS = "projects"
const val REVIEW_STEP_PERIOD_BALANCE = "period_balance"
const val REVIEW_STEP_PERIOD_PLAN = "period_plan"
const val REVIEW_STEP_DONE = "done"

const val REVIEW_LEVEL_MONTH = "month"
const val REVIEW_LEVEL_QUARTER = "quarter"
const val REVIEW_LEVEL_SEMESTER = "semester"
const val REVIEW_LEVEL_YEAR = "year"

/** The value of `Event.activity` for the monthly review appointment. */
const val ACTIVITY_FINANCE_REVIEW = "finance-review"

/**
 * The steps of the activity, in order, with the title the header shows. A month's review walks the
 * first five; the one that closes a quarter, a semester or a year walks seven (MAG-462): the bilan of
 * the period and the planning of the next one come after Projets.
 */
enum class ReviewStep(val value: String, val title: String) {
    REVIEW(REVIEW_STEP_REVIEW, "Revue"),
    BALANCE(REVIEW_STEP_BALANCE, "Bilan"),
    ENVELOPES(REVIEW_STEP_ENVELOPES, "Enveloppes"),
    UPCOMING(REVIEW_STEP_UPCOMING, "À venir"),
    PROJECTS(REVIEW_STEP_PROJECTS, "Projets"),
    PERIOD_BALANCE(REVIEW_STEP_PERIOD_BALANCE, "Bilan de la période"),
    PERIOD_PLAN(REVIEW_STEP_PERIOD_PLAN, "Planification"),
    ;

    val next: ReviewStep? get() = entries.getOrNull(ordinal + 1)
    val previous: ReviewStep? get() = entries.getOrNull(ordinal - 1)

    /** The title shown for a review of [level]: « Bilan du trimestre », « Planification de l'année suivante »… */
    fun titleFor(level: String): String {
        val words = PeriodWords.of(level) ?: return title
        return when (this) {
            PERIOD_BALANCE -> "Bilan ${words.of}"
            PERIOD_PLAN -> "Planification ${words.next}"
            else -> title
        }
    }

    companion object {
        /** The stored step; null for `done` (or a step this version does not know). */
        fun of(value: String): ReviewStep? = entries.firstOrNull { it.value == value }

        /** The steps of a plain month's review. */
        val MONTH: List<ReviewStep> = listOf(REVIEW, BALANCE, ENVELOPES, UPCOMING, PROJECTS)
    }
}

/** How a period is named in the titles: « du trimestre », « du trimestre suivant ». */
internal class PeriodWords(val of: String, val next: String) {
    companion object {
        fun of(level: String): PeriodWords? = when (level) {
            REVIEW_LEVEL_QUARTER -> PeriodWords("du trimestre", "du trimestre suivant")
            REVIEW_LEVEL_SEMESTER -> PeriodWords("du semestre", "du semestre suivant")
            REVIEW_LEVEL_YEAR -> PeriodWords("de l'année", "de l'année suivante")
            else -> null
        }
    }
}

private val REVIEW_MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** The last month of a quarter, a semester, a year: the review of the 25th closes a period (MAG-462). */
private val PERIOD_ENDS = mapOf(
    3 to "Revue de fin de trimestre",
    6 to "Revue de fin de semestre",
    9 to "Revue de fin de trimestre",
    12 to "Revue de fin d'année",
)

/**
 * « Revue d'octobre », « Revue de novembre », « Revue de fin de trimestre »: the title of a `finance-review`
 * occurrence, from its date.
 */
fun reviewTitle(date: LocalDate): String {
    PERIOD_ENDS[date.monthValue]?.let { return it }
    val month = REVIEW_MONTHS[date.monthValue - 1]
    val elided = month.first() in "aeiouyéèâàô"
    return if (elided) "Revue d'$month" else "Revue de $month"
}

/** « avril à juin 2027 », or « 2028 » for a year: how a period is read in a sentence. */
fun periodLabel(period: PlanningPeriodRef): String {
    if (period.level == REVIEW_LEVEL_YEAR) return period.from.take(4)
    val from = REVIEW_MONTHS.getOrNull(period.from.drop(5).toIntOrNull()?.minus(1) ?: -1) ?: return period.key
    val to = REVIEW_MONTHS.getOrNull(period.to.drop(5).toIntOrNull()?.minus(1) ?: -1) ?: return period.key
    return "$from à $to ${period.to.take(4)}"
}

/** One month of the period: what was budgeted, spent and put aside. */
@Serializable
data class PeriodMonth(
    val month: String = "",
    val budgetCents: Int = 0,
    val spentCents: Int = 0,
    val savedCents: Int = 0,
)

/** A planned expense of the period, the stand-in for a project until projects exist. */
@Serializable
data class PeriodProject(
    val id: String = "",
    val label: String = "",
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val month: String = "",
    val status: String = "",
    val categoryName: String? = null,
)

@Serializable
data class PeriodTotals(
    val budgetCents: Int = 0,
    val spentCents: Int = 0,
    val savedCents: Int = 0,
)

@Serializable
data class PeriodEnvelope(
    val categoryId: String = "",
    val name: String = "",
    val budgetCents: Int = 0,
    val consumedCents: Int = 0,
    val percent: Int? = null,
    val status: String = "held",
)

@Serializable
data class PeriodProjects(
    val done: List<PeriodProject> = emptyList(),
    val postponed: List<PeriodProject> = emptyList(),
)

/** `GET /api/finance/review/period-balance?month=`: the bilan of the quarter, semester or year a review closes. */
@Serializable
data class PeriodBalance(
    val period: PlanningPeriodRef = PlanningPeriodRef(),
    val months: List<PeriodMonth> = emptyList(),
    val totals: PeriodTotals = PeriodTotals(),
    val envelopes: List<PeriodEnvelope> = emptyList(),
    val satisfaction: BalanceSatisfaction = BalanceSatisfaction(),
    val savings: BalanceSavings = BalanceSavings(),
    val projects: PeriodProjects = PeriodProjects(),
)

@Serializable
data class PlanLastPeriod(
    val consumedCents: Int = 0,
)

/** One category of the plan: what the previous period cost, what is decided, what is proposed. */
@Serializable
data class PlanCategory(
    val categoryId: String = "",
    val categoryName: String = "",
    val currency: String = "EUR",
    val lastYear: PlanLastPeriod = PlanLastPeriod(),
    /** What the envelopes already set on the period add up to; null when there is none. */
    val envelopeCents: Int? = null,
    val decidedCents: Int = 0,
    val suggestedCents: Int = 0,
    val suggestedMonthlyCents: Int = 0,
)

/** `GET /api/finance/annual-plan?period=`: the planning session of a period. */
@Serializable
data class PeriodPlan(
    val period: PlanningPeriodRef = PlanningPeriodRef(),
    val sourcePeriod: PlanningPeriodRef = PlanningPeriodRef(),
    val totalSuggestedCents: Int = 0,
    val totalEnvelopedCents: Int = 0,
    val categories: List<PlanCategory> = emptyList(),
)

/**
 * One line of what `POST /api/finance/annual-plan` applies: [kind] is `annual` or `monthly` for a category
 * (with [categoryId]), `one_off` for an event with a [name].
 */
@Serializable
data class PlanItem(
    val kind: String,
    val amountCents: Int,
    val currency: String = "EUR",
    val categoryId: String? = null,
    val name: String? = null,
)

@Serializable
data class ApplyPlanRequest(
    val period: String,
    val items: List<PlanItem>,
)

/** The 400 of a plan with a line refused before anything was written: [index] is the line to blame, null for the plan itself. */
@Serializable
data class ApplyPlanRefusal(
    val error: String = "",
    val index: Int? = null,
)

/** What a refused plan says, to show it and name the line. */
class PlanRefusedException(message: String, val index: Int?) : Exception(message)
