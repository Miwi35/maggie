package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(
    sheetState: SheetState,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    voiceManager: VoiceManager? = null,
) {
    val uiState by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    // Wire voice callbacks when in voice mode
    if (voiceManager != null) {
        val voiceState by voiceManager.state.collectAsState()
        val partialResult by voiceManager.partialResult.collectAsState()

        DisposableEffect(voiceManager) {
            voiceManager.onFinalResult = { text -> viewModel.sendMessage(text) }
            onDispose { voiceManager.onFinalResult = null }
        }

        // Speak assistant responses in voice mode
        LaunchedEffect(uiState.messages.size, uiState.isLoading) {
            if (!uiState.isLoading && uiState.messages.isNotEmpty()) {
                val last = uiState.messages.last()
                if (last.role == "assistant") {
                    voiceManager.speak(last.content)
                }
            }
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader(onClose = onDismiss)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
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

                // Partial STT result
                if (voiceState == VoiceState.LISTENING && partialResult.isNotBlank()) {
                    Text(
                        text = partialResult,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }

                VoiceControlBar(voiceManager = voiceManager)
            }
        }
    } else {
        // Text mode
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.fillMaxHeight(0.85f),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader(onClose = onDismiss)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = { Text("Demander à Maggie...") },
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && !uiState.isLoading,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp),
    ) {
        Text(
            text = "Maggie",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Fermer")
        }
    }
}
