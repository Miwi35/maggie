package com.maggie.app.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.PendingApproval
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.ApprovalDecision
import com.maggie.app.ui.screens.chat.ApprovalItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalCardScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private val approval = PendingApproval(
        id = "ap-1",
        toolName = "delete_event",
        arguments = buildJsonObject { put("summary", "Dentiste") },
    )

    private fun show(
        item: ApprovalItem,
        onApprove: () -> Unit = {},
        onDeny: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) = compose.setContent {
        ApprovalCard(item, onApprove = onApprove, onDeny = onDeny, onDismiss = onDismiss)
    }

    @Test
    fun `the card says what Maggie wants to do and offers both answers`() {
        show(ApprovalItem(approval))

        compose.onNodeWithText("Maggie demande ton accord").assertIsDisplayed()
        compose.onNodeWithText("Delete event").assertIsDisplayed()
        compose.onNodeWithText("summary : Dentiste").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsEnabled()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsEnabled()
    }

    @Test
    fun `Autoriser and Refuser each report their own answer`() {
        val answers = mutableListOf<String>()
        show(ApprovalItem(approval), onApprove = { answers += "approve" }, onDeny = { answers += "deny" })

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).performClick()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).performClick()

        assertEquals(listOf("approve", "deny"), answers)
    }

    @Test
    fun `both buttons lock while an answer is on its way`() {
        show(ApprovalItem(approval, decision = ApprovalDecision.APPROVE))

        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsNotEnabled()
        compose.onNodeWithTag(UiTags.approvalDeny("ap-1")).assertIsNotEnabled()
    }

    @Test
    fun `a network error says so and gives the buttons back`() {
        show(ApprovalItem(approval, error = true))

        compose.onNodeWithText("Réponse non transmise, réessaie.").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertIsEnabled()
    }

    @Test
    fun `a failed action shows its result and can only be closed`() {
        var closed = false
        show(
            ApprovalItem(approval.copy(status = PendingApproval.STATUS_FAILED, result = "Agenda indisponible")),
            onDismiss = { closed = true },
        )

        compose.onNodeWithText("Action échouée").assertIsDisplayed()
        compose.onNodeWithText("Agenda indisponible").assertIsDisplayed()
        compose.onNodeWithTag(UiTags.approvalAllow("ap-1")).assertDoesNotExist()

        compose.onNodeWithTag(UiTags.approvalDismiss("ap-1")).performClick()
        assertEquals(true, closed)
    }
}
