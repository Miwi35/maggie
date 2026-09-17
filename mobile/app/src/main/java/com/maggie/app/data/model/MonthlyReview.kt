package com.maggie.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PendingSpend(
    val id: String,
    val label: String,
    val amountCents: Int = 0,
    val currency: String = "EUR",
    val bookedAt: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val retrospect: String = "unrated",
)

@Serializable
data class ReviewComparison(
    val thisMonthCents: Int = 0,
    val previousMonthCents: Int = 0,
    val recentAverageCents: Int = 0,
    val sameMonthLastYearCents: Int = 0,
)

@Serializable
data class MonthlyReview(
    val year: Int = 0,
    val month: Int = 0,
    val reviewableCents: Int = 0,
    val keptCents: Int = 0,
    val avoidableCents: Int = 0,
    val unratedCents: Int = 0,
    val ratedCount: Int = 0,
    val pendingCount: Int = 0,
    val optimisationScore: Int? = null,
    val isComplete: Boolean = false,
    val pending: List<PendingSpend> = emptyList(),
    val comparison: ReviewComparison = ReviewComparison(),
)

/** Human-readable French label for a review verdict. */
fun verdictLabel(verdict: String): String = when (verdict) {
    "keep" -> "À conserver"
    "avoidable" -> "J'aurais pu m'en passer"
    "unrated" -> "À revoir"
    else -> verdict
}

/** The optimisation score, or why there is none yet. */
fun optimisationScoreLabel(review: MonthlyReview): String = when (review.optimisationScore) {
    null -> "Aucune dépense qualifiée pour l'instant"
    else -> "${review.optimisationScore} % de ce que vous avez jugé était à conserver"
}
