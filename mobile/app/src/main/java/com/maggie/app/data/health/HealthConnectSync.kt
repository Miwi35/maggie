package com.maggie.app.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.api.HealthSyncRequest
import com.maggie.app.data.api.SyncedDay
import com.maggie.app.data.api.SyncedMeasurement
import com.maggie.app.data.api.SyncedWorkout
import com.maggie.app.data.model.Workout
import com.maggie.app.data.repository.HealthSyncRepository
import com.maggie.app.data.repository.WorkoutRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** What became of one pass. */
enum class SyncOutcome {
    /** The API took the batch (and Maggie's workouts were written to Health Connect when allowed). */
    SENT,

    /** Health Connect is not on the phone, or too old: nothing read, nothing sent. */
    UNAVAILABLE,

    /** A read permission is missing (or the background read is refused): nothing sent. */
    NOT_PERMITTED,

    /** The API refused the batch (4xx): sending it again would change nothing. */
    REFUSED,

    /** A network or server failure: worth another try. */
    RETRY,
}

/**
 * The Health Connect pass (MAG-708): the séances, pesées, pas and dépense active since the last batch the API took
 * (30 days on the first run) leave in one `POST /api/health/sync`; then Maggie's own workouts (manual, activity,
 * estimated) not yet in Health Connect are written there as `ExerciseSessionRecord`s with their id as client id, and
 * the record's id goes back to the API as their `externalId`. A session carrying a Workout id as client id is Maggie's
 * own, read back: it is never imported. The mark only moves after a batch the API took.
 */
