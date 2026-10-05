package com.maggie.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import com.maggie.app.ui.components.MaggieNavigationRail
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.components.RAIL_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shell, drawn in the six formats of MAG-35 — on the JVM.
 *
 * `WindowLayoutTest` says what [appLayoutFor] decides; this says the frame is really
 * built that way, which a constant cannot. `@Config(qualifiers = …)` is what makes a
 * 1280 dp window out of a unit test: Robolectric writes the configuration
 * [rememberAppLayout] reads, so the shell composes the way it would on the device.
 * Per `agent-os/standards/mobile/screen-tests.md` the emulator keeps only what a JVM
 * cannot be, and « which navigation does an 800 dp window get » is not that.
 *
 * The shell is assembled here the way `NavGraph` assembles it; *what* to assemble is
 * `chromeFor`'s answer, and that is unit-tested in `AdaptiveNavigationTest`.
 */
@RunWith(AndroidJUnit4::class)
class AppShellScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private var navigatedTo: String? = null

    @Composable
    private fun Shell(route: String = "cookbook") {
        val layout = rememberAppLayout()
        val hasRail = layout.navigation == NavigationKind.RAIL

        AppShell(
            drawerState = rememberDrawerState(DrawerValue.Closed),
            drawerGesturesEnabled = true,
            drawer = {
                AppDrawerContent(currentRoute = route, onNavigate = {}, onCloseDrawer = {})
            },
            rail = if (hasRail) {
                { MaggieNavigationRail(currentRoute = route, onNavigate = { navigatedTo = it }) }
            } else {
                null
            },
            chatPanel = if (layout.chatPanelFits) {
                { Box(Modifier.width(CHAT_PANEL_WIDTH).testTag(UiTags.CHAT_PANEL)) { Text("Maggie") } }
            } else {
                null
            },
        ) {
            Scaffold(
                modifier = Modifier.weight(1f),
                topBar = {
                    MaggieTopBar(title = "Cuisine", onMenuClick = if (hasRail) null else { {} })
                },
                bottomBar = { if (!layout.chatPanelFits) ChatBottomBar(onOpenChat = {}) },
            ) {
                Box(Modifier.fillMaxSize()) { Text("Chili sin carne") }
            }
        }
    }

    @Test
    @Config(qualifiers = "w412dp-h1000dp-xhdpi")
    fun `a narrow very tall phone keeps the burger and the collapsed bar`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_MENU).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.NAV_RAIL).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertDoesNotExist()
        compose.onNodeWithText("Chili sin carne").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w374dp-h840dp-xhdpi")
    fun `a foldable closed is the phone layout`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_MENU).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.NAV_RAIL).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w280dp-h290dp-xhdpi")
    fun `the cover screen of a Flip is the phone layout, not a cropped tablet`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_MENU).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.NAV_RAIL).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w674dp-h841dp-xhdpi")
    fun `a foldable opened flat trades the burger for the rail`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_RAIL).assertExists()
        compose.onNodeWithTag(UiTags.NAV_MENU).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tablet in portrait draws the rail and keeps the collapsed bar`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_RAIL).assertExists()
        compose.onNodeWithTag(UiTags.NAV_MENU).assertDoesNotExist()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertDoesNotExist()
        compose.onNodeWithText("Chili sin carne").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `a tablet in landscape draws the rail and the permanent panel instead of the bar`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_RAIL).assertExists()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).assertDoesNotExist()
        compose.onNodeWithText("Chili sin carne").assertIsDisplayed()
    }

    /** The rail is left of the content and the conversation right of it — « is it visible » cannot say that. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the content sits between the rail and the conversation`() {
        compose.setContent { Shell() }

        val railX = compose.onNodeWithTag(UiTags.NAV_RAIL).fetchSemanticsNode().positionInRoot.x
        val contentX = compose.onNodeWithText("Chili sin carne").fetchSemanticsNode().positionInRoot.x
        val panelX = compose.onNodeWithTag(UiTags.CHAT_PANEL).fetchSemanticsNode().positionInRoot.x

        assertEquals(
            "the shell is not laid out left to right",
            listOf("rail", "contenu", "chat"),
            listOf("rail" to railX, "contenu" to contentX, "chat" to panelX)
                .sortedBy { it.second }
                .map { it.first },
        )
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `the rail carries every drawer destination plus the settings`() {
        compose.setContent { Shell() }

        RAIL_DESTINATIONS.forEach { destination ->
            compose.onNodeWithTag(UiTags.railItem(destination.route)).assertExists()
        }
        assertEquals(DRAWER_DESTINATIONS.size + 1, RAIL_DESTINATIONS.size)
        compose.onNodeWithTag(UiTags.railItem("settings")).assertExists()
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `tapping a rail entry asks for the route it names`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.railItem("grocery")).performClick()

        assertEquals("grocery", navigatedTo)
    }
}
