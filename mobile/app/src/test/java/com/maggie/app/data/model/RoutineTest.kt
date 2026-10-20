package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a routine reads (MAG-511): when, its total, its group, and a tick taken or given back. */
class RoutineTest {

    private fun routine(
        anchor: String = Routine.ANCHOR_WAKE,
        anchorTime: String? = null,
        time: String = "07:30",
        recurrence: RoutineRecurrence = RoutineRecurrence(RoutineRecurrence.DAILY),
        monthCount: Int = 13,
        totalCount: Int = 9,
        createdAt: String = "2026-08-03T09:00:00+02:00",
    ) = Routine(
        id = "r1",
        title = "Sortir les poubelles",
        anchor = anchor,
        anchorTime = anchorTime,
        time = time,
        recurrence = recurrence,
        createdAt = createdAt,
        monthCount = monthCount,
        totalCount = totalCount,
        dueToday = true,
        recentDays = listOf(RoutineDay("2026-10-19", due = true, checked = true), RoutineDay("2026-10-20", due = true, checked = false)),
    )

    @Test
    fun `each moment of the day reads as the card says it`() {
        assertEquals("au réveil", routine(anchor = Routine.ANCHOR_WAKE).whenLabel)
        assertEquals("après le petit-déjeuner", routine(anchor = Routine.ANCHOR_AFTER_BREAKFAST).whenLabel)
        assertEquals("après le déjeuner", routine(anchor = Routine.ANCHOR_AFTER_LUNCH).whenLabel)
        assertEquals("après le dîner", routine(anchor = Routine.ANCHOR_AFTER_DINNER).whenLabel)
    }

    @Test
    fun `an hour reads in French, the minutes left out on the hour`() {
        assertEquals("à 22 h 30", routine(anchor = Routine.ANCHOR_TIME, anchorTime = "22:30", time = "22:30").whenLabel)
        assertEquals("à 7 h", routine(anchor = Routine.ANCHOR_TIME, anchorTime = "07:00", time = "07:00").whenLabel)
        assertEquals("à 7 h 05", routine(anchor = Routine.ANCHOR_TIME, anchorTime = "07:05", time = "07:05").whenLabel)
    }

    @Test
    fun `a weekly routine names its day, a monthly one its date, weekdays say so`() {
        val tuesday = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 2)
        assertEquals("le mardi, après le dîner", routine(anchor = Routine.ANCHOR_AFTER_DINNER, recurrence = tuesday).whenLabel)
        assertEquals("le 15, au réveil", routine(recurrence = RoutineRecurrence(RoutineRecurrence.MONTHLY, monthDay = 15)).whenLabel)
        assertEquals("le 1er, au réveil", routine(recurrence = RoutineRecurrence(RoutineRecurrence.MONTHLY, monthDay = 1)).whenLabel)
        assertEquals("le dimanche, au réveil", routine(recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 7)).whenLabel)
        assertEquals("en semaine, au réveil", routine(recurrence = RoutineRecurrence(RoutineRecurrence.WEEKDAYS)).whenLabel)
    }

    @Test
    fun `a daily routine counts the month, a weekly or monthly one since it was made`() {
        assertEquals("ce mois : 13 fois", routine().totalLabel)
        assertEquals("ce mois : 13 fois", routine(recurrence = RoutineRecurrence(RoutineRecurrence.WEEKDAYS)).totalLabel)
        assertEquals("depuis août : 9 fois", routine(recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 2)).totalLabel)
        assertEquals(
            "depuis janvier : 9 fois",
            routine(recurrence = RoutineRecurrence(RoutineRecurrence.MONTHLY, monthDay = 1), createdAt = "2026-01-31T23:30:00+01:00").totalLabel,
        )
    }

    @Test
    fun `one time is said in the singular`() {
        assertEquals("ce mois : 1 fois", routine(monthCount = 1).totalLabel)
        assertEquals("depuis août : 0 fois", routine(recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 2), totalCount = 0).totalLabel)
    }

    @Test
    fun `daily routines group by the part of the day, the others by their rhythm`() {
        assertEquals(RoutineGroup.MORNING, routine(time = "11:59").group)
        assertEquals(RoutineGroup.AFTERNOON, routine(time = "12:00").group)
        assertEquals(RoutineGroup.AFTERNOON, routine(time = "17:59").group)
        assertEquals(RoutineGroup.EVENING, routine(time = "18:00").group)
        assertEquals(RoutineGroup.EVENING, routine(time = "22:30", recurrence = RoutineRecurrence(RoutineRecurrence.WEEKDAYS)).group)
        assertEquals(RoutineGroup.WEEKLY, routine(time = "07:00", recurrence = RoutineRecurrence(RoutineRecurrence.WEEKLY, weekday = 1)).group)
        assertEquals(RoutineGroup.MONTHLY, routine(time = "20:00", recurrence = RoutineRecurrence(RoutineRecurrence.MONTHLY, monthDay = 3)).group)
    }

    @Test
    fun `groups come in their order, the empty ones left out, the API's order kept inside`() {
        val evening = routine(time = "21:00").copy(id = "e")
        val morningA = routine(time = "07:00").copy(id = "a")
        val morningB = routine(time = "08:00").copy(id = "b")
        val monthly = routine(recurrence = RoutineRecurrence(RoutineRecurrence.MONTHLY, monthDay = 3)).copy(id = "m")

        val grouped = RoutineGroup.grouped(listOf(monthly, morningA, evening, morningB))

        assertEquals(listOf(RoutineGroup.MORNING, RoutineGroup.EVENING, RoutineGroup.MONTHLY), grouped.map { it.first })
        assertEquals(listOf("a", "b"), grouped.first().second.map { it.id })
    }

    @Test
    fun `a tick moves the counts and today's dot, and giving it back undoes them`() {
        val ticked = routine().withCheckedToday(true)
        assertTrue(ticked.checkedToday)
        assertEquals(14, ticked.monthCount)
        assertEquals(10, ticked.totalCount)
        assertTrue(ticked.recentDays.last().checked)
        assertTrue(ticked.recentDays.first().checked)

        val back = ticked.withCheckedToday(false)
        assertFalse(back.checkedToday)
        assertEquals(13, back.monthCount)
        assertFalse(back.recentDays.last().checked)
    }

    @Test
    fun `the repeat chips name the day chosen`() {
        assertEquals("Chaque jour", RoutineText.repeatOf(RoutineRecurrence.DAILY, 2))
        assertEquals("Jours de semaine", RoutineText.repeatOf(RoutineRecurrence.WEEKDAYS, 2))
        assertEquals("Chaque mardi", RoutineText.repeatOf(RoutineRecurrence.WEEKLY, 2))
        assertEquals("Chaque mois", RoutineText.repeatOf(RoutineRecurrence.MONTHLY, 2))
    }
}
