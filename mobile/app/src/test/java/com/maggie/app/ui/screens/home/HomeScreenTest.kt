package com.maggie.app.ui.screens.home

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.HomeDay
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.DRAWER_DESTINATIONS
import com.maggie.app.ui.layout.appLayoutFor
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

    /** A card that tells the Accueil its [status] and draws [text] only when it is not empty, as the real ones do. */
    private fun reporting(module: String, status: HomeCardStatus, text: String = "$module:card") =
        HomeCardEntry(module) {
            ReportHomeCard(module, status)
            if (status != HomeCardStatus.EMPTY) Text(text)
        }

    private fun card(module: String) = HomeCardEntry(module) { day -> Text("$module:${day.apiValue}") }

    private fun layoutOf(
        phone: List<HomeCardSetting>,
        tablet: List<HomeCardSetting> = phone,
        launch: List<HomeCardSetting> = emptyList(),
    ) = HomeLayoutFile(
        mapOf(
            HomeFormat.PHONE to mapOf(HomeSlot.LAUNCH to launch, HomeSlot.CARD to phone),
            HomeFormat.TABLET to mapOf(HomeSlot.CARD to tablet),
        ),
    )

    private fun settings(vararg modules: String) = modules.map { HomeCardSetting(it) }

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
    fun `the launch cards come first, then the others in the layout's order, whatever the registry's`() {
        val registry = listOf(
            card(HomeModules.LEISURE),
            card(HomeModules.FINANCE),
            card(HomeModules.AGENDA),
            card(HomeModules.TASKS),
            card(HomeModules.HEALTH),
        )
        val layout = layoutOf(
            phone = settings(HomeModules.TASKS, HomeModules.AGENDA, HomeModules.FINANCE, HomeModules.LEISURE),
            launch = settings(HomeModules.HEALTH),
        )
        compose.setContent { HomeScreen(viewModel = HomeViewModel(layout), registry = registry, format = HomeFormat.PHONE) }

        val tops = listOf("health:", "tasks:", "agenda:", "finance:", "leisure:").map { top(it) }

        assertEquals(tops.sorted(), tops)
        assertEquals(5, tops.toSet().size)
    }

    @Test
    fun `a disabled card is not drawn, and a key with no component is skipped`() {
        val registry = listOf(card(HomeModules.TASKS), card(HomeModules.AGENDA), card(HomeModules.FINANCE))
        val layout = layoutOf(
            phone = listOf(
                HomeCardSetting(HomeModules.TASKS),
                HomeCardSetting(HomeModules.AGENDA, enabled = false),
                HomeCardSetting(HomeModules.HEALTH),
                HomeCardSetting(HomeModules.FINANCE),
            ),
        )
        compose.setContent { HomeScreen(viewModel = HomeViewModel(layout), registry = registry, format = HomeFormat.PHONE) }

        compose.onNodeWithText("tasks:today").assertIsDisplayed()
        compose.onNodeWithText("finance:today").assertIsDisplayed()
        compose.onNodeWithText("agenda:today").assertDoesNotExist()
    }

    @Test
    fun `a card the layout does not name is not drawn even when it is registered`() {
        val registry = listOf(card(HomeModules.TASKS), card(HomeModules.AGENDA))
        compose.setContent {
            HomeScreen(viewModel = HomeViewModel(layoutOf(phone = settings(HomeModules.TASKS))), registry = registry, format = HomeFormat.PHONE)
        }

        compose.onNodeWithText("tasks:today").assertIsDisplayed()
        compose.onNodeWithText("agenda:today").assertDoesNotExist()
    }

    @Test
    fun `each format is drawn from its own section`() {
        val registry = listOf(card(HomeModules.TASKS), card(HomeModules.AGENDA))
        val layout = layoutOf(
            phone = settings(HomeModules.TASKS, HomeModules.AGENDA),
            tablet = settings(HomeModules.AGENDA, HomeModules.TASKS),
        )
        var format by mutableStateOf(HomeFormat.PHONE)
        compose.setContent { HomeScreen(viewModel = HomeViewModel(layout), registry = registry, format = format) }
        assertTrue(top("tasks:") < top("agenda:"))

        format = HomeFormat.TABLET
        compose.waitForIdle()

        assertTrue(top("agenda:") < top("tasks:"))
    }

    @Test
    fun `the window picks the format - a phone width is the phone, a wider one the tablet`() {
        assertEquals(HomeFormat.PHONE, HomeLayout.formatFor(appLayoutFor(411, 891)))
        assertEquals(HomeFormat.PHONE, HomeLayout.formatFor(appLayoutFor(280, 653)))
        assertEquals(HomeFormat.TABLET, HomeLayout.formatFor(appLayoutFor(674, 841)))
        assertEquals(HomeFormat.TABLET, HomeLayout.formatFor(appLayoutFor(1280, 800)))
    }

    @Test
    fun `a card that draws nothing takes no place between the others`() {
        val withHole = listOf(
            card(HomeModules.TASKS),
            HomeCardEntry(HomeModules.AGENDA) { },
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

    @Test
    fun `the Tâches card is the first of the default layout, and registered once`() {
        assertEquals(HomeModules.TASKS, HomeLayout.cards(HomeFormat.PHONE, HomeSlot.CARD).first())
        assertEquals(1, HomeCardRegistry.entries.count { it.module == HomeModules.TASKS })
    }

    @Test
    fun `a card opens its module through the route the Accueil was given`() {
        val opened = mutableListOf<String>()
        val registry = listOf(
            HomeCardEntry(HomeModules.TASKS) {
                val open = LocalHomeNavigation.current
                Text("ouvrir", modifier = androidx.compose.ui.Modifier.clickable { open(Screen.Tasks.route) })
            },
        )
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry, onNavigate = { opened += it }) }

        compose.onNodeWithText("ouvrir").performClick()

        assertEquals(listOf(Screen.Tasks.route), opened)
    }

    @Test
    fun `an empty card is not drawn and leaves no gap between the others`() {
        val registry = listOf(
            reporting(HomeModules.TASKS, HomeCardStatus.FILLED),
            reporting(HomeModules.AGENDA, HomeCardStatus.EMPTY),
            reporting(HomeModules.GROCERY, HomeCardStatus.EMPTY),
            reporting(HomeModules.FINANCE, HomeCardStatus.FILLED),
        )
        val closed = listOf(reporting(HomeModules.TASKS, HomeCardStatus.FILLED), reporting(HomeModules.FINANCE, HomeCardStatus.FILLED))
        var shown by mutableStateOf(registry)
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = shown) }
        val gap = top("finance:") - top("tasks:")

        shown = closed
        compose.waitForIdle()

        assertEquals(gap, top("finance:") - top("tasks:"))
        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertDoesNotExist()
    }

    @Test
    fun `when every card is empty the screen says one calm sentence, for each day`() {
        val registry = listOf(
            reporting(HomeModules.TASKS, HomeCardStatus.EMPTY),
            reporting(HomeModules.CUISINE, HomeCardStatus.EMPTY),
        )
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry) }

        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertIsDisplayed()
        compose.onNodeWithText("Rien de prévu aujourd'hui.").assertIsDisplayed()

        selectSegment(UiTags.HOME_DAY_TOMORROW)

        compose.onNodeWithText("Rien de prévu demain.").assertIsDisplayed()
    }

    @Test
    fun `a card still loading, or failed, keeps the sentence away`() {
        val loading = listOf(reporting(HomeModules.TASKS, HomeCardStatus.EMPTY), reporting(HomeModules.AGENDA, HomeCardStatus.LOADING))
        val failed = listOf(reporting(HomeModules.TASKS, HomeCardStatus.EMPTY), reporting(HomeModules.AGENDA, HomeCardStatus.ERROR))
        var registry by mutableStateOf(loading)
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry) }

        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertDoesNotExist()

        registry = failed
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertDoesNotExist()
        compose.onNodeWithText("agenda:card").assertIsDisplayed()
    }

    @Test
    fun `a card empty today and full tomorrow shows up on Demain, and the other way round`() {
        val byDay = listOf(
            HomeCardEntry(HomeModules.TASKS) { day ->
                val status = if (day == HomeDay.TODAY) HomeCardStatus.EMPTY else HomeCardStatus.FILLED
                ReportHomeCard(HomeModules.TASKS, status)
                if (status == HomeCardStatus.FILLED) Text("tasks:card")
            },
            HomeCardEntry(HomeModules.AGENDA) { day ->
                val status = if (day == HomeDay.TODAY) HomeCardStatus.FILLED else HomeCardStatus.EMPTY
                ReportHomeCard(HomeModules.AGENDA, status)
                if (status == HomeCardStatus.FILLED) Text("agenda:card")
            },
        )
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = byDay) }

        compose.onNodeWithText("agenda:card").assertIsDisplayed()
        compose.onNodeWithText("tasks:card").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertDoesNotExist()

        selectSegment(UiTags.HOME_DAY_TOMORROW)

        compose.onNodeWithText("tasks:card").assertIsDisplayed()
        compose.onNodeWithText("agenda:card").assertDoesNotExist()
        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertDoesNotExist()
    }

    @Test
    fun `a card that leaves the registry stops counting`() {
        var registry by mutableStateOf(listOf(reporting(HomeModules.TASKS, HomeCardStatus.EMPTY), reporting(HomeModules.AGENDA, HomeCardStatus.FILLED)))
        compose.setContent { HomeScreen(viewModel = HomeViewModel(), registry = registry) }
        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertDoesNotExist()

        registry = listOf(reporting(HomeModules.TASKS, HomeCardStatus.EMPTY))
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.HOME_EMPTY).assertIsDisplayed()
    }
}
