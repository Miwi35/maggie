package com.maggie.app.data.model

import com.maggie.app.ui.screens.finance.treasuryChartDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TreasuryTest {

    private val forecast = TreasuryOutlook(
        overdraft = TreasuryOverdraft(isForecast = true, onDate = "2026-10-20", worstBalanceCents = -30000),
        balanceBeforeSalary = BalanceBeforeSalary(onDate = "2026-10-24", balanceCents = -25000),
    )

    @Test
    fun `the banner carries the overdraft date and the balance the eve of the salary`() {
        assertEquals(TreasuryBannerState("2026-10-20", -25000, "2026-10-24"), forecast.toBanner())
    }

    @Test
    fun `no forecast overdraft, no banner`() {
        assertNull(forecast.copy(overdraft = TreasuryOverdraft()).toBanner())
    }

    @Test
    fun `an overdraft without the balance before the salary, no banner`() {
        assertNull(forecast.copy(balanceBeforeSalary = null).toBanner())
    }

    @Test
    fun `the delay says jour for one and jours for more`() {
        assertEquals("Salaire en retard de 1 jour", salaryDelayLabel(1))
        assertEquals("Salaire en retard de 5 jours", salaryDelayLabel(5))
    }

    @Test
    fun `a date reads in French, and an unreadable one stays as it came`() {
        assertEquals("1 octobre", treasuryDate("2026-10-01"))
        assertEquals("20 octobre", treasuryDate("2026-10-20"))
        assertEquals("bientôt", treasuryDate("bientôt"))
    }

    @Test
    fun `the advance is prepared as an outflow from the source account, spent, dated today`() {
        val advice = TreasuryAdvice(amountCents = 70000, sourceAccountId = "s1", sourceAccountName = "Livret A", sourceAmountCents = 50000)

        assertEquals(
            TransactionPrefill("Avance vers le compte courant", -50000, "spent", "2026-10-13"),
            advice.toPrefill("2026-10-13"),
        )
    }

    @Test
    fun `no source account, nothing to prepare`() {
        assertNull(TreasuryAdvice(amountCents = 70000, uncoveredCents = 70000).toPrefill("2026-10-13"))
    }

    @Test
    fun `the warning keeps the wording the spec fixes`() {
        assertEquals("entame le matelas, il se reconstituera quand tu rendras l'avance", CUSHION_ADVANCE_WARNING)
    }

    @Test
    fun `the chart is described by its range, its lowest day and the overdraft zone`() {
        val days = listOf(
            TreasuryDay("2026-10-13", 10000),
            TreasuryDay("2026-10-20", -30000),
            TreasuryDay("2026-10-24", 5000),
        )

        val text = treasuryChartDescription(days, limitCents = 0)

        assertTrue(text, text.contains("du 13 octobre au 24 octobre"))
        assertTrue(text, text.contains("Plus bas : ${formatCents(-30000)} le 20 octobre"))
        assertTrue(text, text.contains("Zone de découvert atteinte le 20 octobre"))
        assertTrue(treasuryChartDescription(days, limitCents = 50000).contains("non atteinte"))
    }

    @Test
    fun `the form opens with the advance as an expense of the amount typed without its sign`() {
        val form = TransactionPrefill(ADVANCE_LABEL, -50000, "spent", "2026-10-13").toFormValues(LocalDate.of(2000, 1, 1))

        assertEquals(TransactionFormValues(ADVANCE_LABEL, "500.00", false, "spent", LocalDate.of(2026, 10, 13)), form)
    }

    @Test
    fun `a credit opens as an income and an unreadable date falls back`() {
        val form = TransactionPrefill("Retour", 1250, "spent", "demain").toFormValues(LocalDate.of(2026, 10, 14))

        assertEquals("12.50", form.amountText)
        assertTrue(form.isIncome)
        assertEquals(LocalDate.of(2026, 10, 14), form.date)
    }
}
