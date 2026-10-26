package com.maggie.app.ui.screens.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTheme
import com.maggie.app.ui.theme.MaggieTokens
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The two frames a module's card is drawn in (MAG-560). */
@RunWith(AndroidJUnit4::class)
class HomeCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `a card draws its title and its content`() {
        compose.setContent {
            HomeCard(title = "Tâches", module = HomeModules.TASKS, onOpen = {}) { Text("Appeler le garage") }
        }

        compose.onNodeWithText("Tâches").assertIsDisplayed()
        compose.onNodeWithText("Appeler le garage").assertIsDisplayed()
    }

    @Test
    fun `a tap on the header opens the module, a tap on the content does not`() {
        var opened = 0
        compose.setContent {
            HomeCard(title = "Tâches", module = HomeModules.TASKS, onOpen = { opened++ }) { Text("Appeler le garage") }
        }

        compose.onNodeWithText("Appeler le garage").performClick()
        assertEquals(0, opened)

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.TASKS)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the header is a button, at least 48 dp tall`() {
        compose.setContent {
            HomeCard(title = "Finance", module = HomeModules.FINANCE, onOpen = {}) { }
        }

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.FINANCE))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `an action card says what and when, and its pill runs the action`() {
        var done = 0
        compose.setContent {
            HomeActionCard(
                icon = Icons.Default.TaskAlt,
                title = "Ta journée",
                moment = "ce matin",
                actionLabel = "Planifier",
                onAction = { done++ },
            )
        }

        compose.onNodeWithText("Ta journée").assertIsDisplayed()
        compose.onNodeWithText("ce matin").assertIsDisplayed()
        compose.onNodeWithText("Planifier").performClick()

        assertEquals(1, done)
    }

    @Test
    fun `every module has a colour, Cuisine and Courses sharing the charter's blue, the accent for an unknown one`() {
        val colors = mutableMapOf<String, Long>()
        compose.setContent {
            (HomeLayout.cards(HomeFormat.PHONE, HomeSlot.CARD) + "unknown").forEach { colors[it] = homeModuleColor(it).value.toLong() }
        }
        compose.waitForIdle()

        assertEquals(HomeLayout.cards(HomeFormat.PHONE, HomeSlot.CARD).size + 1, colors.size)
        assertEquals(HomeLayout.cards(HomeFormat.PHONE, HomeSlot.CARD).size, colors.values.toSet().size)
    }

    @Test
    fun `the Accueil cards wear the charter's hue of their module on the dark Veilleuse`() {
        val colors = mutableMapOf<String, Color>()
        compose.setContent {
            MaggieTheme("dark") {
                listOf(HomeModules.TASKS, HomeModules.GROCERY, HomeModules.NUTRITION, HomeModules.AGENDA, HomeModules.FINANCE)
                    .forEach { colors[it] = homeModuleColor(it) }
            }
        }
        compose.waitForIdle()

        assertEquals(MaggieTokens.Module.taches.second, colors.getValue(HomeModules.TASKS))
        assertEquals(MaggieTokens.Module.cuisine.second, colors.getValue(HomeModules.GROCERY))
        assertEquals(MaggieTokens.Module.cuisine.second, colors.getValue(HomeModules.NUTRITION))
        assertEquals(MaggieTokens.Module.agenda.second, colors.getValue(HomeModules.AGENDA))
        assertEquals(MaggieTokens.Module.finance.second, colors.getValue(HomeModules.FINANCE))
    }
}
