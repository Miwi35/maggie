package com.maggie.app.data.location

import androidx.work.Data
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The movements the app listens for; any other is ignored. [STILL] comes last: the ordinal travels in WorkManager data. */
enum class Movement {
    IN_VEHICLE,
    ON_FOOT,
    OTHER,
    STILL,
    ;

    companion object {
        /** `ON_FOOT`, `WALKING`, `RUNNING` and `ON_BICYCLE` all mean the owner moves by their own means. */
        fun of(detectedActivity: Int): Movement = when (detectedActivity) {
            DetectedActivity.IN_VEHICLE -> IN_VEHICLE
            DetectedActivity.ON_FOOT, DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_BICYCLE -> ON_FOOT
            DetectedActivity.STILL -> STILL
            else -> OTHER
        }
    }
}

/** One transition of the Activity Recognition API: [movement] was entered, or left when [entering] is false. */
data class TransitionEvent(val movement: Movement, val entering: Boolean, val at: Instant) {
    companion object {
        /**
         * The events of a delivery, in the order they happened. The API stamps them with the time since boot:
         * [elapsedNowNanos] is that clock now, [now] the wall clock at the same moment.
         */
        fun from(result: ActivityTransitionResult, elapsedNowNanos: Long, now: Instant): List<TransitionEvent> =
            result.transitionEvents
                .sortedBy { it.elapsedRealTimeNanos }
                .map {
                    TransitionEvent(
                        movement = Movement.of(it.activityType),
                        entering = it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER,
                        at = now.minusNanos(elapsedNowNanos - it.elapsedRealTimeNanos).truncatedTo(ChronoUnit.MILLIS),
                    )
                }

        private const val MOVEMENTS = "movements"
        private const val ENTERING = "entering"
        private const val AT = "at"

        /** WorkManager input: three parallel arrays, the instants in epoch milliseconds (what the API gets). */
        fun toData(events: List<TransitionEvent>): Data = Data.Builder()
            .putIntArray(MOVEMENTS, events.map { it.movement.ordinal }.toIntArray())
            .putBooleanArray(ENTERING, events.map { it.entering }.toBooleanArray())
            .putLongArray(AT, events.map { it.at.toEpochMilli() }.toLongArray())
            .build()

        fun fromData(data: Data): List<TransitionEvent> {
            val movements = data.getIntArray(MOVEMENTS) ?: return emptyList()
            val entering = data.getBooleanArray(ENTERING) ?: return emptyList()
            val at = data.getLongArray(AT) ?: return emptyList()
            return movements.indices.map {
                TransitionEvent(Movement.entries[movements[it]], entering[it], Instant.ofEpochMilli(at[it]))
            }
        }
    }
}

/**
 * « En voiture → à pied »: the owner leaves a vehicle, then stands on their feet. The exit is kept on
 * the phone because the next event may reach a freshly started process. Anything else — a walk with no
 * car before it, a car entered again, a pause too long to still be the same stop — parks nothing.
 */
class ParkingDetector(private val preferences: LocationPreferences) {

    private val lock = Mutex()

    /** When the car was left, if [events] complete a parking; null otherwise. */
    suspend fun onTransitions(events: List<TransitionEvent>): Instant? = lock.withLock {
        var left = preferences.vehicleExit()
        var parkedAt: Instant? = null
        // Play services may stamp the exit and the walk with the same instant, in either order: the exit comes first.
        for (event in events.sortedWith(compareBy({ it.at }, { it.entering }))) {
            when {
                event.movement == Movement.IN_VEHICLE && !event.entering -> left = event.at
                event.movement == Movement.IN_VEHICLE -> left = null
                event.movement == Movement.ON_FOOT && event.entering -> {
                    val exit = left
                    if (exit != null && Duration.between(exit, event.at) <= MAX_GAP) parkedAt = exit
                    left = null
                }
            }
        }
        preferences.setVehicleExit(left)
        parkedAt
    }

    companion object {
        /** Standing up and walking off follow within seconds; later, the owner stopped somewhere else first. */
        val MAX_GAP: Duration = Duration.ofMinutes(10)
    }
}

/**
 * What a delivery of transitions leads to. The receiver, in the instant the system grants it, remembers what
 * the phone now does and starts the trip service ([onDelivery]): Android 12+ lets a foreground service start
 * from there and not from a worker that runs later. WorkManager then places the car when a parking is
 * complete, sent now or retried later ([handle]).
 */
class ActivityTransitionHandler(
    private val detector: ParkingDetector,
    private val reporter: LocationReporter,
    private val scheduler: LocationScheduler,
    private val preferences: LocationPreferences,
    private val launcher: TripLauncher,
) {
    private val deliveries = Mutex()

    suspend fun onDelivery(events: List<TransitionEvent>) {
        deliveries.withLock { remember(events) }
        launcher.startIfNeeded()
    }

    suspend fun handle(events: List<TransitionEvent>) {
        val parkedAt = detector.onTransitions(events)
        if (parkedAt != null && reporter.reportCar(parkedAt) == ReportOutcome.RETRY) scheduler.retryCarLater()
    }

    private suspend fun remember(events: List<TransitionEvent>) {
        var motion = preferences.motion()
        // Same order as the detector: at one instant, what is left comes before what is entered.
        for (event in events.sortedWith(compareBy({ it.at }, { it.entering }))) {
            val entered = when (event.movement) {
                Movement.IN_VEHICLE -> Motion.IN_VEHICLE
                Movement.ON_FOOT -> Motion.MOVING
                Movement.STILL -> Motion.STILL
                Movement.OTHER -> continue
            }
            if (event.entering) motion = entered else if (motion == entered) motion = Motion.UNKNOWN
        }
        val moved = events.any { it.entering && (it.movement == Movement.IN_VEHICLE || it.movement == Movement.ON_FOOT) }
        preferences.recordMotion(motion, moved)
    }
}
