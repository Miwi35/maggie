package com.maggie.app.data.model

sealed class AgUiEvent {
    data class RunStarted(val runId: String) : AgUiEvent()
    data class RunFinished(val runId: String) : AgUiEvent()
    data class TextMessageStart(val messageId: String) : AgUiEvent()
    data class TextMessageContent(val messageId: String, val delta: String) : AgUiEvent()
    data class TextMessageEnd(val messageId: String) : AgUiEvent()
    data class ToolCallStart(val toolCallId: String, val toolName: String) : AgUiEvent()
    data class ToolCallEnd(val toolCallId: String, val toolName: String) : AgUiEvent()
    data class ContextUpdate(
        val id: String,
        val label: String,
        val status: String,
        val action: String,
    ) : AgUiEvent()
    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val status: String,
    ) : AgUiEvent()
    data class Error(val message: String) : AgUiEvent()
}
