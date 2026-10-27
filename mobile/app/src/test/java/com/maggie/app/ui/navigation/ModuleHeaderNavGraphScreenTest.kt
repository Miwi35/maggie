package com.maggie.app.ui.navigation

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.ui.UiTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One header per module screen (MAG-670): the bar's title or the module's breadcrumb « Module › Section ▾ », never
 * both stacked. Tâches and Cuisine kept the bar's title above their breadcrumb once the breadcrumb was drawn in
 * their content.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-port-xhdpi")
class ModuleHeaderNavGraphScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<NavGraphHostActivity>

    @After
    fun tearDown() {
        scenario.close()
        stopKoin()
    }

    private fun launchOnHome() {
        startNavGraphApp()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, NavGraphHostActivity::class.java))
        scenario = ActivityScenario.launch(NavGraphHostActivity::class.java)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    private fun open(destination: String) {
        compose.onNodeWithTag(UiTags.drawerItem(destination)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun headersShown(): Int =
        compose.onAllNodesWithTagCount(UiTags.TOP_BAR_TITLE) + compose.onAllNodesWithTagCount(UiTags.BREADCRUMB_MODULE)

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `Tâches shows one header, the breadcrumb Tâches › Aujourd'hui`() {
        launchOnHome()

        open("tasks")

        assertEquals(1, headersShown())
        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Tâches")
        compose.onNodeWithTag(UiTags.TOP_BAR_TITLE).assertDoesNotExist()
    }

    @Test
    fun `Cuisine shows one header, the breadcrumb Cuisine › Repas`() {
        launchOnHome()

        open("cookbook")

        assertEquals(1, headersShown())
        compose.onNodeWithTag(UiTags.BREADCRUMB_MODULE).assertTextEquals("Cuisine")
        compose.onNodeWithTag(UiTags.TOP_BAR_TITLE).assertDoesNotExist()
    }

    @Test
    fun `Courses shows one header`() {
        launchOnHome()

        open("grocery")

        assertEquals(1, headersShown())
    }

    @Test
    fun `Agenda keeps the bar's title and has no breadcrumb`() {
        launchOnHome()

        open("calendar")

        assertEquals(1, headersShown())
        compose.onNodeWithTag(UiTags.TOP_BAR_TITLE).assertTextEquals("Agenda")
    }
}
