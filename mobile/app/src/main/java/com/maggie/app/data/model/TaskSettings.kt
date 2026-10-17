package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The user's task settings (MAG-407), `GET /api/task-settings/me`: the levels of the task
 * order (MAG-406) and the alert schedule per urgency, with the morning the alerts go out and
 * the evening the review opens after (MAG-500), then how a task done is rewarded (MAG-508):
 * the animation, the vibration and the sound, each on or off, and the [tone] of its message.
 *
 * - `orderLevels`: group (`timePressure`, `criticality`, `priority`) → level 0..4, what the
 *   sliders of « Ce qui passe en premier » set (the API turns them into weights);
 * - `alertSchedule`: urgency (`critical`, `high`, `normal` — `low` never alerts) → its rule.
 *
 * No defaults on purpose: the API always sends every key. The app writes with partial
 * `PUT` bodies built as JSON (see `MaggieApiService.updateTaskSettings`), so it can send
 * `overdueEveryDays: null` (« never ») explicitly — the API keeps a key it is not sent.
 */
@Serializable
data class TaskSettings(
    val id: String,
    val orderLevels: Map<String, Int>,
    val alertSchedule: Map<String, AlertRule>,
    val morningTime: String,
    val eveningTime: String,
    val rewardAnimation: Boolean,
    val rewardVibration: Boolean,
    val rewardSound: Boolean,
    /** `doux`, `direct` or `drole`. */
    val tone: String,
    val defaults: TaskSettingsValues,
) {
    val values: TaskSettingsValues
        get() = TaskSettingsValues(
            orderLevels, alertSchedule, morningTime, eveningTime, rewardAnimation, rewardVibration, rewardSound, tone,
        )

    companion object {
        const val TONE_DOUX = "doux"
        const val TONE_DIRECT = "direct"
        const val TONE_DROLE = "drole"
    }
}

/** The editable part of [TaskSettings], as `defaults` holds it. */
@Serializable
data class TaskSettingsValues(
    val orderLevels: Map<String, Int>,
    val alertSchedule: Map<String, AlertRule>,
    val morningTime: String,
    val eveningTime: String,
    val rewardAnimation: Boolean,
    val rewardVibration: Boolean,
    val rewardSound: Boolean,
    val tone: String,
)

/** When a task of one urgency alerts: days before the due day (furthest first), the due day, every n days late (null: never). */
@Serializable
data class AlertRule(
    val daysBefore: List<Int>,
    val dueDay: Boolean,
    val overdueEveryDays: Int?,
)

/**
 * One of the five real tasks `GET /api/task-settings/me/preview` returns, in the order the
 * user's levels give them — the preview under the sliders.
 */
@Serializable
data class TaskOrderPreviewItem(
    val id: String,
    val title: String,
    val dueDate: String? = null,
    val urgency: String? = null,
    val orderScore: Int? = null,
)
