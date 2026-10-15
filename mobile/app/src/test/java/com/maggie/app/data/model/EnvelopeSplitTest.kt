package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvelopeSplitTest {

    // Loisirs 300 €: Bars 100 €, Cinéma 50 €, 150 € free.
    private val loisirs = splitSharesOf(listOf(10000, 5000), 30000)

    @Test
    fun `the free part is what the daughters leave in the mother`() {
        assertEquals(SplitShares(listOf(10000, 5000), 15000), loisirs)
    }

    @Test
    fun `moving the Bars-Cinema boundary by 20 euros gives Bars 120, Cinema 30, free 150`() {
        assertEquals(SplitShares(listOf(12000, 3000), 15000), loisirs.moveBoundary(0, 2000))
    }

    @Test
    fun `the last boundary trades with the free part`() {
        assertEquals(SplitShares(listOf(10000, 7000), 13000), loisirs.moveBoundary(1, 2000))
    }

    @Test
    fun `a boundary cannot push a neighbour below zero nor past the mother`() {
        assertEquals(SplitShares(listOf(15000, 0), 15000), loisirs.moveBoundary(0, 99999))
        assertEquals(SplitShares(listOf(0, 15000), 15000), loisirs.moveBoundary(0, -99999))
        assertEquals(SplitShares(listOf(10000, 20000), 0), loisirs.moveBoundary(1, 99999))
    }

    @Test
    fun `an over-allocated mother can only give back on its last boundary`() {
        // Loisirs 100 €, Bars 200 €, Cinéma 10 €: free is -110 €.
        val over = splitSharesOf(listOf(20000, 1000), 10000)

        assertEquals(SplitShares(listOf(20000, 1000), -11000), over.moveBoundary(1, 500))
        assertEquals(SplitShares(listOf(20000, 500), -10500), over.moveBoundary(1, -500))
    }

    @Test
    fun `an unknown boundary changes nothing`() {
        assertEquals(loisirs, loisirs.moveBoundary(5, 2000))
    }

    @Test
    fun `daughters are the envelopes directly below a mother for the same mode`() {
        fun line(id: String, parent: String?, mode: String = "monthly") =
            BudgetLine(id = id, categoryId = id, categoryName = id, parentEnvelopeId = parent, mode = mode)
        val mother = line("loisirs", null)
        val lines = listOf(
            mother,
            line("bars", "loisirs"),
            line("cinema", "loisirs"),
            line("annual-one", "loisirs", mode = "annual"),
            line("obsydian", "bars"),
        )

        assertEquals(listOf("bars", "cinema"), daughtersOf(mother, lines).map { it.id })
    }
}
