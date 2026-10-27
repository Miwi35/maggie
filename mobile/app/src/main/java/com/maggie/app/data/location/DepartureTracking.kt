package com.maggie.app.data.location

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

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

    // NonCancellable: leaving the screen mid-call must not leave the flag off with the work still planned.
    suspend fun disable() = withContext(NonCancellable) {
        preferences.setEnabled(false)
        preferences.setPendingCar(null)
        preferences.setVehicleExit(null)
        preferences.forgetPositions()
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

    /**
     * Signed out, by the button or by a refused refresh token: the position is no one's any more, so the
     * switch goes off and the next account starts from off. Signed in: back to [resume].
     */
    suspend fun follow(signedIn: Flow<Boolean>) {
        signedIn.distinctUntilChanged().collect { if (it) resume() else disable() }
    }
}
