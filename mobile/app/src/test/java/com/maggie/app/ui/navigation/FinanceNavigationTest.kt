package com.maggie.app.ui.navigation

import androidx.compose.runtime.saveable.SaverScope
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceNavigationTest {
    private val legacyDrawerRoutes = listOf(
        Screen.AccountList, Screen.CategoryList, Screen.BudgetList,
        Screen.Cushion, Screen.LoanList, Screen.ReviewList,
    ).map { it.route }

    private val allRoutes = FinanceSection.entries.flatMap { it.pages + it.details }

    private val saverScope = SaverScope { true }

    @Test
    fun `the drawer has one finance entry and it opens the finance dashboard`() {
        val finance = DRAWER_DESTINATIONS.filter { it.label == "Finance" }

        assertEquals(listOf(Screen.FinanceDashboard.route), finance.map { it.route })
        assertTrue(DRAWER_DESTINATIONS.none { it.route in legacyDrawerRoutes })
    }

    @Test
    fun `there are seven sections, in the order of the admin`() {
        assertEquals(
            listOf("dashboard", "accounts", "review", "budget", "treasury", "planning", "settings"),
            FinanceSection.entries.map { it.key },
        )
        assertEquals(
            listOf("Dashboard", "Comptes", "Revue", "Budget", "Trésorerie", "Planification", "Configuration"),
            FinanceSection.entries.map { it.label },
        )
    }

    @Test
    fun `each section offers exactly the sub-views of the mock-ups, and Comptes and Trésorerie none`() {
        fun labels(section: FinanceSection) = section.subviews.map { it.label }

        assertEquals(listOf("État courant", "À venir"), labels(FinanceSection.DASHBOARD))
        assertEquals(emptyList<String>(), labels(FinanceSection.ACCOUNTS))
        assertEquals(listOf("Toutes", "À qualifier", "Reconnues"), labels(FinanceSection.REVIEW))
        assertEquals(listOf("Mois", "Hors budget"), labels(FinanceSection.BUDGET))
        assertEquals(emptyList<String>(), labels(FinanceSection.TREASURY))
        // « Placements » (MAG-555) came after the mock-ups: its summary card is gone, so it is a sub-view.
        assertEquals(listOf("Projets", "Prêts", "Placements", "Indépendance"), labels(FinanceSection.PLANNING))
        assertEquals(listOf("Catégories", "Banques"), labels(FinanceSection.SETTINGS))
    }

    @Test
    fun `a sub-view that has a screen of its own is a page of its section, the others are states of its home`() {
        FinanceSection.entries.forEach { section ->
            section.subviews.mapNotNull { it.route }.forEach {
                assertTrue("$it should be a page of ${section.key}", it in section.pages)
            }
        }
        assertTrue(FinanceSection.DASHBOARD.subviews.all { it.route == null })
        assertTrue(FinanceSection.REVIEW.subviews.all { it.route == null })
        assertTrue(FinanceSection.BUDGET.subviews.all { it.route == null })
        assertTrue(FinanceSection.PLANNING.subviews.all { it.route != null })
        assertTrue(FinanceSection.SETTINGS.subviews.all { it.route != null })
    }

    @Test
    fun `every part of the finance module belongs to exactly one section`() {
        assertTrue(allRoutes.containsAll(legacyDrawerRoutes))
        assertTrue(Screen.CategorizationRuleList.route in allRoutes)
        assertTrue(Screen.BankConnectionList.route in allRoutes)
        assertTrue(Screen.RecurringOperations.route in allRoutes)
        assertTrue(Screen.Treasury.route in allRoutes)
        assertTrue(Screen.SavingsAdvances.route in allRoutes)
        assertTrue(Screen.Independence.route in allRoutes)
        assertEquals(allRoutes.size, allRoutes.toSet().size)
    }

    @Test
    fun `a section opens on its own first page`() {
        FinanceSection.entries.forEach { assertEquals(it.pages.first(), it.home) }
        assertEquals(Screen.FinanceDashboard.route, FinanceSection.DASHBOARD.home)
        assertEquals(Screen.AccountList.route, FinanceSection.ACCOUNTS.home)
        assertEquals(Screen.ReviewList.route, FinanceSection.REVIEW.home)
        assertEquals(Screen.BudgetList.route, FinanceSection.BUDGET.home)
        assertEquals(Screen.Treasury.route, FinanceSection.TREASURY.home)
        assertEquals(Screen.ProjectList.route, FinanceSection.PLANNING.home)
        assertEquals(Screen.CategoryList.route, FinanceSection.SETTINGS.home)
    }

    @Test
    fun `a screen is found under the section it belongs to`() {
        assertEquals(FinanceSection.TREASURY, FinanceSection.of(Screen.Cushion.route))
        assertEquals(FinanceSection.TREASURY, FinanceSection.of(Screen.SavingsAdvances.route))
        assertEquals(FinanceSection.REVIEW, FinanceSection.of(Screen.RecurringOperations.route))
        assertEquals(FinanceSection.REVIEW, FinanceSection.of(Screen.CategorizationRuleList.route))
        assertEquals(FinanceSection.PLANNING, FinanceSection.of(Screen.LoanList.route))
        assertEquals(FinanceSection.PLANNING, FinanceSection.of(Screen.ProjectList.route))
        assertEquals(FinanceSection.PLANNING, FinanceSection.of(Screen.Independence.route))
        // The project form is full screen: no section, so no breadcrumb, row or chat band around it (MAG-519).
        assertNull(FinanceSection.of(Screen.ProjectForm.route))
        // So is the loan sheet (MAG-523).
        assertNull(FinanceSection.of(Screen.LoanDetail.route))
        // And the project sheet (MAG-525).
        assertNull(FinanceSection.of(Screen.ProjectSheet.route))
        assertEquals(FinanceSection.PLANNING, FinanceSection.of(Screen.PlacementList.route))
        // The placement form is full screen too (MAG-555).
        assertNull(FinanceSection.of(Screen.PlacementForm.route))
        assertEquals(FinanceSection.SETTINGS, FinanceSection.of(Screen.CategoryList.route))
        assertEquals(FinanceSection.ACCOUNTS, FinanceSection.of(Screen.AccountTransactions.route))
        assertEquals(FinanceSection.ACCOUNTS, FinanceSection.of(Screen.Contacts.route))
        assertEquals(FinanceSection.ACCOUNTS, FinanceSection.of(Screen.PeerDebts.route))
        assertNull(FinanceSection.of(Screen.Home.route))
        assertNull(FinanceSection.of(null))
    }

    @Test
    fun `only the pages of a section wear the breadcrumb, the screens behind an icon keep a back arrow`() {
        assertEquals(FinanceSection.TREASURY, FinanceSection.pageOf(Screen.Treasury.route))
        assertEquals(FinanceSection.REVIEW, FinanceSection.pageOf(Screen.ReviewList.route))
        assertNull(FinanceSection.pageOf(Screen.Cushion.route))
        assertNull(FinanceSection.pageOf(Screen.SavingsAdvances.route))
        assertNull(FinanceSection.pageOf(Screen.AccountTransactions.route))
        assertNull(FinanceSection.pageOf(Screen.CategorizationRuleList.route))
        assertNull(FinanceSection.pageOf(Screen.RecurringOperations.route))
        assertNull(FinanceSection.pageOf(null))
    }

    @Test
    fun `the sub-view of a route is found for the sections whose sub-views are routes`() {
        assertEquals("loans", FinanceSection.PLANNING.subviewOf(Screen.LoanList.route)?.key)
        assertEquals("placements", FinanceSection.PLANNING.subviewOf(Screen.PlacementList.route)?.key)
        assertEquals("banks", FinanceSection.SETTINGS.subviewOf(Screen.BankConnectionList.route)?.key)
        assertNull(FinanceSection.REVIEW.subviewOf(Screen.ReviewList.route))
        assertEquals("outside", FinanceSection.BUDGET.subview("outside")?.key)
        assertNull(FinanceSection.BUDGET.subview("nowhere"))
    }

    @Test
    fun `every part can still be opened from outside`() {
        val withoutLink = setOf(Screen.AccountTransactions.route)

        allRoutes.filter { it !in withoutLink }.forEach {
            assertTrue("$it lost its deep link", DeepLinks.patternsFor(it).isNotEmpty())
        }
    }

    @Test
    fun `the Revue's single list is the home of the review section, with a deep link`() {
        assertEquals("finance_review_list", Screen.ReviewList.route)
        assertEquals("Revue", Screen.ReviewList.label)
        assertEquals(FinanceSection.REVIEW, FinanceSection.of(Screen.ReviewList.route))
        assertEquals(Screen.ReviewList.route, FinanceSection.REVIEW.home)
        assertTrue(FinanceSection.REVIEW.pages.none { it in listOf("transactions_to_sort", "recognised_to_control", "monthly_review") })
    }

    @Test
    fun `a tap on a section goes to its home and, with the dashboard underneath, back to it`() {
        FinanceSection.entries.forEach { section ->
            assertEquals(
                FinanceSectionAction(section.home, popToDashboard = true),
                financeSectionAction(section, dashboardInBackStack = true),
            )
        }
    }

    @Test
    fun `a tap on a section replaces the current screen when the dashboard is not underneath`() {
        assertEquals(
            FinanceSectionAction(Screen.ReviewList.route, popToDashboard = false),
            financeSectionAction(FinanceSection.REVIEW, dashboardInBackStack = false),
        )
    }

    @Test
    fun `back goes to the dashboard it was opened from`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceDashboard.route))
    }

    @Test
    fun `back goes to the finance page a screen belongs to`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.CategoryList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.Treasury.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.ReviewList.route))
        // Suggestions are opened from the rules screen, which is where back goes.
        assertEquals(FinanceBack.POP, financeBackAction(Screen.CategorizationRuleList.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.RuleSuggestions.route))
    }

    @Test
    fun `back from the recurring operations goes to the dashboard, or to the account its transaction came from`() {
        assertEquals(FinanceBack.POP, financeBackAction(Screen.FinanceDashboard.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountTransactions.route))
        assertEquals(FinanceBack.POP, financeBackAction(Screen.AccountList.route))
    }

    @Test
    fun `a screen opened by a link is swapped for the dashboard on back`() {
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(Screen.Home.route))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(Screen.Loading.route))
        assertEquals(FinanceBack.REPLACE_WITH_DASHBOARD, financeBackAction(null))
    }

    @Test
    fun `a section that was never touched shows its first state sub-view`() {
        val subviews = FinanceSubviews()

        assertEquals("current", subviews.current(FinanceSection.DASHBOARD).key)
        assertEquals("month", subviews.current(FinanceSection.BUDGET).key)
    }

    @Test
    fun `the sub-view chosen in a section is kept, and sections do not share it`() {
        val subviews = FinanceSubviews()

        subviews.select(FinanceSection.BUDGET, "outside")

        assertEquals("outside", subviews.current(FinanceSection.BUDGET).key)
        assertEquals("current", subviews.current(FinanceSection.DASHBOARD).key)
    }

    @Test
    fun `an unknown sub-view falls back to the first`() {
        val subviews = FinanceSubviews()

        subviews.select(FinanceSection.DASHBOARD, "nowhere")

        assertEquals("current", subviews.current(FinanceSection.DASHBOARD).key)
    }

    @Test
    fun `the sub-views survive a rotation, saved and restored`() {
        val subviews = FinanceSubviews()
        subviews.select(FinanceSection.DASHBOARD, "upcoming")
        subviews.select(FinanceSection.BUDGET, "outside")

        val saved = with(FinanceSubviews.Saver) { saverScope.save(subviews) }!!
        val restored = FinanceSubviews.Saver.restore(saved)!!

        assertEquals("upcoming", restored.current(FinanceSection.DASHBOARD).key)
        assertEquals("outside", restored.current(FinanceSection.BUDGET).key)
    }
}
