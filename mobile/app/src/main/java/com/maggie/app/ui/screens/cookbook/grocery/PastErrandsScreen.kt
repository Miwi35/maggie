package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Errand
import com.maggie.app.data.model.PurchasedItem
import com.maggie.app.data.model.endedAtLabel
import com.maggie.app.data.model.itemCountLabel
import com.maggie.app.data.model.storeLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

/** « Courses passées » (MAG-488): the errands « Terminer la course » archived, the most recent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastErrandsScreen(
    viewModel: PastErrandsViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = Modifier.testTag(UiTags.PAST_ERRANDS),
        topBar = {
            TopAppBar(
                title = { Text("Courses passées") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading && uiState.hasLoaded,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize().padding(paddingValues),
        ) {
            when {
                uiState.errands.isEmpty() && uiState.error != null -> CenteredMessage(
                    text = uiState.error!!,
                    actionLabel = "Réessayer",
                    onAction = { viewModel.refresh() },
                )

                uiState.isEmpty -> CenteredMessage(
                    text = "Aucune course passée",
                    detail = "Les articles cochés y sont gardés quand tu termines une course.",
                )

                !uiState.hasLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    uiState.error?.let { message ->
                        item(key = "error") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = viewModel::dismissError) { Text("Fermer") }
                            }
                        }
                    }
                    items(uiState.errands, key = { it.id }) { errand ->
                        ErrandRow(errand = errand, onClick = { viewModel.openDetail(errand) })
                    }
                    if (uiState.canLoadMore) {
                        item(key = "more") {
                            Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                                if (uiState.isLoadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                } else {
                                    OutlinedButton(onClick = { viewModel.loadMore() }) {
                                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Voir les courses plus anciennes")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    uiState.selected?.let { errand ->
        ErrandDetailSheet(
            errand = errand,
            isLoading = uiState.isDetailLoading,
            error = uiState.detailError,
            onDismiss = { viewModel.closeDetail() },
        )
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    detail: String? = null,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (actionLabel != null) {
            TextButton(onClick = onAction, modifier = Modifier.padding(top = 8.dp)) { Text(actionLabel) }
        }
    }
}

/** One errand: the store, the day and hour, and how many lines were bought. A tap opens its sheet. */
@Composable
private fun ErrandRow(errand: Errand, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Storefront,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = errand.storeLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = errand.endedAtLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            ItemCountBadge(errand.itemCount)
        }
    }
}

/** The number of lines bought, with its basket: read at a glance, spoken as « 3 articles ». */
@Composable
private fun ItemCountBadge(count: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.clearAndSetSemantics { contentDescription = itemCountLabel(count) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ShoppingBasket, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(text = count.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One errand's lines, as they were bought (MAG-488). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErrandDetailSheet(
    errand: Errand,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(UiTags.PAST_ERRAND_SHEET),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = errand.storeLabel, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = errand.endedAtLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ItemCountBadge(errand.itemCount)
            }
            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))

            when {
                errand.items.isEmpty() && isLoading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                errand.items.isEmpty() -> Text(
                    text = error ?: "Aucun article",
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )

                else -> LazyColumn {
                    items(errand.items) { item ->
                        PurchasedItemRow(item)
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun PurchasedItemRow(item: PurchasedItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = item.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        item.quantityLabel()?.let { quantity ->
            Text(
                text = quantity,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}
