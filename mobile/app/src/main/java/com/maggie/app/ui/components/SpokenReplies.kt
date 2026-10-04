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

    LaunchedEffect(reply) {
        val message = reply ?: return@LaunchedEffect
        voiceManager.speak(message.content)
        viewModel.onReplySpoken()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.dropPendingReply() }
    }
}
