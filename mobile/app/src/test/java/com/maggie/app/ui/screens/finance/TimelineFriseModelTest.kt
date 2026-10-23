package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.TimelineEvent
import com.maggie.app.data.model.formatCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The API's frise as the screen draws it (MAG-554): colours from the categories, months on the 240-month axis. */
class TimelineFriseModelTest {

    @Test
    fun `a category colour is the API's hex, opaque`() {
        assertEquals(0xFFB8DE6F.toInt(), timelineCategoryColor("#B8DE6F"))
        assertEquals(0xFF7FD1AE.toInt(), timelineCategoryColor("#7FD1AE"))
    }

    @Test
    fun `a colour that cannot be read is the neutral grey, not a crash`() {
        assertEquals(0xFFABA6B8.toInt(), timelineCategoryColor(""))
        assertEquals(0xFFABA6B8.toInt(), timelineCategoryColor("#12"))
        assertEquals(0xFFABA6B8.toInt(), timelineCategoryColor("#GGGGGG"))
    }

    @Test
    fun `the first month is the left edge and the last month of the 20 years the right one`() {
        assertEquals(0, timelineMonthIndex("2026-10", "2026-10", 240))
        assertEquals(5, timelineMonthIndex("2026-10", "2027-03", 240))
        assertEquals(239, timelineMonthIndex("2026-10", "2046-09", 240))
    }

    @Test
    fun `a month outside the horizon is pulled to the nearest edge`() {
        assertEquals(239, timelineMonthIndex("2026-10", "2050-01", 240))
        assertEquals(0, timelineMonthIndex("2026-10", "2026-01", 240))
    }

    @Test
    fun `the events come in the order of their month, each with its category's colour and label`() {
        val frise = friseOf(TimelineFixtures.alone())
        val line = frise.lines.single()

        assertEquals(listOf("project_purchase", "debt_end", "independence_reached"), line.events.map { it.type })
        assertEquals(listOf(5, 10, 230), line.events.map { it.monthIndex })
        assertEquals(listOf(0xFFB8DE6F.toInt(), 0xFF8FB8FF.toInt(), 0xFF7FD1AE.toInt()), line.events.map { it.color })
        assertEquals(listOf("Projets", "Dettes", "Indépendance"), line.events.map { it.categoryLabel })
        assertTrue(line.events.last().isIndependence)
        assertFalse(line.events.first().isIndependence)
    }

    @Test
    fun `the followed line says its N, the independence event sits on the same month as the marker`() {
        val line = friseOf(TimelineFixtures.alone()).lines.single()

        assertEquals("Suivi", line.role)
        assertEquals("Mon plan", line.name)
        assertEquals("Date N · décembre 2045", line.nLabel)
        assertEquals(230, line.nIndex)
        assertEquals(line.nIndex, line.events.single { it.isIndependence }.monthIndex)
        assertEquals("décembre 2045", line.events.single { it.isIndependence }.monthLabel)
    }

    @Test
    fun `a compared scenario is a second line on the same axis, with the API's sentence about the gap`() {
        val frise = friseOf(TimelineFixtures.compared())

        assertEquals(listOf("Suivi", "Comparé"), frise.lines.map { it.role })
        assertEquals(listOf(false, true), frise.lines.map { it.isCompared })
        assertEquals("Date N · septembre 2043", frise.lines[1].nLabel)
        assertEquals(203, frise.lines[1].nIndex)
        assertEquals("N 2 ans 3 mois plus tôt que « Mon plan »", frise.difference)
    }

    @Test
    fun `alone, the frise carries no gap and no second line`() {
        val frise = friseOf(TimelineFixtures.alone())

        assertEquals(1, frise.lines.size)
        assertNull(frise.difference)
        assertEquals("Hypothèses : matelas intact, revenus stables, sans vacance locative ni imprévu", frise.assumptions)
        assertEquals(6, frise.legend.size)
    }

    @Test
    fun `N not reached within the 20 years is said, with no marker`() {
        val unreached = TimelineFixtures.alone().let { it.copy(current = it.current.copy(nDate = null, events = emptyList())) }
        val line = friseOf(unreached).lines.single()

        assertEquals("Date N non atteinte d'ici 20 ans", line.nLabel)
        assertNull(line.nIndex)
        assertTrue(line.events.isEmpty())
    }

    @Test
    fun `the figures of an event are said once each, and an event without any says nothing`() {
        val purchase = friseOf(TimelineFixtures.alone()).lines.single().events.first()
        val debt = friseOf(TimelineFixtures.alone()).lines.single().events[1]
        val reached = friseOf(TimelineFixtures.alone()).lines.single().events.last()

        assertEquals("${formatCents(250000)} · date N 3 mois plus tard", purchase.figures)
        assertEquals("budget mensuel +${formatCents(18000)} · date N 2 mois plus tôt", debt.figures)
        assertNull(reached.figures)
    }

    @Test
    fun `a payment lowers the monthly budget and an event that does not move N says so`() {
        val event = TimelineEvent(id = "e", month = "2027-01", type = "early_repayment", monthlyImpactEur = -250.0, nImpactMonths = 0)

        assertEquals("budget mensuel ${formatCents(-25000)} · date N inchangée", eventFigures(event))
    }

    @Test
    fun `no verdict word is ever added to what the API says`() {
        val frise = friseOf(TimelineFixtures.compared())
        val words = frise.lines.flatMap { line -> line.events.flatMap { listOfNotNull(it.label, it.sublabel, it.figures) } + line.nLabel + line.role }
        val said = (words + listOfNotNull(frise.difference, frise.assumptions)).joinToString(" ").lowercase()

        listOf("meilleur", "recommand", "optimal").forEach { assertFalse(it, said.contains(it)) }
    }
}
