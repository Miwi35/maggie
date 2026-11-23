package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyEnergy
import com.maggie.app.data.model.HealthProfile
import com.maggie.app.data.model.Workout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** « Santé › Activité » (MAG-898): the projection, the sessions of the day, the week and the missing source. */
class ActivitySeriesTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.parse("2026-11-07")

    private fun workout(id: String, type: String, source: String, at: String = "2026-11-07T08:30:00+01:00") =
        Workout(id, type, "Séance $id", at, 25, kcal = 120, source = source)

    @Test
    fun `projects 2 100 steps at 12h40 to 5 400 by 20 h`() {
        val projection = stepProjection(2100, LocalTime.of(12, 40), "08:00", "20:00")
        assertEquals(5400, projection)
        assertEquals(2600, gapToGoal(projection, 8000))
    }

    @Test
    fun `no projection before an hour into the window`() {
        assertNull(stepProjection(300, LocalTime.of(8, 59), "08:00", "20:00"))
        assertNull(stepProjection(300, LocalTime.of(6, 0), "08:00", "20:00"))
        assertEquals(3600, stepProjection(300, LocalTime.of(9, 0), "08:00", "20:00"))
    }

    @Test
    fun `after the window the projection is the day's steps`() {
        assertEquals(6234, stepProjection(6234, LocalTime.of(20, 0), "08:00", "20:00"))
        assertEquals(6234, stepProjection(6234, LocalTime.of(22, 15), "08:00", "20:00"))
    }

    @Test
    fun `no gap once the projection reaches the goal`() {
        assertNull(gapToGoal(8000, 8000))
        assertNull(gapToGoal(9100, 8000))
        assertNull(gapToGoal(null, 8000))
    }

    @Test
    fun `walk and bike are active sessions, planned strength is cited, done run is left out`() {
        val walk = workout("walk", "walk", "activity")
        val bike = workout("bike", "bike", "planned", "2026-11-07T17:00:00+01:00")
        val strength = workout("strength", "strength", "planned", "2026-11-07T18:30:00+01:00")
        val run = workout("run", "run", "activity")
        val yesterday = workout("old", "walk", "activity", "2026-11-06T08:30:00+01:00")
        val all = listOf(bike, strength, run, walk, yesterday)

        assertEquals(listOf(walk, bike), activeSessions(all, today, zone))
        assertEquals(listOf(strength), plannedSportSessions(all, today, zone))
        assertTrue(isActiveSession(walk))
        assertFalse(isActiveSession(run))
    }

    @Test
    fun `no step source only without steps today`() {
        val none = HealthProfile(stepSource = HealthProfile.STEP_SOURCE_NONE)
        assertTrue(hasNoStepSource(none, null))
        assertTrue(hasNoStepSource(none, 0))
        assertFalse(hasNoStepSource(none, 3000))
        assertFalse(hasNoStepSource(HealthProfile(stepSource = HealthProfile.STEP_SOURCE_HEALTH_CONNECT), null))
    }

    @Test
    fun `seven bars from J-6 to auj`() {
        val rows = listOf(DailyEnergy("2026-11-01", steps = 4000), DailyEnergy("2026-11-07", steps = 8200))
        val bars = weekBars(rows, 8000, today)

        assertEquals(7, bars.size)
        assertEquals(LocalDate.parse("2026-11-01"), bars.first().date)
        assertEquals(4000, bars.first().steps)
        assertFalse(bars.first().reached)
        assertEquals(0, bars[1].steps)
        assertEquals("auj.", bars.last().label)
        assertTrue(bars.last().reached)
        assertEquals("dim.", bars[0].label)
    }

    @Test
    fun `the week reads abbreviated days then auj`() {
        val labels = weekBars(emptyList(), 8000, LocalDate.parse("2026-11-20")).map { it.label }

        assertEquals(listOf("sam.", "dim.", "lun.", "mar.", "mer.", "jeu.", "auj."), labels)
    }

    @Test
    fun `the count names only what exists - 1 faite, 1 prevue, 1 faite et 1 prevue, 2 faites et 2 prevues`() {
        assertEquals("1 faite", sessionsCountLabel(1, 0))
        assertEquals("1 prévue", sessionsCountLabel(0, 1))
        assertEquals("1 faite · 1 prévue", sessionsCountLabel(1, 1))
        assertEquals("2 faites · 2 prévues", sessionsCountLabel(2, 2))
    }

    @Test
    fun `no session gives no count`() {
        assertNull(sessionsCountLabel(0, 0))
    }
}
