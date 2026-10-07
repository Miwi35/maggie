package com.maggie.app.ui.screens.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class TaskDueDateTest {
    @Test
    fun `a chosen day is sent as its midnight in Paris`() {
        assertEquals("2026-10-07T22:00:00Z", TaskDueDate.toIso(LocalDate.of(2026, 10, 8)))
    }

    @Test
    fun `no day is sent as nothing, which clears the due date`() {
        assertNull(TaskDueDate.toIso(null))
    }

    @Test
    fun `the form opens on the day the API stored`() {
        assertEquals(LocalDate.of(2026, 10, 8), TaskDueDate.fromIso("2026-10-07T22:00:00Z"))
    }

    @Test
    fun `a task without a due date, or with an unreadable one, opens empty`() {
        assertNull(TaskDueDate.fromIso(null))
        assertNull(TaskDueDate.fromIso("demain"))
    }
}
