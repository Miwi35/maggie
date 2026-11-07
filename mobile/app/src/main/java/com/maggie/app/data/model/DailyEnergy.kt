package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * One local day of `GET /api/health/daily-energy` (MAG-746). The steps and the active energy are null on a day with no
 * Health Connect data; [bmrKcal] and [expendedKcal] are null while the profile is incomplete. [expendedKcal] is the
 * API's total, never recomputed here: it must read as in the admin.
 */
@Serializable
data class DailyEnergy(
    val date: String,
    val steps: Int? = null,
    val activeKcal: Int? = null,
    val bmrKcal: Int? = null,
    val workoutKcal: Int = 0,
    val stepsKcal: Int? = null,
    val expendedKcal: Int? = null,
)
