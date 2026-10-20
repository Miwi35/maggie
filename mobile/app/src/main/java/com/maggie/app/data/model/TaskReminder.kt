package com.maggie.app.data.model

import kotlinx.serialization.Serializable

/**
 * What `POST /api/task-reminders/{id}/action` answers (MAG-510): the reminder as the button left
 * it, the one planned after it (« Dans 10 min », « Ce soir »), the reward a « Fait » earned, and
 * the notification as it now reads — the bravo in place of the reminder's words. Plain JSON.
 */
@Serializable
data class TaskReminderAnswer(
    val reminder: TaskReminder,
    val next: TaskReminder? = null,
    val reward: Reward? = null,
    val notification: TaskReminderNotification? = null,
)

/**
 * One reminder of a task: `due` (the morning's alert) or `nudge` (the task « now » of the day,
 * at a [moment] of it), sent at [sentAt], or dropped at [droppedAt] by the quiet hours or the cap.
 * [action] is the button that answered it, null while none has.
 */
@Serializable
data class TaskReminder(
    val id: String,
    val kind: String,
    val subjectId: String,
    val moment: String? = null,
    val scheduledFor: String,
    val sentAt: String? = null,
    val droppedAt: String? = null,
    val snoozed: Boolean,
    val messageKey: String? = null,
    val action: String? = null,
)

/** The notification of a reminder once answered; no [actions] left once it says the bravo. */
@Serializable
data class TaskReminderNotification(
    val id: String,
    val title: String,
    val body: String? = null,
    val actions: List<String> = emptyList(),
)
