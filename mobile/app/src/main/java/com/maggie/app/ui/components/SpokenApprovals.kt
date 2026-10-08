package com.maggie.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

/**
 * Asks aloud the question the first held action raises (MAG-310), once, so the user can
 * answer it with their voice — a spoken « oui » only answers a card that was read out.
 *
 * It waits for the room to be quiet — no reply on its way or being read — because two
 * syntheses would play over each other. The listening that opens with the overlay is not
 * in the way as long as nobody has spoken into it: it is cut for the question, then
 * reopened ([onListen]) once the question has been read.
 */
@Composable
fun SpokenApprovals(viewModel: ChatViewModel, voiceManager: VoiceManager, onListen: () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val voiceState by voiceManager.state.collectAsState()
    val handsFree by voiceManager.handsFree.collectAsState()
    val duration by voiceManager.duration.collectAsState()
    var listenAfterQuestion by remember { mutableStateOf(false) }

    // The card a « oui » would answer: the same one the view model targets.
    val target = uiState.pendingApprovals
        .firstOrNull { it.approval.isPending && it.decision == null }
        ?.approval
    val nobodySpeaking = voiceState == VoiceState.IDLE ||
        (voiceState == VoiceState.LISTENING && handsFree && duration <= UNSPOKEN_SECONDS)
    val quiet = nobodySpeaking && !uiState.isLoading && uiState.replyToSpeak == null

    LaunchedEffect(target?.id, quiet) {
        if (target == null || !quiet || viewModel.isApprovalAsked(target.id)) return@LaunchedEffect
        val question = viewModel.questionFor(target)
        if (viewModel.isApprovalAsked(target.id)) return@LaunchedEffect
        viewModel.markApprovalAsked(target.id)
        voiceManager.cancelListening()
        voiceManager.speak(question)
        listenAfterQuestion = true
    }

    LaunchedEffect(voiceState, listenAfterQuestion) {
        if (listenAfterQuestion && voiceState == VoiceState.IDLE) {
            listenAfterQuestion = false
            onListen()
        }
    }
}

// A hands-free listening that has run this long without a word is still the one the overlay opened with.
private const val UNSPOKEN_SECONDS = 2
