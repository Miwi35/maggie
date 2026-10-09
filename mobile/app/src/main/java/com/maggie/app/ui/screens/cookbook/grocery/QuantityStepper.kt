package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.maggie.app.data.model.GroceryItem

/** − quantity + on a list line (MAG-291); a tap on the quantity types it by hand. */
@Composable
fun QuantityStepper(
    item: GroceryItem,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onSetQuantity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf<String?>(null) }
    val quantity = item.quantity
    val unitText = unitLabel(item.countedUnit, quantity ?: 0f)
    val shown = (quantity?.let(::formatQuantity) ?: "—") + if (unitText.isNotEmpty()) " $unitText" else ""

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            onClick = onDecrement,
            enabled = canDecreaseQuantity(quantity, item.countedUnit),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Diminuer la quantité de ${item.label}",
                modifier = Modifier.size(18.dp),
            )
        }

        val current = draft
        if (current == null) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .semantics { contentDescription = "Modifier la quantité de ${item.label}" }
                    .clickable { draft = quantity?.let(::formatQuantity) ?: "" }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
            )
        } else {
            val focusRequester = remember { FocusRequester() }
            val focusManager = LocalFocusManager.current
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            fun commit() {
                val typed = draft ?: return
                draft = null
                onSetQuantity(typed)
            }

            BasicTextField(
                value = current,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .widthIn(min = 56.dp)
                    .padding(horizontal = 4.dp, vertical = 8.dp)
                    .semantics { contentDescription = "Quantité de ${item.label}" }
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) hadFocus = true else if (hadFocus) commit()
                    },
            )
        }

        val packaging = item.packagingContent()
        if (packaging != null && current == null) {
            Text(
                text = packaging,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Contenu d'un conditionnement de ${item.label} : $packaging" },
            )
        }

        IconButton(onClick = onIncrement, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Augmenter la quantité de ${item.label}",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
