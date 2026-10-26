package com.maggie.app.ui.components

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.NutritionHomeMeal
import com.maggie.app.data.model.NutritionHomeSlot
import com.maggie.app.data.model.NutritionHomeState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The drawer's lines and counters (MAG-620): each from the data of its module, none from nowhere. */
class DrawerSummaryTest {
    // Sunday 25 October 2026, 10:00 in Paris (UTC+1 after the clocks went back).
    private val morning = Instant.parse("2026-10-25T09:00:00Z")
    private val evening = Instant.parse("2026-10-25T17:30:00Z")

    private fun event(summary: String, start: String?, end: String? = null, allDay: Boolean = false) =
        AgendaHomeEvent(id = summary, summary = summary, startAt = start, endAt = end, allDay = allDay)

    @Test
    fun `nothing known, nothing said`() {
        assertEquals(emptyMap<String, DrawerEntryStatus>(), DrawerSummary.of(now = morning).entries)
    }

    @Test
    fun `the tasks say how many are done of the day and count the open ones`() {
        val summary = DrawerSummary.of(tasks = HomeTasks(done = 3, total = 5), now = morning)

        assertEquals(DrawerEntryStatus("3 sur 5 aujourd'hui", count = 2), summary["tasks"])
    }

    @Test
    fun `all the tasks done leaves the line without a counter, no task leaves nothing`() {
        assertEquals(DrawerEntryStatus("5 sur 5 aujourd'hui", count = null), DrawerSummary.of(tasks = HomeTasks(done = 5, total = 5))["tasks"])
        assertNull(DrawerSummary.of(tasks = HomeTasks())["tasks"])
    }

    @Test
    fun `the agenda names the next event that is not over, at its Paris hour`() {
        val agenda = AgendaHomeState(
            date = "2026-10-25",
            events = listOf(
                event("Dentiste", "2026-10-25T07:30:00Z", "2026-10-25T08:00:00Z"),
                event("Récupérer Ael", "2026-10-25T15:30:00Z", "2026-10-25T16:00:00Z"),
                event("Apéro", "2026-10-25T17:00:00Z", "2026-10-25T18:00:00Z"),
            ),
        )

        assertEquals("16:30 · Récupérer Ael", DrawerSummary.of(agenda = agenda, now = morning)["calendar"]?.line)
        assertEquals("18:00 · Apéro", DrawerSummary.of(agenda = agenda, now = Instant.parse("2026-10-25T16:30:00Z"))["calendar"]?.line)
        assertNull(DrawerSummary.of(agenda = agenda, now = Instant.parse("2026-10-25T19:00:00Z"))["calendar"])
    }

    @Test
    fun `an all-day event speaks only when no timed event is left`() {
        val agenda = AgendaHomeState(
            date = "2026-10-25",
            events = listOf(event("Anniversaire", null, allDay = true), event("Déjeuner", "2026-10-25T11:00:00Z", "2026-10-25T12:30:00Z")),
        )

        assertEquals("12:00 · Déjeuner", DrawerSummary.of(agenda = agenda, now = morning)["calendar"]?.line)
        assertEquals("Toute la journée · Anniversaire", DrawerSummary.of(agenda = agenda, now = evening)["calendar"]?.line)
    }

    @Test
    fun `Courses counts the open lines`() {
        assertEquals("1 article", DrawerSummary.of(grocery = GroceryHomeState(count = 1))["grocery"]?.line)
        assertEquals("12 articles", DrawerSummary.of(grocery = GroceryHomeState(count = 12))["grocery"]?.line)
        assertNull(DrawerSummary.of(grocery = GroceryHomeState())["grocery"])
    }

    @Test
    fun `Cuisine names the lunch until the afternoon, then the dinner`() {
        val meals = NutritionHomeState(
            listOf(
                NutritionHomeSlot("lunch", listOf(NutritionHomeMeal("m1", title = "Déjeuner", recipes = listOf("Salade")))),
                NutritionHomeSlot("dinner", listOf(NutritionHomeMeal("m2", title = "Dîner", recipes = listOf("Pâtes", "Tarte")))),
            ),
        )

        assertEquals("Ce midi · Salade", DrawerSummary.of(meals = meals, now = morning)["cookbook"]?.line)
        assertEquals("Ce soir · Pâtes · Tarte", DrawerSummary.of(meals = meals, now = evening)["cookbook"]?.line)
    }

    @Test
    fun `Cuisine with only the lunch behind says nothing`() {
        val meals = NutritionHomeState(listOf(NutritionHomeSlot("lunch", listOf(NutritionHomeMeal("m1", recipes = listOf("Salade")))), NutritionHomeSlot("dinner")))

        assertNull(DrawerSummary.of(meals = meals, now = evening)["cookbook"])
    }

    @Test
    fun `Finance counts the lines to qualify and says nothing at zero`() {
        assertEquals(DrawerEntryStatus("4 à trier", count = 4), DrawerSummary.of(finance = FinanceHomeState(toQualifyCount = 4))["finance_dashboard"])
        assertNull(DrawerSummary.of(finance = FinanceHomeState(toQualifyCount = 0))["finance_dashboard"])
    }
}
