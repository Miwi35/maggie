package com.maggie.app.ui.navigation

import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceNavigationTest {
    private val legacyDrawerRoutes = listOf(
        Screen.AccountList, Screen.CategoryList, Screen.BudgetList,
        Screen.Cushion, Screen.LoanList, Screen.MonthlyReview,
    ).map { it.route }

    @Test
    fun `the drawer has one finance entry and it opens the finance dashboard`() {
        val finance = DRAWER_DESTINATIONS.filter { it.label == "Finance" }

        assertEquals(listOf(Screen.FinanceDashboard.route), finance.map { it.route })
        assertTrue(DRAWER_DESTINATIONS.none { it.route in legacyDrawerRoutes })
    }

    @Test
    fun `every part of the finance module is one tap from the dashboard`() {
        val routes = FINANCE_ACCESSES.map { it.route }

        assertTrue(routes.containsAll(legacyDrawerRoutes))
        assertTrue(Screen.CategorizationRuleList.route in routes)
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `every access can still be opened from outside`() {
        FINANCE_ACCESSES.forEach {
            assertTrue("${it.route} lost its deep link", DeepLinks.patternsFor(it.route).isNotEmpty())
        }
    }

    @Test
    fun `accesses are ordered daily then monthly then rarely used`() {
        val order = FINANCE_ACCESSES.map { it.frequency.ordinal }

        assertEquals(order.sorted(), order)
        assertEquals(
            listOf(Screen.BudgetList.route, Screen.AccountList.route),
            financeAccessesBy(FinanceFrequency.DAILY).map { it.route },
        )
        assertEquals(
            listOf(Screen.MonthlyReview.route, Screen.Cushion.route),
            financeAccessesBy(FinanceFrequency.MONTHLY).map { it.route },
        )
        assertEquals(
            listOf(Screen.LoanList.route, Screen.CategoryList.route, Screen.CategorizationRuleList.route),
            financeAccessesBy(FinanceFrequency.RARE).map { it.route },
        )
    }

    @Test
    fun `back goes to the dashboard it was opened from`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceDashboard.route))
    }

    @Test
    fun `back goes to the finance list a screen belongs to`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.CategoryList.route))
    }

    @Test
    fun `a screen opened by a link is swapped for the dashboard on back`() {
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(Screen.Dashboard.route))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(Screen.Loading.route))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(null))
    }

    @Test
    fun `each access has its own tag`() {
        assertEquals("finance_access_budgets", UiTags.financeAccess(Screen.BudgetList.route))
    }
}