class HealthConnectSync(
    private val gateway: HealthConnectGateway,
    private val syncRepository: HealthSyncRepository,
    private val workoutRepository: WorkoutRepository,
    private val preferences: HealthSyncPreferences,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    // The hourly pass and the one at opening may meet: one at a time.
    private val running = Mutex()

    suspend fun run(): SyncOutcome = running.withLock { runOnce() }

    private suspend fun runOnce(): SyncOutcome {
        if (gateway.availability() != HealthConnectAvailability.AVAILABLE) return SyncOutcome.UNAVAILABLE
        val granted = healthConnect { gateway.grantedPermissions() } ?: return SyncOutcome.UNAVAILABLE
        if (!granted.containsAll(HealthConnectPermissions.READ)) return SyncOutcome.NOT_PERMITTED

        val now = clock.instant()
        val zone = clock.zone
        val since = readWindowStart(preferences.lastSync(), now)
        val request = try {
            read(since, now, zone)
        } catch (e: SecurityException) {
            return SyncOutcome.NOT_PERMITTED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SyncOutcome.RETRY
        }

        val sent = syncRepository.sync(request).fold(onSuccess = { SyncOutcome.SENT }, onFailure = ::outcomeOf)
        if (sent != SyncOutcome.SENT) return sent
        preferences.advanceTo(now)

        if (HealthConnectPermissions.WRITE_EXERCISE !in granted) return SyncOutcome.SENT
        return writeBack(now)
    }

    private suspend fun read(since: Instant, now: Instant, zone: ZoneId): HealthSyncRequest {
        val workouts = gateway.readExerciseSessions(since, now).mapNotNull { toSyncedWorkout(it, zone) }
        val weights = gateway.readWeights(since, now)
            // Checked as sent, rounded: one value the API refuses would refuse the whole batch.
            .filter { round2(it.kilograms) > 0 && round2(it.kilograms) <= MAX_WEIGHT_KG }
            .map { SyncedMeasurement(it.id, isoAt(it.time, it.offset, zone), weightKg = round2(it.kilograms), source = MEASUREMENT_SOURCE) }
        val bodyFat = gateway.readBodyFat(since, now)
            // The API keeps one decimal: 99.96 % would be stored as 100.
            .filter { Math.round(it.percent * 10) / 10.0 in 0.1..<MAX_BODY_FAT_PERCENT.toDouble() }
            .map { SyncedMeasurement(it.id, isoAt(it.time, it.offset, zone), bodyFatPercent = round2(it.percent), source = MEASUREMENT_SOURCE) }
        val days = gateway.aggregateDays(LocalDate.ofInstant(since, zone), LocalDate.ofInstant(now, zone), zone).map {
            SyncedDay(
                date = it.date.toString(),
                steps = it.steps.coerceIn(0, MAX_STEPS.toLong()).toInt(),
                activeKcal = it.activeKcal.roundToInt().coerceIn(0, MAX_ACTIVE_KCAL),
            )
        }
        return HealthSyncRequest(workouts = workouts, measurements = weights + bodyFat, days = days)
    }

    private fun toSyncedWorkout(session: HcExerciseSession, zone: ZoneId): SyncedWorkout? {
        // Maggie's own workout, written by the write-back and read again.
        if (session.clientRecordId?.let(::isUlid) == true) return null
        val minutes = (Duration.between(session.start, session.end).seconds / 60.0).roundToLong().coerceAtLeast(1)
        if (session.end.isBefore(session.start) || minutes > MAX_DURATION_MINUTES) return null
        return SyncedWorkout(
            externalId = session.id,
            type = workoutTypeOf(session.exerciseType),
            title = session.title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE),
            startedAt = isoAt(session.start, session.startOffset, zone),
            durationMinutes = minutes.toInt(),
            source = WORKOUT_SOURCE,
        )
    }

    /** Maggie's workouts of the last 30 days that Health Connect does not have yet. */
    private suspend fun writeBack(now: Instant): SyncOutcome {
        val workouts = workoutRepository.getWorkouts().getOrElse { return outcomeOf(it) }
        val oldest = now.minus(FIRST_SYNC_WINDOW)
        workouts.filter { it.externalId == null && it.source in WRITTEN_BACK_SOURCES }.forEach { workout ->
            val session = toSessionToWrite(workout) ?: return@forEach
            if (session.start.isBefore(oldest) || session.start.isAfter(now)) return@forEach
            val recordId = healthConnect { gateway.insertSession(session) } ?: return SyncOutcome.SENT
            workoutRepository.setExternalId(workout.id, recordId).onFailure { return outcomeOf(it) }
        }
        return SyncOutcome.SENT
    }

    /** A Health Connect call that may be refused (permission revoked meanwhile, service gone): null then. */
    private suspend fun <T> healthConnect(call: suspend () -> T): T? = try {
        call()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The first run reads this far back — also all Health Connect gives without the history permission. */
        val FIRST_SYNC_WINDOW: Duration = Duration.ofDays(30)

        /** Read again before the mark: the watch's app may hand Health Connect a day late. The batch is idempotent. */
        val OVERLAP: Duration = Duration.ofDays(1)

        const val WORKOUT_SOURCE = "watch"
        const val MEASUREMENT_SOURCE = "health_connect"
        val WRITTEN_BACK_SOURCES = setOf("manual", "activity", "estimated")

        // The API's own bounds: one value past them refuses the whole batch.
        const val MAX_DURATION_MINUTES = 1440
        const val MAX_TITLE = 255
        const val MAX_WEIGHT_KG = 500.0
        const val MAX_BODY_FAT_PERCENT = 100.0
        const val MAX_STEPS = 200_000
        const val MAX_ACTIVE_KCAL = 20_000

        /** Seconds and a `+02:00` offset always (never `Z`, never a time without seconds). */
        private val ISO_WITH_OFFSET: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx")

        fun readWindowStart(lastSync: Instant?, now: Instant): Instant {
            val oldest = now.minus(FIRST_SYNC_WINDOW)
            val fromMark = lastSync?.minus(OVERLAP) ?: return oldest
            return if (fromMark.isBefore(oldest)) oldest else fromMark
        }

        internal fun isoAt(time: Instant, offset: ZoneOffset?, zone: ZoneId): String =
            OffsetDateTime.ofInstant(time, offset ?: zone.rules.getOffset(time)).format(ISO_WITH_OFFSET)

        private val ULID = Regex("^[0-7][0-9A-HJKMNP-TV-Z]{25}$", RegexOption.IGNORE_CASE)

        internal fun isUlid(value: String): Boolean = ULID.matches(value)

        private fun round2(value: Double): Double = Math.round(value * 100) / 100.0

        internal fun toSessionToWrite(workout: Workout): HcSessionToWrite? {
            val started = runCatching { OffsetDateTime.parse(workout.startedAt) }.getOrNull() ?: return null
            val start = started.toInstant()
            val end = start.plus(Duration.ofMinutes(workout.durationMinutes.coerceAtLeast(1).toLong()))
            return HcSessionToWrite(
                clientRecordId = workout.id,
                exerciseType = exerciseTypeOf(workout.type),
                title = workout.title,
                start = start,
                startOffset = started.offset,
                end = end,
                endOffset = started.offset,
            )
        }

        /** Network, timeout or server error: another try; the API refusing the request: no. */
        internal fun outcomeOf(error: Throwable): SyncOutcome = when {
            error is CancellationException -> throw error
            error is ApiRefusalException && error.status < 500 -> SyncOutcome.REFUSED
            else -> SyncOutcome.RETRY
        }
    }
}

/** Health Connect's exercise type → the API's WorkoutType. */
fun workoutTypeOf(exerciseType: Int): String = when (exerciseType) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "bike"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
    ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> "climbing"
    else -> "other"
}

/** The API's WorkoutType → Health Connect's exercise type, for the write-back. */
fun exerciseTypeOf(workoutType: String): Int = when (workoutType) {
    "run" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
    "walk" -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
    "bike" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    "swim" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
    "strength" -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
    "climbing" -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
    else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}
