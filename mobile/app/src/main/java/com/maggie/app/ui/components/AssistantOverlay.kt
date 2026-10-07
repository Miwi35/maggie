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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.voice.ScreenContext
import com.maggie.app.voice.ScreenshotEncoder
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState
import java.io.File

/**
 * [pendingContext] is what the screen behind the overlay was showing when the
 * assistant was summoned (MAG-30), named on screen until it is used. [onVoiceResult]
 * receives what the microphone heard: the activity, which starts the listening,
 * hands it in, so the sentence goes where the listening was asked for. [onListen]
 * reopens the microphone once a held action's question has been read, for its answer.
 */
@Composable
fun AssistantOverlay(
    viewModel: ChatViewModel,
    voiceManager: VoiceManager,
    onDismiss: () -> Unit,
    pendingContext: ScreenContext? = null,
    onVoiceResult: (String) -> Unit,
    onListen: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val voiceState by voiceManager.state.collectAsState()
    val listState = rememberLazyListState()

    SpokenReplies(viewModel, voiceManager)
    SpokenApprovals(viewModel, voiceManager, onListen)

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .uiTagRoot()
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
                pendingContext?.screenshotPath?.let { path ->
                    val image = remember(path) {
                        runCatching { ScreenshotEncoder.decodeThumbnail(File(path).readBytes()) }
                            .getOrNull()?.asImageBitmap()
                    }
                    if (image != null) {
                        ScreenshotImage(
                            image,
                            contentDescription = "Capture d'écran à envoyer",
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }
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
                        MessageBubble(message, thumbnail = uiState.thumbnails[message.id])
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

                // Held actions stay in view whatever the conversation above scrolls to:
                // they wait for an answer, by touch or by voice (MAG-310).
                if (uiState.pendingApprovals.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        uiState.pendingApprovals.forEach { item ->
                            ApprovalCard(
                                item = item,
                                onApprove = { viewModel.approve(item.approval.id) },
                                onDeny = { viewModel.deny(item.approval.id) },
                                onDismiss = { viewModel.dismissApproval(item.approval.id) },
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
                VoiceControlBar(voiceManager = voiceManager, onResult = onVoiceResult)
            }
        }
    }
}
