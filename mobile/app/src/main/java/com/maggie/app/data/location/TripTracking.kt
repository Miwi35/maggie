package com.maggie.app.data.location

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/** What the phone is doing, as Activity Recognition last said. */
enum class Motion { UNKNOWN, STILL, MOVING, IN_VEHICLE }

/** How the position is followed (MAG-666): nothing, on foot, at the wheel, at the wheel with a low battery. */
enum class TrackingMode { IDLE, MOVING, DRIVING, DRIVING_SAVER }

/** GPS fixes asked every [intervalMs]; one is sent to the API at most every [sendEveryMs]. */
data class UpdatePlan(val intervalMs: Long, val sendEveryMs: Long)

/** Google's way: sensors before the GPS, the GPS only when the owner moves, continuously only at the wheel. */
object TrackingPolicy {
    const val LOW_BATTERY_PERCENT = 15

    fun modeFor(motion: Motion, carConnected: Boolean, batteryPercent: Int): TrackingMode = when {
        carConnected || motion == Motion.IN_VEHICLE ->
            if (batteryPercent > LOW_BATTERY_PERCENT) TrackingMode.DRIVING else TrackingMode.DRIVING_SAVER
        motion == Motion.MOVING -> TrackingMode.MOVING
        else -> TrackingMode.IDLE
    }

    fun planFor(mode: TrackingMode): UpdatePlan? = when (mode) {
        TrackingMode.IDLE -> null
        TrackingMode.MOVING -> UpdatePlan(Duration.ofMinutes(2).toMillis(), Duration.ofMinutes(2).toMillis())
        TrackingMode.DRIVING -> UpdatePlan(Duration.ofSeconds(10).toMillis(), Duration.ofMinutes(1).toMillis())
        TrackingMode.DRIVING_SAVER -> UpdatePlan(Duration.ofMinutes(5).toMillis(), Duration.ofMinutes(5).toMillis())
    }
}

/** GPS fixes (`PRIORITY_HIGH_ACCURACY`) every [intervalMs] for as long as the flow is collected. */
interface LocationUpdates {
    fun gps(intervalMs: Long): Flow<Position>
}

/** Whether the phone is connected to the car through Android Auto; emits at once. */
interface CarConnectionSource {
    val connected: Flow<Boolean>
}

/** The battery level in percent; emits at once. */
interface BatteryLevel {
    val percent: Flow<Int>
}

/** Starts the foreground service that follows the trip, when there is a trip to follow. */
interface TripLauncher {
    suspend fun startIfNeeded()
}

/**
 * The position while the owner moves or drives (MAG-666), run by a foreground service for the length of the
 * trip: GPS fixes at the pace of the [TrackingMode], sent no more often than its plan says, every one kept
 * for the car's place. When the car's connection ends, the last point is the car's place.
 */
class TripTracker(
    private val preferences: LocationPreferences,
    private val reporter: LocationReporter,
    private val updates: LocationUpdates,
    private val car: CarConnectionSource,
    private val battery: BatteryLevel,
) {

    private data class State(val mode: TrackingMode, val carConnected: Boolean)

    private fun states(): Flow<State> =
        combine(preferences.enabled, preferences.motion, car.connected, battery.percent) { enabled, motion, connected, percent ->
            State(if (enabled) TrackingPolicy.modeFor(motion, connected, percent) else TrackingMode.IDLE, connected)
        }.distinctUntilChanged()

    suspend fun currentMode(): TrackingMode = states().first().mode

    /** Follows the trip until there is nothing left to follow. */
    suspend fun run() = coroutineScope {
        var job: Job? = null
        var mode: TrackingMode? = null
        var wasConnected = false
        states().firstOrNull { state ->
            if (wasConnected && !state.carConnected) reporter.reportCarNow()
            wasConnected = state.carConnected
            if (state.mode != mode) {
                mode = state.mode
                job?.cancel()
                val plan = TrackingPolicy.planFor(state.mode)
                job = plan?.let { launch { follow(it) } }
            }
            state.mode == TrackingMode.IDLE
        }
        job?.cancel()
    }

    private suspend fun follow(plan: UpdatePlan) {
        var sentAt: Instant? = preferences.lastSent()?.capturedAt
        updates.gps(plan.intervalMs).collect { fix ->
            val due = sentAt == null || Duration.between(sentAt, fix.capturedAt).toMillis() >= plan.sendEveryMs - SEND_TOLERANCE_MS
            if (!due) {
                reporter.keepFix(fix)
            } else if (reporter.reportFix(fix) == ReportOutcome.SENT) {
                sentAt = fix.capturedAt
            }
        }
    }

    private companion object {
        /** Fixes arrive a little before or after their time; a minute of them should not skip one. */
        const val SEND_TOLERANCE_MS = 5_000L
    }
}

/** At every start of the app, and while its process lives, a car that connects: the trip starts at once. */
class TripWatcher(private val car: CarConnectionSource, private val launcher: TripLauncher) {
    suspend fun watch() {
        launcher.startIfNeeded()
        car.connected.filter { it }.collect { launcher.startIfNeeded() }
    }
}
