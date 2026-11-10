package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.FastingProtocol
import com.maggie.app.data.model.FastingStatus
import com.maggie.app.data.model.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The « Jeûne » card's figures (MAG-770): its branches the ViewModel test does not reach, and the formatters. */
class NutritionFastingTest {

    private val paris = ZoneId.of("Europe/Paris")

    // Saturday 7 November 2026, 12:30 in Paris.
    private val now = Instant.parse("2026-11-07T11:30:00Z")

    @Test
    fun `nothing eaten once the window's hour has come reads Fenêtre ouverte from its start`() {
        val status = FastingStatus("2026-11-07", "16:8", "12:00", "20:00", state = FastingStatus.STATE_FASTING)

        val view = fastingView(status, now, paris)!!

        assertTrue(view.open)
        assertEquals("12 h 00 → 20 h 00", view.span)
        assertEquals(30f / 480f, view.progress, 0.001f)
        assertEquals(450L, view.remainingMinutes)
        assertEquals(0f, fastingView(status.copy(windowStart = "13:00", windowEnd = "21:00"), now, paris)!!.progress)
    }

    @Test
    fun `a window past midnight closes the next day`() {
        val status = FastingStatus(
            "2026-11-07", "16:8", "22:00", "06:00",
            openedAt = "2026-11-07T22:10:00+01:00", state = FastingStatus.STATE_OPEN,
        )

        val view = fastingView(status, Instant.parse("2026-11-07T23:00:00Z"), paris)!!

        assertEquals(360L, view.remainingMinutes)
        assertEquals("22 h 10 → 6 h 00", view.span)
    }

    @Test
    fun `an unknown protocol or no protocol draws no card`() {
        assertNull(fastingView(FastingStatus("2026-11-07"), now, paris))
        assertNull(fastingView(FastingStatus("2026-11-07", "12:12", "12:00", "00:00", state = "open"), now, paris))
    }

    @Test
    fun `durations and times read as the mockup writes them`() {
        assertEquals("2 h 20", formatFastDuration(140))
        assertEquals("16 h 05", formatFastDuration(965))
        assertEquals("0 h 45", formatFastDuration(45))
        assertEquals("0 h 00", formatFastDuration(-3))
        assertEquals("09:05", fastingTime(9, 5))
        assertEquals("8 h de fenêtre", FastingProtocol.P16_8.windowLabel)
    }

    @Test
    fun `the fourteen nights end today, a night the journal does not hold has no bar`() {
        val today = LocalDate.parse("2026-11-07")
        val history = fastingHistory(listOf(HealthDay(date = "2026-11-07", nightFastMinutes = 965), HealthDay(date = "2026-10-20", nightFastMinutes = 900)), today)

        assertEquals(FASTING_HISTORY_DAYS, history.size)
        assertEquals(LocalDate.parse("2026-10-25"), history.first().first)
        assertEquals(today to 965, history.last())
        assertNull(history.first().second)
        assertEquals(20 * 60, fastingChartScale(history))
        assertEquals(1500, fastingChartScale(history + (today to 1500)))
    }
}
