package com.maggie.app.ui.navigation

import android.app.Application
import android.content.ComponentName
import android.view.ViewGroup
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.ui.UiTags
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The route never draws under the launcher's taskbar (MAG-531).
 *
 * On a tablet the conversation is a panel, so no bar sits under the content to pay the
 * navigation bar — and the taskbar is one. Every screen without a bottom bar of its own
 * ran to the bottom of the window, and the nightly's tablet tapped the launcher instead
 * of `routines_add`, `recurring_save`, `project_save`, `tasks_reserve_toggle` and
 * `tasks_evening_good_night`. The frame pays it once for all of them. Robolectric reports
 * no insets by itself, so they are dispatched here, to the real `NavGraph`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-land-mdpi")
class NavigationBarInsetNavGraphScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<NavGraphHostActivity>

    @After
    fun tearDown() {
        scenario.close()
        stopKoin()
    }

    private fun launchOnTasks(rail: String = "tasks") {
        startNavGraphApp()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, NavGraphHostActivity::class.java))
        scenario = ActivityScenario.launch(NavGraphHostActivity::class.java)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.railItem(rail)).performClick()
        compose.waitForIdle()
    }

    private fun giveTaskbar() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, TASKBAR))
            .build()
        scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        compose.waitForIdle()
    }

    private fun giveStatusBar() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR, 0, 0))
            .build()
        scenario.onActivity { activity ->
            val content = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        compose.waitForIdle()
    }

    // Padded once: under the status bar, not twice (the frame's bar is gone, the breadcrumb pays it).
    private fun assertBreadcrumbPaysTheStatusBarOnce() {
        val bounds = compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).getUnclippedBoundsInRoot()
        assertTrue("the breadcrumb starts at ${bounds.top}, under the ${STATUS_BAR} dp status bar", bounds.top >= STATUS_BAR.dp)
        assertTrue("the breadcrumb ends at ${bounds.bottom}: the status bar was paid twice", bounds.bottom <= (STATUS_BAR + 60).dp)
    }

    private fun assertAboveTheTaskbar(tag: String) {
        val bottom = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom
        val limit = (800 - TASKBAR).dp
        assertTrue("$tag ends at $bottom, the taskbar starts at $limit", bottom <= limit)
    }

    @Test
    fun `the tasks screen ends above the taskbar`() {
        launchOnTasks()
        giveTaskbar()

        assertAboveTheTaskbar(UiTags.TASKS)
    }

    @Test
    fun `the button to add a routine stays above the taskbar`() {
        launchOnTasks()
        compose.onNodeWithTag(UiTags.BREADCRUMB_SECTION).performClick()
        compose.onNodeWithTag(UiTags.breadcrumbMenu("routines")).performClick()
        compose.waitForIdle()
        giveTaskbar()

        assertAboveTheTaskbar(UiTags.ROUTINES_ADD)
    }

    @Test
    fun `the Tâches breadcrumb pays the status bar once`() {
        launchOnTasks()
        giveStatusBar()

        assertBreadcrumbPaysTheStatusBarOnce()
    }

    @Test
    fun `the Cuisine breadcrumb pays the status bar once`() {
        launchOnTasks(rail = "cookbook")
        giveStatusBar()

        assertBreadcrumbPaysTheStatusBarOnce()
    }

    private companion object {
        const val STATUS_BAR = 24
        const val TASKBAR = 48
    }
}
