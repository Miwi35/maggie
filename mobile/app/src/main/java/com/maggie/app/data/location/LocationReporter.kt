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
 * switch is off or a permission is missing. Only GPS points are ever sent (MAG-666): a network position
 * can be kilometres off while claiming a few hundred metres. The API holds the last value only; the phone
 * keeps a few minutes of GPS-grade points — to place the car where the trip ended, and to send the last
 * one again while the phone has not moved, whatever its age.
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
     * The owner's position. While the phone has not moved, the last GPS point stays the position whatever its
     * age and no sensor is woken. A [precise] one (the API is waiting for it before a departure) and a phone
     * that has moved take a GPS fix, 30 s at most; with none, the last GPS point is sent, and nothing at all
     * when there is none — never a network position, and no retry that would drain the battery on a ceiling.
     */
    suspend fun reportPosition(precise: Boolean = false): ReportOutcome {
        if (!canReport()) return ReportOutcome.SKIPPED
        val still = preferences.keptGpsPoint()
        if (still != null && !precise) return deliver(still.copy(capturedAt = clock.instant()), record = false)
        val fresh = gpsFix()
        if (fresh != null) return deliver(fresh)
        val last = still?.copy(capturedAt = clock.instant()) ?: preferences.gpsLog().lastOrNull() ?: return ReportOutcome.SKIPPED
        return deliver(last, record = false)
    }

    private suspend fun gpsFix(): Position? = source.current()?.takeIf { it.isGpsGrade }

    /** A fix of the trip service (GPS while the owner moves or drives): sent as the position, and kept. A network one is refused. */
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
            .filter { it.source == FixSource.GPS && it.accuracy > 0.0 }
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
        val logged = preferences.gpsLog().lastOrNull { !it.capturedAt.isAfter(parkedAt.plus(PARKING_GRACE)) }
            ?.takeIf { !it.capturedAt.isBefore(parkedAt.minus(MAX_POINT_AGE)) }
        // No point: the fix of now is the car's place only if the owner has not had time to walk away.
        val recent = Duration.between(parkedAt, clock.instant()) <= MAX_FIX_DELAY
        val place = logged ?: (if (recent) gpsFix() else null) ?: return ReportOutcome.DROPPED
        preferences.setPendingCar(PendingCar(place.latitude, place.longitude, parkedAt))
        preferences.setLastCarParkedAt(parkedAt)
        return sendPendingCar()
    }

    /** The moment of now, on the clock the points are compared with. */
    fun now(): Instant = clock.instant()

    /**
     * The Android Auto connection that began at [connectedSince] ended: the owner is at the car now — unless
     * stepping out of it was already reported during this connection, which saw the car's place first.
     */
    suspend fun reportCarDisconnected(connectedSince: Instant): ReportOutcome {
        val reported = preferences.lastCarParkedAt()
        if (reported != null && !reported.isBefore(connectedSince)) return ReportOutcome.SKIPPED
        return reportCar(clock.instant())
    }

    private suspend fun canReport() = preferences.isEnabled() && permissions.canReadPosition()

    private suspend fun deliver(position: Position, record: Boolean = true): ReportOutcome {
        if (position.source != FixSource.GPS) return ReportOutcome.SKIPPED
        if (record && position.isGpsGrade) preferences.addGpsPoint(position)
        val outcome = send { api.putMyLocation(position.latitude, position.longitude, position.accuracy, position.capturedAt.toString(), position.source.wire) }
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

        /** Past this, a fix taken for a car left that long ago says where the owner went (~400 m on foot), not where the car is. */
        val MAX_FIX_DELAY: Duration = Duration.ofMinutes(5)
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
