package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LoanTest {

    @Test
    fun `formatRate renders basis points as a percentage`() {
        assertEquals("3,50 %", formatRate(350))
        assertEquals("0,00 %", formatRate(0))
    }

    @Test
    fun `formatLoanMonth names the month the API returns`() {
        assertEquals("Mars 2027", formatLoanMonth("2027-03"))
    }

    @Test
    fun `formatLoanMonth falls back rather than breaking`() {
        assertEquals("—", formatLoanMonth(null))
        assertEquals("nonsense", formatLoanMonth("nonsense"))
    }

    @Test
    fun `formatRemaining counts in months under a year`() {
        assertEquals("7 mois", formatRemaining(7))
    }

    @Test
    fun `formatRemaining counts in years and months beyond`() {
        assertEquals("1 an", formatRemaining(12))
        assertEquals("2 ans et 6 mois", formatRemaining(30))
    }

    @Test
    fun `formatRemaining says so when the loan outlives the horizon`() {
        assertEquals("Au-delà de l'horizon", formatRemaining(null))
    }
}
