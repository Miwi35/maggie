package com.maggie.app.data.location

import kotlinx.coroutines.flow.Flow

/** The « Partir à l'heure » switch of this phone and what it starts or stops (MAG-651). */
class DepartureTracking(
    private val preferences: LocationPreferences,
    private val scheduler: LocationScheduler,
    private val permissions: LocationPermissions,
) {
    val enabled: Flow<Boolean> = preferences.enabled

    suspend fun enable() {
        preferences.setEnabled(true)
        scheduler.startTracking()
    }

    suspend fun disable() {
        preferences.setEnabled(false)
        preferences.setPendingCar(null)
        preferences.setVehicleExit(null)
        scheduler.stopTracking()
    }

    /**
     * At every start of the app: the transitions do not survive a reboot, so they are registered again.
     * A permission taken back in Android's settings turns the switch off, rather than leave it on and mute.
     */
    suspend fun resume() {
        if (!preferences.isEnabled()) return
        if (permissions.canTrack()) scheduler.startTracking() else disable()
    }
}
