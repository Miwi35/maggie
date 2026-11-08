package com.maggie.app.ui.screens.grocery.run

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.activity.ActivityImmersion
import com.maggie.app.ui.activity.ActivityScaffold
import com.maggie.app.ui.activity.rememberWindowImmersion
import com.maggie.app.ui.screens.cookbook.cooking.mainButtonHeight
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.uiTagRoot
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** What the screen can ask of the activity; the ViewModel's actions, apart from it so a test can watch them. */
class GroceryRunActions(
    val check: (String) -> Unit = {},
    val markMissing: (String) -> Unit = {},
    val setVisited: (String, Boolean) -> Unit = { _, _ -> },
    val move: (Int, Int) -> Unit = { _, _ -> },
    val start: () -> Unit = {},
    val retry: () -> Unit = {},
    val dismissQuit: () -> Unit = {},
) {
    companion object {
        fun of(viewModel: GroceryRunViewModel) = GroceryRunActions(
            check = viewModel::check,
            markMissing = viewModel::markMissing,
            setVisited = viewModel::setVisited,
            move = viewModel::move,
            start = viewModel::start,
            retry = viewModel::retry,
            dismissQuit = viewModel::dismissQuit,
        )
    }
}

/** The Courses activity (MAG-762): `maggie://courses/run`. */
@Composable
fun GroceryRunScreen(viewModel: GroceryRunViewModel, onQuit: () -> Unit, onOpenMaggie: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    GroceryRunContent(
        state = state,
        actions = GroceryRunActions.of(viewModel),
        onQuit = { if (viewModel.requestQuit()) onQuit() },
        onConfirmQuit = {
            viewModel.dismissQuit()
            onQuit()
        },
        onOpenMaggie = onOpenMaggie,
    )
}

@Composable
fun GroceryRunContent(
    state: GroceryRunUiState,
    actions: GroceryRunActions,
    onQuit: () -> Unit,
    onOpenMaggie: () -> Unit,
    onConfirmQuit: () -> Unit = onQuit,
    immersion: ActivityImmersion = rememberWindowImmersion(),
) {
    ActivityScaffold(
        name = "Courses",
        hue = ModuleHue.COURSES,
        onQuit = onQuit,
        onOpenMaggie = onOpenMaggie,
        immersion = immersion,
    ) {
        val current = state.current
        when {
            state.load == GroceryRunLoad.LOADING ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag(UiTags.GROCERY_RUN_LOADING))
                }
            state.load == GroceryRunLoad.ERROR -> GroceryRunError(actions.retry)
            state.load == GroceryRunLoad.EMPTY ->
                Box(Modifier.fillMaxSize().testTag(UiTags.GROCERY_RUN_EMPTY), contentAlignment = Alignment.Center) {
                    Text("Aucun article à prendre", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            current != null -> GroceryRunCurrentStore(state, current, actions)
            else -> GroceryRunTour(state, actions)
        }
        if (state.quitAsked) {
            AlertDialog(
                onDismissRequest = actions.dismissQuit,
                modifier = Modifier.uiTagRoot().testTag(UiTags.GROCERY_RUN_QUIT_DIALOG),
                title = { Text("Quitter les courses ?") },
                confirmButton = {
                    TextButton(onClick = onConfirmQuit, modifier = Modifier.testTag(UiTags.GROCERY_RUN_QUIT_CONFIRM)) {
                        Text("Quitter")
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions.dismissQuit, modifier = Modifier.testTag(UiTags.GROCERY_RUN_QUIT_STAY)) {
                        Text("Rester")
                    }
                },
            )
        }
    }
}

@Composable
private fun GroceryRunError(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(UiTags.GROCERY_RUN_ERROR),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Impossible de lire la liste.", color = MaterialTheme.colorScheme.error)
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(UiTags.GROCERY_RUN_RETRY)) {
            Text("Réessayer")
        }
    }
}

/** The store being done (MAG-763): its articles aisle by aisle, in the order of the store; in landscape the tour sits at its right. */
@Composable
private fun GroceryRunCurrentStore(state: GroceryRunUiState, store: RunStore, actions: GroceryRunActions) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Row(modifier = Modifier.fillMaxSize().testTag(UiTags.GROCERY_RUN_CURRENT)) {
        Column(modifier = Modifier.weight(if (landscape) 3f else 1f).fillMaxHeight().padding(horizontal = 16.dp)) {
            StoreHeader(state, store)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.GROCERY_RUN_ITEMS),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                state.currentAisles.forEach { aisle ->
                    item(key = "aisle-${aisle.id}") {
                        Text(
                            text = aisle.name ?: "Sans rayon",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(aisle.lines, key = { it.id }) { line -> RunLineRow(line, actions.check, actions.markMissing) }
                }
            }
        }
        if (landscape) SideTour(state)
    }
}

