package com.maggie.app.ui.screens.grocery.run

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.ModuleHue
import com.maggie.app.ui.uiTagRoot
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val RunMuted = MaggieTokens.surfaceDark.caption
private val RunMutedText = MaggieTokens.surfaceDark.textMuted

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
    val choose: (String, LeftoverChoice) -> Unit = { _, _ -> },
    val endRun: () -> Unit = {},
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
            choose = viewModel::choose,
            endRun = viewModel::endRun,
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
        quitFirst = true,
    ) {
        val current = state.current
        when {
            state.ended -> GroceryRunEnd(state, onQuit)
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
                text = { Text("Ce qui est coché est gardé.") },
                confirmButton = {
                    TextButton(onClick = onConfirmQuit, modifier = Modifier.testTag(UiTags.GROCERY_RUN_QUIT_CONFIRM)) {
                        Text("Quitter")
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions.dismissQuit, modifier = Modifier.testTag(UiTags.GROCERY_RUN_QUIT_STAY)) {
                        Text("Continuer")
                    }
                },
            )
        }
    }
}

/** The header: « Courses », the articles of the tour, and one segment per store of the tour, the store being done filled up to. */
@Composable
private fun GroceryRunProgress(state: GroceryRunUiState) {
    val total = state.tour.size
    if (state.load != GroceryRunLoad.READY || total == 0 || state.ended) return
    val done = state.run.current?.let { state.run.tour.indexOf(it) }?.coerceAtLeast(0) ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val position = if (state.run.finished) null else state.tourPosition
            Text(
                text = if (position != null) "${state.current?.name.orEmpty()} · ${position.first} / ${position.second}" else "Courses",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f).testTag(UiTags.GROCERY_RUN_HEADER),
            )
            Text(
                text = if (position != null) state.tourProgress.let { "${it.checked} / ${it.total}" } else articlesLabel(state.tour.sumOf { it.count }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.testTag(UiTags.GROCERY_RUN_HEADER_COUNT),
            )
        }
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.currentAisles.forEach { aisle ->
                    item(key = "aisle-${aisle.id}") {
                        Text(
                            text = (aisle.name ?: "Sans rayon").uppercase(),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = 1.7.sp),
                            color = RunMuted,
                            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(aisle.lines, key = { it.id }) { line -> RunLineRow(line, actions.check, actions.markMissing) }
                }
            }
            MainButton(
                "Terminer ${store.name}",
                UiTags.GROCERY_RUN_FINISH,
                onClick = actions.openRecap,
                muted = state.progress.checked < state.progress.total,
            )
        }
        if (landscape) SideTour(state)
    }
}

@Composable
private fun StoreHeader(state: GroceryRunUiState, store: RunStore) {
    val progress = state.progress
    Column(modifier = Modifier.padding(top = 8.dp)) {
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
                text = "${progress.checked} / ${progress.total} ici",
                style = MaterialTheme.typography.bodyMedium,
                color = RunMuted,
                modifier = Modifier.testTag(UiTags.GROCERY_RUN_STORE_PROGRESS),
            )
        }
    }
}

