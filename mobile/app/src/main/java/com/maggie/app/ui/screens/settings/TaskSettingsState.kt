package com.maggie.app.ui.screens.settings

import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskOrderPreviewItem
import com.maggie.app.data.model.TaskSettings

/**
 * Réglages › Tâches and « Ce qui passe en premier » (MAG-407). Every change is saved at once:
 * [settings] shows it before the server answers, and goes back to what the server holds
 * when the save fails.
 */
data class TaskSettingsUiState(
    /** The settings as shown: the server's, with the changes being saved; `defaults` comes with them. */
    val settings: TaskSettings? = null,
    val isLoading: Boolean = false,
    /** The last save went through and none has started since: « Enregistré ». */
    val saved: Boolean = false,
    /** Why the load or the last save failed — the API's own sentence for a refused write. */
    val error: String? = null,
    /** Five real tasks in the order the levels give them. */
    val preview: List<TaskOrderPreviewItem> = emptyList(),
    /** Task id → how it moved since the previous preview; a task that kept its place is absent. */
    val moves: Map<String, TaskOrderMove> = emptyMap(),
)

enum class TaskOrderMove { UP, DOWN }

/** The labels and the choices of the two screens, and the pure arithmetic behind them. */
object TaskSettingsChoices {
    /** The urgencies that alert, as the screen shows them — `low` never alerts. */
    val URGENCY_LABELS: Map<String, String> = linkedMapOf(
        "critical" to "Critique",
        "high" to "Haute",
        "normal" to "Normale",
    )

    /** The days-before chips, furthest first; « Jour J » follows them. */
    val DAYS_BEFORE: List<Int> = listOf(7, 3, 2, 1)

    const val DUE_DAY_LABEL = "Jour J"

    fun dayLabel(days: Int): String = "J-$days"

    /** « En retard »: never, then every n days. */
    val OVERDUE_OPTIONS: List<Pair<Int?, String>> = listOf(
        null to "Jamais",
        1 to "Chaque matin",
        2 to "Tous les 2 jours",
        7 to "Chaque semaine",
    )

    /** The options of « En retard », with a stored value none of them offers. */
    fun overdueOptions(current: Int?): List<Pair<Int?, String>> =
        if (OVERDUE_OPTIONS.any { it.first == current }) OVERDUE_OPTIONS else OVERDUE_OPTIONS + (current to overdueLabel(current))

    fun overdueLabel(every: Int?): String =
        OVERDUE_OPTIONS.firstOrNull { it.first == every }?.second ?: "Tous les $every jours"

    /** The sliders of « Ce qui passe en premier », in the order shown. */
    val ORDER_GROUPS: List<Pair<String, String>> = listOf(
        "timePressure" to "L'échéance",
        "criticality" to "La gravité",
        "priority" to "Ma priorité",
    )

    /** The reward's tones (MAG-508), as « Ton » shows them. */
    val TONE_LABELS: Map<String, String> = linkedMapOf(
        TaskSettings.TONE_DOUX to "Doux",
        TaskSettings.TONE_DIRECT to "Direct",
        TaskSettings.TONE_DROLE to "Drôle",
    )

    /** « Mes moments » (MAG-510): the moments of the day the reminders are anchored to, in the day's order. */
    val MOMENT_LABELS: Map<String, String> = linkedMapOf(
        TaskSettings.MOMENT_WAKE to "Réveil",
        TaskSettings.MOMENT_AFTER_BREAKFAST to "Après le petit-déjeuner",
        TaskSettings.MOMENT_AFTER_LUNCH to "Après le déjeuner",
        TaskSettings.MOMENT_AFTER_DINNER to "Après le dîner",
    )

    /** « Au plus, par jour » moved by [step] (− 1 or + 1), kept within what the API takes. */
    fun dailyCap(current: Int, step: Int): Int =
        (current + step).coerceIn(TaskSettings.MIN_DAILY_CAP, TaskSettings.MAX_DAILY_CAP)

    val LEVEL_LABELS: List<String> = listOf("Très peu", "Peu", "Normal", "Beaucoup", "Énormément")

    fun levelLabel(level: Int): String = LEVEL_LABELS[level.coerceIn(LEVEL_LABELS.indices)]

    /** [rule]'s days with [days] switched on or off; a day no chip shows (e.g. 5) stays. Furthest first, as the API keeps them. */
    fun toggleDay(rule: AlertRule, days: Int): List<Int> {
        val next = if (days in rule.daysBefore) rule.daysBefore - days else rule.daysBefore + days
        return next.distinct().sortedDescending()
    }

    /**
     * How each task of [next] moved against [previous]: a task higher than before is [TaskOrderMove.UP],
     * lower [TaskOrderMove.DOWN]; one that was not shown came up from below. Nothing before: nothing moved.
     */
    fun moves(previous: List<TaskOrderPreviewItem>, next: List<TaskOrderPreviewItem>): Map<String, TaskOrderMove> {
        if (previous.isEmpty()) return emptyMap()
        val before = previous.mapIndexed { index, task -> task.id to index }.toMap()
        return next.mapIndexedNotNull { index, task ->
            val was = before[task.id]
            when {
                was == null || was > index -> task.id to TaskOrderMove.UP
                was < index -> task.id to TaskOrderMove.DOWN
                else -> null
            }
        }.toMap()
    }
}
