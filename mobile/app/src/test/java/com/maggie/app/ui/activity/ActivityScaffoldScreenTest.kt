package com.maggie.app.ui.activity

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieDarkColorScheme
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.theme.inModule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The frame every activity shares (MAG-580): immersion, leaving, Maggie. */
@RunWith(AndroidJUnit4::class)
class ActivityScaffoldScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private class RecordingImmersion : ActivityImmersion {
        val calls = mutableListOf<String>()

        override fun enter() {
            calls += "enter"
        }

        override fun exit() {
            calls += "exit"
        }
    }

    private val immersion = RecordingImmersion()
    private var quits = 0
    private var maggie = 0
    private var shown by mutableStateOf(true)
    private lateinit var back: OnBackPressedDispatcher

    private fun draw() = compose.setContent {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        if (shown) {
            ActivityScaffold(
                name = "Cuisine",
                hue = ModuleHue.CUISINE,
                onQuit = { quits++ },
                onOpenMaggie = { maggie++ },
                immersion = immersion,
                progress = { Text("Étape 1 sur 3") },
            ) {
                Box { Text("Contenu") }
            }
        }
    }

    private fun systemBack() = compose.runOnUiThread { back.onBackPressed() }

    @Test
    fun `it shows the progress, the content, Maggie and the cross`() {
        draw()

        compose.onNodeWithText("Étape 1 sur 3").assertIsDisplayed()
        compose.onNodeWithText("Contenu").assertIsDisplayed()
        compose.onNodeWithContentDescription("Demander à Maggie").assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitter Cuisine").assertIsDisplayed()
    }

    @Test
    fun `the background carries a halo in the module's colour`() {
        draw()

        val halo = compose.onNode(SemanticsMatcher.keyIsDefined(HaloColorKey)).fetchSemanticsNode().config[HaloColorKey]

        assertEquals(MaggieDarkColorScheme.inModule(ModuleHue.CUISINE).primary, halo)
        assertEquals(Color(0xFF8FB8FF), halo)
    }

    @Test
    fun `the header buttons are at least 56 dp`() {
        draw()

        listOf(UiTags.ACTIVITY_MAGGIE, UiTags.ACTIVITY_QUIT).forEach {
            compose.onNodeWithTag(it).assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        }
    }

    @Test
    fun `the immersion is taken when the frame opens and given back when it leaves`() {
        draw()
        compose.waitForIdle()
        assertEquals(listOf("enter"), immersion.calls)

        shown = false
        compose.waitForIdle()

        assertEquals(listOf("enter", "exit"), immersion.calls)
    }

    @Test
    fun `the cross leaves at once when nothing is at stake`() {
        draw()

        compose.onNodeWithTag(UiTags.ACTIVITY_QUIT).performClick()

        assertEquals(1, quits)
    }

    @Test
    fun `the system back does what the cross does`() {
        draw()

        systemBack()
        compose.waitForIdle()

        assertEquals(1, quits)
    }

    @Test
    fun `Maggie opens from the frame without leaving`() {
        draw()

        compose.onNodeWithTag(UiTags.ACTIVITY_MAGGIE).performClick()

        assertEquals(1, maggie)
        assertEquals(0, quits)
    }
}
