package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maggie.app.data.api.EndErrandRemainingItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemainingItemsSheet(
    storeName: String,
    items: List<EndErrandRemainingItem>,
    restockedCount: Int = 0,
    onTransferClick: (EndErrandRemainingItem) -> Unit,
    onKeepClick: (EndErrandRemainingItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Articles non achetés à $storeName",
                style = MaterialTheme.typography.titleMedium,
            )
            restockedProductsMessage(restockedCount)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = "Transfère vers un autre magasin ou garde sur la liste pour la prochaine fois.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val subtitle = buildString {
                            item.quantity?.let { q ->
                                append(if (q == q.toLong().toFloat()) q.toLong().toString() else q.toString())
                                item.unit?.let { append(" "); append(it) }
                            }
                        }
                        if (subtitle.isNotBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = { onTransferClick(item) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Default.Store,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("Transférer")
                            }
                            OutlinedButton(
                                onClick = { onKeepClick(item) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text("Garder")
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.size(16.dp))
        }
    }
}
