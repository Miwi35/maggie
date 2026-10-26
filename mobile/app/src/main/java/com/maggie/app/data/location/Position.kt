package com.maggie.app.data.location

import java.time.Instant

/** One fix of the phone, as `PUT /api/me/location` takes it. */
data class Position(
    val latitude: Double,
    val longitude: Double,
    /** Metres; may be zero. */
    val accuracy: Double,
    val capturedAt: Instant,
)

/** Where a fresh fix comes from — the fused provider, or a fake in tests. Null when none could be had in time. */
fun interface PositionSource {
    suspend fun current(): Position?
}

/** What the owner granted. Position needs the fine and the background permissions; parking detection, the activity one. */
interface LocationPermissions {
    fun hasFineLocation(): Boolean
    fun hasBackgroundLocation(): Boolean
    fun hasActivityRecognition(): Boolean

    fun canReadPosition(): Boolean = hasFineLocation() && hasBackgroundLocation()

    fun canTrack(): Boolean = canReadPosition() && hasActivityRecognition()
}
