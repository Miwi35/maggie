package com.maggie.app.data.api

import android.util.Log
import com.maggie.app.data.model.AgUiEvent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AgUiStreamParser {

    private const val TAG = "AgUiStreamParser"
    private const val DATA_PREFIX = "data: "

    private val json = Json { ignoreUnknownKeys = true }

    fun parseStream(channel: ByteReadChannel): Flow<AgUiEvent> = flow {
        try {
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (line.startsWith(DATA_PREFIX)) {
                    val payload = line.removePrefix(DATA_PREFIX).trim()
                    if (payload.isNotEmpty()) {
                        val event = parseEvent(payload)
                        if (event != null) {
                            emit(event)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream read error: ${e.message}")
            emit(AgUiEvent.Error(e.message ?: "Stream error"))
        }
    }

    fun parseEvent(payload: String): AgUiEvent? {
        return try {
            val obj = json.parseToJsonElement(payload).jsonObject
            val type = obj["type"]?.jsonPrimitive?.content ?: return null

            when (type) {
                "RUN_STARTED" -> AgUiEvent.RunStarted(
                    runId = obj.str("runId"),
                )
                "RUN_FINISHED" -> AgUiEvent.RunFinished(
                    runId = obj.str("runId"),
                )
                "TEXT_MESSAGE_START" -> AgUiEvent.TextMessageStart(
                    messageId = obj.str("messageId"),
                )
                "TEXT_MESSAGE_CONTENT" -> AgUiEvent.TextMessageContent(
                    messageId = obj.str("messageId"),
                    delta = obj.str("delta"),
                )
                "TEXT_MESSAGE_END" -> AgUiEvent.TextMessageEnd(
                    messageId = obj.str("messageId"),
                )
                "TOOL_CALL_START" -> AgUiEvent.ToolCallStart(
                    toolCallId = obj.str("toolCallId"),
                    toolName = obj.str("toolName"),
                )
                "TOOL_CALL_END" -> AgUiEvent.ToolCallEnd(
                    toolCallId = obj.str("toolCallId"),
                    toolName = obj.str("toolName"),
                )
                "CUSTOM" -> parseCustomEvent(obj)
                else -> {
                    Log.d(TAG, "Unknown event type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse event: ${e.message}")
            null
        }
    }

    private fun parseCustomEvent(obj: JsonObject): AgUiEvent? {
        val name = obj["name"]?.jsonPrimitive?.content ?: return null
        val value = obj["value"]?.jsonObject ?: return null

        return when (name) {
            "context_update" -> AgUiEvent.ContextUpdate(
                id = value.str("id"),
                label = value.str("label"),
                status = value.str("status"),
                action = value.str("action"),
            )
            "tool_result" -> AgUiEvent.ToolResult(
                toolCallId = value.str("toolCallId"),
                toolName = value.str("toolName"),
                status = value.str("status"),
            )
            else -> {
                Log.d(TAG, "Unknown custom event: $name")
                null
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""
}
