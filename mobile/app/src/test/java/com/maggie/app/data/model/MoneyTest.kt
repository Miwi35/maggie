package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test
    fun `an exact amount has a thin space between thousands, a decimal comma and the symbol after`() {
        assertEquals("2 743,60 €", formatCents(274360))
        assertEquals("4 950,00 €", formatCents(495000))
        assertEquals("1 234 567,89 €", formatCents(123456789))
        assertEquals("0,05 €", formatCents(5))
        assertEquals("0,00 €", formatCents(0))
    }

    @Test
    fun `a negative amount takes a real minus sign`() {
        assertEquals("−120,00 €", formatCents(-12000))
        assertEquals("−0,05 €", formatCents(-5))
    }

    @Test
    fun `a rounded amount has no decimals`() {
        assertEquals("9 482 €", formatCentsRounded(948200))
        assertEquals("9 483 €", formatCentsRounded(948250))
        assertEquals("9 482 €", formatCentsRounded(948249))
        assertEquals("74 430 €", formatCentsRounded(7443007L))
        assertEquals("−120 €", formatCentsRounded(-12049))
    }

    @Test
    fun `an amount that rounds to zero carries no sign`() {
        assertEquals("0 €", formatCentsRounded(-49))
        assertEquals("0 €", formatCentsRounded(0))
    }

    @Test
    fun `the symbol of the currency is shown, never its code`() {
        val dollars = formatCents(1200, "USD")
        assertTrue(dollars.startsWith("12,00\u00A0"))
        assertFalse(dollars.contains("USD"))
        assertEquals("12,00 €", formatCents(1200, "EUR"))
    }

    @Test
    fun `a missing, unknown or XXX currency falls back to the euro sign`() {
        listOf("XXX", "xxx", "", " ", null, "ZZZZ").forEach { currency ->
            val text = formatCents(1200, currency)
            assertEquals("12,00 €", text)
            assertFalse(text.contains("XXX"))
        }
        assertEquals("12 €", formatCentsRounded(1200, "XXX"))
    }
}
