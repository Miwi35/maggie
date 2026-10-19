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

/** `GET|PUT /api/finance/review/progress`: where the review of [month] stands, on any device. */
@Serializable
data class ReviewProgress(
    val month: String = "",
    val step: String = REVIEW_STEP_REVIEW,
    val handledTransactionIds: List<String> = emptyList(),
    val updatedAt: String? = null,
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
const val REVIEW_STEP_DONE = "done"

/** The value of `Event.activity` for the monthly review appointment. */
const val ACTIVITY_FINANCE_REVIEW = "finance-review"

/** The five steps of the activity, in order, with the title the header shows. */
enum class ReviewStep(val value: String, val title: String) {
    REVIEW(REVIEW_STEP_REVIEW, "Revue"),
    BALANCE(REVIEW_STEP_BALANCE, "Bilan"),
    ENVELOPES(REVIEW_STEP_ENVELOPES, "Enveloppes"),
    UPCOMING(REVIEW_STEP_UPCOMING, "À venir"),
    PROJECTS(REVIEW_STEP_PROJECTS, "Projets"),
    ;

    val next: ReviewStep? get() = entries.getOrNull(ordinal + 1)
    val previous: ReviewStep? get() = entries.getOrNull(ordinal - 1)

    companion object {
        /** The stored step; null for `done` (or a step this version does not know). */
        fun of(value: String): ReviewStep? = entries.firstOrNull { it.value == value }
    }
}

private val REVIEW_MONTHS = listOf(
    "janvier", "février", "mars", "avril", "mai", "juin",
    "juillet", "août", "septembre", "octobre", "novembre", "décembre",
)

/** « Revue d'octobre », « Revue de novembre »: the title of a `finance-review` occurrence, from its date. */
fun reviewTitle(date: LocalDate): String {
    val month = REVIEW_MONTHS[date.monthValue - 1]
    val elided = month.first() in "aeiouyéèâàô"
    return if (elided) "Revue d'$month" else "Revue de $month"
}
