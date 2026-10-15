package com.maggie.app.data.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The day's PUT body as `DayPlanController` reads it, and a tick applied before the server answers (MAG-497). */
class DayPlanTest {

    // The app's client: defaults are not encoded, so a slot's `type` must not be one.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `the PUT body is the whole list of slots, each with its type and task id`() {
        val body = json.encodeToString(DayPlanUpdateRequest(listOf("a", "b").map(DaySlotRequest::task)))

        assertEquals("""{"slots":[{"type":"task","taskId":"a"},{"type":"task","taskId":"b"}]}""", body)
    }

    @Test
    fun `an emptied day sends an empty list`() {
        assertEquals("""{"slots":[]}""", json.encodeToString(DayPlanUpdateRequest(emptyList())))
    }

    @Test
    fun `a tick moves « Maintenant » to the next slot not done and counts it, an untick brings it back`() {
        val slots = listOf(DayTask("a", "A"), DayTask("b", "B")).mapIndexed { i, t -> DaySlot(i + 1, task = t) }
        val day = DayPlan(id = "d", date = "2026-10-15", slots = slots, now = slots[0], total = 2)

        val ticked = day.withCompleted("a", "2026-10-15T08:00:00Z")
        assertEquals("b", ticked.now?.task?.id)
        assertEquals(1, ticked.done)
        assertEquals(1, ticked.left)
        assertFalse(ticked.isFull)

        val back = ticked.withCompleted("a", null)
        assertEquals("a", back.now?.task?.id)
        assertEquals(0, back.done)
    }

    @Test
    fun `five slots fill the day`() {
        val slots = (1..5).map { DaySlot(it, task = DayTask("t$it", "T$it")) }
        assertTrue(DayPlan(id = "d", date = "2026-10-15", slots = slots, total = 5).isFull)
    }
}
