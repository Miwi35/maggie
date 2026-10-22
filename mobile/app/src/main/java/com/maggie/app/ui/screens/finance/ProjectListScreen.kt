package com.maggie.app.ui.screens.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Project
import com.maggie.app.data.model.ProjectWellbeing
import com.maggie.app.data.model.formatCents
import com.maggie.app.data.model.projectDateLabel
import com.maggie.app.data.model.projectDependenciesLabel
import com.maggie.app.data.model.projectFundingSummary
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.EmptyState
import com.maggie.app.ui.components.ErrorSnackbar
import com.maggie.app.ui.components.FinanceTopBar
import com.maggie.app.ui.layout.WindowWidth
import com.maggie.app.ui.layout.rememberAppLayout
import com.maggie.app.ui.navigation.Screen
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * « Projets » of Planification (MAG-519): the owner's projects in priority order. A long press on a
 * row — or a drag on its handle — moves it; a tap opens its sheet (MAG-525), « + » the form of a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    viewModel: ProjectViewModel,
    onBack: () -> Unit,
    onOpenForm: (projectId: String?) -> Unit,
    onOpenSheet: (projectId: String) -> Unit = { onOpenForm(it) },
) {
    Scaffold(
        topBar = {
            FinanceTopBar(Screen.ProjectList.route) {
                TopAppBar(
                    title = { Text("Projets") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                        }
                    },
                )
            }
        },
    ) { paddingValues ->
        ProjectListContent(viewModel, onOpenForm, Modifier.padding(paddingValues), onOpenSheet)
    }
}

