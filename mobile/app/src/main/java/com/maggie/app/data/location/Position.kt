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

/** How a fix is read: [HIGH] is the GPS (a position the API acts on), [BALANCED] Wi-Fi and cell towers (±500 m, easy on the battery). */
enum class PositionAccuracy { BALANCED, HIGH }

/** Where a fresh fix comes from — the fused provider, or a fake in tests. Null when none could be had in time. */
fun interface PositionSource {
    suspend fun current(accuracy: PositionAccuracy): Position?
}

/** What the owner granted. Position needs the fine and the background permissions; parking detection, the activity one. */
interface LocationPermissions {
    fun hasFineLocation(): Boolean
    fun hasBackgroundLocation(): Boolean
    fun hasActivityRecognition(): Boolean

    fun canReadPosition(): Boolean = hasFineLocation() && hasBackgroundLocation()

    fun canTrack(): Boolean = canReadPosition() && hasActivityRecognition()
}
