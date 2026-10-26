package com.maggie.app.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The drawer to the letter of `charte/Tiroir` (MAG-620): its header, its entries, and no figure it cannot back. */
@RunWith(AndroidJUnit4::class)
class AppDrawerScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun textMatching(pattern: String) = SemanticsMatcher("text matching $pattern") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { Regex(pattern).matches(it.text) } == true
    }

    private val sunday = LocalDate.of(2026, 10, 25)

    private fun draw(route: String? = "home", onNavigate: (String) -> Unit = {}, onClose: () -> Unit = {}) =
        compose.setContent {
            AppDrawerContent(currentRoute = route, onNavigate = onNavigate, onCloseDrawer = onClose, today = sunday)
        }

    @Test
    fun `the header says the day`() {
        draw()

        compose.onNodeWithTag(UiTags.DRAWER_HEADER).assertIsDisplayed()
        compose.onNodeWithText("Dimanche 25 octobre").assertIsDisplayed()
    }

    @Test
    fun `the date label is French, capitalised, without the year`() {
        assertEquals("Lundi 2 février", drawerDateLabel(LocalDate.of(2026, 2, 2)))
        assertEquals("Dimanche 25 octobre", drawerDateLabel(sunday))
    }

    @Test
    fun `the six modules then Réglages are drawn, and no Chat`() {
        draw()

        listOf("Accueil", "Agenda", "Tâches", "Courses", "Cuisine", "Finance", "Réglages").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        listOf("Chat", "Calendrier", "Paramètres", "Nutrition").forEach {
            compose.onAllNodesWithText(it).assertCountEquals(0)
        }
        compose.onAllNodesWithTag(UiTags.drawerItem("chat")).assertCountEquals(0)
    }

    /** The phone's top bar is the menu and the title (MAG-624): the two icons it dropped live here. */
    @Test
    fun `the search and the notifications are drawer entries above Réglages`() {
        val opened = mutableListOf<String>()
        draw(onNavigate = { opened += it })

        compose.onNodeWithTag(UiTags.drawerItem("search")).assertIsDisplayed().tap()
        compose.onNodeWithTag(UiTags.drawerItem("notifications")).assertIsDisplayed().tap()

        assertEquals(listOf("search", "notifications"), opened)
    }

    @Test
    fun `unread notifications are counted on their entry, none shows no number`() {
        compose.setContent {
            AppDrawerContent(
                currentRoute = "home",
                onNavigate = {},
                onCloseDrawer = {},
                today = sunday,
                unreadNotifications = 3,
            )
        }

        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `no counter and no state line while the app holds no figure for them`() {
        draw()

        compose.onAllNodes(textMatching("^\\d+$")).assertCountEquals(0)
        compose.onAllNodes(textMatching(".*\\d+ (tâche|événement|article).*")).assertCountEquals(0)
    }

    @Test
    fun `an entry navigates and closes the drawer`() {
        val opened = mutableListOf<String>()
        var closed = 0
        draw(onNavigate = { opened += it }, onClose = { closed++ })

        compose.onNodeWithTag(UiTags.drawerItem("calendar")).tap()

        assertEquals(listOf("calendar"), opened)
        assertEquals(1, closed)
    }

    @Test
    fun `Réglages keeps its route`() {
        val opened = mutableListOf<String>()
        draw(onNavigate = { opened += it })

        compose.onNodeWithTag(UiTags.drawerItem("settings")).tap()

        assertEquals(listOf("settings"), opened)
    }
}
