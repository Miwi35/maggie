package com.maggie.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthBalanceTest {

    @Test
    fun `the lines to qualify give a pill with their count and weight`() {
        assertEquals(
            "À qualifier · 3 · ${formatCents(6520)}",
            toQualifyLabel(BalanceToQualify(count = 3, cents = 6520)),
        )
    }

    @Test
    fun `nothing to qualify gives no pill`() {
        assertNull(toQualifyLabel(BalanceToQualify()))
    }

    @Test
    fun `each envelope status is said in words with its glyph`() {
        assertEquals("✓ Tenue", envelopeStatusWord("held"))
        assertEquals("▲ Proche", envelopeStatusWord("close"))
        assertEquals("✕ Dépassée", envelopeStatusWord("over"))
    }
}
