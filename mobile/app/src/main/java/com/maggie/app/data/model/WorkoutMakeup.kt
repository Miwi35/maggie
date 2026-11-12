package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/** `GET /api/workout-makeups/{id}` (MAG-772): the minutes the make-up buttons are worded with. */
@Serializable
data class WorkoutMakeup(
    val softerMinutes: Int,
    val shorterMinutes: Int,
    val status: String? = null,
)
