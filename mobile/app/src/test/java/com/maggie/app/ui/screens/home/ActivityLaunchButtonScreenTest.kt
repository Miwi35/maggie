package com.maggie.app.ui.screens.home

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ACTIVITY_FINANCE_REVIEW
import com.maggie.app.data.model.HomeDay
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The « ▶ Lancer » of the Accueil (MAG-568). */
@RunWith(AndroidJUnit4::class)
class ActivityLaunchButtonScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun draw(day: HomeDay, onLaunch: () -> Unit = {}) = compose.setContent {
        ActivityLaunchButton(
            module = HomeModules.FINANCE,
            activity = "la revue d'octobre",
            day = day,
            onLaunch = onLaunch,
        )
    }

    @Test
    fun `it is a button spoken as Lancer and the activity`() {
        draw(HomeDay.TODAY)

        compose.onNodeWithContentDescription("Lancer la revue d'octobre")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun `the pill is 28 dp inside a tap area of at least 44 dp`() {
        draw(HomeDay.TODAY)

        compose.onNodeWithTag(UiTags.homeLaunch(HomeModules.FINANCE)).assertHeightIsAtLeast(44.dp)
        compose.onNodeWithTag(UiTags.homeLaunch(HomeModules.FINANCE) + "_pill", useUnmergedTree = true)
            .assertHeightIsEqualTo(28.dp)
    }

    @Test
    fun `a tap launches`() {
        var launched = 0
        draw(HomeDay.TODAY) { launched++ }

        compose.onNodeWithTag(UiTags.homeLaunch(HomeModules.FINANCE)).performClick()

        assertEquals(1, launched)
    }

    @Test
    fun `tomorrow draws nothing`() {
        draw(HomeDay.TOMORROW)

        compose.onNodeWithTag(UiTags.homeLaunch(HomeModules.FINANCE)).assertDoesNotExist()
    }

    @Test
    fun `the review appointment launches the Comptes activity, a plain event launches nothing`() {
        assertEquals(Screen.FinanceReviewActivity.route, launchRouteFor(ACTIVITY_FINANCE_REVIEW))
        assertNull(launchRouteFor(null))
        assertNull(launchRouteFor("something-else"))
    }
}
