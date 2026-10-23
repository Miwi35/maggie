package com.maggie.app.ui.screens.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.GroceryHomeLine
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.GroceryHomeStore
import com.maggie.app.data.model.neededLabel
import com.maggie.app.data.model.recipeNeedsLabel
import com.maggie.app.data.model.toCookbookUnit
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.navigation.Screen
import com.maggie.app.ui.screens.cookbook.grocery.formatQuantity
import com.maggie.app.ui.screens.cookbook.grocery.unitLabel
import org.koin.androidx.compose.koinViewModel

/**
 * The Courses card of the Accueil (MAG-563): the open list as a compact tile, by shop, with what the
 * meals of today and tomorrow lack above it. Nothing to tick here — a tap anywhere opens Courses.
 * The card does not follow « Demain »: the list is the same whatever the day.
 */
@Composable
fun GroceryHomeCard(viewModel: GroceryHomeViewModel = koinViewModel()) {
    val ui by viewModel.uiState.collectAsState()
    val open = LocalHomeNavigation.current
    GroceryHomeCardContent(ui = ui, onOpen = { open(Screen.Grocery.route) }, onRetry = viewModel::refresh)
}

@Composable
internal fun GroceryHomeCardContent(ui: GroceryHomeUiState, onOpen: () -> Unit, onRetry: () -> Unit) {
    HomeCard(title = "Courses", module = HomeModules.GROCERY, onOpen = onOpen) {
        val state = ui.state
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(onOpen) { detectTapGestures { onOpen() } },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state != null -> GroceryHomeBody(state)
                ui.failed && !ui.isLoading -> Unit
                else -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (ui.failed && !ui.isLoading) GroceryHomeError(onRetry)
    }
}

@Composable
private fun GroceryHomeBody(state: GroceryHomeState) {
    if (state.count == 0 && state.forRecipes.isEmpty()) {
        Text(
            text = "Rien à acheter pour le moment.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(UiTags.HOME_GROCERY_EMPTY),
        )
        return
    }
    if (state.forRecipes.isNotEmpty()) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Pour les recettes") }
                append(" · ")
                append(state.forRecipes.recipeNeedsLabel())
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(UiTags.HOME_GROCERY_RECIPES),
        )
    }
    state.stores.forEach { store -> key(store.id ?: "none") { GroceryHomeStoreSection(store) } }
}

@Composable
private fun GroceryHomeStoreSection(store: GroceryHomeStore) {
    Column(modifier = Modifier.testTag(UiTags.HOME_GROCERY_STORE)) {
        Text(
            text = store.name ?: "Non assigné",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        store.items.forEach { line ->
            key(line.id) { GroceryHomeLineRow(line) }
        }
        if (store.more > 0) {
            Text(
                text = "+ ${store.more}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.HOME_GROCERY_MORE),
            )
        }
    }
}

@Composable
private fun GroceryHomeLineRow(line: GroceryHomeLine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .testTag(UiTags.HOME_GROCERY_ITEM),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = line.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        line.quantityText()?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GroceryHomeError(onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.HOME_GROCERY_ERROR),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "Impossible de lire la liste.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .weight(1f)
                .padding(top = 12.dp),
        )
        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_GROCERY_RETRY)) {
            Text("Réessayer")
        }
    }
}

/** « 800 g » when the meals say what they need, else the line's own quantity: « 2 pièces ». */
internal fun GroceryHomeLine.quantityText(): String? {
    needed.neededLabel()?.let { return it }
    val quantity = quantity ?: return null
    val unitText = unitLabel(unit?.toCookbookUnit(), quantity)
    return formatQuantity(quantity) + if (unitText.isNotEmpty()) " $unitText" else ""
}
