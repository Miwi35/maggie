package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.voice.CropRegion
import com.maggie.app.voice.ScreenContext
import com.maggie.app.voice.ScreenshotEncoder
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

/**
 * [pendingContext] is what the screen behind the overlay was showing when the
 * assistant was summoned (MAG-30), named on screen until it is used. Its screenshot is not
 * sent unless [screenAttached] (MAG-486): « + Écran » ([onAttachScreen]) attaches it, the cross
 * ([onRemoveScreen]) takes it back; [screenshotRegion] is the zone of it the user circled (MAG-316). [onVoiceResult]
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
    screenAttached: Boolean = false,
    onAttachScreen: () -> Unit = {},
    onRemoveScreen: () -> Unit = {},
    screenshotRegion: CropRegion? = null,
    onSelectScreenshotRegion: (CropRegion?) -> Unit = {},
    onVoiceResult: (String) -> Unit,
    onListen: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val voiceState by voiceManager.state.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    DisposableEffect(voiceManager, viewModel) {
        // The answer being written is not shown here, only "Maggie réfléchit…": until
        // she speaks, nothing of it was seen either (MAG-223).
        val release = voiceManager.addInterruptListener { heard ->
            viewModel.interrupt(heard, streamingIsShown = false)
        }
        // Handed back on the way out: the manager is a singleton, and a lambda
        // left behind pins this activity's view model for the life of the process.
        onDispose { release() }
    }

    SpokenReplies(viewModel, voiceManager)
    SpokenApprovals(viewModel, voiceManager, onListen)

    LaunchedEffect(uiState.messages.size, uiState.failure) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - (if (uiState.failure == null) 1 else 0))
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
                if (screenAttached) {
                    if (pendingContext?.hasScreenshot == true) {
                        // The session's own file, never a path from the intent; keyed on its
                        // date too, since each invocation overwrites the same file.
                        val file = ScreenshotEncoder.file(context)
                        val image = remember(pendingContext, file.lastModified()) {
                            runCatching { ScreenshotEncoder.decodeThumbnail(file.readBytes()) }
                                .getOrNull()?.asImageBitmap()
                        }
                        if (image != null) {
                            ScreenshotSelector(
                                image,
                                region = screenshotRegion,
                                onSelect = onSelectScreenshotRegion,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            )
                        } else {
                            ScreenshotStatus("Capture d'écran indisponible : elle n'a pas pu être lue")
                        }
                    }
                    // No thumbnail without a word (MAG-214): still coming, or why it is not.
                    pendingContext?.screenshotStatus()?.let { ScreenshotStatus(it) }
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
                    if (uiState.answering) {
                        item {
                            // Cuts the voice if it is heard, then tells the agent, like a tap on the mic.
                            TextButton(
                                onClick = {
                                    if (!voiceManager.interrupt()) viewModel.interrupt(streamingIsShown = false)
                                },
                                modifier = Modifier.testTag(UiTags.CHAT_STOP),
                            ) {
                                Text("Arrêter")
                            }
                        }
                    }
                    uiState.failure?.let { failure ->
                        item { ChatFailureNotice(failure, onRetry = viewModel::retry) }
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

                // Nothing is attached by default (MAG-486): the capture waits on the phone for a touch.
                val screenOffered = pendingContext?.let { it.hasScreenshot || it.screenshotPending } == true
                if (screenAttached || screenOffered) {
                    ScreenChip(
                        attached = screenAttached,
                        source = pendingContext?.source(),
                        onAttach = onAttachScreen,
                        onRemove = onRemoveScreen,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                // Voice control
                VoiceControlBar(voiceManager = voiceManager, onResult = onVoiceResult)
            }
        }
    }
}

/** « + Écran » until touched, then « Écran · app » with the cross that takes it back (MAG-486). */
@Composable
private fun ScreenChip(
    attached: Boolean,
    source: String?,
    onAttach: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!attached) {
        AssistChip(
            onClick = onAttach,
            label = { Text("+ Écran") },
            modifier = modifier.testTag(UiTags.ASSIST_SCREEN_ADD),
        )
        return
    }
    Surface(
        modifier = modifier.testTag(UiTags.ASSIST_SCREEN_ATTACHMENT),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOfNotNull("Écran", source).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
            )
            IconButton(onClick = onRemove, modifier = Modifier.testTag(UiTags.ASSIST_SCREEN_REMOVE)) {
                Icon(Icons.Default.Close, contentDescription = "Retirer l'écran")
            }
        }
    }
}

@Composable
private fun ScreenshotStatus(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp)
            .testTag(UiTags.ASSIST_SCREENSHOT_STATUS),
    )
}
