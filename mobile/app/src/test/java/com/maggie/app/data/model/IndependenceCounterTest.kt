package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class IndependenceCounterTest {

    private fun counter(
        coveragePercent: Int = 80,
        gapCents: Int = 2000,
        isMeasurable: Boolean = true,
        hasPassiveIncomeCategories: Boolean = true,
        isReached: Boolean = false,
    ) = IndependenceCounter(
        coveragePercent = coveragePercent,
        lifestyleCents = 10000,
        passiveIncomeCents = 8000,
        gapCents = gapCents,
        isMeasurable = isMeasurable,
        hasPassiveIncomeCategories = hasPassiveIncomeCategories,
        isReached = isReached,
    )

    @Test
    fun `a coverage under 100 says the percentage and what is missing`() {
        assertEquals(
            "80 % du train de vie — il manque ${formatCents(2000)} par mois",
            independenceSummary(counter()),
        )
    }

    @Test
    fun `once the rentes cover the train de vie it says so`() {
        assertEquals(
            "200 % — les rentes couvrent le train de vie",
            independenceSummary(counter(coveragePercent = 200, gapCents = 0, isReached = true)),
        )
    }

    /** 0 % would be a verdict; there is simply no denominator yet. */
    @Test
    fun `nothing measured yet is said instead of zero percent`() {
        assertEquals(
            "Pas encore de train de vie mesuré",
            independenceSummary(counter(coveragePercent = 0, isMeasurable = false)),
        )
    }

    @Test
    fun `no rente declared points at what to do, not at a figure`() {
        assertEquals(
            "Aucune catégorie déclarée comme rente",
            independenceSummary(
                counter(coveragePercent = 0, hasPassiveIncomeCategories = false),
            ),
        )
    }

    @Test
    fun `a category says whether it is a rente, and a recette is named`() {
        val rent = Category(id = "1", name = "Loyers perçus", obligation = "income", passiveIncome = true)
        val salary = Category(id = "2", name = "Salaire", obligation = "income")

        assertEquals("Recette · rente", categoryKindLabel(rent))
        assertEquals("Recette", categoryKindLabel(salary))
    }
}
