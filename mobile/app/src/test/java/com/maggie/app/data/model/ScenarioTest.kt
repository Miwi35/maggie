package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/** The words and figures of the scenarios (MAG-551): statements only, never a verdict. */
class ScenarioTest {

    private val rapid = Scenario(id = "auto-rapid", name = "Rapide", kind = "auto", nDate = "2039-05")
    private val balanced = Scenario(id = "auto-balanced", name = "Équilibré", kind = "auto", nDate = "2041-03", current = true)
    private val mine = Scenario(id = "s1", name = "Mon plan", kind = "user", status = "saved")
    private val advice = Scenario(id = "adv1", name = "Idée", kind = "advisor")

    @Test
    fun `a month is written in words`() {
        assertEquals("mars 2041", scenarioMonthLabel("2041-03"))
        assertEquals("décembre 2027", scenarioMonthLabel("2027-12"))
        assertEquals("not a month", scenarioMonthLabel("not a month"))
    }

    @Test
    fun `N is the month, or not reached`() {
        assertEquals("N : mars 2041", scenarioNLabel("2041-03"))
        assertEquals("N non atteinte avant 20 ans", scenarioNLabel(null))
    }

    @Test
    fun `the current title names the scenario, or says there is none`() {
        assertEquals("Scénario courant · Équilibré", scenarioCurrentTitle(balanced))
        assertEquals("Aucun scénario courant", scenarioCurrentTitle(null))
        assertEquals("N : mars 2041", scenarioCurrentSubtitle(balanced))
        assertEquals("Choisir un scénario", scenarioCurrentSubtitle(null))
    }

    @Test
    fun `a draft says what it is`() {
        assertEquals("Automatique · brouillon", scenarioKindLabel(rapid))
        assertEquals("Suggestion du conseiller · brouillon", scenarioKindLabel(advice))
        assertEquals("Brouillon", scenarioKindLabel(mine.copy(status = "draft")))
        assertNull(scenarioKindLabel(mine))
    }

    @Test
    fun `the list is read current first, then the owner's, the automatic ones, the suggestions`() {
        val ordered = orderedScenarios(listOf(advice, rapid, mine, balanced))

        assertEquals(listOf("auto-balanced", "s1", "auto-rapid", "adv1"), ordered.map { it.id })
    }

    @Test
    fun `a copy is named after its source, and never twice the same`() {
        assertEquals("Rapide (copie)", scenarioCopyName("Rapide", listOf("Rapide")))
        assertEquals("Rapide (copie 2)", scenarioCopyName("Rapide", listOf("Rapide", "Rapide (copie)")))
        assertEquals("Rapide (copie 3)", scenarioCopyName("Rapide", listOf("Rapide (copie)", "Rapide (copie 2)")))
    }

    @Test
    fun `the table has a column per scenario and no column marked best`() {
        val rows = scenarioTableRows(
            listOf(
                rapid.copy(metrics = ScenarioMetrics(projectsDoneBy = "2029-08", debtsEndDate = "2029-05", interestPaid = 123400, wellbeing = 8)),
                mine,
            ),
        )

        assertEquals(listOf("Date N", "Projets bouclés", "Dettes soldées", "Intérêts payés", "Bien-être"), rows.map { it.label })
        assertTrue(rows.all { it.cells.size == 2 })
        assertEquals(listOf("mai 2039", "Non atteinte"), rows[0].cells)
        assertEquals(listOf("août 2029", "Au-delà de l'horizon"), rows[1].cells)
        assertEquals(listOf("mai 2029", "—"), rows[2].cells)
        assertEquals(formatCents(123400), rows[3].cells[0])
        assertEquals("8", rows[4].cells[0])
    }

    @Test
    fun `the curves share one axis from the earliest to the latest month`() {
        val a = rapid.copy(curve = listOf(CoveragePoint("2026-10", 0.2), CoveragePoint("2027-10", 0.6)))
        val b = balanced.copy(curve = listOf(CoveragePoint("2026-12", 0.1), CoveragePoint("2028-10", 1.0)))

        val span = scenarioCurveSpan(listOf(a, b))!!

        assertEquals(YearMonth.of(2026, 10), span.first)
        assertEquals(YearMonth.of(2028, 10), span.last)
        assertEquals(24, span.months)
        assertEquals(0f, span.position("2026-10")!!, 0.001f)
        assertEquals(0.5f, span.position("2027-10")!!, 0.001f)
        assertEquals(1f, span.position("2028-10")!!, 0.001f)
        assertNull(span.position("nope"))
    }

    @Test
    fun `no curve at all is no span`() {
        assertNull(scenarioCurveSpan(listOf(rapid, balanced)))
    }

    @Test
    fun `a single month sits in the middle of the axis`() {
        val span = scenarioCurveSpan(listOf(rapid.copy(curve = listOf(CoveragePoint("2026-10", 0.2)))))!!

        assertEquals(0.5f, span.position("2026-10")!!, 0.001f)
    }

    @Test
    fun `a curve is described for a screen reader`() {
        val described = scenarioCurveDescription(
            rapid.copy(curve = listOf(CoveragePoint("2026-10", 0.2), CoveragePoint("2039-05", 1.0))),
        )

        assertEquals("Rapide : 20 % en octobre 2026, 100 % en mai 2039. 100 % atteint en mai 2039.", described)
        assertEquals("Mon plan : aucune courbe", scenarioCurveDescription(mine))
        assertTrue(scenarioCurveDescription(mine.copy(curve = listOf(CoveragePoint("2026-10", 0.2)))).endsWith("100 % non atteint avant 20 ans."))
    }

    @Test
    fun `the gap of N is the API's sentence, absent for the reference`() {
        val comparison = ScenarioComparison(
            referenceId = "auto-balanced",
            differences = listOf(ScenarioDifference("auto-rapid", "auto-balanced", months = -22, sentence = "N 22 mois plus tôt que « Équilibré »")),
        )

        assertEquals("N 22 mois plus tôt que « Équilibré »", comparison.differenceOf("auto-rapid"))
        assertNull(comparison.differenceOf("auto-balanced"))
    }

    @Test
    fun `the projects of a scenario are read in its order`() {
        val scenario = mine.copy(projects = listOf(ScenarioProject("b", 1), ScenarioProject("a", 0)))

        assertEquals(listOf("a", "b"), scenario.projectIds)
    }
}
