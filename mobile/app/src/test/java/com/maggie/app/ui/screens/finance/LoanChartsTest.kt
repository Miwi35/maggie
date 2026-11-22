package com.maggie.app.ui.screens.finance

import com.maggie.app.data.model.DebtTimeline
import com.maggie.app.data.model.MonthlyRelief
import com.maggie.app.data.model.formatCentsRounded
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class LoanChartsTest {

    private fun timeline(vararg reliefs: MonthlyRelief) = DebtTimeline(reliefByMonth = reliefs.toList())

    @Test
    fun `reliefSteps spans 60 months at least and steps at each relief`() {
        val (months, steps) = reliefSteps(
            timeline(MonthlyRelief("2028-06", 21000, 21000), MonthlyRelief("2029-03", 9500, 30500)),
            YearMonth.of(2026, 11),
        )

        assertEquals(60, months)
        assertEquals(listOf(ReliefStep(19, 21000), ReliefStep(28, 30500)), steps)
    }

    @Test
    fun `reliefSteps reaches the last relief beyond 60 months`() {
        val (months, _) = reliefSteps(timeline(MonthlyRelief("2034-03", 9500, 9500)), YearMonth.of(2026, 11))

        assertEquals(89, months)
    }

    @Test
    fun `reliefDescription lists each cumulative step`() {
        val description = reliefDescription(
            timeline(MonthlyRelief("2028-06", 21000, 21000), MonthlyRelief("2029-03", 9500, 30500)),
        )

        assertEquals("Mensualités libérées cumulées : ${formatCentsRounded(21000)} en juin 2028, ${formatCentsRounded(30500)} en mars 2029", description)
    }
}
