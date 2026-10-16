package com.maggie.app.ui.screens.tasks

/**
 * « Juste 10 minutes » (MAG-499): a countdown that holds timestamps, never a count of ticks, so a
 * rotation, a screen off or the app in the background cannot make it drift. [clock] is read in
 * milliseconds from a monotonic source that keeps running while the phone sleeps.
 */
data class FocusTimer(
    val totalMs: Long = DEFAULT_MS,
    /** Time already spent in the runs before the current one. */
    val spentMs: Long = 0,
    /** When the current run began; null while idle or paused. */
    val startedAt: Long? = null,
) {
    val isRunning: Boolean get() = startedAt != null

    fun remainingMs(now: Long): Long {
        val running = startedAt?.let { now - it } ?: 0L
        return (totalMs - spentMs - running).coerceIn(0, totalMs)
    }

    fun phase(now: Long): FocusTimerPhase = when {
        remainingMs(now) == 0L -> FocusTimerPhase.FINISHED
        isRunning -> FocusTimerPhase.RUNNING
        spentMs > 0 -> FocusTimerPhase.PAUSED
        else -> FocusTimerPhase.IDLE
    }

    /** Starts, or resumes where it stopped. */
    fun started(now: Long): FocusTimer = if (isRunning) this else copy(startedAt = now)

    fun paused(now: Long): FocusTimer =
        startedAt?.let { copy(spentMs = (spentMs + (now - it)).coerceAtMost(totalMs), startedAt = null) } ?: this

    companion object {
        const val DEFAULT_MS = 10 * 60 * 1000L
    }
}

enum class FocusTimerPhase { IDLE, RUNNING, PAUSED, FINISHED }

/** `10:00`, rounded up: the last second reads `00:01`, never a `00:00` that is not over. */
internal fun timerLabelOf(remainingMs: Long): String {
    val seconds = (remainingMs + 999) / 1000
    return "%02d:%02d".format(seconds / 60, seconds % 60)
}
