package com.maggie.app.ui.screens.chat

import com.maggie.app.data.model.PendingApproval
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindStateTest {

    @Test
    fun `a known tool is named in plain words`() {
        assertEquals("Suppression d'un événement", toolActivityLabel("delete_event"))
        assertEquals("Consultation de la météo", toolActivityLabel("get_weather"))
    }

    @Test
    fun `an unknown tool falls back on its verb and never shows its name`() {
        assertEquals("Consultation de tes données", toolActivityLabel("get_independence_counter"))
        assertEquals("Modification d'un élément", toolActivityLabel("manage_loans"))
        assertEquals("Action de Maggie", toolActivityLabel("mystery"))
        listOf("get_independence_counter", "manage_loans", "mystery").forEach {
            assertFalse(toolActivityLabel(it).contains("_") || toolActivityLabel(it).contains(it))
        }
    }

    @Test
    fun `the statuses of the stream are read, an unknown one is not a status`() {
        assertEquals(ToolCallStatus.PENDING_APPROVAL, ToolCallStatus.fromWire("pending_approval"))
        assertEquals(ToolCallStatus.SUCCESS, ToolCallStatus.fromWire("success"))
        assertNull(ToolCallStatus.fromWire("whatever"))
    }

    @Test
    fun `only a pending action before its expiry waits for an answer`() {
        val now = Instant.parse("2026-10-13T10:00:00Z")
        val pending = PendingApproval(id = "a", toolName = "delete_event", expiresAt = "2026-10-13T11:00:00Z")

        assertTrue(ApprovalItem(pending).isAwaitingAnswer(now))
        assertTrue(ApprovalItem(pending.copy(expiresAt = null)).isAwaitingAnswer(now))
        assertFalse(ApprovalItem(pending.copy(expiresAt = "2026-10-13T09:00:00Z")).isAwaitingAnswer(now))
        assertFalse(ApprovalItem(pending.copy(status = PendingApproval.STATUS_FAILED)).isAwaitingAnswer(now))
    }
}
