package com.maggie.app.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.AppDrawerContent
import com.maggie.app.ui.components.ChatBottomBar
import com.maggie.app.ui.components.ChatRailActions
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import com.maggie.app.ui.components.MaggieNavigationRail
import com.maggie.app.ui.components.MaggieTopBar
import com.maggie.app.ui.components.RAIL_DESTINATIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private companion object {
        const val CONTENT = "contenu"
    }

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
                {
                    MaggieNavigationRail(
                        currentRoute = route,
                        onNavigate = { navigatedTo = it },
                        chatAction = if (layout.chatEntry == ChatEntry.RAIL) {
                            { ChatRailActions(onOpenChat = {}) }
                        } else {
                            null
                        },
                    )
                }
            } else {
                null
            },
            chatPanel = if (layout.chatEntry == ChatEntry.PANEL) {
                { Box(Modifier.width(CHAT_PANEL_WIDTH).testTag(UiTags.CHAT_PANEL)) { Text("Maggie") } }
            } else {
                null
            },
        ) {
            Scaffold(
                modifier = Modifier.weight(1f),
                // The same insets `NavGraph` gives its own Scaffold: without the band
                // under the content nothing else consumes the bottom one. Under
                // Robolectric they measure zero, so what the assertions read is the
                // chrome's height — but the frame has to be the real one to read it.
                contentWindowInsets = if (layout.chatEntry == ChatEntry.RAIL) {
                    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                } else {
                    WindowInsets(0)
                },
                topBar = {
                    MaggieTopBar(
                        title = "Cuisine",
                        onMenuClick = if (hasRail) null else { {} },
                        dense = layout.denseTopBar,
                    )
                },
                bottomBar = { if (layout.chatEntry == ChatEntry.BOTTOM_BAR) ChatBottomBar(onOpenChat = {}) },
            ) { paddingValues ->
                // Padded like `NavGraph` pads its `NavHost`: what is measured below is
                // the room the chrome leaves, not the whole column.
                Box(Modifier.padding(paddingValues).fillMaxSize().testTag(CONTENT)) {
                    Text("Chili sin carne")
                }
            }
        }
    }

    /** The height the content is actually given, in dp — what the chrome did not take. */
    private fun contentHeightDp(): Int {
        val heightPx = compose.onNodeWithTag(CONTENT).fetchSemanticsNode().size.height
        return (heightPx / compose.density.density).toInt()
    }

    /**
     * Where the content starts, in dp — which *is* the top bar's height.
     *
     * The system insets measure zero under Robolectric and the `Scaffold`'s top bar is
     * the only thing above the content, so the offset of the content node is the bar,
     * measured without a test tag inside `MaggieTopBar`.
     */
    private fun contentTopDp(): Int {
        val topPx = compose.onNodeWithTag(CONTENT).fetchSemanticsNode().positionInRoot.y
        return (topPx / compose.density.density).toInt()
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

    /**
     * What the recette refused (MAG-35): « en mode paysage sur mobile, entre le header
     * et le chat de maggie, on n'a que très peu d'espace pour le contenu ».
     *
     * 411 dp of window, minus a 64 dp top bar, minus the 72 dp of the collapsed bar,
     * left 275 dp — two list rows between two bands of chrome. A short window gets a
     * dense top bar and no bar at the bottom instead, so what is asserted is the
     * number the owner was complaining about.
     */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun `a phone in landscape keeps its height for the content`() {
        compose.setContent { Shell() }

        val height = contentHeightDp()
        assertTrue(
            "le contenu n'a que $height dp de haut sur les 411 de la fenêtre",
            height >= 355,
        )
        assertEquals(
            "la top bar dense est ce qui est mesuré : le contenu commence trop bas",
            48,
            contentTopDp(),
        )
    }

    /** The other half of the fix is the bar a tall window still pays for in full. */
    @Test
    @Config(qualifiers = "w412dp-h1000dp-xhdpi")
    fun `a tall window keeps a full height top bar`() {
        compose.setContent { Shell() }

        assertEquals("1000 dp de fenêtre paie une top bar de 64 dp", 64, contentTopDp())
    }

    /** The three buttons of the band are not lost with it: they move into the rail. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun `a phone in landscape reaches the conversation from the rail`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.NAV_RAIL).assertExists()
        compose.onNodeWithTag(UiTags.CHAT_OPEN).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_MIC).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_CONTEXTS).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_PANEL).assertDoesNotExist()

        // Inside the rail, not merely right of its left edge: the rail is the Row's
        // first child, so « x ≥ the rail's x » holds for the whole window.
        val rail = compose.onNodeWithTag(UiTags.NAV_RAIL).fetchSemanticsNode()
        val railRight = rail.positionInRoot.x + rail.size.width
        listOf(UiTags.CHAT_OPEN, UiTags.CHAT_MIC, UiTags.CHAT_CONTEXTS).forEach { tag ->
            val x = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.x
            assertTrue("$tag n'est pas dans le rail", x < railRight)
        }
    }

    /** A tall window keeps the band: the fix is for the windows that cannot pay for it. */
    @Test
    @Config(qualifiers = "w800dp-h1280dp-xhdpi")
    fun `a tall window keeps the chat in the band under the content`() {
        compose.setContent { Shell() }

        val railWidth = compose.onNodeWithTag(UiTags.NAV_RAIL).fetchSemanticsNode().size.width
        val askX = compose.onNodeWithTag(UiTags.CHAT_OPEN).fetchSemanticsNode().positionInRoot.x
        assertTrue("« Demander à Maggie » est passé dans le rail", askX >= railWidth)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-xhdpi")
    fun `tapping a rail entry asks for the route it names`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.railItem("grocery")).performClick()

        assertEquals("grocery", navigatedTo)
    }

    /**
     * Criterion 6 in the format the recette refused: the three buttons at the top of
     * the rail push the destinations down, and the last one must still be reachable —
     * the rail scrolls, so « exists » would pass on a window where nothing can be
     * tapped.
     */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xhdpi")
    fun `a phone in landscape still reaches the last rail destination`() {
        compose.setContent { Shell() }

        compose.onNodeWithTag(UiTags.railItem("settings")).performScrollTo().performClick()

        assertEquals("settings", navigatedTo)
    }
}