/** The body of the screen, « + » and messages included: the Planification page shows it as its « Projets » tab (MAG-518). */
@Composable
fun ProjectListContent(
    viewModel: ProjectViewModel,
    onOpenForm: (projectId: String?) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSheet: (projectId: String) -> Unit = { onOpenForm(it) },
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // From the medium width (foldable, tablet) the rows keep the reading width of a phone and gain air.
    val wide = rememberAppLayout().width != WindowWidth.COMPACT

    ErrorSnackbar(
        error = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
        onRetry = if (uiState.hasLoaded) null else viewModel::refresh,
        message = uiState.error.orEmpty(),
    )
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.PROJECTS)) {
        when {
            uiState.isLoading && !uiState.hasLoaded -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            uiState.projects.isEmpty() -> {
                EmptyState(
                    modifier = Modifier.testTag(UiTags.PROJECT_EMPTY),
                    title = if (uiState.hasLoaded) "Aucun projet" else "Projets indisponibles",
                    description = if (uiState.hasLoaded) {
                        "Ajoutez ce que vous voulez financer, avec ou sans date : l'ordre de la liste est votre priorité."
                    } else {
                        "Les projets n'ont pas pu être chargés."
                    },
                    actionLabel = if (uiState.hasLoaded) "Ajouter un projet" else "Réessayer",
                    onAction = if (uiState.hasLoaded) ({ onOpenForm(null) }) else viewModel::refresh,
                )
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                ProjectList(
                    projects = uiState.projects,
                    planning = {
                        ProjectPlanningSection(
                            state = uiState,
                            wide = wide,
                            onRetry = viewModel::refreshPlanning,
                        )
                    },
                    wide = wide,
                    onOpen = onOpenSheet,
                    onPreviewMove = viewModel::previewMove,
                    onEndMove = viewModel::endMove,
                    onMoveBy = viewModel::moveBy,
                )
            }
        }
        FloatingActionButton(
            onClick = { onOpenForm(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag(UiTags.PROJECT_ADD),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Nouveau projet")
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ProjectList(
    projects: List<Project>,
    planning: @Composable () -> Unit,
    wide: Boolean,
    onOpen: (String) -> Unit,
    onPreviewMove: (Int, Int) -> Unit,
    onEndMove: () -> Unit,
    onMoveBy: (String, Int) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    // By key, not by index: the indices of the lazy list are the projects' only while nothing else is in it.
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromIndex = projects.indexOfFirst { it.id == from.key }
        val toIndex = projects.indexOfFirst { it.id == to.key }
        if (fromIndex != -1 && toIndex != -1) {
            onPreviewMove(fromIndex, toIndex)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier.then(if (wide) Modifier.widthIn(max = 720.dp) else Modifier).fillMaxSize(),
        // The bottom leaves room for « + », drawn over the list.
        contentPadding = PaddingValues(start = if (wide) 24.dp else 16.dp, top = 16.dp, end = if (wide) 24.dp else 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(if (wide) 12.dp else 8.dp),
    ) {
        // The phone reads the timeline first, then the list; the tablet has the list, then the Gantt under it.
        if (!wide) item(key = PLANNING_ITEM_KEY) { planning() }
        itemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
            ReorderableItem(reorderState, key = project.id) { isDragging ->
                val moveActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Monter ${project.name}") { onMoveBy(project.id, -1); true })
                    if (index < projects.lastIndex) {
                        add(CustomAccessibilityAction("Descendre ${project.name}") { onMoveBy(project.id, 1); true })
                    }
                }
                Card(
                    onClick = { onOpen(project.id) },
                    elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 1.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .longPressDraggableHandle(onDragStopped = { onEndMove() })
                        .semantics { customActions = moveActions }
                        .testTag(UiTags.PROJECT_ROW),
                ) {
                    ProjectRow(
                        rank = index + 1,
                        project = project,
                        all = projects,
                        wide = wide,
                        handle = {
                            // A finger-sized handle that drags at once, without the long press. A node of
                            // its own, not merged into the row: it is what a journey drags by.
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .semantics(mergeDescendants = true) {}
                                    .draggableHandle(onDragStopped = { onEndMove() })
                                    .testTag(UiTags.PROJECT_DRAG_HANDLE),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.DragIndicator,
                                    contentDescription = "Déplacer ${project.name}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
            }
        }
        if (wide) item(key = PLANNING_ITEM_KEY) { planning() }
    }
}

private const val PLANNING_ITEM_KEY = "project-planning"

/**
 * The Gantt (MAG-527) or, when the planning could not be read, a line that says so with a retry.
 * Nothing while it loads for the first time, and nothing without a known income: the list stands alone.
 */
@Composable
private fun ProjectPlanningSection(state: ProjectUiState, wide: Boolean, onRetry: () -> Unit) {
    val planning = state.planning
    when {
        planning != null && ganttRows(planning).isNotEmpty() ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = if (wide) 8.dp else 4.dp)) {
                Text("Planning", style = MaterialTheme.typography.titleMedium)
                // One scroll for both: the funding chart slides with the Gantt it sits under (MAG-528).
                val scroll = rememberScrollState()
                ProjectGantt(planning, scrollState = scroll)
                ProjectFundingChart(planning, scrollState = scroll)
            }
        state.planningError ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag(UiTags.GANTT_ERROR),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Le planning n'a pas pu être chargé.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Réessayer") }
            }
    }
}

@Composable
private fun ProjectRow(
    rank: Int,
    project: Project,
    all: List<Project>,
    wide: Boolean,
    handle: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = if (wide) 16.dp else 12.dp, bottom = if (wide) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        handle()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$rank. ${project.name}", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${formatCents(project.amountCents, project.currency)} · ${projectDateLabel(project)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            projectFundingSummary(project)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(UiTags.PROJECT_FUNDING_SUMMARY),
                )
            }
            projectDependenciesLabel(project, all)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (project.unmetDependencies.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.testTag(UiTags.PROJECT_UNMET),
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "Dépendance non respectée",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        WellbeingBadge(ProjectWellbeing.of(project.wellbeing), project.wellbeingScore)
    }
}

/** « Indispensable 4 »: the level in words, and the score the planning weighs it with. */
@Composable
internal fun WellbeingBadge(wellbeing: ProjectWellbeing, score: Int) {
    val (container, content) = wellbeingColors(wellbeing)
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(wellbeing.label, style = MaterialTheme.typography.labelMedium)
            Text(
                (if (score > 0) score else wellbeing.score).toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun wellbeingColors(wellbeing: ProjectWellbeing): Pair<Color, Color> = with(MaterialTheme.colorScheme) {
    when (wellbeing) {
        ProjectWellbeing.ESSENTIAL -> primaryContainer to onPrimaryContainer
        ProjectWellbeing.HIGH -> secondaryContainer to onSecondaryContainer
        ProjectWellbeing.MEDIUM -> tertiaryContainer to onTertiaryContainer
        ProjectWellbeing.COMFORT -> surfaceVariant to onSurfaceVariant
    }
}
