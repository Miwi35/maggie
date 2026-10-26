package com.maggie.app.data.location

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CancellationException
import java.time.Instant

/** What became of one report. */
enum class ReportOutcome {
    /** The API has it. */
    SENT,

    /** Switch off, permission missing, or nothing to send: not an error, nothing was asked of the API. */
    SKIPPED,

    /** The API refused this very report: sending it again would change nothing. */
    DROPPED,

    /** A network or server failure, or no fix in time: worth another try later. */
    RETRY,
}

/**
 * Sends the owner's position and the car's place (MAG-651). Nothing leaves the phone while the
 * switch is off or a permission is missing, and nothing is kept: the API holds the last value only.
 */
class LocationReporter(
    private val api: MaggieApiService,
    private val preferences: LocationPreferences,
    private val permissions: LocationPermissions,
    private val source: PositionSource,
) {

    /** A fresh fix, sent as the owner's position. */
    suspend fun reportPosition(): ReportOutcome {
        if (!preferences.isEnabled() || !permissions.canReadPosition()) return ReportOutcome.SKIPPED
        val position = source.current() ?: return ReportOutcome.RETRY
        return send { api.putMyLocation(position.latitude, position.longitude, position.accuracy, position.capturedAt.toString()) }
    }

    /**
     * The owner just stepped out of the car, [parkedAt]: the fix of now is where it stands. It is
     * kept until the API has it, so a retry sends this place and not the one the owner has walked to.
     */
    suspend fun reportCar(parkedAt: Instant): ReportOutcome {
        if (!preferences.isEnabled() || !permissions.canReadPosition()) return ReportOutcome.SKIPPED
        // No fix: the owner is already elsewhere, a later one would put the car in the wrong place.
        val position = source.current() ?: return ReportOutcome.DROPPED
        preferences.setPendingCar(PendingCar(position.latitude, position.longitude, parkedAt))
        return sendPendingCar()
    }

    /** The car's place kept by a failed send, sent again. */
    suspend fun sendPendingCar(): ReportOutcome {
        val car = preferences.pendingCar() ?: return ReportOutcome.SKIPPED
        if (!preferences.isEnabled()) {
            preferences.setPendingCar(null)
            return ReportOutcome.SKIPPED
        }
        val outcome = send { api.putMyCar(car.latitude, car.longitude, car.parkedAt.toString()) }
        if (outcome != ReportOutcome.RETRY) preferences.setPendingCar(null)
        return outcome
    }

    private suspend fun send(call: suspend () -> Unit): ReportOutcome = try {
        call()
        ReportOutcome.SENT
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiRefusalException) {
        ReportOutcome.DROPPED
    } catch (e: Exception) {
        ReportOutcome.RETRY
    }
}
