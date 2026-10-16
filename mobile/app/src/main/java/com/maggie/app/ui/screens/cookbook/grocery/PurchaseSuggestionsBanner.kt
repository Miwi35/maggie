package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.PurchaseSuggestion
import com.maggie.app.ui.UiTags

/**
 * « Souvent pris » (MAG-495): the products due again by their rhythm, above the list.
 * One gesture per action — add it, or « Pas besoin » — and nothing to read but the
 * product and how often it is bought. Absent when there is nothing to suggest.
 */
@Composable
fun PurchaseSuggestionsBanner(
    suggestions: List<PurchaseSuggestion>,
    onAdd: (PurchaseSuggestion) -> Unit,
    onSnooze: (PurchaseSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag(UiTags.GROCERY_SUGGESTIONS),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .testTag(UiTags.GROCERY_SUGGESTIONS_TOGGLE)
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Souvent pris (${suggestions.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Replier" else "Déplier",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                suggestions.forEach { suggestion ->
                    SuggestionRow(suggestion = suggestion, onAdd = onAdd, onSnooze = onSnooze)
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: PurchaseSuggestion,
    onAdd: (PurchaseSuggestion) -> Unit,
    onSnooze: (PurchaseSuggestion) -> Unit,
) {
    val name = suggestion.product.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$name · ${purchaseRhythmLabel(suggestion.everyDays)}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { onSnooze(suggestion) },
            modifier = Modifier
                .testTag(UiTags.grocerySuggestionSnooze(suggestion.product.id))
                .semantics { contentDescription = "Pas besoin de $name" },
        ) {
            Text("Pas besoin")
        }
        IconButton(
            onClick = { onAdd(suggestion) },
            modifier = Modifier.testTag(UiTags.grocerySuggestionAdd(suggestion.product.id)),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Ajouter $name")
        }
    }
}
