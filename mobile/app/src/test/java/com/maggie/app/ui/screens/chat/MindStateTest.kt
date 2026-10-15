package com.maggie.app.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
}
