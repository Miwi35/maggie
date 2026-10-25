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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
    onMicClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberChatListState(viewModel)

    if (voiceManager != null) {
        val voiceState by voiceManager.state.collectAsState()

        DisposableEffect(voiceManager, viewModel) {
            val release = voiceManager.addInterruptListener { heard -> viewModel.interrupt(heard) }
            onDispose { release() }
        }

        SpokenReplies(viewModel, voiceManager)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            modifier = Modifier.fillMaxHeight(0.85f),
        ) {
            // A sheet is a window of its own, with its own semantics root, so
            // `uiTagRoot()` goes here too (MAG-98): without it `voice_state` and
            // `chat_close` are invisible to Maestro.
            Column(modifier = Modifier.fillMaxWidth().uiTagRoot()) {
                ChatHeader(onClose = onDismiss, onThreads = onOpenThreads)

                ChatTabbedBody(
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
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            BottomSheetDefaults.DragHandle()
                        }

                        ChatHeader(onClose = onDismiss, onThreads = onOpenThreads)

                        ChatTabbedBody(
                            viewModel = viewModel,
                            listState = listState,
                            modifier = Modifier.weight(1f),
                        )

                        ChatSnackbarHost(viewModel)

                        ChatInputRow(
                            viewModel = viewModel,
                            isSending = uiState.isLoading,
                            focusRequester = focusRequester,
                            leading = { ChatMicButton(onMicClick) },
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
    onClosePanel: (() -> Unit)? = null,
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
        // Edge to edge, and the frame adds no inset: without these, the header's
        // buttons sit in the status-bar strip, where a tap opens the
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
            ChatHeader(onClose = onClosePanel, onThreads = onOpenThreads, showsIdentity = true)

            ChatTabbedBody(
                viewModel = viewModel,
                listState = listState,
                modifier = Modifier.weight(1f),
            )

            ChatSnackbarHost(viewModel)

            ChatInputRow(
                viewModel = viewModel,
                isSending = uiState.isLoading,
                windowIsResizedByKeyboard = true,
                leading = { ChatMicButton(onMicClick) },
            )
        }
    }
}

/**
 * « Chat | Mind » over the conversation, or over what Maggie is doing (MAG-403). Under the
 * tabs only the middle changes: the input row or the voice bar stays, so a request sent from
 * the Mind tab can be watched as it runs.
 */
@Composable
private fun ChatTabbedBody(
    viewModel: ChatViewModel,
    listState: ChatListState,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var tab by rememberSaveable { mutableStateOf(ChatTab.CHAT) }

    Column(modifier = modifier) {
        ChatTabs(selected = tab, onSelect = { tab = it }, awaitingCount = uiState.awaitingApprovalCount)
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                ChatTab.CHAT -> ChatHistory(viewModel = viewModel, listState = listState, modifier = Modifier.fillMaxSize())
                ChatTab.MIND -> MindContent(
                    agentState = uiState.agentState,
                    toolCalls = uiState.toolCalls,
                    approvals = uiState.pendingApprovals,
                    onApprove = viewModel::approve,
                    onDeny = viewModel::deny,
                    onDismissApproval = viewModel::dismissApproval,
                )
            }
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
        editableMessageId = uiState.editableMessageId,
        onEditMessage = viewModel::editMessage,
        onRateMessage = viewModel::rateMessage,
    )

    // Keyed on the answer: a new thumb down opens an empty field.
    uiState.commentingMessageId?.let { id ->
        key(id) {
            FeedbackCommentDialog(onSend = viewModel::sendFeedbackComment, onSkip = viewModel::skipFeedbackComment)
        }
    }
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

    ErrorSnackbar(
        error = "feedback".takeIf { uiState.messageFeedbackFailed },
        snackbarHostState = hostState,
        onDismiss = viewModel::consumeMessageFeedbackFailed,
        message = "Votre avis n'a pas été enregistré. Réessayez.",
    )

    SnackbarHost(hostState)
}

/**
 * The threads and the ✕, nothing else (MAG-624, `Chat-Tel`): the sheet has no title and no
 * search — the search button opened nothing the app bar does not already open.
 *
 * @param onClose `null` when there is nothing to dismiss.
 * @param showsIdentity the tablet's drawer names its owner — Maggie's face and her name — since
 * nothing else on the page says whose conversation it is; the sheet comes from her field.
 */
@Composable
private fun ChatHeader(onClose: (() -> Unit)?, onThreads: () -> Unit, showsIdentity: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp),
    ) {
        if (showsIdentity) {
            Row(modifier = Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                MaggieAvatar(size = 32.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(text = "Maggie", style = MaterialTheme.typography.titleMedium)
            }
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            // The threads are how the one conversation is filed: a discreet icon (MAG-342).
            IconButton(onClick = onThreads, modifier = Modifier.testTag(UiTags.CHAT_THREADS)) {
                Icon(Icons.Outlined.Forum, contentDescription = "Fils de discussion")
            }
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.testTag(UiTags.CHAT_CLOSE)) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
        }
    }
}

/**
 * @param leading what sits left of the field — the panel puts the mic there.
 * @param windowIsResizedByKeyboard the sheet is a dialog, which the keyboard does not
 * resize, so its row lifts itself by the keyboard's height. The panel is in the
 * activity's own window, which the system already shrinks above the keyboard: lifting
 * it again pushed the field out of the top of the screen on a tablet.
 */
@Composable
private fun ChatInputRow(
    viewModel: ChatViewModel,
    isSending: Boolean,
    focusRequester: FocusRequester? = null,
    windowIsResizedByKeyboard: Boolean = false,
    leading: @Composable () -> Unit = {},
) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val uiState by viewModel.uiState.collectAsState()

    // « Arrêter » and « Modifier » bring a question back: the cursor goes after its last word.
    LaunchedEffect(uiState.inputToRestore) {
        val text = uiState.inputToRestore ?: return@LaunchedEffect
        input = TextFieldValue(text, TextRange(text.length))
        viewModel.consumeInputToRestore()
        focusRequester?.requestFocus()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                if (windowIsResizedByKeyboard) {
                    WindowInsets.navigationBars
                } else {
                    WindowInsets.ime.union(WindowInsets.navigationBars)
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                viewModel.onInputChanged(it.text)
            },
            modifier = Modifier
                .weight(1f)
                .testTag(UiTags.CHAT_INPUT)
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            placeholder = { Text("Écrire à Maggie…") },
            singleLine = true,
            shape = CircleShape,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (uiState.answering) {
            FilledIconButton(
                onClick = viewModel::stop,
                modifier = Modifier.testTag(UiTags.CHAT_STOP),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(Icons.Default.Stop, contentDescription = "Arrêter")
            }
        } else {
            FilledIconButton(
                onClick = {
                    viewModel.sendMessage(input.text)
                    input = TextFieldValue("")
                },
                modifier = Modifier.testTag(UiTags.CHAT_SEND),
                enabled = input.text.isNotBlank() && !isSending,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Envoyer")
            }
        }
    }
}
