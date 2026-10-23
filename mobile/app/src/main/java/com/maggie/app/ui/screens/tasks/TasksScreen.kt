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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.interruption.rememberReducedMotion

/** What a tab says when it holds nothing. */
internal fun emptyTextOf(view: TaskView): String = when (view) {
    TaskView.TODAY -> "Rien à faire aujourd'hui."
    TaskView.UPCOMING -> "Aucune tâche à venir."
    TaskView.SOMEDAY -> "Aucune tâche pour « un jour »."
    TaskView.DONE -> "Aucune tâche faite pour l'instant."
}

/** The add field's hint: it names the day the task will get. */
internal fun addHintOf(view: TaskView): String = when (view) {
    TaskView.TODAY -> "Ajouter une tâche pour aujourd'hui"
    TaskView.UPCOMING -> "Ajouter une tâche pour demain"
    TaskView.SOMEDAY -> "Ajouter une tâche sans date"
    TaskView.DONE -> ""
}

/**
 * « Aujourd'hui (3) »: the count comes with every read of a view, so the other tabs stay true.
 * « Aujourd'hui » counts what is left of the day (MAG-497), not every task due.
 */
internal fun tabLabelOf(view: TaskView, state: TasksUiState): String {
    val count = if (view == TaskView.TODAY) state.day?.left else state.counts?.get(view)
    return count?.let { "${view.label} ($it)" } ?: view.label
}

/** The key of the « Routines » tab (MAG-511), beside the four views: `tasks_tab_routines`. */
internal const val ROUTINES_TAB = "routines"

/**
 * The tabs in their order: « Aujourd'hui », then « Routines » when the screen has them (MAG-511),
 * then the other views. A null entry is the « Routines » tab.
 */
internal fun tabsOf(withRoutines: Boolean): List<TaskView?> =
    listOf(TaskView.TODAY) + (if (withRoutines) listOf(null) else emptyList()) + TaskView.entries.drop(1)

/**
 * Tasks in four tabs (MAG-408), as the admin shows them. The list is the API's: its order, its
 * views, subtasks after their parent; « Aujourd'hui » is the day (MAG-497, [DayContent]).
 * [onTaskClick] opens the detail, a sheet or the pane beside the list, which the caller draws;
 * [onFocus] opens the Focus (MAG-499) on a task of the day; [onBravo] the Bravo (MAG-508) on a task
 * of the day just ticked. A task ticked in another tab shows its reward as a banner instead.
 * [routines] draws the « Routines » tab (MAG-511), next to « Aujourd'hui »; without it, no such tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onTaskClick: (Task) -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (taskId: String) -> Unit = {},
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

    Column(modifier = modifier.fillMaxSize().testTag(UiTags.TASKS)) {
        val tabs = tabsOf(withRoutines = routines != null)
        val selected = if (routinesShown) tabs.indexOf(null) else tabs.indexOf(state.view)
        ScrollableTabRow(selectedTabIndex = selected, edgePadding = 0.dp) {
            tabs.forEach { view ->
                if (view == null) {
                    Tab(
                        selected = routinesShown,
                        onClick = { showRoutines = true },
                        text = { Text("Routines", maxLines = 1) },
                        modifier = Modifier.testTag(UiTags.tasksTab(ROUTINES_TAB)),
                    )
                } else {
                    Tab(
                        selected = !routinesShown && state.view == view,
                        onClick = {
                            showRoutines = false
                            viewModel.selectView(view)
                        },
                        text = { Text(tabLabelOf(view, state), maxLines = 1) },
                        modifier = Modifier.testTag(UiTags.tasksTab(view.key)),
                    )
                }
            }
        }

        if (routinesShown) {
            routines?.invoke()
        } else {
            // The day has its own add field, between its five and its reserve.
            if (state.view.addable && state.view != TaskView.TODAY) {
                QuickAdd(
                    state = state,
                    viewModel = viewModel,
                    hint = addHintOf(state.view),
                    inputTag = UiTags.TASKS_ADD_INPUT,
                    submitTag = UiTags.TASKS_ADD_SUBMIT,
                )
            }

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
                            text = emptyTextOf(state.view),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(UiTags.TASKS_EMPTY),
                        )
                    }
                    else -> {
                        val day = state.day
                        val morning = state.morning
                        val evening = state.evening
                        if (state.view == TaskView.TODAY && evening != null) {
                            EveningContent(evening = evening, viewModel = viewModel)
                        } else if (state.view == TaskView.TODAY && morning != null) {
                            MorningContent(morning = morning, viewModel = viewModel)
                        } else if (state.view == TaskView.TODAY && day != null) {
                            DayContent(day = day, state = state, viewModel = viewModel, onFocus = onFocus)
                        } else {
                            TaskList(state = state, viewModel = viewModel, onTaskClick = onTaskClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun QuickAdd(
    state: TasksUiState,
    viewModel: TasksViewModel,
    hint: String,
    inputTag: String,
    submitTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::onDraftChange,
            placeholder = { Text(hint) },
            singleLine = true,
            enabled = !state.isAdding,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.addTask() }),
            modifier = Modifier.weight(1f).testTag(inputTag),
        )
        IconButton(
            onClick = viewModel::addTask,
            enabled = state.draft.isNotBlank() && !state.isAdding,
            modifier = Modifier.testTag(submitTag),
        ) {
            Icon(Icons.Default.Add, contentDescription = "Ajouter la tâche")
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

    // « Faites » is paged: the next page is asked for once the end of what is loaded is close.
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
