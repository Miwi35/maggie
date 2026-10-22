package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.data.model.label
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens

/**
 * The quantity of a line (MAG-573): « Quantité · Conditionnement » when the product is bought in a
 * packaging, a quantity and its unit otherwise. [need] is what the week's meals need of the product
 * (the add sheet reads it): it feeds the suggested quantities, the summary and the coverage alert.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuantityEntry(
    product: Product?,
    draft: QuantityDraft,
    onChange: (QuantityDraft) -> Unit,
    modifier: Modifier = Modifier,
    need: Need? = null,
) {
    val packagingUnit = product?.packagingUnit
    val mode = if (packagingUnit != null) draft.mode else QuantityMode.QUANTITY
    val count = parsePackagings(draft.packagings)
    val effective = draft.copy(mode = mode)
    val summary = effective.summary(product, need)
    val alert = effective.coverageAlert(product, need)
    val adjustLabel = effective.adjustLabel(product, need)
    val suggestions = if (mode == QuantityMode.QUANTITY) suggestedQuantities(product, need) else emptyList()
    var unitExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.GROCERY_QUANTITY_ENTRY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (packagingUnit != null) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = mode == QuantityMode.QUANTITY,
                    onClick = { onChange(draft.copy(mode = QuantityMode.QUANTITY)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag(UiTags.GROCERY_QUANTITY_MODE_QUANTITY),
                ) { Text("Quantité") }
                SegmentedButton(
                    selected = mode == QuantityMode.PACKAGINGS,
                    onClick = { onChange(draft.copy(mode = QuantityMode.PACKAGINGS)) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag(UiTags.GROCERY_QUANTITY_MODE_PACKAGINGS),
                ) { Text("Conditionnement") }
            }
        }

        if (mode == QuantityMode.PACKAGINGS && product != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = { onChange(draft.copy(packagings = ((count ?: 2) - 1).coerceAtLeast(1).toString(), prefill = Prefill.NONE)) },
                    enabled = count != null && count > 1,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Diminuer le nombre de conditionnements")
                }
                OutlinedTextField(
                    value = draft.packagings,
                    onValueChange = { onChange(draft.copy(packagings = it, prefill = Prefill.NONE)) },
                    label = { Text("Nombre de ${packagingUnit?.frenchLabel(2f)}") },
                    isError = count == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp).testTag(UiTags.GROCERY_PACKAGINGS_FIELD),
                )
                IconButton(onClick = { onChange(draft.copy(packagings = ((count ?: 0) + 1).toString(), prefill = Prefill.NONE)) }) {
                    Icon(Icons.Default.Add, contentDescription = "Augmenter le nombre de conditionnements")
                }
                Text(
                    text = packagingCaption(product, count ?: 1),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(UiTags.GROCERY_PACKAGING_CAPTION),
                )
            }
            if (draft.prefilled) {
                val origin = if (draft.prefill == Prefill.RESTOCK) "réassort" else "recettes de la semaine"
                AssistChip(onClick = {}, label = { Text("prérempli · $origin") }, modifier = Modifier.testTag(UiTags.GROCERY_PREFILLED_CHIP))
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.quantity,
                    onValueChange = { onChange(draft.copy(quantity = it)) },
                    label = { Text("Quantité") },
                    isError = !draft.copy(mode = mode).isValid(),
                    modifier = Modifier.weight(1f).testTag(UiTags.GROCERY_QUANTITY_FIELD),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                ExposedDropdownMenuBox(
                    expanded = unitExpanded,
                    onExpandedChange = { unitExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = draft.unit?.name?.lowercase() ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Unité") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                        CookbookUnit.entries.forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.name.lowercase()) },
                                onClick = {
                                    onChange(draft.copy(unit = unit))
                                    unitExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    AssistChip(
                        onClick = { onChange(draft.withSuggestedQuantity(suggestion)) },
                        label = { Text(suggestion.label()) },
                        modifier = Modifier.testTag(UiTags.groceryQuantitySuggestion(index)),
                    )
                }
            }
        }

        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(UiTags.GROCERY_QUANTITY_SUMMARY),
            )
        }

        if (alert != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaggieTokens.Signal.warning, RoundedCornerShape(8.dp))
                    .background(MaggieTokens.Signal.warning.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
                    .testTag(UiTags.GROCERY_COVERAGE_ALERT),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = alert, style = MaterialTheme.typography.bodyMedium)
                if (adjustLabel != null) {
                    OutlinedButton(
                        onClick = { onChange(draft.adjustedToNeed(product, need)) },
                        modifier = Modifier.testTag(UiTags.GROCERY_COVERAGE_ADJUST),
                    ) { Text(adjustLabel) }
                }
            }
        }
    }
}
