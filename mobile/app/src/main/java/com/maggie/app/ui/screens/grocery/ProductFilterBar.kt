package com.maggie.app.ui.screens.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.AisleFilter
import com.maggie.app.data.model.ProductSort
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.uiTagRoot

private enum class FilterSheetKind { STORE, AISLE, SORT }

/** One row of a filter's bottom sheet; a disabled row says it cannot be chosen yet. */
private data class FilterOption(
    val label: String,
    val selected: Boolean,
    val enabled: Boolean = true,
    val onPick: () -> Unit,
)

/**
 * The filters of the products list (MAG-606): Magasin, Rayon (of that store) and Trier, each a chip that
 * opens its choices in a bottom sheet. Rayon waits for a store, and so does the sort by rayon.
 */
@Composable
internal fun ProductFilterBar(
    state: ProductUiState,
    onStore: (String?) -> Unit,
    onAisle: (AisleFilter) -> Unit,
    onSort: (ProductSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf<FilterSheetKind?>(null) }
    val query = state.query
    val store = state.stores.firstOrNull { it.id == query.storeId }
    val aisleName = when (val aisle = query.aisle) {
        AisleFilter.All -> null
        AisleFilter.None -> NO_AISLE_TITLE
        is AisleFilter.One -> state.storeAisles.firstOrNull { it.id == aisle.id }?.name
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterDropChip(
            label = store?.let { "Magasin : ${it.name}" } ?: "Magasin",
            active = store != null,
            enabled = true,
            tag = UiTags.PRODUCT_FILTER_STORE,
            onClick = { open = FilterSheetKind.STORE },
        )
        FilterDropChip(
            label = aisleName?.let { "Rayon : $it" } ?: "Rayon",
            active = aisleName != null,
            enabled = store != null,
            tag = UiTags.PRODUCT_FILTER_AISLE,
            onClick = { open = FilterSheetKind.AISLE },
        )
        FilterDropChip(
            label = "Trier : ${query.sort.label}",
            active = query.sort != ProductSort.NAME,
            enabled = true,
            tag = UiTags.PRODUCT_FILTER_SORT,
            onClick = { open = FilterSheetKind.SORT },
        )
    }

    val kind = open ?: return
    val close = { open = null }
    val (title, options) = when (kind) {
        FilterSheetKind.STORE -> "Magasin" to
            listOf(FilterOption("Tous les magasins", query.storeId == null) { onStore(null) }) +
            state.stores.map { s -> FilterOption(s.name, s.id == query.storeId) { onStore(s.id) } }

        FilterSheetKind.AISLE -> "Rayon" to
            listOf(FilterOption("Tous les rayons", query.aisle == AisleFilter.All) { onAisle(AisleFilter.All) }) +
            state.storeAisles.map { a ->
                FilterOption(a.name, query.aisle == AisleFilter.One(a.id)) { onAisle(AisleFilter.One(a.id)) }
            } +
            FilterOption(NO_AISLE_TITLE, query.aisle == AisleFilter.None) { onAisle(AisleFilter.None) }

        FilterSheetKind.SORT -> "Trier" to ProductSort.entries.map { sort ->
            FilterOption(
                label = sort.label,
                selected = sort == query.sort,
                enabled = sort != ProductSort.AISLE || query.storeId != null,
            ) { onSort(sort) }
        }
    }
    FilterSheet(title = title, options = options, onPick = { it.onPick(); close() }, onDismiss = close)
}

@Composable
private fun FilterDropChip(label: String, active: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        modifier = Modifier.testTag(tag),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(
    title: String,
    options: List<FilterOption>,
    onPick: (FilterOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        modifier = Modifier.uiTagRoot(),
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(options) { option ->
                ListItem(
                    headlineContent = { Text(option.label) },
                    trailingContent = {
                        if (option.selected) Icon(Icons.Default.Check, contentDescription = "Choisi")
                    },
                    modifier = Modifier
                        .testTag(UiTags.PRODUCT_FILTER_OPTION)
                        .alpha(if (option.enabled) 1f else 0.4f)
                        .clickable(enabled = option.enabled) { onPick(option) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
