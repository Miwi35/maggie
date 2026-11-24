package com.maggie.app.ui.screens.bilan

import com.maggie.app.data.model.AgendaHomeEvent
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BilanTomorrowTest {
    private val day = LocalDate.parse("2026-11-09")

    private fun event(id: String, summary: String, at: String, location: String? = null) = AgendaHomeEvent(
        id = id,
        summary = summary,
        location = location,
        locationShort = location,
        startAt = "2026-11-09T${at}:00+01:00",
        endAt = "2026-11-09T${at}:00+01:00",
    )

    private fun trip(eventId: String, decidedBy: String = "auto", mode: String = "drive") = Trip(
        id = "t$eventId",
        event = "/api/events/$eventId",
        mode = mode,
        durationMinutes = 20,
        departAt = "2026-11-09T08:40:00+01:00",
        originDecidedBy = decidedBy,
    )

    private fun plan(events: List<AgendaHomeEvent>, trips: List<Trip> = emptyList()) =
        TomorrowPlan.of(day, events, trips, emptyList(), emptyList())

    @Test
    fun `no located event means no question`() {
        val plan = plan(listOf(event("e1", "Appel", "09:00")))
        assertNull(plan.nextQuestion(emptySet()))
        assertEquals(1, plan.rows.size)
    }

    @Test
    fun `a located event without trip asks where the owner leaves from`() {
        val question = plan(listOf(event("e1", "Dentiste", "09:00", "Cabinet Martin"))).nextQuestion(emptySet())!!
        assertEquals(TripQuestionKind.ORIGIN, question.kind)
        assertEquals(listOf("De la maison", "De ma position"), question.choices.map { it.label })
        assertTrue(question.choices.first().message!!.contains("e1"))
    }

    @Test
    fun `an origin chosen by the owner is not asked again`() {
        val plan = plan(listOf(event("e1", "Dentiste", "09:00", "Cabinet Martin")), listOf(trip("e1", decidedBy = "user")))
        assertEquals(TripQuestionKind.MODE, plan.nextQuestion(emptySet())!!.kind)
    }

    @Test
    fun `answered keys move on to parking then to nothing`() {
        val plan = plan(listOf(event("e1", "Dentiste", "09:00", "Cabinet Martin")), listOf(trip("e1", decidedBy = "user")))
        val parking = plan.nextQuestion(setOf(tripAnswerKey("e1", TripQuestionKind.MODE)))!!
        assertEquals(TripQuestionKind.PARKING, parking.kind)
        val done = setOf(tripAnswerKey("e1", TripQuestionKind.MODE), tripAnswerKey("e1", TripQuestionKind.PARKING))
        assertNull(plan.nextQuestion(done))
    }

    @Test
    fun `a second located event asks whether the owner passes home`() {
        val events = listOf(event("e1", "Dentiste", "09:00", "Cabinet"), event("e2", "Déjeuner", "12:00", "Resto"))
        val answered = setOf(tripAnswerKey("e1", TripQuestionKind.ORIGIN))
        val question = plan(events, listOf(trip("e1"))).nextQuestion(answered + tripAnswerKey("e1", TripQuestionKind.MODE) + tripAnswerKey("e1", TripQuestionKind.PARKING))!!
        assertEquals("e2", question.eventId)
        assertEquals(listOf("Oui", "Non, je reste en ville"), question.choices.map { it.label })
    }

    @Test
    fun `say names the first appointments then the question`() {
        val plan = plan(listOf(event("e1", "Dentiste", "09:00", "Cabinet Martin")))
        val line = plan.say(plan.nextQuestion(emptySet()))
        assertTrue(line.startsWith("Dentiste à 9 h."))
        assertTrue(line.endsWith("D’où partez-vous pour Cabinet Martin ?"))
    }

    @Test
    fun `trip answers are kept beside the other answers`() {
        val session = BilanSession(day, BilanStep.DEMAIN).withTripAnswer("e1:origin").withTripAnswer("e1:mode")
        assertEquals(setOf("e1:origin", "e1:mode"), session.answeredTrips())
        assertEquals(setOf("e1:origin", "e1:mode"), session.copy(step = BilanStep.JOURNEE).answeredTrips())
    }

    private val papers = PlannedTask("a", "Trier les papiers", reason = "reportée hier")

    @Test
    fun `with tasks Maggie says they can be swapped`() {
        val plan = plan(listOf(event("e1", "Kiné", "09:30"), event("e2", "Réunion", "14:00")))
        assertEquals("Kiné à 9 h 30, réunion à 14 h, et vos tâches, à échanger si besoin.", plan.say(null, hasTasks = true))
        assertEquals("Kiné à 9 h 30, et vos tâches, à échanger si besoin.", plan(listOf(event("e1", "Kiné", "09:30"))).say(null, true))
    }

    @Test
    fun `without tasks the line is unchanged`() {
        val plan = plan(listOf(event("e1", "Kiné", "09:30"), event("e2", "Réunion", "14:00")))
        assertEquals("Kiné à 9 h 30, puis réunion à 14 h.", plan.say(null))
    }

    @Test
    fun `rows skip a task done since`() {
        val tasks = mapOf("a" to Task(id = "a", title = "Trier les papiers"), "b" to Task(id = "b", title = "Payer", completedAt = "2026-11-08T10:00:00Z"))
        val rows = plannedRows(listOf("a", "b", "gone"), listOf(papers), tasks, day)
        assertEquals(listOf("a"), rows.map { it.taskId })
    }

    @Test
    fun `Maggie's reason comes before the task's meta`() {
        val task = Task(id = "a", title = "Trier les papiers", postponedSince = "2026-11-07")
        assertEquals("reportée hier", plannedRows(listOf("a"), listOf(papers), mapOf("a" to task), day).single().meta)
        assertEquals("Trier les papiers", plannedRows(listOf("a"), emptyList(), mapOf("a" to task), day).single().title)
    }

    @Test
    fun `the hour shows for a task at a time`() {
        val task = Task(id = "a", title = "Trier", scheduledDate = day.toString(), scheduledMoment = "time", scheduledTime = "10:30")
        assertEquals("10:30", plannedRows(listOf("a"), emptyList(), mapOf("a" to task), day).single().time)
        assertEquals("", plannedRows(listOf("a"), emptyList(), mapOf("a" to Task(id = "a", title = "T")), day).single().time)
    }
}
