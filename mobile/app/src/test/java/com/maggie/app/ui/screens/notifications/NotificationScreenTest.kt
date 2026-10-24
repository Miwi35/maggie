package com.maggie.app.ui.screens.notifications

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.Notification
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The notifications list: a shortage alert carries its « Ajouter à la liste » (MAG-570). */
@RunWith(AndroidJUnit4::class)
class NotificationScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val shortage = Notification(
        id = "n-1",
        type = "grocery",
        title = "Il manque : parmesan",
        body = "Pour Pâtes carbonara, ce soir.",
        relatedEntityIri = "/api/shortage-alerts/01HZX3K9P2M7Q4R8S5T6V0W1AB",
    )
    private val plain = Notification(id = "n-2", title = "Dentiste", relatedEntityIri = "/api/events/01HX")

    private fun show(vararg notifications: Notification): NotificationViewModel {
        val viewModel = mockk<NotificationViewModel>(relaxed = true) {
            every { uiState } returns MutableStateFlow(NotificationUiState(notifications = notifications.toList()))
        }
        compose.setContent { NotificationScreen(viewModel = viewModel, onBack = {}) }
        return viewModel
    }

    @Test
    fun `a shortage alert shows Ajouter à la liste and pressing it answers that notification`() {
        val viewModel = show(shortage)

        compose.onNodeWithText("Il manque : parmesan").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.NOTIFICATION_ADD_TO_LIST).performClick()

        verify(exactly = 1) { viewModel.addToList(shortage) }
    }

    @Test
    fun `any other notification has no such button, and a shortage already read has none left`() {
        show(plain, shortage.copy(id = "n-3", readAt = "2026-10-23T09:00:00+00:00"))

        compose.onNodeWithTag(UiTags.NOTIFICATION_ADD_TO_LIST).assertDoesNotExist()
    }
}
