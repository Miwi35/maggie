package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeTest {

    @Test
    fun `budgetPeriodLabel names the month of a monthly envelope`() {
        assertEquals("Juillet 2026", budgetPeriodLabel("monthly", 2026, 7))
    }

    @Test
    fun `budgetPeriodLabel names the year of an annual envelope`() {
        assertEquals("Année 2026", budgetPeriodLabel("annual", 2026, null))
    }

    @Test
    fun `budgetPeriodLabel falls back to the year when a monthly envelope has no month`() {
        assertEquals("Année 2026", budgetPeriodLabel("monthly", 2026, null))
    }

    @Test
    fun `budgetModeLabel translates the two API modes`() {
        assertEquals("Mensuel", budgetModeLabel("monthly"))
        assertEquals("Annuel", budgetModeLabel("annual"))
        assertEquals("unknown", budgetModeLabel("unknown"))
    }

    @Test
    fun `consumedFraction reports the spent share of the budget`() {
        assertEquals(0.5f, consumedFraction(20000, 40000), 0.001f)
    }

    @Test
    fun `consumedFraction clamps an overspent budget to one`() {
        assertEquals(1f, consumedFraction(50000, 40000), 0.001f)
    }

    @Test
    fun `consumedFraction treats a zero budget as full once anything is spent`() {
        assertEquals(1f, consumedFraction(100, 0), 0.001f)
        assertEquals(0f, consumedFraction(0, 0), 0.001f)
    }

    private fun line(id: String, path: String, parent: String? = null) = BudgetLine(
        id = id,
        categoryId = "cat-$id",
        categoryName = path.substringAfterLast(" › "),
        categoryPath = path,
        parentEnvelopeId = parent,
    )

    @Test
    fun `nestBudgetLines puts each envelope under its mother with the path seen from her`() {
        val rows = nestBudgetLines(
            listOf(
                line("obsydian", "Loisirs › Bars › Obsydian", parent = "loisirs"),
                line("groceries", "Alimentation"),
                line("loisirs", "Loisirs"),
            ),
        )

        assertEquals(listOf("groceries", "loisirs", "obsydian"), rows.map { it.line.id })
        assertEquals(listOf(1, 1, 2), rows.map { it.depth })
        assertEquals(listOf("Alimentation", "Loisirs", "Bars › Obsydian"), rows.map { it.label })
    }

    @Test
    fun `nestBudgetLines nests three envelopes of one chain`() {
        val rows = nestBudgetLines(
            listOf(
                line("bars", "Loisirs › Bars", parent = "loisirs"),
                line("obsydian", "Loisirs › Bars › Obsydian", parent = "bars"),
                line("loisirs", "Loisirs"),
            ),
        )

        assertEquals(listOf("loisirs", "bars", "obsydian"), rows.map { it.line.id })
        assertEquals(listOf(1, 2, 3), rows.map { it.depth })
        assertEquals(listOf("Loisirs", "Bars", "Obsydian"), rows.map { it.label })
    }

    @Test
    fun `nestBudgetLines keeps at the top an envelope whose mother is not listed`() {
        val rows = nestBudgetLines(listOf(line("obsydian", "Loisirs › Bars › Obsydian", parent = "gone")))

        assertEquals(1, rows.single().depth)
        assertEquals("Loisirs › Bars › Obsydian", rows.single().label)
    }

    @Test
    fun `nestBudgetLines falls back to the category name when the server sent no path`() {
        val rows = nestBudgetLines(listOf(BudgetLine(id = "e", categoryId = "c", categoryName = "Courses")))

        assertEquals("Courses", rows.single().label)
    }

    @Test
    fun `the threshold field takes blank for the default and 1 to 99 (MAG-49)`() {
        assertEquals(AlertThresholdInput.Valid(null), AlertThresholdInput.parse("  "))
        assertEquals(AlertThresholdInput.Valid(90), AlertThresholdInput.parse("90"))
        assertEquals(AlertThresholdInput.Valid(75), AlertThresholdInput.parse("75 %"))
        assertEquals(AlertThresholdInput.Invalid, AlertThresholdInput.parse("0"))
        assertEquals(AlertThresholdInput.Invalid, AlertThresholdInput.parse("100"))
        assertEquals(AlertThresholdInput.Invalid, AlertThresholdInput.parse("huit"))
    }

    @Test
    fun `a budget line without a threshold reads the default (MAG-49)`() {
        assertEquals(80, BudgetLine(id = "e", categoryId = "c", categoryName = "Loisirs").alertThresholdPercent)
    }

    private val winter = Season(fromMonth = 11, toMonth = 2, amountCents = 4500)

    @Test
    fun `a season may cross the new year`() {
        assertEquals(listOf(11, 12, 1, 2), winter.months)
        assertEquals(listOf(7), Season(7, 7, 0).months)
    }

    @Test
    fun `seasonLabel names a season by its months`() {
        assertEquals("saison nov.–févr.", seasonLabel(winter))
        assertEquals("saison juil.", seasonLabel(Season(7, 7, 0)))
    }

    @Test
    fun `overlappingMonth finds the month two seasons share`() {
        assertEquals(2, overlappingMonth(listOf(winter, Season(2, 3, 3000))))
        assertEquals(null, overlappingMonth(listOf(winter, Season(7, 8, 1000))))
    }

    @Test
    fun `budgetCaption gives the month's amount and its season`() {
        val line = BudgetLine(
            id = "e", categoryId = "c", categoryName = "Cinéma",
            amountCents = 4500, year = 2026, month = 11, season = winter,
        )

        assertEquals("Novembre 2026 · ${formatCents(4500)} · saison nov.–févr.", budgetCaption(line))
        assertEquals("Mars 2027", budgetCaption(line.copy(month = 3, year = 2027, amountCents = 2000, season = null)))
    }
}
