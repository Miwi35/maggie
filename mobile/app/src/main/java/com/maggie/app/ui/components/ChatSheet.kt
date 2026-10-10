package com.maggie.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.layout.CHAT_PANEL_WIDTH
import com.maggie.app.ui.screens.chat.ChatViewModel
import com.maggie.app.ui.uiTagRoot
import com.maggie.app.voice.VoiceManager
import com.maggie.app.voice.VoiceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSheet(
    sheetState: SheetState,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    voiceManager: VoiceManager? = null,
    onOpenThreads: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberChatListState(viewModel)

    if (voiceManager != null) {
        val voiceState by voiceManager.state.collectAsState()

        SpokenReplies(viewModel, voiceManager)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.fillMaxHeight(0.85f),
        ) {
            // A sheet is a window of its own, with its own semantics root, so
            // `uiTagRoot()` goes here too (MAG-98): without it `voice_state` and
            // `chat_close` are invisible to Maestro.
            Column(modifier = Modifier.fillMaxWidth().uiTagRoot()) {
                ChatHeader(onClose = onDismiss, onSearch = viewModel::openSearch, onThreads = onOpenThreads)

                ChatHistory(
                    viewModel = viewModel,
                    listState = listState,
                    modifier = Modifier.weight(1f),
                )

                ChatSnackbarHost(viewModel)

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

                VoiceControlBar(voiceManager = voiceManager, onResult = { viewModel.sendMessage(it) })
            }
        }
    } else {
        // Text mode — Dialog-based for precise keyboard handling
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            val scrimColor = BottomSheetDefaults.ScrimColor

            // Its own window, so its own tag root (MAG-98): `chat_input` and
            // `chat_send` are below here, and the activity's root cannot reach
            // them — which is what the first CI run of this harness found out.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .uiTagRoot()
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = BottomSheetDefaults.ContainerColor,
                    tonalElevation = BottomSheetDefaults.Elevation,
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ChatHeader(onClose = onDismiss, onSearch = viewModel::openSearch, onThreads = onOpenThreads)

                        ChatHistory(
                            viewModel = viewModel,
                            listState = listState,
                            modifier = Modifier.weight(1f),
                        )

                        ChatSnackbarHost(viewModel)

                        ChatInputRow(
                            viewModel = viewModel,
                            isSending = uiState.isLoading,
                            focusRequester = focusRequester,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The conversation kept on screen beside the content, on a window wide enough for
 * it (MAG-35) — from 840 dp, where the sheet would cover a tablet's content to
 * answer a question about it.
 *
 * The same conversation as [ChatSheet]: same header, same history, same input. What
 * it does not have is anything to dismiss — it is the layout, not an overlay — and
 * what it gains is the mic the collapsed [ChatBottomBar] carried, which is
 * the bar the panel replaces. The threads are one icon of the header, as in the sheet (MAG-342). The mic still opens the sheet in voice mode: a
 * push-to-talk session wants `VoiceControlBar`'s big state line, not a text field.
 */
@Composable
fun ChatPanel(
    viewModel: ChatViewModel,
    onMicClick: () -> Unit = {},
    onOpenThreads: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberChatListState(viewModel)

    Surface(
        modifier = modifier
            .width(CHAT_PANEL_WIDTH)
            .fillMaxHeight()
            .testTag(UiTags.CHAT_PANEL),
        tonalElevation = 1.dp,
    ) {
        // Edge to edge, and the frame adds no inset: without these, « Maggie » and
        // the search button sit in the status-bar strip, where a tap opens the
        // notification shade. Inside the Surface, as on `ChatBottomBar`, so the
        // panel's tonal colour still paints under the bar instead of stopping at a
        // seam. The bottom one is on the input row; the rest of the frame has its own.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End),
                ),
        ) {
            ChatHeader(onClose = null, onSearch = viewModel::openSearch, onThreads = onOpenThreads)

            ChatHistory(
                viewModel = viewModel,
                listState = listState,
                modifier = Modifier.weight(1f),
            )

            ChatSnackbarHost(viewModel)

            ChatInputRow(
                viewModel = viewModel,
                isSending = uiState.isLoading,
                leading = { ChatMicButton(onMicClick) },
            )
        }
    }
}

@Composable
private fun ChatHistory(
    viewModel: ChatViewModel,
    listState: ChatListState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    ChatMessageList(
        displayItems = uiState.displayItems,
        listState = listState,
        isLoadingHistory = uiState.isLoadingHistory,
        onLoadMore = viewModel::loadOlderMessages,
        onMessageTapped = viewModel::onMessageTapped,
        modifier = modifier,
        onRetry = viewModel::retry,
        approvals = uiState.pendingApprovals,
        onApprove = viewModel::approve,
        onDeny = viewModel::deny,
        onDismissApproval = viewModel::dismissApproval,
        onDeleteMessage = viewModel::deleteMessage,
    )
}

/**
 * « Message supprimé · Annuler » and the failure of a deletion, above the input field.
 *
 * The undo snackbar has no timer of its own: the ViewModel owns the window, and clears
 * [ChatUiState.undoableMessageDeletion] when it closes, which ends the effect and with it the
 * snackbar. Inside each surface and not on the screen's scaffold: the sheet is a window of its
 * own, drawn over the scaffold's host.
 */
@Composable
private fun ChatSnackbarHost(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val hostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.undoableMessageDeletion?.id) {
        if (uiState.undoableMessageDeletion == null) return@LaunchedEffect
        val result = hostState.showSnackbar(
            message = "Message supprimé",
            actionLabel = "Annuler",
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoMessageDeletion()
    }

    ErrorSnackbar(
        error = "delete".takeIf { uiState.messageDeleteFailed },
        snackbarHostState = hostState,
        onDismiss = viewModel::consumeMessageDeleteFailed,
        message = "La suppression a échoué. Réessayez.",
    )

    SnackbarHost(hostState)
}

/** @param onClose `null` in the permanent panel, which is not an overlay to dismiss. */
@Composable
private fun ChatHeader(onClose: (() -> Unit)?, onSearch: () -> Unit, onThreads: () -> Unit) {
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
            // The threads are how the one conversation is filed: a discreet icon beside the
            // search, which is the other way of finding something in it (MAG-342).
            IconButton(onClick = onThreads, modifier = Modifier.testTag(UiTags.CHAT_THREADS)) {
                Icon(Icons.Outlined.Forum, contentDescription = "Fils de discussion")
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Rechercher")
            }
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.testTag(UiTags.CHAT_CLOSE)) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
        }
    }
}

/** @param leading what sits left of the field — the panel puts the mic there. */
@Composable
private fun ChatInputRow(
    viewModel: ChatViewModel,
    isSending: Boolean,
    focusRequester: FocusRequester? = null,
    leading: @Composable () -> Unit = {},
) {
    var input by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier
                .weight(1f)
                .testTag(UiTags.CHAT_INPUT)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            placeholder = { Text("Demander à Maggie...") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = {
                viewModel.sendMessage(input)
                input = ""
            },
            modifier = Modifier.testTag(UiTags.CHAT_SEND),
            enabled = input.isNotBlank() && !isSending,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
        }
    }
}
