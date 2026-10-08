package com.maggie.app.ui.screens.cookbook.meals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.MealGroceryIngredient
import com.maggie.app.data.model.toBuyLabel
import com.maggie.app.ui.screens.grocery.StockStateChip

internal fun articlesToAddLabel(count: Int): String =
    if (count > 1) "$count articles à ajouter" else "$count article à ajouter"

/**
 * Full screen, never a list in a dialog (MAG-297): the owner ticks which ingredients
 * of the meal just planned go on the grocery list. [onLater] leaves the meal planned
 * and adds nothing; [onDone] follows a successful send.
 */
@Composable
fun MealIngredientChoiceScreen(
    viewModel: MealIngredientChoiceViewModel,
    onLater: () -> Unit,
    onDone: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isDone) {
        if (uiState.isDone) onDone()
    }

    MealIngredientChoiceContent(
        uiState = uiState,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onSubmit = viewModel::submit,
        onRetry = viewModel::load,
        onLater = onLater,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MealIngredientChoiceContent(
    uiState: MealIngredientChoiceUiState,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onLater: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ingrédients du repas") },
                navigationIcon = {
                    IconButton(onClick = onLater) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = articlesToAddLabel(uiState.selectedCount),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onLater) { Text("Plus tard") }
                        Button(onClick = onSubmit, enabled = uiState.canSubmit) {
                            Text("Ajouter aux courses")
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            uiState.error?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (uiState.ingredients.isEmpty()) {
                        TextButton(onClick = onRetry) { Text("Réessayer") }
                    }
                }
            }

            when {
                uiState.isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                uiState.ingredients.isEmpty() -> if (uiState.error == null) {
                    Text(
                        text = "Les recettes de ce repas n'ont aucun ingrédient à acheter.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Cochez ce qu'il faut acheter",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onSelectAll) { Text("Tout cocher") }
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = uiState.ingredients,
                            key = { "${it.ingredientId}:${it.unit}" },
                        ) { ingredient ->
                            IngredientChoiceRow(
                                ingredient = ingredient,
                                checked = ingredient.ingredientId in uiState.selected,
                                enabled = !uiState.isSending,
                                onToggle = { onToggle(ingredient.ingredientId) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientChoiceRow(
    ingredient: MealGroceryIngredient,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = ingredient.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ingredient.toBuyLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StockStateChip(ingredient.stockState)
    }
}
