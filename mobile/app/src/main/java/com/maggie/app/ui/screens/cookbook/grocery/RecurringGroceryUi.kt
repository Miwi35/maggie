package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.RecurringFrequency
import com.maggie.app.data.model.RecurringGroceryItem
import com.maggie.app.data.model.apiValue
import com.maggie.app.data.model.label
import com.maggie.app.data.model.nextDueLabel
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

const val TAB_LIST = 0
const val TAB_RECURRING = 1

/** « Liste » and « Récurrents », the grocery screen's two tabs. */
@Composable
fun GroceryTabs(selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected) {
        Tab(
            selected = selected == TAB_LIST,
            onClick = { onSelect(TAB_LIST) },
            text = { Text("Liste") },
            modifier = Modifier.testTag(UiTags.GROCERY_TAB_LIST),
        )
        Tab(
            selected = selected == TAB_RECURRING,
            onClick = { onSelect(TAB_RECURRING) },
            text = { Text("Récurrents") },
            modifier = Modifier.testTag(UiTags.GROCERY_TAB_RECURRING),
        )
    }
}

/** « Non » and the three frequencies, one tap each; [allowNone] is off where « Non » means nothing (the add dialog). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecurrenceChips(
    selected: RecurringFrequency?,
    onSelect: (RecurringFrequency?) -> Unit,
    modifier: Modifier = Modifier,
    allowNone: Boolean = true,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allowNone) {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Non") },
                modifier = Modifier.testTag(UiTags.recurringOption("none")),
            )
        }
        RecurringFrequency.entries.forEach { frequency ->
            FilterChip(
                selected = selected == frequency,
                onClick = { onSelect(frequency) },
                label = { Text(frequency.label()) },
                modifier = Modifier.testTag(UiTags.recurringOption(frequency.apiValue())),
            )
        }
    }
}

/** The « Récurrent » setting of the product card and of the line's detail: applied on tap, not on « Enregistrer ». */
@Composable
fun RecurringSetting(
    recurring: RecurringFrequency?,
    onChange: (RecurringFrequency?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Récurrent", style = MaterialTheme.typography.labelLarge)
        RecurrenceChips(selected = recurring, onSelect = onChange)
    }
}

/** Under a list line whose product comes back by itself. */
@Composable
fun RecurringIndicator(frequency: RecurringFrequency, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .testTag(UiTags.RECURRING_INDICATOR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Repeat,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = frequency.label(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The « Récurrents » tab: label, frequency and next due date; add through the product search, change, remove. */
@Composable
fun RecurringGroceryTab(
    viewModel: RecurringGroceryViewModel,
    products: List<Product>,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    var showPicker by remember { mutableStateOf(false) }
    var pickedProduct by remember { mutableStateOf<Product?>(null) }
    var editing by remember { mutableStateOf<RecurringGroceryItem?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        uiState.error?.let { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissError) { Text("Fermer") }
            }
        }

        OutlinedButton(
            onClick = { showPicker = true },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(UiTags.RECURRING_ADD),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Ajouter un produit")
        }

        when {
            uiState.isLoading && uiState.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.items.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aucun article récurrent", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(uiState.items, key = { it.id }) { item ->
                    RecurringRow(
                        item = item,
                        onClick = { editing = item },
                        onRemove = { viewModel.remove(item) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showPicker) {
        // A recurring item is a product's: the search offers no « Utiliser … » free label.
        ProductPickerDialog(
            products = products,
            onSelectProduct = {
                pickedProduct = it
                showPicker = false
            },
            onSelectCustomLabel = null,
            onDismiss = { showPicker = false },
        )
    }

    pickedProduct?.let { product ->
        FrequencyDialog(
            title = product.name,
            initial = RecurringFrequency.WEEKLY,
            confirmLabel = "Ajouter",
            onConfirm = {
                viewModel.setFrequency(product.id, it)
                pickedProduct = null
            },
            onDismiss = { pickedProduct = null },
        )
    }

    editing?.let { item ->
        FrequencyDialog(
            title = item.label,
            initial = item.frequency,
            confirmLabel = "Enregistrer",
            onConfirm = {
                viewModel.changeFrequency(item, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun RecurringRow(
    item: RecurringGroceryItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = item.frequency.label(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.nextDueLabel()?.let { date ->
                Text(
                    text = "Prochain ajout : $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Retirer ${item.label}")
        }
    }
}

@Composable
private fun FrequencyDialog(
    title: String,
    initial: RecurringFrequency,
    confirmLabel: String,
    onConfirm: (RecurringFrequency) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf(initial) }
    AlertDialog(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            RecurrenceChips(
                selected = chosen,
                onSelect = { frequency -> frequency?.let { chosen = it } },
                allowNone = false,
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
