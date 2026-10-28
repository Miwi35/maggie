package com.maggie.app.ui.components

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.CuisineHomeState
import com.maggie.app.data.model.recipesLabel
import com.maggie.app.util.DateRanges
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** What an entry of the drawer says under its name ([line]) and on its pill ([count]); both come from data the app holds. */
data class DrawerEntryStatus(val line: String? = null, val count: Int? = null)

/**
 * The « state of the day » of `charte/Tiroir` (MAG-620): one line and one counter per module, from the same
 * answers as the Accueil's cards. A module whose answer is missing, failed or empty has no entry: the drawer
 * shows nothing there rather than a made-up figure.
 */
data class DrawerSummary(val entries: Map<String, DrawerEntryStatus> = emptyMap()) {
    operator fun get(route: String): DrawerEntryStatus? = entries[route]

    companion object {
        private val HOUR = DateTimeFormatter.ofPattern("HH:mm")

        /** From this hour the lunch is behind: the next meal is the dinner. */
        private val LUNCH_ENDS: LocalTime = LocalTime.of(15, 0)

        fun of(
            tasks: HomeTasks? = null,
            agenda: AgendaHomeState? = null,
            grocery: GroceryHomeState? = null,
            meals: CuisineHomeState? = null,
            finance: FinanceHomeState? = null,
            now: Instant = Instant.now(),
        ): DrawerSummary {
            val entries = buildMap {
                tasks?.let(::tasksStatus)?.let { put("tasks", it) }
                agenda?.let { agendaLine(it, now) }?.let { put("calendar", DrawerEntryStatus(line = it)) }
                grocery?.let(::groceryLine)?.let { put("grocery", DrawerEntryStatus(line = it)) }
                meals?.let { mealLine(it, now) }?.let { put("cookbook", DrawerEntryStatus(line = it)) }
                finance?.let(::financeStatus)?.let { put("finance_dashboard", it) }
            }
            return DrawerSummary(entries)
        }

        private fun tasksStatus(tasks: HomeTasks): DrawerEntryStatus? {
            if (tasks.total <= 0) return null
            return DrawerEntryStatus(
                line = "${tasks.done} sur ${tasks.total} aujourd'hui",
                count = (tasks.total - tasks.done).takeIf { it > 0 },
            )
        }

        /** « 16:30 · Récupérer Ael »: the next timed event not over yet, else the day's all-day one. */
        private fun agendaLine(agenda: AgendaHomeState, now: Instant): String? {
            val upcoming = agenda.events
                .filter { !it.allDay && it.start != null && (it.end ?: it.start)!! > now }
                .minByOrNull { it.start!! }
            if (upcoming != null) {
                return "${HOUR.format(upcoming.start!!.atZone(DateRanges.zone))} · ${upcoming.summary}"
            }
            val allDay: AgendaHomeEvent? = agenda.events.firstOrNull { it.allDay }
            return allDay?.let { "Toute la journée · ${it.summary}" }
        }

        private fun groceryLine(grocery: GroceryHomeState): String? = when (grocery.count) {
            0 -> null
            1 -> "1 article"
            else -> "${grocery.count} articles"
        }

        /** « Ce soir · Pâtes · Salade »: the next meal of the day, the lunch only until the afternoon. */
        private fun mealLine(meals: CuisineHomeState, now: Instant): String? {
            val time = now.atZone(DateRanges.zone).toLocalTime()
            val slots = if (time < LUNCH_ENDS) listOf("lunch" to "Ce midi", "dinner" to "Ce soir") else listOf("dinner" to "Ce soir")
            slots.forEach { (slot, moment) ->
                val meal = meals.slots.firstOrNull { it.slot == slot }?.meals?.firstOrNull() ?: return@forEach
                return "$moment · ${meal.recipesLabel()}"
            }
            return null
        }

        private fun financeStatus(finance: FinanceHomeState): DrawerEntryStatus? =
            finance.toQualifyCount.takeIf { it > 0 }?.let { DrawerEntryStatus(line = "$it à trier", count = it) }
    }
}
