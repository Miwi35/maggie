package com.maggie.app.data.local.entity

import androidx.room.Entity

/**
 * A stretch of the calendar read in full from the API, and when (MAG-430).
 *
 * [firstDay] to [endDay] (excluded) are `YYYY-MM-DD` days in Paris. While [fetchedAt] is recent
 * enough, the events of that stretch are served from the table and the API is not asked again.
 */
@Entity(tableName = "fetched_periods", primaryKeys = ["firstDay", "endDay"])
data class FetchedPeriodEntity(
    val firstDay: String,
    val endDay: String,
    val fetchedAt: Long,
)
