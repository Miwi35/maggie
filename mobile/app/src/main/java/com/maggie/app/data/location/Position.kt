package com.maggie.app.data.location

import java.time.Instant

/** One fix of the phone, as `PUT /api/me/location` takes it. */
data class Position(
    val latitude: Double,
    val longitude: Double,
    /** Metres; may be zero. */
    val accuracy: Double,
    val capturedAt: Instant,
    /** How the fix was acquired; only GPS ones leave the phone (MAG-666). */
    val source: FixSource = FixSource.fromAccuracy(accuracy),
) {
    /** Precise enough to stand for where the car is: a GPS fix at 50 m or better. A network fix never is: kept points lose their source. */
    val isGpsGrade: Boolean get() = source == FixSource.GPS && accuracy > 0.0 && accuracy <= GPS_GRADE_METERS

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

/** How a fix was acquired, as `PUT /api/me/location` takes it in `source`. */
enum class FixSource(val wire: String) {
    GPS("gps"),
    NETWORK("network"),
    ;

    companion object {
        /** A fix as good as a GPS one stands for one; the vaguer is a network fix. */
        fun fromAccuracy(accuracy: Double): FixSource =
            if (accuracy > 0.0 && accuracy <= Position.GPS_GRADE_METERS) GPS else NETWORK

        /**
         * The provider Android names on the [android.location.Location] decides when it says `gps` or `network`;
         * the `fused` one (Play services) blends both, so its accuracy decides.
         */
        fun of(provider: String?, accuracy: Double): FixSource = when (provider) {
            "gps" -> GPS
            "network" -> NETWORK
            else -> fromAccuracy(accuracy)
        }
    }
}

/** Where a fresh GPS fix comes from — the fused provider, or a fake in tests. Null when none could be had in time. */
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
