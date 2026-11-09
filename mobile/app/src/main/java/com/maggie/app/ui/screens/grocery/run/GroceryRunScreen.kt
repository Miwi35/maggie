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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    val openRecap: () -> Unit = {},
    val closeRecap: () -> Unit = {},
    val setPaidAmount: (String) -> Unit = {},
    val finishStore: () -> Unit = {},
    val keepForNextTime: (String) -> Unit = {},
    val remove: (String) -> Unit = {},
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
            openRecap = viewModel::openRecap,
            closeRecap = viewModel::closeRecap,
            setPaidAmount = viewModel::setPaidAmount,
            finishStore = viewModel::finishStore,
            keepForNextTime = viewModel::keepForNextTime,
            remove = viewModel::remove,
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
        progress = { GroceryRunProgress(state) },
    ) {
        val current = state.current
        when {
            state.ended -> GroceryRunEnd(onQuit)
            state.load == GroceryRunLoad.LOADING ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag(UiTags.GROCERY_RUN_LOADING))
                }
            state.load == GroceryRunLoad.ERROR -> GroceryRunError(actions.retry)
            state.run.finished -> GroceryRunLeftovers(state, actions)
            state.load == GroceryRunLoad.EMPTY ->
                Box(Modifier.fillMaxSize().testTag(UiTags.GROCERY_RUN_EMPTY), contentAlignment = Alignment.Center) {
                    Text("Aucun article à prendre", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            current != null && state.recap -> GroceryRunRecap(state, current, actions)
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

/** The header: « Courses · n magasins » and one segment per store of the tour, the store being done filled up to. */
@Composable
private fun GroceryRunProgress(state: GroceryRunUiState) {
    val total = state.tour.size
    if (state.load != GroceryRunLoad.READY || total == 0 || state.ended) return
    val done = state.run.current?.let { state.run.tour.indexOf(it) }?.coerceAtLeast(0) ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Courses · " + if (total == 1) "1 magasin" else "$total magasins",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.testTag(UiTags.GROCERY_RUN_HEADER),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(total) { index ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(
                            if (index <= done && state.run.current != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
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
            MainButton("Terminer le magasin", UiTags.GROCERY_RUN_FINISH, onClick = actions.openRecap)
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
            Text(
                text = "Votre tournée",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.GROCERY_RUN_TOUR),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(tour, key = { _, store -> store.id }) { index, store ->
                    ReorderableItem(reorder, key = store.id) { dragging ->
                        StoreRow(store, position = index + 1, dragging = dragging, actions = actions, handle = {
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
                    StoreRow(store, position = null, dragging = false, actions = actions, handle = { Box(Modifier.size(48.dp)) })
                }
            }
            MainButton(
                text = tour.firstOrNull()?.let { "Commencer par ${it.name}" } ?: "Commencer",
                tag = UiTags.GROCERY_RUN_GO,
                enabled = state.canStart,
                onClick = actions.start,
            )
        }
    }
}

/** A store of the tour: the big box says whether it is visited today, then its name, its count, its place and the handle. */
@Composable
private fun StoreRow(
    store: RunStore,
    position: Int?,
    dragging: Boolean,
    actions: GroceryRunActions,
    handle: @Composable () -> Unit,
) {
    val visited = position != null
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (visited) 1f else 0.7f).testTag(UiTags.groceryRunStore(store.id)),
        shape = RoundedCornerShape(16.dp),
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .toggleable(
                        value = visited,
                        role = Role.Checkbox,
                        onValueChange = { actions.setVisited(store.id, !visited) },
                    )
                    .semantics { contentDescription = if (visited) "Pas aujourd'hui : ${store.name}" else "Je visite ${store.name}" }
                    .testTag(if (visited) UiTags.groceryRunSkip(store.id) else UiTags.groceryRunVisit(store.id)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(if (visited) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (visited) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
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
            if (position != null) {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp).testTag(UiTags.groceryRunPosition(store.id)),
                )
            }
            handle()
        }
    }
}

