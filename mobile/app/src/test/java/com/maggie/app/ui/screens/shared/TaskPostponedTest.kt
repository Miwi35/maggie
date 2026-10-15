package com.maggie.app.ui.screens.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** « Reportée » from the day it was first put off (MAG-497); Thursday 15 Oct. 2026 is today. */
class TaskPostponedTest {
    private val today = LocalDate.of(2026, 10, 15)

    @Test
    fun `put off yesterday reads « depuis hier »`() {
        assertEquals("reportée depuis hier", TaskPostponed.label(LocalDate.of(2026, 10, 14), today))
    }

    @Test
    fun `put off two to six days ago reads the weekday, in lowercase`() {
        assertEquals("reportée depuis mardi", TaskPostponed.label(LocalDate.of(2026, 10, 13), today))
        assertEquals("reportée depuis lundi", TaskPostponed.label(LocalDate.of(2026, 10, 12), today))
        assertEquals("reportée depuis vendredi", TaskPostponed.label(LocalDate.of(2026, 10, 9), today))
    }

    @Test
    fun `put off a week ago or more reads the date`() {
        assertEquals("reportée depuis le 8 oct.", TaskPostponed.label(LocalDate.of(2026, 10, 8), today))
        assertEquals("reportée depuis le 3 sept.", TaskPostponed.label(LocalDate.of(2026, 9, 3), today))
    }

    @Test
    fun `put off in another year reads the year too`() {
        assertEquals("reportée depuis le 20 déc. 2025", TaskPostponed.label(LocalDate.of(2025, 12, 20), today))
    }

    @Test
    fun `a task not put off, or critical, gets no label`() {
        assertNull(TaskPostponed.labelOf(null, "high", today))
        assertNull(TaskPostponed.labelOf("not a day", "high", today))
        assertNull(TaskPostponed.labelOf("2026-10-13", "critical", today))
        assertEquals("reportée depuis mardi", TaskPostponed.labelOf("2026-10-13", "normal", today))
        assertEquals("reportée depuis mardi", TaskPostponed.labelOf("2026-10-13", null, today))
    }

    @Test
    fun `minutes read as minutes, then hours`() {
        assertEquals("10 min", minutesLabel(10))
        assertEquals("1 h", minutesLabel(60))
        assertEquals("1 h 30", minutesLabel(90))
        assertEquals("2 h 05", minutesLabel(125))
    }

    @Test
    fun `an estimate is whole minutes from 1 to 1440, an empty field none`() {
        assertEquals("120", TaskEstimate.sanitize("1 20 min"))
        assertEquals("1440", TaskEstimate.sanitize("144000"))
        assertTrue(TaskEstimate.isValid(""))
        assertTrue(TaskEstimate.isValid("1440"))
        assertFalse(TaskEstimate.isValid("0"))
        assertFalse(TaskEstimate.isValid("1441"))
        assertNull(TaskEstimate.toApi(""))
        assertNull(TaskEstimate.toApi("0"))
        assertEquals(20, TaskEstimate.toApi("20"))
    }

    @Test
    fun `an emptied first step is sent as null`() {
        assertNull(TaskEstimate.firstStepToApi("   "))
        assertEquals("Ouvrir le dossier", TaskEstimate.firstStepToApi("  Ouvrir le dossier "))
        assertEquals(255, TaskEstimate.firstStepToApi("a".repeat(300))!!.length)
    }
}
