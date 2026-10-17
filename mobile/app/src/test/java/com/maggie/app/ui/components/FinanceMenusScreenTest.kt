package com.maggie.app.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.FinanceMenu
import com.maggie.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The seven Finance menus, drawn (MAG-426): tabs under the title bar on a phone, a rail from
 * 600 dp. Which routes belong to which menu is `FinanceNavigationTest`'s; what a tap does to
 * the back stack is the `NavGraph`'s and the Maestro journey's.
 */
@RunWith(AndroidJUnit4::class)
class FinanceMenusScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val selected = mutableListOf<FinanceMenu>()

    private fun setPhoneContent(route: String) {
        compose.setContent {
            CompositionLocalProvider(LocalFinanceMenuSelection provides { selected += it }) {
                FinanceTopBar(route) { Text("Barre de titre") }
            }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a phone draws the title bar and the seven menus under it`() {
        setPhoneContent(Screen.FinanceDashboard.route)

        compose.onNodeWithText("Barre de titre").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_MENU_TABS).assertIsDisplayed()
        FinanceMenu.entries.forEach {
            compose.onNodeWithTag(UiTags.financeMenu(it.key)).assertExists()
        }
        compose.onNodeWithTag(UiTags.FINANCE_MENU_RAIL).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `the active menu is highlighted, and a screen of a menu highlights that menu`() {
        setPhoneContent(Screen.SavingsAdvances.route)

        compose.onNodeWithTag(UiTags.financeMenu("treasury")).assertIsSelected()
        compose.onNodeWithTag(UiTags.financeMenu("dashboard")).assertIsNotSelected()
        compose.onNodeWithTag(UiTags.financeMenu("settings")).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a tap on a menu asks for that menu`() {
        setPhoneContent(Screen.FinanceDashboard.route)

        compose.onNodeWithTag(UiTags.financeMenu("planning")).performScrollTo().performClick()

        assertEquals(listOf(FinanceMenu.PLANNING), selected)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `the row scrolls the active menu back on screen when it opens at the far end`() {
        setPhoneContent(Screen.BankConnectionList.route)

        compose.onNodeWithTag(UiTags.financeMenu("settings")).assertIsSelected().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun `a screen drawn without the graph has no tabs`() {
        compose.setContent { FinanceTopBar(Screen.Cushion.route) { Text("Barre de titre") } }

        compose.onNodeWithText("Barre de titre").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.FINANCE_MENU_TABS).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet has no tabs, the same menus are its rail`() {
        compose.setContent {
            CompositionLocalProvider(LocalFinanceMenuSelection provides { selected += it }) {
                FinanceTopBar(Screen.Cushion.route) { Text("Barre de titre") }
                FinanceNavigationRail(currentRoute = Screen.Cushion.route, onSelect = { selected += it })
            }
        }

        compose.onNodeWithTag(UiTags.FINANCE_MENU_TABS).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.FINANCE_MENU_RAIL).assertIsDisplayed()
        FinanceMenu.entries.forEach {
            compose.onNodeWithTag(UiTags.financeMenu(it.key)).assertExists()
        }
        compose.onNodeWithTag(UiTags.financeMenu("treasury")).assertIsSelected()
        compose.onNodeWithTag(UiTags.financeMenu("review")).assertIsNotSelected()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tap on the rail asks for that menu`() {
        compose.setContent {
            FinanceNavigationRail(currentRoute = Screen.FinanceDashboard.route, onSelect = { selected += it })
        }

        compose.onNodeWithTag(UiTags.financeMenu("review")).performClick()

        assertEquals(listOf(FinanceMenu.REVIEW), selected)
    }
}
