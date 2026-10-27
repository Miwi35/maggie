package com.maggie.app.data.location

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
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
 * switch is off or a permission is missing. The API holds the last value only; the phone keeps a few
 * minutes of GPS-grade points (MAG-666) — to place the car where the trip ended, and to send the same
 * precise point again while the phone has not moved rather than a vaguer network one.
 */
class LocationReporter(
    private val api: MaggieApiService,
    private val preferences: LocationPreferences,
    private val permissions: LocationPermissions,
    private val source: PositionSource,
    private val clock: Clock = Clock.systemUTC(),
) {

    /** The switch is on and every permission is there: the car may be listened for. */
    suspend fun mayListen(): Boolean = preferences.isEnabled() && permissions.canTrack()

    /**
     * A fresh fix, sent as the owner's position; the GPS when the API is waiting for it ([PositionAccuracy.HIGH]),
     * else the cheap sources. A GPS-grade point the phone has not moved from is kept when the fix is vaguer.
     */
    suspend fun reportPosition(accuracy: PositionAccuracy = PositionAccuracy.BALANCED): ReportOutcome {
        if (!canReport()) return ReportOutcome.SKIPPED
        val fresh = source.current(accuracy)
        val kept = preferences.keptGpsPoint()
        return when {
            fresh != null && (kept == null || (fresh.accuracy > 0.0 && fresh.accuracy <= kept.accuracy)) -> deliver(fresh)
            kept != null -> deliver(kept.copy(capturedAt = clock.instant()), record = false)
            else -> ReportOutcome.RETRY
        }
    }

    /** A fix of the trip service (GPS while the owner moves or drives): sent as the position, and kept. */
    suspend fun reportFix(position: Position): ReportOutcome {
        if (!canReport()) return ReportOutcome.SKIPPED
        return deliver(position)
    }

    /** A fix of the trip service not worth a send yet: kept for the car's place only. */
    suspend fun keepFix(position: Position) {
        if (canReport() && position.isGpsGrade) preferences.addGpsPoint(position)
    }

    /**
     * Fixes other apps obtained (Maps, Waze) and the system handed over for free: the most precise one is
     * sent when it is newer and more precise than the last position sent, else nothing is.
     */
    suspend fun reportPassive(points: List<Position>): ReportOutcome {
        if (!canReport()) return ReportOutcome.SKIPPED
        val last = preferences.lastSent()
        val better = points
            .filter { it.accuracy > 0.0 }
            .filter { last == null || (it.capturedAt.isAfter(last.capturedAt) && it.accuracy < last.accuracy) }
            .minByOrNull { it.accuracy }
            ?: return ReportOutcome.SKIPPED
        return deliver(better)
    }

    /**
     * The owner just stepped out of the car, [parkedAt]. It stands where the trip's last GPS-grade point
     * before that was taken; with none, where the fix of now is. It is kept until the API has it, so a
     * retry sends this place and not the one the owner has walked to.
     */
    suspend fun reportCar(parkedAt: Instant): ReportOutcome {
        if (!canReport()) return ReportOutcome.SKIPPED
        val place = preferences.gpsLog().lastOrNull { !it.capturedAt.isAfter(parkedAt.plus(PARKING_GRACE)) }
            ?.takeIf { !it.capturedAt.isBefore(parkedAt.minus(MAX_POINT_AGE)) }
            // No point: the owner is already elsewhere, a later fix would put the car in the wrong place if it fails.
            ?: source.current(PositionAccuracy.HIGH)
            ?: return ReportOutcome.DROPPED
        preferences.setPendingCar(PendingCar(place.latitude, place.longitude, parkedAt))
        return sendPendingCar()
    }

    /** The AA connection ended: the owner is at the car now. */
    suspend fun reportCarNow(): ReportOutcome = reportCar(clock.instant())

    private suspend fun canReport() = preferences.isEnabled() && permissions.canReadPosition()

    private suspend fun deliver(position: Position, record: Boolean = true): ReportOutcome {
        if (record && position.isGpsGrade) preferences.addGpsPoint(position)
        val outcome = send { api.putMyLocation(position.latitude, position.longitude, position.accuracy, position.capturedAt.toString()) }
        if (outcome == ReportOutcome.SENT) preferences.setLastSent(position)
        return outcome
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

    private companion object {
        /** An exit is stamped a little before the points that follow it still stand at the car. */
        val PARKING_GRACE: Duration = Duration.ofSeconds(30)
        val MAX_POINT_AGE: Duration = Duration.ofMinutes(10)
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