@Composable
private fun StoreHeader(state: GroceryRunUiState, store: RunStore) {
    val progress = state.progress
    Column(modifier = Modifier.padding(top = 8.dp)) {
        state.tourPosition?.let { (position, total) ->
            Text(
                text = "Magasin $position sur $total",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(UiTags.GROCERY_RUN_TOUR_PROGRESS),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = store.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${progress.checked} / ${progress.total}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag(UiTags.GROCERY_RUN_STORE_PROGRESS),
            )
        }
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.checked.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
private fun RunLineRow(line: RunLine, onCheck: (String) -> Unit, onMissing: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = line.checked, role = Role.Checkbox, onValueChange = { onCheck(line.id) })
            .testTag(UiTags.groceryRunLine(line.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        if (line.checked) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (line.checked) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                text = line.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (line.done) muted else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (line.checked) TextDecoration.LineThrough else null,
            )
            line.quantity?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            line.forMeal?.let {
                Text(
                    text = "pour : $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag(UiTags.groceryRunFor(line.id)),
                )
            }
            line.missing?.let { missing ->
                Text(
                    text = when (missing) {
                        is RunMissing.MovedFrom -> "manquant chez ${missing.store}"
                        RunMissing.NoFallback -> "manquant · pas de repli"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag(UiTags.groceryRunMissingTag(line.id)),
                )
            }
        }
        // A ticked article was found: only a missing one can be given back to its store.
        if (!line.checked) {
            TextButton(
                onClick = { onMissing(line.id) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.groceryRunMissing(line.id)),
            ) {
                Text(if (line.missing == null) "Manquant" else "Annuler")
            }
        }
    }
}

@Composable
private fun RowScope.SideTour(state: GroceryRunUiState) {
    Column(
        modifier = Modifier
            .weight(2f)
            .fillMaxHeight()
            .padding(16.dp)
            .testTag(UiTags.GROCERY_RUN_SIDE_TOUR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.tour.forEach { store ->
            val current = store.id == state.run.current
            Surface(
                modifier = Modifier.fillMaxWidth().testTag(UiTags.groceryRunStore(store.id)),
                shape = RoundedCornerShape(16.dp),
                color = if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(store.name, style = MaterialTheme.typography.titleMedium, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                    Text(articlesLabel(store.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun GroceryRunTour(state: GroceryRunUiState, actions: GroceryRunActions) {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    val tour = state.tour
    val skipped = state.skipped
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> actions.move(from.index, to.index) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = if (tablet) 720.dp else 560.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.GROCERY_RUN_TOUR),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(tour, key = { _, store -> store.id }) { _, store ->
                    ReorderableItem(reorder, key = store.id) { dragging ->
                        StoreRow(store, visited = true, dragging = dragging, actions = actions, handle = {
                            Box(
                                modifier = Modifier.size(48.dp).draggableHandle(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.DragIndicator,
                                    contentDescription = "Déplacer ${store.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        })
                    }
                }
                itemsIndexed(skipped, key = { _, store -> store.id }) { _, store ->
                    StoreRow(store, visited = false, dragging = false, actions = actions, handle = { Box(Modifier.size(48.dp)) })
                }
            }
            Button(
                onClick = actions.start,
                enabled = state.canStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .height(mainButtonHeight(LocalConfiguration.current.smallestScreenWidthDp))
                    .testTag(UiTags.GROCERY_RUN_GO),
            ) {
                Text("C'est parti", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StoreRow(
    store: RunStore,
    visited: Boolean,
    dragging: Boolean,
    actions: GroceryRunActions,
    handle: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (visited) 1f else 0.7f).testTag(UiTags.groceryRunStore(store.id)),
        shape = RoundedCornerShape(16.dp),
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                handle()
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = store.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = articlesLabel(store.count),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = visited,
                    onClick = { actions.setVisited(store.id, true) },
                    label = { Text("Je le visite") },
                    modifier = Modifier.height(48.dp).testTag(UiTags.groceryRunVisit(store.id)),
                )
                FilterChip(
                    selected = !visited,
                    onClick = { actions.setVisited(store.id, false) },
                    label = { Text("Pas aujourd'hui") },
                    modifier = Modifier.height(48.dp).testTag(UiTags.groceryRunSkip(store.id)),
                )
            }
        }
    }
}

internal fun articlesLabel(count: Int): String = if (count == 1) "1 article" else "$count articles"
