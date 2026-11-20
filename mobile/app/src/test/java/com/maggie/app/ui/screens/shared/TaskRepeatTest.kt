package com.maggie.app.ui.screens.shared

import com.maggie.app.data.model.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** The « Répéter » contract (MAG-887): the table of rules written and the table of texts read. */
class TaskRepeatTest {

    private val tuesday = LocalDate.of(2026, 11, 24)
    private val wednesday = LocalDate.of(2026, 11, 25)

    private fun text(rrule: String?, anchor: LocalDate? = tuesday) = TaskRepeatText.ruleText(rrule, anchor)

    private fun task(rrule: String?, day: LocalDate = tuesday) =
        Task(id = "1", title = "t", priority = "medium", criticality = "low", dueDate = null, completedAt = null,
            scheduledDate = day.toString(), rrule = rrule)

    @Test
    fun `no rule reads Jamais`() {
        assertEquals("Jamais", text(null))
    }

    @Test
    fun `daily reads Chaque jour, every 3 days reads Tous les 3 jours`() {
        assertEquals("Chaque jour", text("FREQ=DAILY"))
        assertEquals("Tous les 3 jours", text("FREQ=DAILY;INTERVAL=3"))
    }

    @Test
    fun `weekly on Tuesday reads Chaque mardi`() {
        assertEquals("Chaque mardi", text("FREQ=WEEKLY;BYDAY=TU"))
    }

    @Test
    fun `every other Tuesday reads Un mardi sur deux`() {
        assertEquals("Un mardi sur deux", text("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU"))
    }

    @Test
    fun `every 3 weeks on Monday and Thursday reads Le lundi et jeudi, toutes les 3 semaines`() {
        assertEquals("Le lundi et jeudi, toutes les 3 semaines", text("FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,TH"))
    }

    @Test
    fun `weekly on three days reads Chaque lundi, mercredi et vendredi`() {
        assertEquals("Chaque lundi, mercredi et vendredi", text("FREQ=WEEKLY;BYDAY=MO,WE,FR"))
    }

    @Test
    fun `weekly without BYDAY reads the anchor's weekday`() {
        assertEquals("Chaque mardi", text("FREQ=WEEKLY"))
        assertEquals("Chaque mercredi", text("FREQ=WEEKLY", wednesday))
    }

    @Test
    fun `monthly on the 3rd reads Le 3 de chaque mois, on the 1st Le 1er de chaque mois`() {
        assertEquals("Le 3 de chaque mois", text("FREQ=MONTHLY;BYMONTHDAY=3"))
        assertEquals("Le 1er de chaque mois", text("FREQ=MONTHLY;BYMONTHDAY=1"))
    }

    @Test
    fun `every 2 months on the 24th reads Le 24, tous les 2 mois`() {
        assertEquals("Le 24, tous les 2 mois", text("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=24"))
    }

    @Test
    fun `monthly without BYMONTHDAY reads the anchor's day`() {
        assertEquals("Le 24 de chaque mois", text("FREQ=MONTHLY"))
    }

    @Test
    fun `a rule outside the contract falls back to the event text`() {
        assertEquals("Tous les ans", text("FREQ=YEARLY"))
        assertNull(TaskRepeatText.parse("FREQ=MONTHLY;BYDAY=MO;BYSETPOS=2"))
        assertNull(TaskRepeatText.parse("FREQ=MONTHLY;BYMONTHDAY=-1"))
        assertEquals("Tous les mois", text("FREQ=MONTHLY;BYMONTHDAY=-1"))
    }

    @Test
    fun `UNTIL stays out of the rule text and reads as a suffix on the sheet`() {
        val rule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;UNTIL=20261231"
        assertEquals("Un mardi sur deux", text(rule))
        assertEquals(", jusqu’au jeu. 31 déc.", TaskRepeatText.untilSuffix(rule))
        assertNull(TaskRepeatText.untilSuffix("FREQ=DAILY"))
    }

    @Test
    fun `the end field reads Aucune fin or the day`() {
        assertEquals("Aucune fin", TaskRepeatText.untilLabel(null))
        assertEquals("Jeu. 31 déc.", TaskRepeatText.untilLabel(LocalDate.of(2026, 12, 31)))
    }

