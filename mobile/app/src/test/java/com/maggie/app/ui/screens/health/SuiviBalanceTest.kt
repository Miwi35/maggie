package com.maggie.app.ui.screens.health

import com.maggie.app.data.model.DailyBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuiviBalanceTest {
    private fun day(balance: Int?, expended: Int? = 1_662) =
        DailyBalance("2026-10-21", eatenKcal = 1_500, plannedKcal = 480, expendedKcal = expended, balanceKcal = balance)

    @Test
    fun `the value is signed with a true minus and the word follows the zone`() {
        assertEquals("−764 kcal", suiviBalanceValue(day(-764)))
        assertEquals("Déficit", suiviBalanceWord(-764))
        assertEquals("+318 kcal", suiviBalanceValue(day(318)))
        assertEquals("Excédent", suiviBalanceWord(318))
        assertEquals("Équilibre", suiviBalanceWord(100))
        assertEquals("0 kcal", suiviBalanceValue(day(0)))
        assertNull(suiviBalanceWord(null))
        assertEquals("800 kcal", suiviBalanceValue(DailyBalance("2026-10-21", eatenKcal = 500, plannedKcal = 300)))
    }

    @Test
    fun `the figures read apport then dépense, prévu compris`() {
        assertEquals("apport 1 980 · dépense 1 662", suiviBalanceFigures(day(318)))
        assertEquals("apport 800", suiviBalanceFigures(DailyBalance("2026-10-21", eatenKcal = 500, plannedKcal = 300)))
    }
}
