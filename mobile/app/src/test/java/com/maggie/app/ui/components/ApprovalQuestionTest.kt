package com.maggie.app.ui.components

import com.maggie.app.data.model.PendingApproval
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalQuestionTest {

    @Test
    fun `a known tool is asked as a sentence naming what it touches`() {
        val approval = PendingApproval(
            id = "ap-1",
            toolName = "delete_event",
            arguments = buildJsonObject { put("title", "Test validation") },
        )

        assertEquals("Je supprime l'événement Test validation ?", approvalQuestion(approval))
    }

    @Test
    fun `without a name the question still says what is deleted`() {
        val approval = PendingApproval(id = "ap-1", toolName = "delete_recipe")

        assertEquals("Je supprime la recette ?", approvalQuestion(approval))
    }

    @Test
    fun `an unknown tool is named as it is and still asks`() {
        val approval = PendingApproval(id = "ap-1", toolName = "archive_widget")

        assertEquals("Maggie demande ton accord pour Archive widget. Tu autorises ?", approvalQuestion(approval))
    }
}