    @Test
    fun `each preset writes its rule from the anchor day`() {
        fun sent(preset: RepeatPreset) = TaskRepeat().pick(preset).toApi(tuesday)
        assertNull(sent(RepeatPreset.NEVER))
        assertEquals("FREQ=DAILY", sent(RepeatPreset.DAILY))
        assertEquals("FREQ=WEEKLY;BYDAY=TU", sent(RepeatPreset.WEEKLY))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", sent(RepeatPreset.BIWEEKLY))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=24", sent(RepeatPreset.MONTHLY))
        assertEquals("FREQ=WEEKLY;BYDAY=WE", TaskRepeat().pick(RepeatPreset.WEEKLY).toApi(wednesday))
    }

    @Test
    fun `a custom rule writes INTERVAL above 1 only and its days in week order`() {
        fun sent(rule: RepeatRule) = TaskRepeat().withCustom(rule).toApi(tuesday)
        assertEquals("FREQ=DAILY;INTERVAL=5", sent(RepeatRule(RepeatUnit.DAY, 5)))
        assertEquals("FREQ=DAILY", sent(RepeatRule(RepeatUnit.DAY, 1)))
        assertEquals(
            "FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,FR,SU",
            sent(RepeatRule(RepeatUnit.WEEK, 3, setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY))),
        )
        assertEquals("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=24", sent(RepeatRule(RepeatUnit.MONTH, 2)))
    }

    @Test
    fun `an end day is written UNTIL=YYYYMMDD, last`() {
        val repeat = TaskRepeat().pick(RepeatPreset.BIWEEKLY).withUntil(LocalDate.of(2026, 12, 31))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;UNTIL=20261231", repeat.toApi(tuesday))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", repeat.withUntil(null).toApi(tuesday))
    }

    @Test
    fun `the preset checked is the one that writes the rule, anything else is custom`() {
        fun checked(rule: String?) = TaskRepeat.of(task(rule)).checked(tuesday)
        assertEquals(RepeatPreset.NEVER, checked(null))
        assertEquals(RepeatPreset.DAILY, checked("FREQ=DAILY"))
        assertEquals(RepeatPreset.WEEKLY, checked("FREQ=WEEKLY;BYDAY=TU;UNTIL=20261231"))
        assertEquals(RepeatPreset.BIWEEKLY, checked("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU"))
        assertEquals(RepeatPreset.MONTHLY, checked("FREQ=MONTHLY;BYMONTHDAY=24"))
        assertEquals(RepeatPreset.CUSTOM, checked("FREQ=WEEKLY;BYDAY=MO"))
        assertEquals(RepeatPreset.CUSTOM, checked("FREQ=DAILY;INTERVAL=3"))
        assertEquals(RepeatPreset.CUSTOM, checked("FREQ=YEARLY"))
    }

    @Test
    fun `an untouched rule is sent as stored even when the day moves`() {
        val repeat = TaskRepeat.of(task("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU"))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=TU", repeat.toApi(wednesday))
        assertFalse(repeat.touched)
    }

    @Test
    fun `Jamais clears the end day, a preset keeps it`() {
        val repeat = TaskRepeat.of(task("FREQ=DAILY;UNTIL=20261231"))
        assertEquals(LocalDate.of(2026, 12, 31), repeat.until)
        assertEquals(LocalDate.of(2026, 12, 31), repeat.pick(RepeatPreset.WEEKLY).until)
        val never = repeat.pick(RepeatPreset.NEVER)
        assertNull(never.until)
        assertNull(never.toApi(tuesday))
        assertFalse(never.repeats(tuesday))
        assertTrue(repeat.repeats(tuesday))
    }

    @Test
    fun `no anchor day means no rule`() {
        assertNull(TaskRepeat().pick(RepeatPreset.DAILY).toApi(null))
    }

    @Test
    fun `only the end day touched keeps a rule the form cannot write`() {
        val repeat = TaskRepeat.of(task("FREQ=YEARLY")).withUntil(LocalDate.of(2030, 1, 1))
        assertEquals("FREQ=YEARLY;UNTIL=20300101", repeat.toApi(tuesday))
    }
}
