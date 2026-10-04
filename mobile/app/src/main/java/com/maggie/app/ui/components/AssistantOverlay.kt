package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.ScreenContext
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

/**
 * [screenContext] is what the screen behind the overlay was showing when the
 * assistant was summoned (MAG-30). It rides along with the **first** thing said
 * and is then forgotten: « ajoute ça à mon agenda » is about the screen, the
 * follow-up question is about the answer.
 *
 * [invocation] counts the times the assistant was summoned, and is what re-arms
 * that « not used yet » state. Keying it on [screenContext] would not: two
 * invocations from the same screen carry an equal value, and the second
 * question would lose its context.
 */
@Composable
fun AssistantOverlay(
    viewModel: ChatViewModel,
    voiceManager: VoiceManager,
    onDismiss: () -> Unit,
    screenContext: ScreenContext? = null,
    invocation: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val voiceState by voiceManager.state.collectAsState()
    val listState = rememberLazyListState()
    // Track which assistant messages we've already spoken, plus whether a
    // request has been observed since the overlay opened. Initializing from
    // the current messages doesn't work because history loads asynchronously
    // after composition, so we'd still speak whatever lands first.
    var lastSpokenMessageId by remember { mutableStateOf<String?>(null) }
    var sawLoadingSinceOpen by remember { mutableStateOf(false) }

    var pendingContext by remember(invocation) { mutableStateOf(screenContext) }

    DisposableEffect(invocation) {
        voiceManager.onFinalResult = { text ->
            viewModel.sendMessage(text, pendingContext?.toPromptBlock())
            pendingContext = null
        }
        // Handed back on the way out: the manager is a singleton, and a lambda
        // left behind pins this activity's view model (and its context) for the
        // life of the process.
        onDispose { voiceManager.onFinalResult = null }
    }

    LaunchedEffect(uiState.isLoading) {
        if (uiState.isLoading) sawLoadingSinceOpen = true
    }

    // Only speak responses to requests sent from within this overlay session;
    // history that loads on open just syncs the cursor without speaking.
    LaunchedEffect(uiState.messages.size, uiState.isLoading) {
        if (uiState.isLoading || uiState.messages.isEmpty()) return@LaunchedEffect
        val last = uiState.messages.last()
        if (last.role != "assistant" || last.id == lastSpokenMessageId) return@LaunchedEffect
        val shouldSpeak = sawLoadingSinceOpen
        lastSpokenMessageId = last.id
        if (shouldSpeak) {
            voiceManager.speak(last.content)
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { /* consume click to prevent dismiss */ },
                ),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 3.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Close button
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(8.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }

                // What Maggie is about to read, named before anything is said.
                pendingContext?.source()?.let { source ->
                    Text(
                        text = "Contexte : $source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                // Messages
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.messages) { message ->
                        MessageBubble(message)
                    }
                    if (uiState.isLoading) {
                        item {
                            Text(
                                text = "Maggie réfléchit...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }

                if (voiceState == VoiceState.TRANSCRIBING) {
                    Text(
                        text = "Transcription en cours...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                // Voice control
                VoiceControlBar(voiceManager = voiceManager)
            }
        }
    }
}
