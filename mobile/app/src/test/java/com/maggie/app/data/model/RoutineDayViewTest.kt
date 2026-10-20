package com.maggie.app.data.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class RoutineDayViewTest {
    private fun routine(id: String, checked: Boolean = false, anchor: String = "wake", time: String = "07:30") =
        DayRoutine(id = id, title = "Routine $id", anchor = anchor, time = time, checked = checked)

    private val wake = RoutineMoment("wake", "07:30", listOf(routine("a"), routine("b", checked = true)))
    private val lunch = RoutineMoment("time", "12:30", listOf(routine("c", anchor = "time", time = "12:30")))
    private val evening = RoutineMoment("afterDinner", "22:00", listOf(routine("d", anchor = "afterDinner", time = "22:00")))
    private val day = RoutineDayView(date = "2026-10-20", moments = listOf(wake, lunch, evening))

    private fun band(now: String, day: RoutineDayView? = this.day) = RoutinesBand.of(day, LocalTime.parse(now))

    @Test
    fun `before the first moment the band holds the first moment`() {
        val band = band("06:00")!!

        assertEquals("Routines · matin", band.title)
        assertEquals(listOf("a", "b"), band.routines.map { it.id })
    }

    @Test
    fun `mid-day the band holds the moment reached, plus what earlier moments left unticked`() {
        val band = band("13:00")!!

        assertEquals("Routines · après-midi", band.title)
        assertEquals(listOf("a", "c"), band.routines.map { it.id })
    }

    @Test
    fun `a routine ticked in an earlier moment is not in the band`() {
        val band = band("23:00")!!

        assertEquals("Routines · soir", band.title)
        assertEquals(listOf("a", "c", "d"), band.routines.map { it.id })
    }

    @Test
    fun `the routines of the current moment stay once ticked, so a tick can be undone`() {
        val ticked = day.withChecked("a", true)

        assertEquals(listOf("a", "b"), band("08:00", ticked)!!.routines.map { it.id })
        assertEquals(listOf(true, true), band("08:00", ticked)!!.routines.map { it.checked })
    }

    @Test
    fun `a moment still to come is not in the band`() {
        assertEquals(listOf("a", "b"), band("11:59")!!.routines.map { it.id })
    }

    @Test
    fun `no day or no routine, no band`() {
        assertNull(band("09:00", null))
        assertNull(band("09:00", RoutineDayView(date = "2026-10-20")))
        assertNull(band("09:00", RoutineDayView(date = "2026-10-20", moments = listOf(RoutineMoment("wake", "07:30")))))
    }

    @Test
    fun `the moment is matin before noon, après-midi before six, soir after`() {
        assertEquals("matin", RoutinesBand.momentOf(LocalTime.of(0, 0)))
        assertEquals("matin", RoutinesBand.momentOf(LocalTime.of(11, 59)))
        assertEquals("après-midi", RoutinesBand.momentOf(LocalTime.of(12, 0)))
        assertEquals("après-midi", RoutinesBand.momentOf(LocalTime.of(17, 59)))
        assertEquals("soir", RoutinesBand.momentOf(LocalTime.of(18, 0)))
        assertEquals("soir", RoutinesBand.momentOf(LocalTime.of(23, 59)))
    }

    @Test
    fun `the summary counts the ticks of the day`() {
        assertEquals("1 sur 4 aujourd'hui", day.summary)
        assertEquals("2 sur 4 aujourd'hui", day.withChecked("c", true).summary)
        assertEquals("0 sur 4 aujourd'hui", day.withChecked("b", false).summary)
    }

    @Test
    fun `the moments read as a label and an hour`() {
        assertEquals("22 h", RoutineMoment("time", "22:00").label)
        assertEquals("Au réveil · 7 h 30", RoutineMoment("wake", "07:30").label)
        assertEquals("Après le dîner · 21 h", RoutineMoment("afterDinner", "21:00").label)
    }

    @Test
    fun `the day the API describes is read`() {
        val json = """
            {"date":"2026-10-20","moments":[{"anchor":"wake","time":"07:30","routines":[
              {"id":"r1","title":"Vitamines","anchor":"wake","anchorTime":"07:30","time":"07:30","remind":true,"checked":true,"extra":1}
            ]}]}
        """.trimIndent()

        val read = Json { ignoreUnknownKeys = true }.decodeFromString<RoutineDayView>(json)

        assertEquals("1 sur 1 aujourd'hui", read.summary)
        assertEquals(true, read.routines.single().remind)
    }
}
