package com.maggie.app.ui.screens.settings

import com.maggie.app.data.model.AlertRule
import com.maggie.app.data.model.TaskSettings
import com.maggie.app.data.model.TaskSettingsValues

/** Réglages › Tâches (MAG-407): what was read from the server, and the form being edited. */
data class TaskSettingsUiState(
    /** The settings as the server last returned them; `defaults` comes with them. */
    val settings: TaskSettings? = null,
    /** The edited form; null until the settings are read. */
    val form: TaskSettingsForm? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    /** The last save went through and nothing was edited since. */
    val saved: Boolean = false,
    /** Why the load or the save failed — the API's own sentence for a refused write. */
    val error: String? = null,
)

/** One urgency's alert rule as typed: the days as « 3, 1 », late every n days as text (empty: never). */
data class AlertRuleForm(
    val daysBefore: String,
    val dueDay: Boolean,
    val overdueEveryDays: String,
)

/**
 * The task settings as the user types them. Text fields hold text: a half-typed value is
 * kept as it is, and only [toValues] decides whether the form can be sent.
 */
data class TaskSettingsForm(
    val morningTime: String,
    val alertSchedule: Map<String, AlertRuleForm>,
    val orderWeights: Map<String, Map<String, String>>,
) {
    fun withAlert(urgency: String, change: (AlertRuleForm) -> AlertRuleForm): TaskSettingsForm =
        alertSchedule[urgency]?.let { copy(alertSchedule = alertSchedule + (urgency to change(it))) } ?: this

    fun withWeight(group: String, key: String, value: String): TaskSettingsForm =
        copy(orderWeights = orderWeights + (group to (orderWeights[group].orEmpty() + (key to value))))

    /** The values to send, or an [IllegalArgumentException] naming the first field that does not read. */
    fun toValues(): Result<TaskSettingsValues> = runCatching {
        require(MORNING_TIME.matches(morningTime.trim())) { "Heure du matin : au format HH:MM, par exemple 08:00." }
        TaskSettingsValues(
            orderWeights = orderWeights.mapValues { (group, weights) ->
                weights.mapValues { (key, text) ->
                    text.trim().toIntOrNull()?.takeIf { it >= 0 }
                        ?: throw IllegalArgumentException("${weightLabel(group, key)} : un nombre entier, 0 ou plus.")
                }
            },
            alertSchedule = alertSchedule.mapValues { (urgency, rule) ->
                val name = URGENCY_LABELS[urgency] ?: urgency
                val days = rule.daysBefore.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map {
                    it.toIntOrNull()?.takeIf { n -> n in ALERT_DAYS }
                        ?: throw IllegalArgumentException("$name, jours avant l'échéance : des nombres de 1 à 30 séparés par des virgules.")
                }
                val every = rule.overdueEveryDays.trim().takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull()?.takeIf { n -> n in ALERT_DAYS }
                        ?: throw IllegalArgumentException("$name, en retard : un nombre de jours de 1 à 30, ou vide pour jamais.")
                }
                AlertRule(daysBefore = days, dueDay = rule.dueDay, overdueEveryDays = every)
            },
            morningTime = morningTime.trim(),
        )
    }

    companion object {
        private val MORNING_TIME = Regex("([01]\\d|2[0-3]):[0-5]\\d")
        private val ALERT_DAYS = 1..30

        fun from(values: TaskSettingsValues) = TaskSettingsForm(
            morningTime = values.morningTime,
            alertSchedule = values.alertSchedule.mapValues { (_, rule) ->
                AlertRuleForm(
                    daysBefore = rule.daysBefore.joinToString(", "),
                    dueDay = rule.dueDay,
                    overdueEveryDays = rule.overdueEveryDays?.toString().orEmpty(),
                )
            },
            orderWeights = values.orderWeights.mapValues { (_, weights) -> weights.mapValues { it.value.toString() } },
        )

        /** The urgencies that alert, as the screen shows them — `low` never alerts. */
        val URGENCY_LABELS: Map<String, String> = linkedMapOf(
            "critical" to "Critique",
            "high" to "Haute",
            "normal" to "Normale",
        )

        /** The order weights, group by group, as the screen shows them (MAG-406's keys). */
        val WEIGHT_GROUPS: List<Pair<String, String>> = listOf(
            "timePressure" to "Pression du temps",
            "criticality" to "Criticité",
            "priority" to "Priorité",
        )

        val WEIGHT_LABELS: Map<String, List<Pair<String, String>>> = mapOf(
            "timePressure" to listOf(
                "overdue" to "En retard",
                "overduePerDay" to "Par jour de retard",
                "overdueMax" to "Plafond du retard",
                "today" to "Aujourd'hui",
                "tomorrow" to "Demain",
                "in2To3Days" to "Dans 2 à 3 jours",
                "in4To7Days" to "Dans 4 à 7 jours",
                "in8To30Days" to "Dans 8 à 30 jours",
                "later" to "Plus tard ou sans date",
            ),
            "criticality" to listOf(
                "critical" to "Critique",
                "high" to "Haute",
                "medium" to "Moyenne",
                "low" to "Basse",
            ),
            "priority" to listOf(
                "high" to "Haute",
                "medium" to "Moyenne",
                "low" to "Basse",
            ),
        )

        private fun weightLabel(group: String, key: String): String {
            val groupLabel = WEIGHT_GROUPS.firstOrNull { it.first == group }?.second ?: group
            val keyLabel = WEIGHT_LABELS[group]?.firstOrNull { it.first == key }?.second ?: key
            return "$groupLabel, $keyLabel"
        }
    }
}
