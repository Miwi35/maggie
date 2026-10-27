package com.maggie.app.data.location

import java.time.Instant

/** One fix of the phone, as `PUT /api/me/location` takes it. */
data class Position(
    val latitude: Double,
    val longitude: Double,
    /** Metres; may be zero. */
    val accuracy: Double,
    val capturedAt: Instant,
) {
    /** Precise enough to stand for where the car is: a GPS fix, or any fix as good as one. */
    val isGpsGrade: Boolean get() = accuracy > 0.0 && accuracy <= GPS_GRADE_METERS

    /** Metres to [other], great-circle. */
    fun distanceTo(other: Position): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(a))
    }

    companion object {
        const val GPS_GRADE_METERS = 50.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

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
