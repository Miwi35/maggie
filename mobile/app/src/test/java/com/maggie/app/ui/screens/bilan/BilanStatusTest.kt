package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.ActivityOrigin
import com.maggie.app.data.model.ActivityStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** The tomorrow lists of a bilan Maggie prepared (MAG-967), and the swap that rewrites them. */
class BilanStatusTest {
    private val day = LocalDate.of(2026, 3, 15)

    private fun planned(id: String, title: String) =
        JsonObject(mapOf("taskId" to JsonPrimitive(id), "title" to JsonPrimitive(title)))

    private fun session(tasks: List<JsonObject>, spares: List<JsonObject>) = BilanSession(
        day,
        BilanStep.DEMAIN,
        JsonObject(mapOf("tomorrowTasks" to JsonArray(tasks), "tomorrowSpares" to JsonArray(spares))),
    )

    @Test
    fun `reads the tomorrow lists of the recorded bilan`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "api/contract").isDirectory) dir = dir.parentFile
        val text = File(dir, "api/contract/responses/activity_status.bilan.read.json").readText()
        val status = Json { ignoreUnknownKeys = true }.decodeFromString(ActivityStatus.serializer(), text)
        val session = BilanSession(day, BilanStep.DEMAIN, status.state)

        assertEquals(listOf("Trier les papiers", "Appeler le garage"), session.tomorrowTasks().map { it.title })
        assertEquals("reportée hier", session.tomorrowTasks()[0].reason)
        assertNull(session.tomorrowTasks()[1].reason)
        assertEquals(listOf("Ranger les photos"), session.tomorrowSpares().map { it.title })
    }

    @Test
    fun `a swap takes the first spare and sends the task out last`() {
        val swapped = session(
            listOf(planned("a", "Trier les papiers")),
            listOf(planned("b", "Ranger les photos"), planned("c", "Payer")),
        ).withSwap("a")!!

        assertEquals(listOf("b"), swapped.tomorrowTasks().map { it.taskId })
        assertEquals(listOf("c", "a"), swapped.tomorrowSpares().map { it.taskId })
    }

    @Test
    fun `no spare, no swap`() {
        assertNull(session(listOf(planned("a", "Trier")), emptyList()).withSwap("a"))
    }

    @Test
    fun `a task left for tomorrow at Journée is swapped by adding the spare`() {
        val swapped = session(listOf(planned("a", "Trier")), listOf(planned("b", "Ranger")))
            .withTaskChoice("j", "tomorrow")
            .withSwap("j", "Appeler")!!

        assertEquals(listOf("a", "b"), swapped.tomorrowTasks().map { it.taskId })
        assertEquals(listOf("j"), swapped.tomorrowSpares().map { it.taskId })
        assertEquals("Appeler", swapped.tomorrowSpares()[0].title)
        assertEquals(listOf("j"), swapped.leftForTomorrow())
    }

    @Test
    fun `a step-less status of today is prepared, of yesterday is not`() {
        fun status(id: String, state: JsonObject) =
            ActivityStatus("bilan", ActivityOrigin("day", id), state, "2026-03-15T20:00:00+00:00", "2026-03-15T20:00:00+00:00")

        val bare = session(listOf(planned("a", "Trier")), emptyList()).state
        assertNotNull(status("2026-03-15", bare).preparedFor(day))
        assertNull(status("2026-03-14", bare).preparedFor(day))
        assertNull(status("2026-03-15", JsonObject(bare + ("step" to JsonPrimitive("journee")))).preparedFor(day))
    }
}
