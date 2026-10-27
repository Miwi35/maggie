package com.maggie.app.data.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant

private val Context.departureDataStore: DataStore<Preferences> by preferencesDataStore(name = "departure_prefs")

/** The car's place, taken when the owner stepped out of it and not yet taken by the API. */
data class PendingCar(val latitude: Double, val longitude: Double, val parkedAt: Instant)

/**
 * « Partir à l'heure » on this phone (MAG-651): the switch, off until the owner turns it on, and
 * what a receiver that may die between two events has to remember — when the owner left a vehicle,
 * the car's place whose send failed, what the phone is doing (still, moving, in a vehicle), the
 * recent GPS-grade points and the last position sent (MAG-666).
 */
class LocationPreferences(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.departureDataStore)

    private val enabledKey = booleanPreferencesKey("enabled")
    private val vehicleExitKey = longPreferencesKey("vehicle_exit_ms")
    private val carLatitudeKey = doublePreferencesKey("car_latitude")
    private val carLongitudeKey = doublePreferencesKey("car_longitude")
    private val carParkedAtKey = longPreferencesKey("car_parked_at_ms")
    private val motionKey = stringPreferencesKey("motion")
    private val gpsLogKey = stringPreferencesKey("gps_log")
    private val movedKey = booleanPreferencesKey("moved_since_gps_point")
    private val lastSentKey = stringPreferencesKey("last_sent")
    private val carReportedKey = longPreferencesKey("car_reported_parked_at_ms")

    val enabled: Flow<Boolean> = store.data.map { it[enabledKey] ?: false }

    suspend fun isEnabled(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[enabledKey] = value }
    }

    suspend fun vehicleExit(): Instant? = store.data.first()[vehicleExitKey]?.let(Instant::ofEpochMilli)

    suspend fun setVehicleExit(at: Instant?) {
        store.edit { if (at == null) it.remove(vehicleExitKey) else it[vehicleExitKey] = at.toEpochMilli() }
    }

    suspend fun pendingCar(): PendingCar? {
        val prefs = store.data.first()
        return PendingCar(
            prefs[carLatitudeKey] ?: return null,
            prefs[carLongitudeKey] ?: return null,
            Instant.ofEpochMilli(prefs[carParkedAtKey] ?: return null),
        )
    }

    suspend fun setPendingCar(car: PendingCar?) {
        store.edit {
            if (car == null) {
                it.remove(carLatitudeKey)
                it.remove(carLongitudeKey)
                it.remove(carParkedAtKey)
            } else {
                it[carLatitudeKey] = car.latitude
                it[carLongitudeKey] = car.longitude
                it[carParkedAtKey] = car.parkedAt.toEpochMilli()
            }
        }
    }

    val motion: Flow<Motion> = store.data.map { prefs ->
        prefs[motionKey]?.let { name -> Motion.entries.firstOrNull { it.name == name } } ?: Motion.UNKNOWN
    }.distinctUntilChanged()

    suspend fun motion(): Motion = motion.first()

    /** What the phone does now; [moved] when it left stillness, which makes the last GPS point stale. */
    suspend fun recordMotion(motion: Motion, moved: Boolean) {
        store.edit {
            it[motionKey] = motion.name
            if (moved) it[movedKey] = true
        }
    }

    /** The recent GPS-grade points, oldest first. */
    suspend fun gpsLog(): List<Position> = decode(store.data.first()[gpsLogKey])

    /**
     * Keeps [point] among the recent ones (one every [LOG_SPACING] at most, [LOG_SIZE] in all) and
     * says the phone has not moved since: it was taken just now.
     */
    suspend fun addGpsPoint(point: Position) {
        store.edit {
            val log = decode(it[gpsLogKey])
            val last = log.lastOrNull()
            if (last == null || !point.capturedAt.isBefore(last.capturedAt.plus(LOG_SPACING))) {
                it[gpsLogKey] = encode((log + point).takeLast(LOG_SIZE))
            }
            it[movedKey] = false
        }
    }

    /** The newest GPS-grade point, if the phone has not left stillness since it was taken. */
    suspend fun keptGpsPoint(): Position? {
        val prefs = store.data.first()
        if (prefs[movedKey] != false) return null
        return decode(prefs[gpsLogKey]).lastOrNull()
    }

    suspend fun lastSent(): Position? = decode(store.data.first()[lastSentKey]).singleOrNull()

    suspend fun setLastSent(position: Position) {
        store.edit { it[lastSentKey] = encode(listOf(position)) }
    }

    /** When the owner last stepped out of the car, as the last car report had it. */
    suspend fun lastCarParkedAt(): Instant? = store.data.first()[carReportedKey]?.let(Instant::ofEpochMilli)

    suspend fun setLastCarParkedAt(at: Instant) {
        store.edit { it[carReportedKey] = at.toEpochMilli() }
    }

    /** The owner is signed out or turned the switch off: nothing about where they were stays on the phone. */
    suspend fun forgetPositions() {
        store.edit {
            it.remove(motionKey)
            it.remove(gpsLogKey)
            it.remove(movedKey)
            it.remove(lastSentKey)
            it.remove(carReportedKey)
        }
    }

    private fun encode(points: List<Position>): String =
        points.joinToString(";") { "${it.latitude},${it.longitude},${it.accuracy},${it.capturedAt.toEpochMilli()}" }

    private fun decode(text: String?): List<Position> = text.orEmpty().split(';').mapNotNull { entry ->
        val parts = entry.split(',')
        if (parts.size != 4) return@mapNotNull null
        runCatching {
            Position(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble(), Instant.ofEpochMilli(parts[3].toLong()))
        }.getOrNull()
    }

    companion object {
        val LOG_SPACING: Duration = Duration.ofSeconds(30)
        const val LOG_SIZE = 20
    }
}
