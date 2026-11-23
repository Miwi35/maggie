package com.maggie.app.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.components.FabModule
import com.maggie.app.ui.components.ModuleFab
import com.maggie.app.ui.interruption.rememberReducedMotion
import com.maggie.app.util.DateRanges

/** What a section says when it holds nothing. */
internal fun emptyTextOf(section: TasksSection): String = when (section) {
    TasksSection.TODAY -> "Rien à faire aujourd'hui."
    TasksSection.STOCK -> "Aucune tâche."
    TasksSection.ARCHIVES -> "Aucune tâche archivée."
}

/**
 * « Aujourd'hui (3) », a menu entry: the count comes with every read of a view, so the other views stay true.
 * « Aujourd'hui » counts what is left of the tasks of the day (MAG-766), a tick counted at once.
 */
internal fun tabCountOf(section: TasksSection, state: TasksUiState): Int? = when (section) {
    TasksSection.TODAY -> state.todayTasks?.count { !it.isDone } ?: state.counts?.today
    TasksSection.STOCK -> state.counts?.let { it.upcoming + it.someday }
    TasksSection.ARCHIVES -> state.counts?.done
}

internal fun tabLabelOf(section: TasksSection, state: TasksUiState): String =
    tabCountOf(section, state)?.let { "${section.label} ($it)" } ?: section.label

/** The key of the « Routines » entry (MAG-511), beside the four views: `breadcrumb_menu_routines`. */
internal const val ROUTINES_TAB = "routines"

/**
 * The menu entries in their order: « Aujourd'hui », then « Routines » when the screen has them
 * (MAG-511), then the other views. A null entry is « Routines ».
 */
internal fun tabsOf(withRoutines: Boolean): List<TasksSection?> =
    listOf(TasksSection.TODAY, TasksSection.STOCK) + (if (withRoutines) listOf(null) else emptyList()) + TasksSection.ARCHIVES

/**
 * Tasks in four views (MAG-408), as the admin shows them, chosen from the header (MAG-626). The
 * list is the API's: its order, its views, subtasks after their parent; « Aujourd'hui » is the tasks of the day
 * (MAG-766, [DayContent]). [onTaskClick] opens the detail, a sheet or the pane beside the list, which the caller draws;
 * [onTimer] opens the Focus (MAG-499) of a timed task, its timer on; [onBravo] the Bravo (MAG-508) on a task
 * of the day just ticked. A task ticked in another view shows its reward as a banner instead.
 * [routines] draws the « Routines » view (MAG-511), next to « Aujourd'hui »; without it, no such entry.
 * The floating « + » (MAG-721) calls [onCreateTask] with no day: a new task gets no deadline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onTaskClick: (Task) -> Unit,
    onCreateTask: (dueDay: String?) -> Unit,
    modifier: Modifier = Modifier,
    onTimer: (taskId: String) -> Unit = {},
    onBravo: (BravoRequest) -> Unit = {},
    routines: (@Composable () -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsState()
    val reducedMotion = rememberReducedMotion()
    var showRoutines by rememberSaveable { mutableStateOf(false) }
    val routinesShown = showRoutines && routines != null

    DisposableEffect(viewModel) {
        viewModel.onOpened()
        onDispose { viewModel.onClosed() }
    }

    // A task of the day is read whole before its detail opens.
    LaunchedEffect(state.opened) {
        state.opened?.let {
            onTaskClick(it)
            viewModel.onTaskOpened()
        }
    }

    // The home's « Routines » widget lands on its tab.
    LaunchedEffect(state.routinesTabRequested) {
        if (state.routinesTabRequested) {
            showRoutines = true
            viewModel.onRoutinesTabShown()
        }
    }

    LaunchedEffect(state.bravo) {
        state.bravo?.let {
            onBravo(it)
            viewModel.onBravoShown()
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag(UiTags.TASKS)) {
        Column(modifier = Modifier.fillMaxSize()) {
            TasksHeader(
                current = if (routinesShown) null else state.section,
                entries = tabsOf(withRoutines = routines != null),
                countOf = { view -> view?.let { tabCountOf(it, state) } },
                onPick = { view ->
                    showRoutines = view == null
                    if (view != null) viewModel.selectSection(view)
                },
            )

            if (routinesShown) {
                routines?.invoke()
            } else {
                state.reward?.let { RewardBanner(celebration = it, onDismiss = viewModel::dismissReward) }
                state.palier?.let { PalierBanner(celebration = it, animate = it.animate && !reducedMotion, onDismiss = viewModel::dismissPalier) }

                state.message?.let { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::dismissMessage) { Text("Fermer") }
                    }
                }

                PullToRefreshBox(
                    isRefreshing = state.isLoading && state.hasContent,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when {
                        state.error != null -> ErrorState(state.error.orEmpty(), onRetry = viewModel::refresh)
                        state.isLoading && !state.hasContent -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.isEmpty -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                            Text(
                                text = emptyTextOf(state.section),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag(UiTags.TASKS_EMPTY),
                            )
                        }
                        else -> {
                            val todayTasks = state.todayTasks
                            val stock = state.stock
                            when {
                                state.section == TasksSection.TODAY && todayTasks != null ->
                                    DayContent(tasks = todayTasks, state = state, viewModel = viewModel, onTimer = onTimer)
                                state.section == TasksSection.STOCK && stock != null ->
                                    StockContent(groups = stock, today = state.todayDate ?: DateRanges.todayDate(), viewModel = viewModel, onTaskClick = onTaskClick)
                                else -> TaskList(state = state, viewModel = viewModel, onTaskClick = onTaskClick)
                            }
                        }
                    }
                }
            }
        }
        if (!routinesShown) {
            ModuleFab(
                module = FabModule.TASKS,
                contentDescription = "Nouvelle tâche",
                onClick = { onCreateTask(null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                tag = UiTags.TASKS_ADD,
            )
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Erreur : $error", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text("Réessayer") }
    }
}

@Composable
private fun TaskList(state: TasksUiState, viewModel: TasksViewModel, onTaskClick: (Task) -> Unit) {
    val listState = rememberLazyListState()

    // « Archives » is paged: the next page is asked for once the end of what is loaded is close.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.tasks.size, state.hasMore) {
        if (nearEnd && state.hasMore) viewModel.loadMore()
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(state.tasks, key = { it.id }) { task ->
            TaskItem(
                task = task,
                onToggleDone = { viewModel.toggleDone(task) },
                onClick = { onTaskClick(task) },
                nested = true,
                checkTag = UiTags.tasksCheck(task.id),
            )
        }
        if (state.isLoadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}
