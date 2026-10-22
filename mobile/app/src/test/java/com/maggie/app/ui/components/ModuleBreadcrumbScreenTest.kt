package com.maggie.app.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The module header « Finance › Revue ▾ » and its single row of sub-views (MAG-537), drawn on their own.
 * What each Finance screen puts in them is `FinanceTopBarScreenTest`'s; what a tap does to the back stack
 * is `FinanceNavigationTest`'s and the Maestro journeys'.
 */
@RunWith(AndroidJUnit4::class)
class ModuleBreadcrumbScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val sections = listOf(
        BreadcrumbSection("dashboard", "Dashboard"),
        BreadcrumbSection("accounts", "Comptes"),
        BreadcrumbSection("review", "Revue"),
        BreadcrumbSection("budget", "Budget"),
        BreadcrumbSection("treasury", "Trésorerie"),
        BreadcrumbSection("planning", "Planification"),
        BreadcrumbSection("settings", "Configuration"),
    )

    private val chosen = mutableListOf<String>()
    private var drawerOpened = 0

    private fun setBreadcrumb(currentKey: String = "review") {
        compose.setContent {
            ModuleBreadcrumb(
                module = "Finance",
                sections = sections,
                currentKey = currentKey,
                onSelectSection = { chosen += it.key },
                onOpenDrawer = { drawerOpened++ },
            )
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the header reads Finance, a chevron and the current section`() {
        setBreadcrumb("review")

        compose.onNodeWithTag(UiTags.NAV_MENU).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Finance")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertIsDisplayed()
        compose.onNode(hasTestTag(UiTags.BREADCRUMB_SECTION).and(hasText("Revue"))).assertExists()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the section button says where it is and what it does to a screen reader`() {
        setBreadcrumb("review")

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION)
            .assertContentDescriptionEquals("Finance, section Revue : changer de section")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the section button is a 48 dp touch target`() {
        setBreadcrumb("review")

        val bounds = compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertEquals(true, bounds.height / density >= 47.9f)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the burger opens the drawer`() {
        setBreadcrumb()

        compose.onNodeWithTag(UiTags.NAV_MENU).performClick()

        assertEquals(1, drawerOpened)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the menu is closed until the section is tapped`() {
        setBreadcrumb()

        compose.onNodeWithTag(UiTags.BREADCRUMB_MENU).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap on the section opens the seven sections, the current one checked`() {
        setBreadcrumb("review")

        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()

        compose.onNodeWithTag(UiTags.BREADCRUMB_MENU).assertIsDisplayed()
        sections.forEach { compose.onNodeWithTag(UiTags.breadcrumbMenu(it.key)).assertExists() }
        compose.onNodeWithTag(UiTags.breadcrumbMenu("review")).assertIsSelected()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("dashboard")).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("settings")).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the menu rows are radio items of at least 48 dp`() {
        setBreadcrumb("review")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()

        val density = compose.density.density
        sections.forEach {
            val node = compose.onNodeWithTag(UiTags.breadcrumbMenu(it.key)).fetchSemanticsNode()
            assertEquals(
                androidx.compose.ui.semantics.Role.RadioButton,
                node.config[SemanticsProperties.Role],
            )
            assertEquals(true, node.boundsInRoot.height / density >= 47.9f)
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `choosing a section reports it and closes the menu`() {
        setBreadcrumb("review")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()

        compose.onNodeWithTag(UiTags.breadcrumbMenu("treasury")).performClick()

        assertEquals(listOf("treasury"), chosen)
        compose.onNodeWithTag(UiTags.BREADCRUMB_MENU).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap outside the menu closes it and chooses nothing`() {
        setBreadcrumb("review")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()

        compose.onNodeWithTag(UiTags.BREADCRUMB_SCRIM).performClick()

        assertEquals(emptyList<String>(), chosen)
        compose.onNodeWithTag(UiTags.BREADCRUMB_MENU).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet draws the same header`() {
        setBreadcrumb("budget")

        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Finance")
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION)
            .assertContentDescriptionEquals("Finance, section Budget : changer de section")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a detail bar has a back arrow and a title, and no sections menu`() {
        var back = 0
        compose.setContent { ModuleDetailBar(title = "Matelas", onBack = { back++ }) }

        compose.onNodeWithText("Matelas").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).assertDoesNotExist()
        compose.onNode(hasContentDescription("Retour")).performClick()

        assertEquals(1, back)
    }

    private val items = listOf(
        SubviewItem("all", "Toutes", 3),
        SubviewItem("to_qualify", "À qualifier", 2),
        SubviewItem("recognised", "Reconnues", 1),
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the row has one pill per sub-view, the selected one marked, with the counts`() {
        compose.setContent { SubviewRow(items = items, selectedKey = "to_qualify", onSelect = {}) }

        compose.onNodeWithTag(UiTags.SUBVIEW_ROW).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.subview("all")).assertTextEquals("Toutes · 3")
        compose.onNodeWithTag(UiTags.subview("to_qualify")).assertTextEquals("À qualifier · 2").assertIsSelected()
        compose.onNodeWithTag(UiTags.subview("recognised")).assertTextEquals("Reconnues · 1").assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a pill without a count shows its label alone`() {
        compose.setContent {
            SubviewRow(
                items = listOf(SubviewItem("month", "Mois"), SubviewItem("outside", "Hors budget")),
                selectedKey = "month",
                onSelect = {},
            )
        }

        compose.onNodeWithTag(UiTags.subview("outside")).assertTextEquals("Hors budget")
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap on a pill reports it`() {
        val picked = mutableListOf<String>()
        compose.setContent { SubviewRow(items = items, selectedKey = "all", onSelect = { picked += it.key }) }

        compose.onNodeWithTag(UiTags.subview("recognised")).performClick()

        assertEquals(listOf("recognised"), picked)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a pill is 36 dp high`() {
        compose.setContent { SubviewRow(items = items, selectedKey = "all", onSelect = {}) }

        val bounds = compose.onNodeWithTag(UiTags.subview("all")).fetchSemanticsNode().boundsInRoot
        assertEquals(36f, bounds.height / compose.density.density, 0.6f)
        assertEquals(1, compose.onAllNodesWithTag(UiTags.SUBVIEW_ROW).fetchSemanticsNodes().size)
    }
}
