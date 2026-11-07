package com.maggie.app.data.health

import androidx.activity.result.contract.ActivityResultContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Whether Health Connect can be used on this phone (MAG-708). */
enum class HealthConnectAvailability {
    AVAILABLE,

    /** Neither in the system (Android 14+) nor installed from the Play Store, or the phone cannot run it. */
    NOT_INSTALLED,

    /** The Play Store app is there but too old for the client. */
    NEEDS_UPDATE,
}

/** An `ExerciseSessionRecord`: [clientRecordId] is set when an app wrote it with its own id — Maggie writes her workouts' ids. */
data class HcExerciseSession(
    val id: String,
    val clientRecordId: String?,
    val exerciseType: Int,
    val title: String?,
    val start: Instant,
    val startOffset: ZoneOffset?,
    val end: Instant,
)

/** A `WeightRecord`. */
data class HcWeight(val id: String, val time: Instant, val offset: ZoneOffset?, val kilograms: Double)

/** A `BodyFatRecord`. */
data class HcBodyFat(val id: String, val time: Instant, val offset: ZoneOffset?, val percent: Double)

/** One local day of steps and active energy, as Health Connect's aggregation gives it (one source per data type, by priority). */
data class HcDayActivity(val date: LocalDate, val steps: Long, val activeKcal: Double)

/** A workout of Maggie's to write as an `ExerciseSessionRecord`; [clientRecordId] is the Workout's id. */
data class HcSessionToWrite(
    val clientRecordId: String,
    val exerciseType: Int,
    val title: String,
    val start: Instant,
    val startOffset: ZoneOffset,
    val end: Instant,
    val endOffset: ZoneOffset,
)

/** What the app asks of Health Connect (MAG-708): the same list as the manifest. */
object HealthConnectPermissions {
    const val READ_EXERCISE = "android.permission.health.READ_EXERCISE"
    const val READ_WEIGHT = "android.permission.health.READ_WEIGHT"
    const val READ_BODY_FAT = "android.permission.health.READ_BODY_FAT"
    const val READ_STEPS = "android.permission.health.READ_STEPS"
    const val READ_ACTIVE_CALORIES_BURNED = "android.permission.health.READ_ACTIVE_CALORIES_BURNED"
    const val READ_IN_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val WRITE_EXERCISE = "android.permission.health.WRITE_EXERCISE"

    /** Without every one of them the sync does not run: « Non connecté ». */
    val READ = setOf(READ_EXERCISE, READ_WEIGHT, READ_BODY_FAT, READ_STEPS, READ_ACTIVE_CALORIES_BURNED)

    /** What the permission screen offers; the background read and the write are optional. */
    val ALL = READ + READ_IN_BACKGROUND + WRITE_EXERCISE
}

/**
 * Health Connect behind plain Kotlin types, so the sync is tested without a device. The reads cover `[since, until)`;
 * a refused permission throws [SecurityException], as the client does.
 */
interface HealthConnectGateway {
    fun availability(): HealthConnectAvailability

    suspend fun grantedPermissions(): Set<String>

    /** `PermissionController.createRequestPermissionResultContract()`: in, the permissions asked; out, those granted. */
    fun requestPermissionContract(): ActivityResultContract<Set<String>, Set<String>>

    suspend fun readExerciseSessions(since: Instant, until: Instant): List<HcExerciseSession>

    suspend fun readWeights(since: Instant, until: Instant): List<HcWeight>

    suspend fun readBodyFat(since: Instant, until: Instant): List<HcBodyFat>

    /** Steps and active kcal for each local day of [from]..[to] (both included) in [zone]; a day without data may be absent. */
    suspend fun aggregateDays(from: LocalDate, to: LocalDate, zone: ZoneId): List<HcDayActivity>

    /** Inserts (or replaces, same client id) the session and returns its Health Connect id. */
    suspend fun insertSession(session: HcSessionToWrite): String
}