@Composable
private fun MainButton(text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .height(mainButtonHeight(LocalConfiguration.current.smallestScreenWidthDp))
            .testTag(tag),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/** A column centred on a tablet, as wide as the tour. */
@Composable
private fun RunPanel(tag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val tablet = LocalConfiguration.current.smallestScreenWidthDp >= 600
    Box(modifier = Modifier.fillMaxSize().testTag(tag), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = if (tablet) 720.dp else 560.dp).fillMaxSize().padding(horizontal = 16.dp),
            content = content,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun RecapRow(line: RecapLine, detail: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(line.label, style = MaterialTheme.typography.titleMedium)
        listOfNotNull(line.quantity, detail).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** « Terminer le magasin » (MAG-765): what was taken, the missing articles and where they go, and « Montant payé ». */
@Composable
private fun GroceryRunRecap(state: GroceryRunUiState, store: RunStore, actions: GroceryRunActions) {
    val taken = state.recapTaken
    val missing = state.recapMissing
    RunPanel(UiTags.GROCERY_RUN_RECAP) {
        Text(
            text = "Terminer ${store.name}",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "taken") { SectionTitle("Pris · ${articlesLabel(taken.size)}") }
            items(taken, key = { "taken-${it.id}" }) { RecapRow(it, null) }
            if (missing.isNotEmpty()) {
                item(key = "missing") { SectionTitle("Manquants · ${articlesLabel(missing.size)}") }
                items(missing, key = { "missing-${it.id}" }) { line ->
                    RecapRow(line, line.store?.let { "à prendre chez $it" } ?: "pas de repli · à décider à la fin")
                }
            }
            item(key = "paid") {
                OutlinedTextField(
                    value = state.paidAmount,
                    onValueChange = actions.setPaidAmount,
                    label = { Text("Montant payé") },
                    placeholder = { Text("facultatif") },
                    suffix = { Text("€") },
                    singleLine = true,
                    isError = state.amountInvalid,
                    supportingText = if (state.amountInvalid) {
                        { Text("Un montant en euros, par exemple 42,80") }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag(UiTags.GROCERY_RUN_PAID),
                )
            }
            if (state.finishFailed) {
                item(key = "failed") {
                    Text(
                        "Impossible de terminer le magasin. Vérifie le réseau et valide à nouveau.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions.closeRecap,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 8.dp)
                    .height(mainButtonHeight(LocalConfiguration.current.smallestScreenWidthDp))
                    .testTag(UiTags.GROCERY_RUN_RECAP_BACK),
            ) {
                Text("Retour", style = MaterialTheme.typography.titleMedium)
            }
            Button(
                onClick = actions.finishStore,
                enabled = !state.finishing,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 8.dp)
                    .height(mainButtonHeight(LocalConfiguration.current.smallestScreenWidthDp))
                    .testTag(UiTags.GROCERY_RUN_RECAP_CONFIRM),
            ) {
                Text("Valider", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** « Manquants sans repli » (MAG-765): once the last store is finished, each one gets « Prochaine fois » or « Supprimer ». */
@Composable
private fun GroceryRunLeftovers(state: GroceryRunUiState, actions: GroceryRunActions) {
    RunPanel(UiTags.GROCERY_RUN_LEFTOVERS) {
        Text(
            text = "Manquants sans repli",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "Aucun magasin de la tournée ne les avait. On les garde pour la prochaine fois ?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.removeFailed) {
            Text("Impossible de supprimer l'article. Réessaie.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.leftovers, key = { it.id }) { line ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        RecapRow(line, line.store?.let { "manquant chez $it" })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { actions.keepForNextTime(line.id) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.groceryRunNextTime(line.id)),
                            ) {
                                Text("Prochaine fois")
                            }
                            OutlinedButton(
                                onClick = { actions.remove(line.id) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.groceryRunRemove(line.id)),
                            ) {
                                Text("Supprimer")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** « Fin » (MAG-765): the tour is over, the activity is closed. */
@Composable
private fun GroceryRunEnd(onClose: () -> Unit) {
    RunPanel(UiTags.GROCERY_RUN_END) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Text("Courses terminées", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("La liste est à jour.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MainButton("Fermer", UiTags.GROCERY_RUN_END_CLOSE, onClick = onClose)
    }
}

internal fun articlesLabel(count: Int): String = if (count == 1) "1 article" else "$count articles"
