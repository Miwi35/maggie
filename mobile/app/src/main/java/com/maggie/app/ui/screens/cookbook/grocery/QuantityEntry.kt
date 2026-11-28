package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maggie.app.data.model.CookbookUnit
import com.maggie.app.data.model.Need
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.label
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot

/**
 * The quantity of a line (MAG-573): « Quantité · Conditionnement » when the product is bought in a
 * packaging, a quantity and its unit otherwise. [need] is what the week's meals need of the product
 * (the add sheet reads it): it feeds the suggested quantities, the summary and the coverage alert.
 */
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
    val surface = maggieSurface()
    val courses = MaggieTokens.Module.courses.second

    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.GROCERY_QUANTITY_ENTRY), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (packagingUnit != null) {
            Row(
                modifier = Modifier.fillMaxWidth().background(surface.background, CircleShape).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeSegment("Quantité", mode == QuantityMode.QUANTITY, courses, UiTags.GROCERY_QUANTITY_MODE_QUANTITY, Modifier.weight(1f)) {
                    onChange(draft.copy(mode = QuantityMode.QUANTITY))
                }
                ModeSegment("Conditionnement", mode == QuantityMode.PACKAGINGS, courses, UiTags.GROCERY_QUANTITY_MODE_PACKAGINGS, Modifier.weight(1f)) {
                    onChange(draft.copy(mode = QuantityMode.PACKAGINGS))
                }
            }
        }

        if (mode == QuantityMode.PACKAGINGS && product != null) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepButton("−", "Diminuer le nombre de conditionnements", enabled = count != null && count > 1) {
                        onChange(draft.copy(packagings = ((count ?: 2) - 1).coerceAtLeast(1).toString(), prefill = Prefill.NONE))
                    }
                    Text(
                        text = draft.packagings,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = if (count == null) MaterialTheme.colorScheme.error else surface.text,
                        modifier = Modifier.weight(1f).testTag(UiTags.GROCERY_PACKAGINGS_FIELD),
                    )
                    StepButton("+", "Augmenter le nombre de conditionnements", enabled = true) {
                        onChange(draft.copy(packagings = ((count ?: 0) + 1).toString(), prefill = Prefill.NONE))
                    }
                }
                Text(
                    text = packagingCaption(product, count ?: 1),
                    fontSize = 15.sp,
                    color = surface.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(UiTags.GROCERY_PACKAGING_CAPTION),
                )
            }
        } else {
            TextField(
                value = draft.quantity,
                onValueChange = { onChange(draft.copy(quantity = it)) },
                isError = !draft.copy(mode = mode).isValid(),
                singleLine = true,
                textStyle = TextStyle(fontSize = 22.sp, color = surface.text),
                placeholder = { Text("Quantité", fontSize = 22.sp, color = surface.textMuted) },
                trailingIcon = {
                    Box {
                        Text(
                            text = draft.unit?.name?.lowercase() ?: "unité",
                            fontSize = 15.sp,
                            color = surface.textMuted,
                            modifier = Modifier
                                .clickable(role = Role.Button) { unitExpanded = true }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        DropdownMenu(
                            expanded = unitExpanded,
                            onDismissRequest = { unitExpanded = false },
                            modifier = Modifier.uiTagRoot(),
                        ) {
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
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
                colors = filledFieldColors(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(UiTags.GROCERY_QUANTITY_FIELD),
            )
        }

        if (suggestions.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    val applied = draft.withSuggestedQuantity(suggestion)
                    val chosen = applied.quantity == draft.quantity && applied.unit == draft.unit
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .background(if (chosen) courses.copy(alpha = 0.16f) else surface.text.copy(alpha = 0.05f), CircleShape)
                            .clickable(role = Role.Button) { onChange(applied) }
                            .padding(horizontal = 16.dp)
                            .testTag(UiTags.groceryQuantitySuggestion(index)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = suggestion.label(),
                            fontSize = 15.sp,
                            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                            color = if (chosen) courses else surface.textMuted,
                        )
                    }
                }
            }
        }

        if (summary != null) {
            Text(
                text = summary,
                fontSize = 14.sp,
                color = courses,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag(UiTags.GROCERY_QUANTITY_SUMMARY),
            )
        }

        if (alert != null) {
            val warning = (if (isMaggieDark()) MaggieTokens.feedbackDark else MaggieTokens.feedbackLight).warning
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(warning.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .padding(start = 14.dp, top = 10.dp, end = 10.dp, bottom = 10.dp)
                    .testTag(UiTags.GROCERY_COVERAGE_ALERT),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = warning, modifier = Modifier.size(20.dp))
                Text(text = alert, fontSize = 14.sp, color = warning, modifier = Modifier.weight(1f))
                if (adjustLabel != null) {
                    Box(
                        modifier = Modifier
                            .height(44.dp)
                            .background(warning.copy(alpha = 0.18f), CircleShape)
                            .clickable(role = Role.Button) { onChange(draft.adjustedToNeed(product, need)) }
                            .padding(horizontal = 16.dp)
                            .testTag(UiTags.GROCERY_COVERAGE_ADJUST),
                        contentAlignment = Alignment.Center,
                    ) { Text(adjustLabel, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = warning) }
                }
            }
        }
    }
}

@Composable
private fun ModeSegment(text: String, selected: Boolean, accent: Color, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val surface = maggieSurface()
    Box(
        modifier = modifier
            .height(44.dp)
            .background(if (selected) accent else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaggieTokens.surfaceDark.background else surface.textMuted,
        )
    }
}

@Composable
private fun StepButton(symbol: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val surface = maggieSurface()
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(surface.raised, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 20.sp, color = if (enabled) surface.text else surface.textMuted.copy(alpha = 0.4f))
    }
}
