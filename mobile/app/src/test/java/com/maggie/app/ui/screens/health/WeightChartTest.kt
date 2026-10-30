package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.BodyMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The Poids card's rules (MAG-706): the day's value, the 7-day trend, the buckets, the labels and the French formats. */
class WeightChartTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 21)

    private fun m(
        id: String,
        at: String,
        kg: Double? = null,
        fat: Double? = null,
        waist: Double? = null,
        excluded: Boolean = false,
    ) = BodyMeasurement(id, at, weightKg = kg, bodyFatPercent = fat, waistCm = waist, excluded = excluded)

    @Test
    fun `the day's value is its first weighing with a weight that is not set aside, with that weighing's body fat`() {
        val days = weightDays(
            listOf(
                m("waist", "2026-10-21T06:00:00+02:00", waist = 84.0),
                m("aside", "2026-10-21T06:30:00+02:00", kg = 90.0, excluded = true),
                m("late", "2026-10-21T19:00:00+02:00", kg = 73.0, fat = 22.0),
                m("first", "2026-10-21T07:12:00+02:00", kg = 72.4, fat = 21.3),
            ),
            today, today, zone,
        )

        assertEquals("first", days.single().retained?.id)
        assertEquals(72.4, days.single().weightKg!!, 0.0)
        assertEquals(21.3, days.single().bodyFatPercent!!, 0.0)
        assertEquals(listOf("aside"), days.single().excluded.map { it.id })
    }

    @Test
    fun `a weighing belongs to the local day of the user's zone`() {
        // 23:30 UTC on the 20th is half past one on the 21st in Paris.
        val days = weightDays(listOf(m("night", "2026-10-20T23:30:00Z", kg = 72.0)), today.minusDays(1), today, zone)

        assertNull(days[0].retained)
        assertEquals("night", days[1].retained?.id)
    }

    @Test
    fun `the trend is the mean of the weighed days among the day and the six before, set-aside ones never count`() {
        val days = weightDays(
            listOf(
                m("a", "2026-10-13T07:00:00+02:00", kg = 80.0), // eight days back: out of the window
                m("b", "2026-10-15T07:00:00+02:00", kg = 72.0),
                m("c", "2026-10-18T07:00:00+02:00", kg = 73.0),
                m("d", "2026-10-21T07:00:00+02:00", kg = 99.0, excluded = true),
            ),
            LocalDate.of(2026, 10, 1), today, zone,
        )

        assertEquals(72.5, days.last().trendKg!!, 1e-9)
        assertNull(days.last().weightKg)
        assertNull(days.first().trendKg)
    }

    @Test
    fun `30 days are 30 columns of a day, today last, labelled as the mockup`() {
        val chart = weightChart(listOf(m("t", "2026-10-21T07:12:00+02:00", kg = 72.4, fat = 21.3)), SuiviPeriod.MONTH, today, zone)

        assertEquals(30, chart.columns.size)
        assertEquals("Aujourd’hui", chart.columns.last().label)
        assertEquals("mar. 22 sept.", chart.columns.first().label)
        assertEquals("22 sept.", chart.from)
        assertEquals("21 oct.", chart.to)
        assertEquals("Aujourd’hui : 72,4 kg, tendance 72,4 kg", chart.columns.last().description)
        assertEquals("mar. 22 sept. : pas de pesée, tendance —", chart.columns.first().description)
        assertEquals("t", chart.columns.last().retained?.id)
    }

    @Test
    fun `7 days are 7 columns`() {
        val chart = weightChart(emptyList(), SuiviPeriod.WEEK, today, zone)

        assertEquals(7, chart.columns.size)
        assertEquals("jeu. 15 oct.", chart.columns.first().label)
        assertNull(chart.range)
        assertEquals("—", chart.delta)
    }

    @Test
    fun `90 days are 13 weeks over the last 91 days, each the mean of its weighed days`() {
        val chart = weightChart(
            listOf(
                m("a", "2026-10-16T07:00:00+02:00", kg = 72.0, fat = 21.0),
                m("b", "2026-10-18T07:00:00+02:00", kg = 73.0, fat = 22.0),
            ),
            SuiviPeriod.QUARTER, today, zone,
        )

        assertEquals(13, chart.columns.size)
        assertEquals("Sem. du 23 juil.", chart.columns.first().label)
        assertEquals("Sem. du 15 oct.", chart.columns.last().label)
        assertEquals("23 juil.", chart.from)
        assertEquals(72.5, chart.columns.last().weightKg!!, 1e-9)
        assertEquals(21.5, chart.columns.last().bodyFatPercent!!, 1e-9)
        // A week is never set aside as a whole.
        assertNull(chart.columns.last().retained)
    }

    @Test
    fun `1 year is the twelve calendar months up to today's`() {
        val chart = weightChart(emptyList(), SuiviPeriod.YEAR, today, zone)

        assertEquals(12, chart.columns.size)
        assertEquals("Novembre 2025", chart.columns.first().label)
        assertEquals("Octobre 2026", chart.columns.last().label)
        assertEquals("nov. 25", chart.from)
        assertEquals("oct. 26", chart.to)
        assertEquals(21, chart.columns.last().days.size)
    }

    @Test
    fun `the axis says the trend's change across the period, with a true minus`() {
        val chart = weightChart(
            listOf(
                m("first", "2026-09-22T07:00:00+02:00", kg = 73.6),
                m("last", "2026-10-21T07:00:00+02:00", kg = 72.4),
            ),
            SuiviPeriod.MONTH, today, zone,
        )

        assertEquals("−1,2 kg", chart.delta)
        assertEquals(72.0, chart.range!!.start, 1e-9)
        assertEquals(74.0, chart.range!!.endInclusive, 1e-9)
    }

    @Test
    fun `numbers are written the French way, one decimal`() {
        assertEquals("72,4", formatKg(72.44))
        assertEquals("72,5", formatKg(72.45))
        assertEquals("70,0", formatKg(70.0))
        assertEquals("mer. 21 oct.", dayLabel(today))
    }

    @Test
    fun `the list is asked from the first of the month eleven months back, less six days for its trend`() {
        assertEquals(LocalDate.of(2025, 10, 26), historyStart(today))
        assertEquals("2025-10-26T00:00:00+02:00", historyStartParameter(today, zone))
    }
}
