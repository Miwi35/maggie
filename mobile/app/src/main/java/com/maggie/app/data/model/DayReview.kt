package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The evening's review (MAG-500), `GET /api/day-reviews/{YYYY-MM-DD}`: what helped that day and
 * when « Bonne nuit » closed it. Created empty on its first read. Plain JSON, not API Platform.
 */
@Serializable
data class DayReview(
    val id: String,
    val date: String,
    val helped: List<String> = emptyList(),
    val reviewedAt: String? = null,
) {
    val isDone: Boolean get() = reviewedAt != null

    companion object {
        const val START_SMALL = "start_small"
        const val MORNING = "morning"
        const val TIMER = "timer"

        /** The closed list, in the order the evening shows it, with its words. « Faire avec Maggie » comes with her. */
        val HELPED: List<Pair<String, String>> = listOf(
            START_SMALL to "Commencer petit",
            MORNING to "Préparer le matin",
            TIMER to "Le minuteur",
        )
    }
}

/** `PUT /api/day-reviews/{date}`: « Bonne nuit », the whole list of what helped. */
@Serializable
data class DayReviewUpdateRequest(val helped: List<String>)
