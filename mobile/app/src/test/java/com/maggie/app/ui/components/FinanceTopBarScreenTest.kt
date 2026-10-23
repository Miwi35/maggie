package com.maggie.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.FinanceNav
import com.maggie.app.ui.navigation.FinanceSection
import com.maggie.app.ui.navigation.FinanceSubview
import com.maggie.app.ui.navigation.FinanceSubviews
import com.maggie.app.ui.navigation.LocalFinanceNav
import com.maggie.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a Finance screen puts in the header (MAG-537): the breadcrumb on a page, the one row of the section's
 * sub-views when it has some, a back arrow and a title on a detail. The seven sections and their sub-views are
 * `FinanceNavigationTest`'s; the menu itself is `ModuleBreadcrumbScreenTest`'s.
 */
@RunWith(AndroidJUnit4::class)
class FinanceTopBarScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val sections = mutableListOf<FinanceSection>()
    private val subviewsPicked = mutableListOf<Pair<FinanceSection, String>>()
    private val held = FinanceSubviews()

    private fun setBar(route: String) {
        compose.setContent {
            val nav = FinanceNav(
                subviews = held,
                onSelectSection = { sections += it },
                onSelectSubview = { section: FinanceSection, subview: FinanceSubview ->
                    subviewsPicked += section to subview.key
                    if (subview.route == null) held.select(section, subview.key)
                },
                onOpenDrawer = {},
            )
            CompositionLocalProvider(LocalFinanceNav provides nav) {
                FinanceTopBar(route) { Text("Barre de titre") }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a page reads Finance, a chevron and its section, and draws no title bar of its own`() {
        setBar(Screen.ReviewList.route)

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Finance")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION)
            .assertContentDescriptionEquals("Finance, section Revue : changer de section")
        compose.onNodeWithText("Barre de titre").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a detail screen draws its own bar and no breadcrumb`() {
        setBar(Screen.Cushion.route)

        compose.onNodeWithText("Barre de titre").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.SUBVIEW_ROW).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the menu of a page lists the seven sections with the page's own checked`() {
        setBar(Screen.BudgetList.route)

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()

        FinanceSection.entries.forEach { compose.onNodeWithTag(UiTags.breadcrumbMenu(it.key)).assertExists() }
        compose.onNodeWithTag(UiTags.breadcrumbMenu("budget")).assertIsSelected()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("review")).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `choosing a section in the menu asks the graph for it`() {
        setBar(Screen.FinanceDashboard.route)

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("planning")).performClick()

        assertEquals(listOf(FinanceSection.PLANNING), sections)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `Comptes has a header and no row`() {
        setBar(Screen.AccountList.route)

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SUBVIEW_ROW).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `Trésorerie has a header and no row`() {
        setBar(Screen.Treasury.route)

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SUBVIEW_ROW).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the dashboard row is État courant and À venir, the first selected`() {
        setBar(Screen.FinanceDashboard.route)

        compose.onNodeWithTag(UiTags.subview("current")).assertTextEquals("État courant").assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("upcoming")).assertTextEquals("À venir").assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap on À venir selects it and the row follows`() {
        setBar(Screen.FinanceDashboard.route)

        compose.onNodeWithTag(UiTags.subview("upcoming")).performClick()

        assertEquals(listOf(FinanceSection.DASHBOARD to "upcoming"), subviewsPicked)
        compose.onNodeWithTag(UiTags.subview("upcoming")).assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("current")).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the budget row is Mois and Hors budget`() {
        setBar(Screen.BudgetList.route)

        compose.onNodeWithTag(UiTags.subview("month")).assertTextEquals("Mois").assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("outside")).assertTextEquals("Hors budget")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a sub-view held by the graph is the one shown, as after a rotation`() {
        held.select(FinanceSection.BUDGET, "outside")

        setBar(Screen.BudgetList.route)

        compose.onNodeWithTag(UiTags.subview("outside")).assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("month")).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the planning row is Projets, Prêts and Indépendance, selected by the route`() {
        setBar(Screen.LoanList.route)

        compose.onNodeWithTag(UiTags.subview("projects")).assertTextEquals("Projets").assertIsNotSelected()
        compose.onNodeWithTag(UiTags.subview("loans")).assertTextEquals("Prêts").assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("independence")).assertTextEquals("Indépendance").assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap on a sub-view that is a screen asks the graph to go there`() {
        setBar(Screen.ProjectList.route)

        compose.onNodeWithTag(UiTags.subview("independence")).performClick()

        assertEquals(listOf(FinanceSection.PLANNING to "independence"), subviewsPicked)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the configuration row is Catégories and Banques`() {
        setBar(Screen.BankConnectionList.route)

        compose.onNodeWithTag(UiTags.subview("categories")).assertTextEquals("Catégories").assertIsNotSelected()
        compose.onNodeWithTag(UiTags.subview("banks")).assertTextEquals("Banques").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a screen that supplies its own row, as the Revue does with its counts, replaces the section's`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            FinanceTopBar(
                route = Screen.ReviewList.route,
                subviews = SubviewBar(
                    items = listOf(SubviewItem("all", "Toutes", 3), SubviewItem("to_qualify", "À qualifier", 2)),
                    selectedKey = "all",
                    onSelect = { picked += it.key },
                ),
            )
        }

        compose.onNodeWithTag(UiTags.subview("all")).assertTextEquals("Toutes · 3").assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("to_qualify")).performClick()

        assertEquals(listOf("to_qualify"), picked)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a page drawn without the graph keeps its own sub-view`() {
        compose.setContent { FinanceTopBar(Screen.FinanceDashboard.route) }

        compose.onNodeWithTag(UiTags.subview("upcoming")).performClick()

        compose.onNodeWithTag(UiTags.subview("upcoming")).assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet has the same header and the same row, and no rail`() {
        setBar(Screen.ReviewList.route)

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Finance")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.SUBVIEW_ROW).assertIsDisplayed()
    }
}
