package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.QuestionBlock
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionCardTest {

    @get:Rule
    val compose = ScreenRule()

    private val open = QuestionBlock(id = "q-1", text = "Quels jours ?", options = listOf("Lundi", "Mardi"), multiple = true)

    private fun show(
        block: QuestionBlock = open,
        checked: Set<String> = emptySet(),
        onToggle: (String) -> Unit = {},
        onSubmit: () -> Unit = {},
        failed: Boolean = false,
    ) = compose.setContent {
        QuestionCard(block, checked, onToggle, onSubmit, failed = failed)
    }

    @Test
    fun `the card shows the question, its options and a disabled Valider`() {
        show()

        compose.onNodeWithText("Quels jours ?").assertIsDisplayed()
        compose.onNodeWithText("Lundi").assertIsDisplayed()
        compose.onNodeWithText("Mardi").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.questionSubmit("q-1")).assertIsNotEnabled()
    }

    @Test
    fun `tapping an option asks to tick it`() {
        val toggled = mutableListOf<String>()
        show(onToggle = { toggled += it })

        compose.onNodeWithText("Mardi").performClick()

        assertEquals(listOf("Mardi"), toggled)
    }

    @Test
    fun `Valider is enabled once a box is ticked and asks to send`() {
        var sent = 0
        show(checked = setOf("Lundi"), onSubmit = { sent++ })

        compose.onNodeWithTag(UiTags.questionSubmit("q-1")).assertIsEnabled().performClick()

        assertEquals(1, sent)
    }

    @Test
    fun `an answered question shows what was chosen and no button`() {
        show(open.copy(answered = listOf("Mardi")))

        compose.onNodeWithTag(UiTags.questionSubmit("q-1")).assertDoesNotExist()
        compose.onNodeWithText("Quels jours ?").assertIsDisplayed()
        compose.onNodeWithText("Mardi").assertIsNotEnabled()
    }

    @Test
    fun `an answered question ignores a tap on its options`() {
        val toggled = mutableListOf<String>()
        show(open.copy(answered = listOf("Mardi")), onToggle = { toggled += it })

        compose.onNodeWithText("Lundi").performClick()

        assertEquals(emptyList<String>(), toggled)
    }

    @Test
    fun `a failed answer says so and keeps the button`() {
        show(checked = setOf("Lundi"), failed = true)

        compose.onNodeWithText("Réponse non transmise, réessaie.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.questionSubmit("q-1")).assertIsEnabled()
    }
}
