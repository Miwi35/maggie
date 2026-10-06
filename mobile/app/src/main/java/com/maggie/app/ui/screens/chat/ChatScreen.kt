package com.maggie.app.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.ChatMessage
import com.maggie.app.ui.components.ChatMessageList

@Composable
fun ChatScreen(viewModel: ChatViewModel, draft: String = "") {
    val uiState by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(draft) {
        if (draft.isNotEmpty()) input = draft
    }
    val listState = rememberLazyListState()

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

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.isSearchMode) {
            ChatSearchBar(
                query = uiState.searchQuery,
                onQueryChanged = viewModel::onSearchQueryChanged,
                onClose = viewModel::closeSearch,
            )
            if (uiState.isSearchLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.CenterHorizontally),
                )
            } else if (uiState.searchResults.isNotEmpty()) {
                ChatSearchResults(
                    results = uiState.searchResults,
                    onResultClick = viewModel::navigateToMessage,
                    modifier = Modifier.weight(1f),
                )
            } else if (uiState.searchQuery.length >= 2) {
                Text(
                    text = "Aucun résultat",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = viewModel::openSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Rechercher")
                }
            }

            ChatMessageList(
                displayItems = uiState.displayItems,
                listState = listState,
                isLoadingHistory = uiState.isLoadingHistory,
                onLoadMore = viewModel::loadOlderMessages,
                onMessageTapped = viewModel::onMessageTapped,
                modifier = Modifier.weight(1f),
                approvals = uiState.pendingApprovals,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onDismissApproval = viewModel::dismissApproval,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // Edge to edge, no inset from the Scaffold: keeps the field above a tablet's taskbar.
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
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

@Composable
private fun ChatSearchBar(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Rechercher...") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Fermer la recherche")
        }
    }
}

@Composable
private fun ChatSearchResults(
    results: List<ChatMessage>,
    onResultClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(results, key = { it.id }) { message ->
            Surface(
                onClick = { onResultClick(message.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                    )
                    Text(
                        text = message.createdAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
