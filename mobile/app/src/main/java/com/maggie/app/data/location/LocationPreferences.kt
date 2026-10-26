package com.maggie.app.data.location

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

private val Context.departureDataStore: DataStore<Preferences> by preferencesDataStore(name = "departure_prefs")

/** The car's place, taken when the owner stepped out of it and not yet taken by the API. */
data class PendingCar(val latitude: Double, val longitude: Double, val parkedAt: Instant)

/**
 * « Partir à l'heure » on this phone (MAG-651): the switch, off until the owner turns it on, and
 * the two things a receiver that may die between two events has to remember — when the owner left
 * a vehicle, and the car's place whose send failed.
 */
class LocationPreferences(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.departureDataStore)

    private val enabledKey = booleanPreferencesKey("enabled")
    private val vehicleExitKey = longPreferencesKey("vehicle_exit_ms")
    private val carLatitudeKey = doublePreferencesKey("car_latitude")
    private val carLongitudeKey = doublePreferencesKey("car_longitude")
    private val carParkedAtKey = longPreferencesKey("car_parked_at_ms")

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
}
