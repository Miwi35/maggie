package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * The user's task settings (MAG-407), `GET /api/task-settings/me`: the weights of the task
 * order (MAG-406) and the alert schedule per urgency, with the morning the alerts go out.
 *
 * - `orderWeights`: group (`timePressure`, `criticality`, `priority`) → key → points;
 * - `alertSchedule`: urgency (`critical`, `high`, `normal` — `low` never alerts) → its rule.
 *
 * No defaults on purpose: the API always sends every key, and a request built from these
 * classes must send `overdueEveryDays: null` (« never ») rather than drop it — the API keeps
 * the stored value of a key it is not sent.
 */
@Serializable
data class TaskSettings(
    val id: String,
    val orderWeights: Map<String, Map<String, Int>>,
    val alertSchedule: Map<String, AlertRule>,
    val morningTime: String,
    val defaults: TaskSettingsValues,
) {
    val values: TaskSettingsValues get() = TaskSettingsValues(orderWeights, alertSchedule, morningTime)
}

/** The editable part of [TaskSettings]: what `defaults` holds and what `PUT` takes. */
@Serializable
data class TaskSettingsValues(
    val orderWeights: Map<String, Map<String, Int>>,
    val alertSchedule: Map<String, AlertRule>,
    val morningTime: String,
)

/** When a task of one urgency alerts: days before the due day (furthest first), the due day, every n days late (null: never). */
@Serializable
data class AlertRule(
    val daysBefore: List<Int>,
    val dueDay: Boolean,
    val overdueEveryDays: Int?,
)
