package com.maggie.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.VoiceManager

/**
 * Reads aloud the answers the [viewModel] says are answers to a request (MAG-227),
 * and nothing else: not the history that loads on open, not a proactive message.
 * Leaving the composition silences an answer still on its way, so reopening never
 * reads it.
 */
@Composable
fun SpokenReplies(viewModel: ChatViewModel, voiceManager: VoiceManager) {
    val uiState by viewModel.uiState.collectAsState()
    val reply = uiState.replyToSpeak

    // Declared first, so it runs before the effect below: whatever was offered or
    // awaited while nothing here was listening (a typed request in the chat screen)
    // belongs to the past, not to this opening.
    DisposableEffect(viewModel) {
        viewModel.dropPendingReply()
        onDispose { viewModel.dropPendingReply() }
    }

    LaunchedEffect(reply) {
        if (reply == null) return@LaunchedEffect
        val message = viewModel.uiState.value.replyToSpeak ?: return@LaunchedEffect
        voiceManager.speak(message.content)
        viewModel.onReplySpoken()
    }
}
