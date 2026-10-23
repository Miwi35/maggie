package com.maggie.app.ui.screens.home

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import com.maggie.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Accueil (MAG-560): the selector, what it hands to the cards, and the order they are stacked in. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun card(module: String, zone: HomeZone = HomeZone.MAIN, order: Int = HomeModules.orderOf(module)) =
        HomeCardEntry(module, zone, order) { day -> Text("$module:${day.apiValue}") }

    /**
     * The segment's own click action: under Robolectric a synthetic touch does not select a segmented
     * button, whereas the real tap does (`24-home` and `17c-tasks-evening` tap them on a device).
     */
    private fun selectSegment(tag: String) {
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun top(text: String) = compose.onNodeWithText(text, substring = true).getUnclippedBoundsInRoot().top

    @Test
    fun `the Accueil is called Accueil, in the navigation and in the top bar`() {
        assertEquals("Accueil", Screen.Home.label)
        assertEquals("Accueil", DRAWER_DESTINATIONS.first { it.route == Screen.Home.route }.label)
    }

    @Test
    fun `an empty registry leaves the selector alone, opened on Aujourd'hui`() {
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = emptyList()) }

        compose.onNodeWithTag(UiTags.HOME).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_DAY_SELECTOR).assertIsDisplayed()
        compose.onNodeWithText("Aujourd'hui").assertIsDisplayed()
        compose.onNodeWithText("Demain").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.HOME_DAY_TODAY).assertIsSelected()
        compose.onNodeWithTag(UiTags.HOME_DAY_TOMORROW).assertIsNotSelected()
    }

    @Test
    fun `the segments of the selector are at least 48 dp tall`() {
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = emptyList()) }

        compose.onNodeWithTag(UiTags.HOME_DAY_TODAY).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag(UiTags.HOME_DAY_TOMORROW).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun `choosing Demain hands tomorrow to every card, and Aujourd'hui hands today back`() {
        val registry = listOf(card(HomeModules.TASKS), card(HomeModules.AGENDA))
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry) }

        compose.onNodeWithText("tasks:today").assertIsDisplayed()
        compose.onNodeWithText("agenda:today").assertIsDisplayed()

        selectSegment(UiTags.HOME_DAY_TOMORROW)

        compose.onNodeWithTag(UiTags.HOME_DAY_TOMORROW).assertIsSelected()
        compose.onNodeWithText("tasks:tomorrow").assertIsDisplayed()
        compose.onNodeWithText("agenda:tomorrow").assertIsDisplayed()

        selectSegment(UiTags.HOME_DAY_TODAY)

        compose.onNodeWithText("tasks:today").assertIsDisplayed()
        compose.onNodeWithText("agenda:today").assertIsDisplayed()
    }

    @Test
    fun `the action cards come first, then the others in the spec's order`() {
        val registry = listOf(
            card(HomeModules.LEISURE),
            card(HomeModules.FINANCE),
            card(HomeModules.AGENDA),
            card(HomeModules.TASKS),
            card(HomeModules.HEALTH, zone = HomeZone.ACTION),
        )
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry) }

        val tops = listOf("health:", "tasks:", "agenda:", "finance:", "leisure:").map { top(it) }

        assertEquals(tops.sorted(), tops)
        assertEquals(5, tops.toSet().size)
    }

    @Test
    fun `the order of the modules is the spec's`() {
        assertEquals(
            listOf("tasks", "agenda", "grocery", "nutrition", "health", "finance", "leisure"),
            HomeModules.ORDER,
        )
        assertTrue(HomeModules.orderOf("unknown") > HomeModules.orderOf(HomeModules.LEISURE))
    }

    @Test
    fun `a card that draws nothing takes no place between the others`() {
        val withHole = listOf(
            card(HomeModules.TASKS),
            HomeCardEntry(HomeModules.AGENDA, HomeZone.MAIN) { },
            card(HomeModules.FINANCE),
        )
        val withoutHole = listOf(card(HomeModules.TASKS), card(HomeModules.FINANCE))
        var registry by mutableStateOf(withHole)
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry) }
        val gap = top("finance:") - top("tasks:")

        registry = withoutHole
        compose.waitForIdle()

        assertEquals(gap, top("finance:") - top("tasks:"))
    }
}
