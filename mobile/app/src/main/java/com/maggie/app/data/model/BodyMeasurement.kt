package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * A body measurement of the owner (MAG-706), read from `GET /api/body-measurements` (sorted by [measuredAt], newest
 * first) and pushed on the `body-measurements` topic. [measuredAt] is an ISO date-time with its offset; at least one of
 * the three values is set, the others are null. An [excluded] weighing stays listed but never counts: not the day's
 * value, not in the trend. [source] stays the API's string (`manual`, `health_connect`, `withings`).
 */
@Serializable
data class BodyMeasurement(
    val id: String,
    val measuredAt: String,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val waistCm: Double? = null,
    val source: String = "manual",
    val externalId: String? = null,
    val excluded: Boolean = false,
)