@Composable
private fun RunLineRow(line: RunLine, onCheck: (String) -> Unit, onMissing: (String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .background(
                if (line.checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(20.dp),
            )
            .toggleable(value = line.checked, role = Role.Checkbox, onValueChange = { onCheck(line.id) })
            .padding(end = 8.dp)
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
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .background(MaggieTokens.surfaceDark.text.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(role = Role.Button, onClick = { onMissing(line.id) })
                    .padding(horizontal = 12.dp)
                    .testTag(UiTags.groceryRunMissing(line.id)),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (line.missing == null) "Manquant" else "Annuler", fontSize = 14.sp, color = RunMutedText)
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
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to -> actions.move(from.index, to.index) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(modifier = Modifier.widthIn(max = if (tablet) 720.dp else 560.dp).fillMaxSize().padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Votre tournée",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (state.reordered) {
                    Text(
                        text = "ordre du jour",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(999.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .testTag(UiTags.GROCERY_RUN_REORDERED),
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag(UiTags.GROCERY_RUN_TOUR),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.rows, key = { it.store.id }) { row ->
                    val store = row.store
                    ReorderableItem(reorder, key = store.id) { dragging ->
                        if (row.visited) {
                            StoreRow(store, position = state.run.tour.indexOf(store.id) + 1, dragging = dragging, actions = actions, handle = {
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
                        } else {
                            StoreRow(store, position = null, dragging = false, actions = actions, handle = {})
                        }
                    }
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
        modifier = Modifier.fillMaxWidth().testTag(UiTags.groceryRunStore(store.id)),
        shape = RoundedCornerShape(16.dp),
        color = when {
            !visited -> Color.Transparent
            dragging -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (visited) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
private fun MainButton(text: String, tag: String, enabled: Boolean = true, muted: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (muted) {
            ButtonDefaults.buttonColors(containerColor = MaggieTokens.surfaceDark.text.copy(alpha = 0.08f), contentColor = MaggieTokens.surfaceDark.text)
        } else {
            ButtonDefaults.buttonColors()
        },
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

/** A big number and what it counts, as the summary of a store shows them. */
@Composable
private fun RowScope.CountTile(count: Int, label: String, color: Color, tag: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag(tag),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light, color = color)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A card of lines: an article or a store on the left, what became of it on the right. */
@Composable
private fun RecapCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp), content = content)
    }
}

@Composable
private fun RecapCardRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun Chip(text: String, tag: String? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onErrorContainer,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * « Terminer le magasin » (MAG-765), as the mockup draws it: « <magasin>, c'est fait », the counts taken and missing,
 * where each missing article goes, « Montant payé », then the next store of the tour. The back returns to the store.
 */
@Composable
private fun GroceryRunRecap(state: GroceryRunUiState, store: RunStore, actions: GroceryRunActions) {
    BackHandler(onBack = actions.closeRecap)
    val missing = state.recapMissing
    RunPanel(UiTags.GROCERY_RUN_RECAP) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") { StepTitle("${store.name}, c'est fait") }
            item(key = "counts") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountTile(state.recapTaken.size, "pris", MaterialTheme.colorScheme.primary, UiTags.GROCERY_RUN_RECAP_TAKEN)
                    CountTile(
                        missing.size,
                        if (missing.size > 1) "manquants" else "manquant",
                        MaterialTheme.colorScheme.error,
                        UiTags.GROCERY_RUN_RECAP_MISSING,
                    )
                }
            }
            if (missing.isNotEmpty()) {
                item(key = "missing") {
                    RecapCard {
                        missing.forEach { line ->
                            RecapCardRow {
                                Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Chip(line.store?.let { "→ $it" } ?: "pas de repli", UiTags.groceryRunRecapGoes(line.id))
                            }
                        }
                    }
                }
            }
            item(key = "paid") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Montant payé")
                    OutlinedTextField(
                        value = state.paidAmount,
                        onValueChange = actions.setPaidAmount,
                        placeholder = { Text("0,00") },
                        suffix = { Text("€") },
                        singleLine = true,
                        isError = state.amountInvalid,
                        textStyle = MaterialTheme.typography.headlineSmall,
                        supportingText = if (state.amountInvalid) {
                            { Text("Un montant en euros, par exemple 42,80") }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Montant payé chez ${store.name}, en euros" }
                            .testTag(UiTags.GROCERY_RUN_PAID),
                    )
                }
            }
            if (state.finishFailed) {
                item(key = "failed") {
                    Text("Impossible de terminer le magasin. Vérifie le réseau et réessaie.", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        MainButton(
            text = state.nextStore?.let { "Magasin suivant · ${it.name}" } ?: "Finir les courses",
            tag = UiTags.GROCERY_RUN_RECAP_CONFIRM,
            enabled = !state.finishing,
            onClick = actions.finishStore,
        )
    }
}

/**
 * « Pas trouvés » (MAG-765): once the last store is finished, the missing articles no store of the tour had; each
 * one gets « Prochaine fois » or « Supprimer », then « Finir les courses » applies them.
 */
@Composable
private fun GroceryRunLeftovers(state: GroceryRunUiState, actions: GroceryRunActions) {
    RunPanel(UiTags.GROCERY_RUN_LEFTOVERS) {
        StepTitle("Pas trouvés")
        Text(
            text = "aucun autre magasin de la tournée",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (state.removeFailed) {
            Text("Impossible de supprimer l'article. Réessaie.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.leftovers, key = { it.id }) { line -> LeftoverRow(line, state.choices[line.id], actions.choose) }
        }
        MainButton("Finir les courses", UiTags.GROCERY_RUN_END_RUN, enabled = state.canEndRun, onClick = actions.endRun)
    }
}

@Composable
private fun LeftoverRow(line: RecapLine, choice: LeftoverChoice?, onChoose: (String, LeftoverChoice) -> Unit) {
    val removed = choice == LeftoverChoice.REMOVE
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = line.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (removed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (removed) TextDecoration.LineThrough else null,
                )
                listOfNotNull(line.store?.let { "manquant chez $it" }, line.quantity).takeIf { it.isNotEmpty() }?.let {
                    Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.selectableGroup().semantics { contentDescription = "${line.label} : que faire ?" },
            ) {
                val colors = MaterialTheme.colorScheme
                ChoicePill(
                    "Prochaine fois", choice == LeftoverChoice.NEXT_TIME, colors.primary, colors.onPrimary, UiTags.groceryRunNextTime(line.id),
                ) { onChoose(line.id, LeftoverChoice.NEXT_TIME) }
                ChoicePill(
                    "Supprimer", removed, colors.errorContainer, colors.onErrorContainer, UiTags.groceryRunRemove(line.id),
                ) { onChoose(line.id, LeftoverChoice.REMOVE) }
            }
        }
    }
}

@Composable
private fun RowScope.ChoicePill(text: String, selected: Boolean, color: Color, onColor: Color, tag: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) color else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) onColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .weight(1f)
            .height(52.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag(tag),
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** « 6240 » → « 62,40 € ». */
internal fun eurosLabel(cents: Int): String = "%d,%02d €".format(cents / 100, cents % 100)

/** « 13 pris · 1 vers Biocoop »: what a finished store gave. */
internal fun StoreEnd.summary(): String =
    (listOf("$taken pris") + movedTo.groupingBy { it }.eachCount().map { (store, count) -> "$count vers $store" }).joinToString(" · ")

/**
 * « Courses finies » (MAG-765): each store of the tour with what was taken, where its missing articles went and the
 * amount paid, the articles kept for next time, then « Stock à jour ». The activity is closed.
 */
@Composable
private fun GroceryRunEnd(state: GroceryRunUiState, onClose: () -> Unit) {
    RunPanel(UiTags.GROCERY_RUN_END) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") { StepTitle("Courses finies") }
            if (state.run.ends.isNotEmpty()) {
                item(key = "stores") {
                    RecapCard {
                        state.run.ends.forEach { end ->
                            RecapCardRow(Modifier.heightIn(min = 56.dp).semantics(mergeDescendants = true) {}.testTag(UiTags.groceryRunEndStore(end.storeId))) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(end.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(end.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                end.paidCents?.let { Text(eurosLabel(it), style = MaterialTheme.typography.titleMedium) }
                            }
                        }
                    }
                }
            }
            if (state.keptForNextTime.isNotEmpty()) {
                item(key = "next") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Prochaine fois")
                        RecapCard {
                            state.keptForNextTime.forEach { line ->
                                RecapCardRow {
                                    Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    Text("reste sur la liste", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            item(key = "stock") {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("Stock à jour", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        MainButton("Fermer", UiTags.GROCERY_RUN_END_CLOSE, onClick = onClose)
    }
}

internal fun articlesLabel(count: Int): String = if (count == 1) "1 article" else "$count articles"
