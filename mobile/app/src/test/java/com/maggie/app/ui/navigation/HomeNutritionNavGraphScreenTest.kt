package com.maggie.app.ui.navigation

import android.app.Application
import android.content.ComponentName
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.home.HomeModules
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Accueil's Nutrition card opens Cuisine on its « Repas » tab (MAG-564), and the next visit starts on « Recettes » again. */
@RunWith(AndroidJUnit4::class)
class HomeNutritionNavGraphScreenTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<NavGraphHostActivity>

    @After
    fun tearDown() = stopKoin()

    private fun launchOnHome() {
        startNavGraphApp()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, NavGraphHostActivity::class.java))
        scenario = ActivityScenario.launch(NavGraphHostActivity::class.java)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `a tap on the card opens Cuisine on Repas`() {
        launchOnHome()

        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.NUTRITION)).performClick()
        compose.waitForIdle()

        compose.onNode(hasText("Repas") and isSelected()).assertIsDisplayed()
        scenario.close()
    }

    @Test
    @Config(qualifiers = PHONE_PORTRAIT)
    fun `Cuisine opened from the menu starts on Recettes`() {
        launchOnHome()
        compose.onNodeWithTag(UiTags.homeCardHeader(HomeModules.NUTRITION)).performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(UiTags.drawerItem("home")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag(UiTags.drawerItem("cookbook")).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()

        compose.onNode(hasText("Recettes") and isSelected()).assertIsDisplayed()
        scenario.close()
    }

    private companion object {
        const val PHONE_PORTRAIT = "w411dp-h891dp-port-xhdpi"
    }
}
