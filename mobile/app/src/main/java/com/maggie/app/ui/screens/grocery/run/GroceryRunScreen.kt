package com.maggie.app.ui.screens.grocery.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
    val setVisited: (String, Boolean) -> Unit = { _, _ -> },
    val move: (Int, Int) -> Unit = { _, _ -> },
    val start: () -> Unit = {},
    val retry: () -> Unit = {},
    val dismissQuit: () -> Unit = {},
) {
    companion object {
        fun of(viewModel: GroceryRunViewModel) = GroceryRunActions(
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
            current != null -> GroceryRunCurrentStore(current)
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

// Part 2 (the store being done) replaces this body.
@Composable
private fun GroceryRunCurrentStore(store: RunStore) {
    Column(
        modifier = Modifier.fillMaxSize().testTag(UiTags.GROCERY_RUN_CURRENT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(store.name, style = MaterialTheme.typography.headlineMedium)
        Text(articlesLabel(store.count), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
