package com.maggie.app.ui.screens.cookbook.cooking

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.GroceryItem
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.HEADER_BUTTON
import com.maggie.app.ui.screens.cookbook.grocery.AisleSection
import com.maggie.app.ui.screens.cookbook.grocery.quantityHint
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.uiTagRoot

/** What the screens can ask of the shopping list; the ViewModel's actions, apart from it so a test can watch them. */
class CookingGroceryActions(
    val open: () -> Unit = {},
    val close: () -> Unit = {},
    val toggle: (GroceryItem) -> Unit = {},
    val retry: () -> Unit = {},
    val dismissMessage: () -> Unit = {},
) {
    companion object {
        fun of(viewModel: CookingGroceryViewModel) = CookingGroceryActions(
            open = viewModel::open,
            close = viewModel::close,
            toggle = viewModel::toggle,
            retry = viewModel::retry,
            dismissMessage = viewModel::dismissMessage,
        )
    }
}

/** The cart in the header, with the number of items still to buy. */
@Composable
fun RowScope.CookingGroceryButton(remaining: Int, onClick: () -> Unit) {
    // The figure is a sibling of the button, not a child: a button merges what it holds, and Maestro reads the merged tree.
    Box(modifier = Modifier.size(HEADER_BUTTON)) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.fillMaxSize().testTag(UiTags.COOKING_GROCERY_BUTTON)) {
            Icon(
                Icons.Outlined.ShoppingCart,
                contentDescription = if (remaining > 0) "Liste de courses, $remaining à acheter" else "Liste de courses",
            )
        }
        if (remaining > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.testTag(UiTags.COOKING_GROCERY_COUNT),
                )
            }
        }
    }
}

/** The list over a phone held upright. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookingGrocerySheet(
    state: CookingGroceryUiState,
    stores: List<CookingGroceryStore>,
    actions: CookingGroceryActions,
) {
    // A sheet is a window of its own: its tags need their own root for Maestro to see them.
    ModalBottomSheet(
        onDismissRequest = actions.close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.uiTagRoot(),
    ) {
        CookingGroceryContent(state, stores, actions, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    }
}

/** The list at the right of the step, on a tablet or a phone held sideways: the step stays in view. */
@Composable
fun CookingGroceryPanel(state: CookingGroceryUiState, stores: List<CookingGroceryStore>, actions: CookingGroceryActions) {
    BackHandler(onBack = actions.close)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            // Even corners: Robolectric cannot hit-test an outline with uneven ones.
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.padding(8.dp).width(PANEL_WIDTH).fillMaxHeight(),
        ) {
            CookingGroceryContent(state, stores, actions, Modifier.fillMaxSize().padding(horizontal = 16.dp))
        }
    }
}

private val PANEL_WIDTH = 360.dp

@Composable
private fun CookingGroceryContent(
    state: CookingGroceryUiState,
    stores: List<CookingGroceryStore>,
    actions: CookingGroceryActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag(UiTags.COOKING_GROCERY_SHEET)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Courses",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = actions.close, modifier = Modifier.size(HEADER_BUTTON).testTag(UiTags.COOKING_GROCERY_CLOSE)) {
                Icon(Icons.Default.Close, contentDescription = "Fermer la liste de courses")
            }
        }
        state.message?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(UiTags.COOKING_GROCERY_MESSAGE),
            )
        }
        when {
            state.load == GroceryLoad.LOADING && state.list == null -> Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(UiTags.COOKING_GROCERY_LOADING),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.load == GroceryLoad.ERROR -> Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(UiTags.COOKING_GROCERY_ERROR),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("La liste n'a pas pu être lue.", style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = actions.retry,
                    modifier = Modifier.heightIn(min = HEADER_BUTTON).testTag(UiTags.COOKING_GROCERY_RETRY),
                ) { Text("Réessayer") }
            }

            stores.isEmpty() -> Text(
                "Rien à acheter",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag(UiTags.COOKING_GROCERY_EMPTY),
            )

            else -> Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                stores.forEach { StoreBlock(it, actions.toggle) }
            }
        }
    }
}

@Composable
private fun StoreBlock(group: CookingGroceryStore, onToggle: (GroceryItem) -> Unit) {
    Text(
        group.store?.name ?: "Sans magasin",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).testTag(UiTags.COOKING_GROCERY_STORE),
    )
    group.meal.forEach { GroceryLine(it, onToggle) }
    group.others.forEach { section ->
        if (!group.flat) AisleTitle(section)
        section.items.forEach { GroceryLine(CookingGroceryLine(it), onToggle) }
    }
}

@Composable
private fun AisleTitle(section: AisleSection) {
    Text(
        section.aisle?.name ?: "Sans rayon",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** A line, ticked with one tap anywhere on it: its box is 48 dp, the line 56. */
@Composable
private fun GroceryLine(line: CookingGroceryLine, onToggle: (GroceryItem) -> Unit) {
    val item = line.item
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HEADER_BUTTON)
            .tapTarget(Role.Checkbox) { onToggle(item) }
            .semantics { toggleableState = if (item.checked) ToggleableState.On else ToggleableState.Off }
            .testTag(UiTags.COOKING_GROCERY_ITEM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp).testTag(UiTags.COOKING_GROCERY_CHECK),
            contentAlignment = Alignment.Center,
        ) {
            if (item.checked) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "cochée",
                    tint = MaggieTokens.Signal.success,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = "à cocher",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                item.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (line.needed != null) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (line.needed != null) {
                Text(
                    "ce repas",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(UiTags.COOKING_GROCERY_MEAL),
                )
            }
            line.shortBy?.let { need ->
                Text(
                    "il en faut $need",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaggieTokens.Signal.warning,
                    modifier = Modifier.testTag(UiTags.COOKING_GROCERY_SHORT),
                )
            }
        }
        (line.needed ?: item.quantityHint())?.let { quantity ->
            Text(
                quantity,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}
