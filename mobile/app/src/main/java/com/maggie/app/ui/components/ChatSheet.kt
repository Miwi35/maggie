package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.screens.chat.ScrollBehavior
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

    // Handle scroll commands from ViewModel
    LaunchedEffect(uiState.scrollToIndex, uiState.scrollBehavior) {
        val index = uiState.scrollToIndex ?: return@LaunchedEffect
        when (uiState.scrollBehavior) {
            ScrollBehavior.ANIMATE_TO_BOTTOM -> listState.animateScrollToItem(index)
            ScrollBehavior.INSTANT_TO_INDEX -> listState.scrollToItem(index)
            ScrollBehavior.NONE -> {}
        }
        viewModel.consumeScroll()
    }

    // Detect scroll-to-bottom for unread clearing
    LaunchedEffect(listState) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 2
        }.collect { isAtBottom ->
            if (isAtBottom) {
                viewModel.onScrolledToBottom()
            }
        }
    }

    if (voiceManager != null) {
        val voiceState by voiceManager.state.collectAsState()

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
                SheetHeader(onClose = onDismiss, onSearch = viewModel::openSearch)

                ChatMessageList(
                    displayItems = uiState.displayItems,
                    listState = listState,
                    isLoadingHistory = uiState.isLoadingHistory,
                    onLoadMore = viewModel::loadOlderMessages,
                    onMessageTapped = viewModel::onMessageTapped,
                    modifier = Modifier.weight(1f),
                )

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
                SheetHeader(onClose = onDismiss, onSearch = viewModel::openSearch)

                ChatMessageList(
                    displayItems = uiState.displayItems,
                    listState = listState,
                    isLoadingHistory = uiState.isLoadingHistory,
                    onLoadMore = viewModel::loadOlderMessages,
                    onMessageTapped = viewModel::onMessageTapped,
                    modifier = Modifier.weight(1f),
                )

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
private fun SheetHeader(onClose: () -> Unit, onSearch: () -> Unit) {
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
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Rechercher")
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Fermer")
            }
        }
    }
}
