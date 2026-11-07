package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyBalance
import com.maggie.app.data.model.FoodIntake
import com.maggie.app.data.model.foodIntakeSourceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The bilan's arithmetic-free helpers (MAG-748): the zone, the labels, the bar, the seven days. */
class NutritionBalanceTest {

    @Test
    fun `within 150 kcal either way the day is balanced`() {
        assertEquals(BalanceZone.BALANCED, balanceZone(100))
        assertEquals(BalanceZone.BALANCED, balanceZone(0))
        assertEquals(BalanceZone.BALANCED, balanceZone(150))
        assertEquals(BalanceZone.BALANCED, balanceZone(-150))
        assertEquals(BalanceZone.SURPLUS, balanceZone(151))
        assertEquals(BalanceZone.DEFICIT, balanceZone(-151))
        assertNull(balanceZone(null))
    }

    @Test
    fun `the label is signed, Équilibre in the band, absent without a balance`() {
        assertEquals("Équilibre", balanceLabel(100))
        assertEquals("+320 kcal", balanceLabel(320))
        assertEquals("−480 kcal", balanceLabel(-480))
        assertEquals("−1 234 kcal", balanceLabel(-1234))
        assertNull(balanceLabel(null))
    }

    @Test
    fun `the figures name eaten, planned and spent, spent left out while unknown`() {
        assertEquals(
            "Mangé 515 · Prévu 0 · Dépensé 2 195 kcal",
            balanceFigures(DailyBalance("2026-11-07", eatenKcal = 515, plannedKcal = 0, expendedKcal = 2195, balanceKcal = -1680)),
        )
        assertEquals("Mangé 78 · Prévu 340 kcal", balanceFigures(DailyBalance("2026-11-07", eatenKcal = 78, plannedKcal = 340)))
    }

    @Test
    fun `the bar is centred on zero and wide enough for the balance`() {
        assertEquals(600, balanceHalfRange(100))
        assertEquals(600, balanceHalfRange(null))
        assertEquals(1234, balanceHalfRange(-1234))
        assertEquals(0.5f, balanceFraction(0, 600), 0.0001f)
        assertEquals(0.625f, balanceFraction(150, 600), 0.0001f)
        assertEquals(0.375f, balanceFraction(-150, 600), 0.0001f)
        assertEquals(0f, balanceFraction(-1234, 1234), 0.0001f)
        assertEquals(1f, balanceFraction(5000, 600), 0.0001f)
    }

    @Test
    fun `the week is today and the six days before, a missing day without balance`() {
        val today = LocalDate.parse("2026-11-07")
        val rows = listOf(
            DailyBalance("2026-11-01", balanceKcal = -300),
            DailyBalance("2026-11-07", balanceKcal = 120),
        )

        val week = balanceWeek(rows, today)

        assertEquals((1..7).map { "2026-11-0$it" }, week.map { it.date })
        assertEquals(-300, week.first().balanceKcal)
        assertEquals(120, week.last().balanceKcal)
        assertNull(week[3].balanceKcal)
    }

    @Test
    fun `the week's scale is its largest balance, never under twice the band`() {
        val today = LocalDate.parse("2026-11-07")
        assertEquals(300, balanceWeekScale(balanceWeek(listOf(DailyBalance("2026-11-07", balanceKcal = 100)), today)))
        assertEquals(900, balanceWeekScale(balanceWeek(listOf(DailyBalance("2026-11-06", balanceKcal = -900), DailyBalance("2026-11-07", balanceKcal = 400)), today)))
        assertEquals(300, balanceWeekScale(emptyList()))
    }

    @Test
    fun `the weekday initials run from Monday`() {
        assertEquals("S", weekdayInitial("2026-11-07"))
        assertEquals("L", weekdayInitial("2026-11-02"))
        assertEquals("D", weekdayInitial("2026-11-08"))
        assertEquals("", weekdayInitial("nope"))
    }

    @Test
    fun `grams keep one decimal, a whole number none`() {
        assertEquals("12,9 g", formatGrams(12.9))
        assertEquals("71 g", formatGrams(71.0))
        assertEquals("0 g", formatGrams(0.0))
    }

    @Test
    fun `slots, sources and times read in French`() {
        assertEquals("Déjeuner", slotLabel("lunch"))
        assertEquals("Dîner", slotLabel("dinner"))
        assertEquals("Repas", foodIntakeSourceLabel(FoodIntake.SOURCE_MEAL))
        assertEquals("Saisie", foodIntakeSourceLabel(FoodIntake.SOURCE_MANUAL))
        assertEquals("Maggie", foodIntakeSourceLabel(FoodIntake.SOURCE_CONVERSATION))
        assertEquals("16:00", intakeTime("2026-03-16T15:00:00+00:00", ZoneId.of("Europe/Paris")))
        assertEquals("", intakeTime("not a time", ZoneId.of("Europe/Paris")))
    }

    @Test
    fun `eatenAt is sent with its seconds and a numeric offset`() {
        assertEquals("2026-11-07T12:30:00+01:00", isoWithOffset(OffsetDateTime.of(2026, 11, 7, 12, 30, 0, 0, ZoneOffset.ofHours(1))))
        assertEquals("2026-11-07T11:30:00+00:00", isoWithOffset(OffsetDateTime.of(2026, 11, 7, 11, 30, 0, 0, ZoneOffset.UTC)))
    }

    @Test
    fun `the dialog takes whole kcal up to 10 000 and grams with a comma`() {
        assertEquals(515, parseKcal(" 515 "))
        assertNull(parseKcal("12,5"))
        assertNull(parseKcal("10001"))
        assertNull(parseKcal(""))
        assertEquals(12.5, parseGrams("12,5")!!, 0.001)
        assertNull(parseGrams(""))
        assertNull(parseGrams("-1"))
    }
}
