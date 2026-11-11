package com.maggie.app.data.model

import com.maggie.app.ui.screens.health.sport.isLaunchable
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.max

/** The planned session of the day, [time] being its local start (`18:30`). */
data class HealthHomeWorkout(
    val id: String,
    val title: String,
    val time: String,
    val durationMinutes: Int,
    /** A strength session with exercises: the card offers « ▶ Lancer » (MAG-779). */
    val launchable: Boolean = false,
)

data class HealthHomeSteps(val count: Int, val goal: Int)

/** What was eaten today, or what is planned for tomorrow, against the need. */
data class HealthHomeBalance(val kcal: Int, val needKcal: Int?)

/**
 * The eating window; [minutesToEdge] is the time to its next edge and [progress] the share of the current phase (window
 * open, or the fast before it) already gone, today only.
 */
data class HealthHomeFast(val state: String?, val windowStart: String, val windowEnd: String, val minutesToEdge: Int?, val progress: Float? = null)

/** What the reads give [HealthHomeState.from]; a part whose module answered 404 is null. */
data class HealthHomeReads(
    val workouts: List<Workout> = emptyList(),
    val energy: DailyEnergy? = null,
    val balance: DailyBalance? = null,
    val profile: HealthProfile? = null,
    val fasting: FastingStatus? = null,
)

/**
 * The Santé card of the Accueil (MAG-566), composed on the client from the module's resources. Today: the session, the
 * steps (with a source and a measure), what was eaten against the need, the window. Tomorrow: the session, the planned
 * window, the projected calories — no steps. No protocol, no window line.
 */
data class HealthHomeState(
    val today: Boolean = true,
    val workout: HealthHomeWorkout? = null,
    val steps: HealthHomeSteps? = null,
    val balance: HealthHomeBalance? = null,
    val fast: HealthHomeFast? = null,
) {
    /** Nothing to show for the day: the card is not drawn (MAG-594). */
    val isEmpty: Boolean get() = workout == null && steps == null && balance == null && fast == null

    companion object {
        fun from(today: Boolean, reads: HealthHomeReads, now: LocalTime, zone: ZoneId): HealthHomeState {
            val planned = reads.workouts
                .filter { it.isPlanned }
                .minByOrNull { OffsetDateTime.parse(it.startedAt).toInstant() }
            val profile = reads.profile
            val steps = reads.energy?.steps
            val kcal = reads.balance?.let { if (today) it.eatenKcal else it.plannedKcal } ?: 0
            val fasting = reads.fasting
            val windowStart = fasting?.windowStart
            val windowEnd = fasting?.windowEnd
            val state = if (fasting?.state != null && windowStart != null && windowEnd != null) effectiveState(fasting.state, windowStart, windowEnd, now) else fasting?.state
            return HealthHomeState(
                today = today,
                workout = planned?.let {
                    val start = OffsetDateTime.parse(it.startedAt).atZoneSameInstant(zone).toLocalTime()
                    HealthHomeWorkout(it.id, it.title, "%02d:%02d".format(start.hour, start.minute), it.durationMinutes, it.isLaunchable)
                },
                steps = if (today && profile != null && profile.stepSource != HealthProfile.STEP_SOURCE_NONE && steps != null && steps > 0) {
                    HealthHomeSteps(steps, profile.stepGoal)
                } else {
                    null
                },
                balance = if (kcal > 0) HealthHomeBalance(kcal, profile?.needKcal) else null,
                fast = if (profile?.fastingProtocol != null && fasting != null && (!today || state != null) && windowStart != null && windowEnd != null) {
                    fastOf(today, state, windowStart, windowEnd, now)
                } else {
                    null
                },
            )
        }

        private fun fastOf(today: Boolean, state: String?, windowStart: String, windowEnd: String, now: LocalTime): HealthHomeFast {
            if (!today || state == null) return HealthHomeFast(null, windowStart, windowEnd, null)
            val left = minutesToEdge(state, windowStart, windowEnd, now)
            val window = windowLength(windowStart, windowEnd)
            val phase = if (state == FastingStatus.STATE_OPEN) window else DAY - window
            val progress = left?.let { (1f - it.toFloat() / phase).coerceIn(0f, 1f) }
            return HealthHomeFast(state, windowStart, windowEnd, left, progress)
        }

        private fun windowLength(windowStart: String, windowEnd: String): Int {
            val length = minutes(windowEnd) - minutes(windowStart)
            return if (length <= 0) length + DAY else length
        }

        /** Without a meal the API says fasting, but inside the window the window is open: it closes at its end. */
        private fun effectiveState(state: String, windowStart: String, windowEnd: String, now: LocalTime): String {
            if (state != FastingStatus.STATE_FASTING) return state
            val at = now.hour * 60 + now.minute
            val sinceStart = (at - minutes(windowStart) + DAY) % DAY
            return if (sinceStart < windowLength(windowStart, windowEnd)) FastingStatus.STATE_OPEN else state
        }

        private fun minutes(time: String): Int = time.split(":").let { it[0].toInt() * 60 + it[1].toInt() }

        /** Minutes to the edge the window faces now (its end when open, its start when fasting); null when closed. */
        internal fun minutesToEdge(state: String, windowStart: String, windowEnd: String, now: LocalTime): Int? {
            if (state == FastingStatus.STATE_CLOSED) return null
            val start = minutes(windowStart)
            var end = minutes(windowEnd)
            if (end <= start) end += DAY
            var at = now.hour * 60 + now.minute
            if (at < start && at + DAY < end) at += DAY
            val edge = if (state == FastingStatus.STATE_OPEN) end else start
            return max(0, (if (edge < at) edge + DAY else edge) - at)
        }

        private const val DAY = 24 * 60
    }
}
