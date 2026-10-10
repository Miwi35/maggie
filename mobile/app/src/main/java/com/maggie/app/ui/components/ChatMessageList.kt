package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.chat.ApprovalItem
import com.maggie.app.ui.screens.chat.ChatListItem
import kotlinx.coroutines.launch

@Composable
fun ChatMessageList(
    displayItems: List<ChatListItem>,
    listState: ChatListState,
    isLoadingHistory: Boolean,
    onLoadMore: () -> Unit,
    onMessageTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    approvals: List<ApprovalItem> = emptyList(),
    onApprove: (String) -> Unit = {},
    onDeny: (String) -> Unit = {},
    onDismissApproval: (String) -> Unit = {},
) {
    val lazy = listState.lazy
    val scope = rememberCoroutineScope()

    // Infinite scroll: trigger load when near the top
    LaunchedEffect(lazy) {
        snapshotFlow { lazy.firstVisibleItemIndex }
            .collect { firstVisible ->
                if (firstVisible <= 2 && !isLoadingHistory) {
                    onLoadMore()
                }
            }
    }

    // Follow the latest message while the user has not pulled the list up: a new one, an
    // answer growing, a keyboard or a card changing the height all move the end.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = lazy.layoutInfo
            Triple(info.totalItemsCount, info.visibleItemsInfo.lastOrNull()?.size, info.viewportSize.height)
        }.collect {
            if (listState.following && !listState.dragging) listState.scrollToLatest(animate = false)
        }
    }
    val streamedLength = (displayItems.lastOrNull() as? ChatListItem.StreamingMessage)?.text?.length
    LaunchedEffect(displayItems.size, streamedLength) {
        if (!listState.following) listState.unseenBelow = true
    }

    // The held actions sit under the conversation, right above the input field:
    // they are what Maggie is waiting on, so they stay in view while the history scrolls.
    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = lazy,
                modifier = Modifier.fillMaxSize().testTag(UiTags.CHAT_MESSAGES),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isLoadingHistory) {
                    item(key = "loading_history") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }

                itemsIndexed(
                    items = displayItems,
                    key = { index, item ->
                        when (item) {
                            is ChatListItem.DateSeparator -> "sep_${index}_${item.label}"
                            is ChatListItem.UnreadDivider -> "unread_divider"
                            is ChatListItem.MessageItem -> "msg_${item.message.id}"
                            is ChatListItem.StreamingMessage -> "streaming_message"
                            is ChatListItem.LoadingIndicator -> "loading_indicator"
                            is ChatListItem.Failure -> "failure"
                        }
                    },
                ) { _, item ->
                    when (item) {
                        is ChatListItem.DateSeparator -> {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                        }
                        is ChatListItem.UnreadDivider -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                )
                                Text(
                                    text = "Messages non lus",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                )
                            }
                        }
                        is ChatListItem.MessageItem -> {
                            MessageBubble(
                                message = item.message,
                                onClick = { onMessageTapped(item.message.id) },
                                isHighlighted = item.isHighlighted,
                            )
                        }
                        is ChatListItem.StreamingMessage -> {
                            MessageBubble(
                                message = com.maggie.app.data.model.ChatMessage(
                                    id = "streaming",
                                    role = "assistant",
                                    content = item.text,
                                ),
                                onClick = {},
                                isHighlighted = false,
                            )
                        }
                        is ChatListItem.LoadingIndicator -> {
                            Text(
                                text = "Maggie réfléchit...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        is ChatListItem.Failure -> ChatFailureNotice(item.text, onRetry)
                    }
                }
            }

            if (listState.unseenBelow && !listState.following) {
                FilledTonalButton(
                    onClick = { scope.launch { listState.scrollToLatest(animate = true) } },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .testTag(UiTags.CHAT_JUMP_TO_LATEST),
                ) {
                    Text("↓ nouvelle réponse")
                }
            }
        }

        if (approvals.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                approvals.forEach { item ->
                    ApprovalCard(
                        item = item,
                        onApprove = { onApprove(item.approval.id) },
                        onDeny = { onDeny(item.approval.id) },
                        onDismiss = { onDismissApproval(item.approval.id) },
                    )
                }
            }
        }
    }
}

/**
 * What the thread says when a question got no answer: a line of state under the question, with
 * the way to send it again. Not a message of Maggie's (MAG-363).
 */
@Composable
fun ChatFailureNotice(text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(UiTags.CHAT_RETRY)) {
            Text("Réessayer")
        }
    }
}
