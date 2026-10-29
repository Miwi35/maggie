package com.maggie.app.ui.components.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownComponentsTest {

    @get:Rule
    val compose = ScreenRule()

    @Test
    fun `a plain text reads as it is written`() {
        compose.setContent { MarkdownText("Les cartons sont à la cave") }

        compose.onNodeWithText("Les cartons sont à la cave").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.MARKDOWN_COUNTER).assertDoesNotExist()
    }

    @Test
    fun `boxes are drawn with a counter, and a tap hands back the rewritten text`() {
        var saved: String? = null
        compose.setContent {
            MarkdownText("- [ ] Douche solaire\n- [x] Pompe", onTextChange = { saved = it })
        }

        compose.onNodeWithText("Douche solaire").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.MARKDOWN_COUNTER).assertIsDisplayed()
        compose.onNodeWithText("1/2").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.markdownCheckbox(0)).assertIsOff()
        compose.onNodeWithTag(UiTags.markdownCheckbox(1)).assertIsOn()

        compose.onNodeWithTag(UiTags.markdownCheckbox(0)).performClick()
        assertEquals("- [x] Douche solaire\n- [x] Pompe", saved)
    }

    @Test
    fun `titles, bold and links lose their marks on screen`() {
        compose.setContent { MarkdownText("# Courses\nun **gras** et [un lien](https://x.fr)\n- lait") }

        compose.onNodeWithText("Courses").assertIsDisplayed()
        compose.onNodeWithText("un gras et un lien", substring = true).assertIsDisplayed()
        compose.onNodeWithText("lait").assertIsDisplayed()
    }

    @Composable
    private fun Host(start: String, onText: (String) -> Unit = {}) {
        var text by remember { mutableStateOf(start) }
        MarkdownEditor(value = text, onValueChange = { text = it; onText(it) }, label = "Notes")
    }

    @Test
    fun `the bar shows while the field has the focus and writes at the cursor`() {
        var last = ""
        compose.setContent { Host("", onText = { last = it }) }

        compose.onNodeWithTag(UiTags.MARKDOWN_TOOLBAR).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNodeWithTag(UiTags.MARKDOWN_TOOLBAR).assertIsDisplayed()

        compose.onNodeWithTag(UiTags.MARKDOWN_CHECK).performClick()
        assertEquals("- [ ] ", last)
        compose.onNode(hasSetTextAction()).performTextInput("Douche")
        compose.onNodeWithTag(UiTags.MARKDOWN_BULLET).performClick()
        assertEquals("- Douche", last)
        compose.onNodeWithTag(UiTags.MARKDOWN_HEADING).performClick()
        assertEquals("# Douche", last)
    }
}
