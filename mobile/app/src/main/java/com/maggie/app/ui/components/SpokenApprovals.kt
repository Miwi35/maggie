package com.maggie.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

/**
 * Asks aloud the question a held action raises (MAG-310), once per card, so the user can
 * answer it with their voice. It waits for the room to be quiet — no reply on its way or
 * being read, nobody speaking — because two syntheses would play over each other.
 */
@Composable
fun SpokenApprovals(viewModel: ChatViewModel, voiceManager: VoiceManager) {
    val uiState by viewModel.uiState.collectAsState()
    val voiceState by voiceManager.state.collectAsState()
    val asked = remember { mutableSetOf<String>() }

    val next = uiState.pendingApprovals
        .firstOrNull { it.approval.isPending && it.decision == null && it.approval.id !in asked }
        ?.approval
    val quiet = voiceState == VoiceState.IDLE && !uiState.isLoading && uiState.replyToSpeak == null

    LaunchedEffect(next?.id, quiet) {
        if (next == null || !quiet) return@LaunchedEffect
        asked += next.id
        voiceManager.speak(approvalQuestion(next))
    }
}
