package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.frenchLabel
import com.maggie.app.ui.UiTags

/**
 * The quantity of a line (MAG-573): « Quantité · Conditionnement » when the product is bought in a
 * packaging, a quantity and its unit otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuantityEntry(
    product: Product?,
    draft: QuantityDraft,
    onChange: (QuantityDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val packagingUnit = product?.packagingUnit
    val mode = if (packagingUnit != null) draft.mode else QuantityMode.QUANTITY
    val count = parsePackagings(draft.packagings)
    val hint = draft.copy(mode = mode).conversionHint(product)
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
                    onClick = { onChange(draft.copy(packagings = ((count ?: 2) - 1).coerceAtLeast(1).toString(), prefilled = false)) },
                    enabled = count != null && count > 1,
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Diminuer le nombre de conditionnements")
                }
                OutlinedTextField(
                    value = draft.packagings,
                    onValueChange = { onChange(draft.copy(packagings = it, prefilled = false)) },
                    label = { Text("Nombre de ${packagingUnit?.frenchLabel(2f)}") },
                    isError = count == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp).testTag(UiTags.GROCERY_PACKAGINGS_FIELD),
                )
                IconButton(onClick = { onChange(draft.copy(packagings = ((count ?: 0) + 1).toString(), prefilled = false)) }) {
                    Icon(Icons.Default.Add, contentDescription = "Augmenter le nombre de conditionnements")
                }
                Text(
                    text = packagingCaption(product, count ?: 1),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(UiTags.GROCERY_PACKAGING_CAPTION),
                )
            }
            if (draft.prefilled) {
                AssistChip(onClick = {}, label = { Text("prérempli · recharge auto") }, modifier = Modifier.testTag(UiTags.GROCERY_PREFILLED_CHIP))
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

        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.GROCERY_QUANTITY_CONVERSION),
            )
        }
    }
}
