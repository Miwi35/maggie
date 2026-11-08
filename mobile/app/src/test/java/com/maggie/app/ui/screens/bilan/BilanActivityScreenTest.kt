package com.maggie.app.ui.screens.bilan

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.screentest.tap
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The Bilan de la journée screen (MAG-751): both mockups' layouts, the step counter, the footer, the slots. */
@RunWith(AndroidJUnit4::class)
class BilanActivityScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val noImmersion = object : ActivityImmersion {
        override fun enter() = Unit
        override fun exit() = Unit
    }

    private fun ready(step: BilanStep) = BilanUiState(
        load = BilanLoad.READY,
        session = BilanSession(LocalDate.of(2026, 11, 8), step, JsonObject(emptyMap())),
    )

    private val events = mutableListOf<String>()

    private fun draw(
        state: BilanUiState,
        slots: BilanSlots = BilanSlots(),
    ) {
        compose.setContent {
            BilanActivityContent(
                state = state,
                onQuit = { events += "quit" },
                onAnswerByVoice = { events += "voice" },
                onPrevious = { events += "previous" },
                onNext = { events += "next" },
                onGoTo = { events += "goto ${it.wire}" },
                onRetry = { events += "retry" },
                slots = slots,
                immersion = noImmersion,
            )
        }
    }

    @Test
    fun `the phone shows the step counter, Maggie's line and the footer`() {
        draw(ready(BilanStep.FINANCE))

        compose.onNodeWithTag(UiTags.BILAN_COUNTER).assertTextContains("Finance · 2 sur 5")
        compose.onNodeWithTag(UiTags.BILAN_SAY).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BILAN_MIC).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BILAN_NEXT).assertIsDisplayed()
    }

    @Test
    fun `the footer, the close button and the micro call back`() {
        draw(ready(BilanStep.SANTE))

        compose.onNodeWithTag(UiTags.BILAN_NEXT).tap()
        compose.onNodeWithTag(UiTags.BILAN_PREVIOUS).tap()
        compose.onNodeWithTag(UiTags.BILAN_MIC).tap()
        compose.onNodeWithTag(UiTags.BILAN_QUIT).tap()

        assertEquals(listOf("next", "previous", "voice", "quit"), events)
    }

    @Test
    fun `the last step says Bonne nuit`() {
        draw(ready(BilanStep.DEMAIN))

        compose.onNodeWithText("Bonne nuit", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the step's slot, line and replies come from the ticket that fills the step`() {
        val picked = mutableListOf<String>()
        val slots = BilanSlots(
            content = mapOf(BilanStep.JOURNEE to tasksSlot),
            say = mapOf(BilanStep.JOURNEE to "Il reste deux tâches."),
            asks = mapOf(BilanStep.JOURNEE to listOf(BilanAsk("Oui, demain") { picked += "demain" })),
        )
        draw(ready(BilanStep.JOURNEE), slots)

        compose.onNodeWithTag(UiTags.BILAN_SLOT_PREFIX + "journee").assertIsDisplayed()
        compose.onNodeWithText("Tâches restantes").assertIsDisplayed()
        compose.onNodeWithText("Il reste deux tâches.").assertIsDisplayed()
        compose.onNodeWithText("Oui, demain").tap()
        assertEquals(listOf("demain"), picked)
    }

    @Test
    fun `an error offers Réessayer`() {
        draw(BilanUiState(load = BilanLoad.ERROR))

        compose.onNodeWithTag(UiTags.BILAN_RETRY).tap()

        assertEquals(listOf("retry"), events)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun `the tablet shows the same step beside Maggie`() {
        draw(ready(BilanStep.FINANCE))

        compose.onNodeWithTag(UiTags.BILAN_COUNTER).assertTextContains("Finance · 2 sur 5")
        compose.onNodeWithTag(UiTags.BILAN_SAY).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.BILAN_NEXT).tap()
        compose.onNodeWithTag(UiTags.BILAN_PREVIOUS).tap()

        assertEquals(listOf("next", "previous"), events)
    }

    private val tasksSlot: @Composable ColumnScope.(BilanLayout) -> Unit = { Text("Tâches restantes") }
}
