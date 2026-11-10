package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * One local day of the health journal, `/api/health/days` (MAG-750). The figures — the first weighing and its time,
 * the steps, the kcal eaten and spent, the balance (today's live, planned meals included), the sport minutes — are
 * the API's, never recomputed here; [mood] and [fatigue] (1–10) and [note] are the owner's or Maggie's, [notedBy]
 * says whose (`user`, `maggie`) and [notedAt] when. A day the journal does not hold has [id] null and all of it null.
 */
@Serializable
data class HealthDay(
    val id: String? = null,
    val date: String,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val weighedAt: String? = null,
    val steps: Int? = null,
    val eatenKcal: Int? = null,
    val expendedKcal: Int? = null,
    val balanceKcal: Int? = null,
    val sportMinutes: Int? = null,
    val mood: Int? = null,
    val fatigue: Int? = null,
    val note: String? = null,
    val notedBy: String? = null,
    val notedAt: String? = null,
    /** The fast that ended that day, from the day before's last intake to its first (MAG-770); null without both. */
    val nightFastMinutes: Int? = null,
) {
    companion object {
        const val NOTED_BY_USER = "user"
        const val NOTED_BY_MAGGIE = "maggie"

        /** The two scales run from 1 to 10. */
        val SCALE = 1..10
    }
}

/** `GET /api/health/days?from=&to=`: only the days the journal holds, oldest first. */
@Serializable
data class HealthDayRange(val days: List<HealthDay> = emptyList())
