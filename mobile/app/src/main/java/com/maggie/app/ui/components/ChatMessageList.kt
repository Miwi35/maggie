package com.maggie.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.screens.chat.ChatListItem

@Composable
fun ChatMessageList(
    displayItems: List<ChatListItem>,
    listState: LazyListState,
    isLoadingHistory: Boolean,
    onLoadMore: () -> Unit,
    onMessageTapped: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Infinite scroll: trigger load when near the top
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstVisible ->
                if (firstVisible <= 2 && !isLoadingHistory) {
                    onLoadMore()
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
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
                    is ChatListItem.LoadingIndicator -> "loading_indicator"
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
                is ChatListItem.LoadingIndicator -> {
                    Text(
                        text = "Maggie réfléchit...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
