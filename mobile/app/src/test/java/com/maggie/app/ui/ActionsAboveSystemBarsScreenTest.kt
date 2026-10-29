package com.maggie.app.ui

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.MealGroceryIngredient
import com.maggie.app.data.model.MealGroceryToBuy
import com.maggie.app.screentest.FakeChat
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.components.ChatPanel
import com.maggie.app.ui.screens.cookbook.meals.MealIngredientChoiceContent
import com.maggie.app.ui.screens.cookbook.meals.MealIngredientChoiceUiState
import com.maggie.app.ui.screens.finance.CategoryEditScreen
import com.maggie.app.ui.screens.finance.CategoryEditing
import com.maggie.app.ui.screens.finance.CategoryUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The buttons that validate a screen stay above what the system draws over the bottom
 * of the window (MAG-395).
 *
 * The app is edge to edge, and a tablet's taskbar is a navigation bar: the nightly's
 * tablet and foldable lots could not find « Enregistrer », « Créer » and « Ajouter aux
 * courses », which a `Scaffold`'s bottom bar had left under it, and the panel's `chat_send`,
 * which the panel lifted by the keyboard's height inside a window the system had already
 * shrunk. Robolectric reports no insets by itself, so they are dispatched here.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1280dp-h800dp-mdpi")
class ActionsAboveSystemBarsScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private lateinit var window: View

    @Composable
    private fun Hosted(content: @Composable () -> Unit) {
        window = LocalView.current
        content()
    }

    private fun give(navigationBar: Int = 0, keyboard: Int = 0) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboard))
            .setVisible(WindowInsetsCompat.Type.ime(), keyboard > 0)
            .build()
        compose.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(window, insets) }
        compose.waitForIdle()
    }

    private fun assertAboveTheBar(bottom: androidx.compose.ui.unit.Dp) {
        val limit = 800.dp - TASKBAR
        assertTrue("bottom at $bottom, the bar starts at $limit", bottom <= limit)
    }

    @Test
    fun `the choice of the ingredients keeps its button above the taskbar`() {
        val rice = MealGroceryIngredient(
            ingredientId = "rice",
            name = "Riz",
            quantity = 500f,
            unit = CookbookUnit.G,
            toBuy = MealGroceryToBuy(500f, CookbookUnit.G),
        )
        compose.setContent {
            Hosted {
                MealIngredientChoiceContent(
                    uiState = MealIngredientChoiceUiState(
                        isLoading = false,
                        ingredients = listOf(rice),
                        selected = setOf("rice"),
                    ),
                    onToggle = {},
                    onSelectAll = {},
                    onSubmit = {},
                    onRetry = {},
                    onLater = {},
                )
            }
        }
        give(navigationBar = TASKBAR_PX)

        compose.onNodeWithText("Ajouter aux courses").assertIsDisplayed()
        assertAboveTheBar(compose.onNodeWithText("Ajouter aux courses").getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun `the category editor keeps its button above the taskbar`() {
        compose.setContent {
            Hosted {
                CategoryEditScreen(
                    state = CategoryUiState(editing = CategoryEditing()),
                    editing = CategoryEditing(),
                    onSave = {},
                    onBack = {},
                    onAskDelete = {},
                    onConfirmDelete = {},
                    onCancelDelete = {},
                    onClearError = {},
                )
            }
        }
        give(navigationBar = TASKBAR_PX)

        compose.onNodeWithText("Créer").assertIsDisplayed()
        assertAboveTheBar(compose.onNodeWithText("Créer").getUnclippedBoundsInRoot().bottom)
    }

    /**
     * The window the system shrank above the keyboard (800 dp down to 442 on the nightly's
     * tablet) still reports the keyboard's height as an inset: paid again by the panel, the
     * field left the top of the screen, and `chat_send` with it.
     */
    @Test
    fun `the chat panel keeps its field on screen in a window the keyboard already shrank`() {
        val chat = FakeChat()
        compose.setContent {
            Hosted { Box(Modifier.height(SHRUNK_WINDOW)) { ChatPanel(viewModel = chat.viewModel) } }
        }
        give(navigationBar = TASKBAR_PX, keyboard = KEYBOARD_PX)

        compose.onNodeWithTag(UiTags.CHAT_INPUT).assertIsDisplayed()
        compose.onNodeWithTag(UiTags.CHAT_SEND).assertIsDisplayed()
        val bottom = compose.onNodeWithTag(UiTags.CHAT_INPUT).getUnclippedBoundsInRoot().bottom
        assertTrue("the field ends at $bottom", bottom <= SHRUNK_WINDOW)
    }

    /**
     * MAG-532: edge to edge, `adjustResize` may leave the window at full height and only report
     * the keyboard as an inset. The panel then has to lift its field itself, and the conversation
     * with it, or the short reply stays under the keyboard.
     */
    @Test
    fun `the chat panel keeps its field and the last reply above a keyboard that did not shrink the window`() {
        val chat = FakeChat(
            history = listOf(
                ChatMessage(id = "m1", role = "user", content = "Salut", createdAt = "2026-01-05T09:01:00Z"),
                ChatMessage(id = "m2", role = "assistant", content = "Bonjour Meven", createdAt = "2026-01-05T09:02:00Z"),
            ),
        )
        compose.setContent {
            Hosted { Box(Modifier.height(FULL_WINDOW)) { ChatPanel(viewModel = chat.viewModel) } }
        }
        give(navigationBar = TASKBAR_PX, keyboard = KEYBOARD_PX)

        val keyboardTop = FULL_WINDOW - KEYBOARD_PX.dp
        compose.onNodeWithTag(UiTags.CHAT_SEND).assertIsDisplayed()
        val field = compose.onNodeWithTag(UiTags.CHAT_INPUT).getUnclippedBoundsInRoot().bottom
        assertTrue("the field ends at $field, the keyboard starts at $keyboardTop", field <= keyboardTop)
        compose.onNodeWithText("Bonjour Meven").assertIsDisplayed()
        val reply = compose.onNodeWithText("Bonjour Meven").getUnclippedBoundsInRoot().bottom
        assertTrue("the reply ends at $reply, the keyboard starts at $keyboardTop", reply <= keyboardTop)
    }

    private companion object {
        val TASKBAR = 48.dp
        const val TASKBAR_PX = 48
        const val KEYBOARD_PX = 426
        val SHRUNK_WINDOW = 442.dp
        val FULL_WINDOW = 800.dp
    }
}
