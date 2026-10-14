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
}
