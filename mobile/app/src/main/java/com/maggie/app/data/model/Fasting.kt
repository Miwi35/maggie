package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The intermittent fast of a day, `GET /api/health/fasting?date=` (MAG-770). [protocol] is `14:10`, `16:8`, `18:6` or
 * `20:4`, null when the owner does not fast — then [state] is null too. [windowStart] and [windowEnd] are `HH:MM`, the end
 * possibly past midnight (`06:00`). [openedAt] is the day's first intake, [lastIntakeAt] the latest one up to now, both
 * ISO-8601 with their offset or null. [state] is the API's: [STATE_FASTING] (nothing eaten yet today), [STATE_OPEN] (the
 * first intake is done, before the window's end) or [STATE_CLOSED] (after it). [nightFastMinutes] is the fast that ended
 * that day. A day to come carries only the protocol and its window.
 */
@Serializable
data class FastingStatus(
    val date: String,
    val protocol: String? = null,
    val windowStart: String? = null,
    val windowEnd: String? = null,
    val openedAt: String? = null,
    val lastIntakeAt: String? = null,
    val state: String? = null,
    val nightFastMinutes: Int? = null,
) {
    companion object {
        const val STATE_FASTING = "fasting"
        const val STATE_OPEN = "open"
        const val STATE_CLOSED = "closed"
    }
}

/** The four protocols the profile takes, fast hours then window hours, in the sheet's order. */
enum class FastingProtocol(val key: String, val fastHours: Int, val windowHours: Int) {
    P14_10("14:10", 14, 10),
    P16_8("16:8", 16, 8),
    P18_6("18:6", 18, 6),
    P20_4("20:4", 20, 4),
    ;

    /** « 8 h de fenêtre ». */
    val windowLabel: String get() = "$windowHours h de fenêtre"

    companion object {
        /** The profile's default (`16:8`), and the window's (`12:00`). */
        val DEFAULT = P16_8
        const val DEFAULT_WINDOW_START = "12:00"

        fun byKey(key: String?): FastingProtocol? = entries.firstOrNull { it.key == key }
    }
}
