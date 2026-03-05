package com.maggie.app.data.api

import android.util.Log
import com.maggie.app.data.model.AgUiEvent
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgUiStreamParserTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0
    }

    @Test
    fun `parse RUN_STARTED event`() {
        val payload = """{"type":"RUN_STARTED","runId":"abc123"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.RunStarted)
        assertEquals("abc123", (event as AgUiEvent.RunStarted).runId)
    }

    @Test
    fun `parse RUN_FINISHED event`() {
        val payload = """{"type":"RUN_FINISHED","runId":"abc123"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.RunFinished)
        assertEquals("abc123", (event as AgUiEvent.RunFinished).runId)
    }

    @Test
    fun `parse TEXT_MESSAGE_START event`() {
        val payload = """{"type":"TEXT_MESSAGE_START","messageId":"msg-1","role":"assistant"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.TextMessageStart)
        assertEquals("msg-1", (event as AgUiEvent.TextMessageStart).messageId)
    }

    @Test
    fun `parse TEXT_MESSAGE_CONTENT event`() {
        val payload = """{"type":"TEXT_MESSAGE_CONTENT","messageId":"msg-1","delta":"Hello "}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.TextMessageContent)
        val content = event as AgUiEvent.TextMessageContent
        assertEquals("msg-1", content.messageId)
        assertEquals("Hello ", content.delta)
    }

    @Test
    fun `parse TEXT_MESSAGE_END event`() {
        val payload = """{"type":"TEXT_MESSAGE_END","messageId":"msg-1"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.TextMessageEnd)
        assertEquals("msg-1", (event as AgUiEvent.TextMessageEnd).messageId)
    }

    @Test
    fun `parse TOOL_CALL_START event`() {
        val payload = """{"type":"TOOL_CALL_START","toolCallId":"tool-1","toolName":"grocery_add"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.ToolCallStart)
        val tc = event as AgUiEvent.ToolCallStart
        assertEquals("tool-1", tc.toolCallId)
        assertEquals("grocery_add", tc.toolName)
    }

    @Test
    fun `parse TOOL_CALL_END event`() {
        val payload = """{"type":"TOOL_CALL_END","toolCallId":"tool-1","toolName":"grocery_add"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.ToolCallEnd)
        val tc = event as AgUiEvent.ToolCallEnd
        assertEquals("tool-1", tc.toolCallId)
        assertEquals("grocery_add", tc.toolName)
    }

    @Test
    fun `parse CUSTOM context_update event`() {
        val payload = """{"type":"CUSTOM","name":"context_update","value":{"action":"created","id":"ctx-1","label":"Courses","status":"active"}}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.ContextUpdate)
        val ctx = event as AgUiEvent.ContextUpdate
        assertEquals("ctx-1", ctx.id)
        assertEquals("Courses", ctx.label)
        assertEquals("active", ctx.status)
        assertEquals("created", ctx.action)
    }

    @Test
    fun `parse CUSTOM context_update matched event`() {
        val payload = """{"type":"CUSTOM","name":"context_update","value":{"action":"matched","id":"ctx-2","label":"Recettes","status":"active"}}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.ContextUpdate)
        assertEquals("matched", (event as AgUiEvent.ContextUpdate).action)
    }

    @Test
    fun `parse CUSTOM tool_result event`() {
        val payload = """{"type":"CUSTOM","name":"tool_result","value":{"toolCallId":"tool-1","toolName":"grocery_add","status":"success"}}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.ToolResult)
        val tr = event as AgUiEvent.ToolResult
        assertEquals("tool-1", tr.toolCallId)
        assertEquals("grocery_add", tr.toolName)
        assertEquals("success", tr.status)
    }

    @Test
    fun `parse CUSTOM tool_result error status`() {
        val payload = """{"type":"CUSTOM","name":"tool_result","value":{"toolCallId":"tool-1","toolName":"event_create","status":"error"}}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.ToolResult)
        assertEquals("error", (event as AgUiEvent.ToolResult).status)
    }

    @Test
    fun `unknown event type returns null`() {
        val payload = """{"type":"UNKNOWN_EVENT","data":"test"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertNull(event)
    }

    @Test
    fun `unknown custom event returns null`() {
        val payload = """{"type":"CUSTOM","name":"unknown_custom","value":{}}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertNull(event)
    }

    @Test
    fun `malformed JSON returns null`() {
        val payload = """not valid json"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertNull(event)
    }

    @Test
    fun `missing type field returns null`() {
        val payload = """{"data":"test"}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertNull(event)
    }

    @Test
    fun `empty payload returns null`() {
        val payload = ""
        val event = AgUiStreamParser.parseEvent(payload)
        assertNull(event)
    }

    @Test
    fun `extra fields are ignored`() {
        val payload = """{"type":"RUN_STARTED","runId":"r1","extraField":"value","nested":{"a":1}}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertNotNull(event)
        assertTrue(event is AgUiEvent.RunStarted)
    }

    @Test
    fun `text content with special characters`() {
        val payload = """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"J'ai ajouté les \"articles\" à la liste."}"""
        val event = AgUiStreamParser.parseEvent(payload)
        assertTrue(event is AgUiEvent.TextMessageContent)
        val content = event as AgUiEvent.TextMessageContent
        assertTrue(content.delta.contains("J'ai ajouté"))
    }
}
