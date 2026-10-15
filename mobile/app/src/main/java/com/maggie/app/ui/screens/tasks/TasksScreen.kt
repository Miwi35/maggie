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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.maggie.app.data.model.Task
import com.maggie.app.data.model.TaskView
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.screens.dashboard.DashboardTaskItem

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

/** « Aujourd'hui (3) »: the count comes with every read of a view, so the other tabs stay true. */
internal fun tabLabelOf(view: TaskView, state: TasksUiState): String =
    state.counts?.let { "${view.label} (${it[view]})" } ?: view.label

/**
 * Tasks in four tabs (MAG-408), as the admin shows them. The list is the API's: its order, its
 * views, subtasks after their parent. [onTaskClick] opens the detail, a sheet or the pane beside
 * the list, which the caller draws.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onTaskClick: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    DisposableEffect(viewModel) {
        viewModel.onOpened()
        onDispose { viewModel.onClosed() }
    }

    Column(modifier = modifier.fillMaxSize().testTag(UiTags.TASKS)) {
        ScrollableTabRow(selectedTabIndex = TaskView.entries.indexOf(state.view), edgePadding = 0.dp) {
            TaskView.entries.forEach { view ->
                Tab(
                    selected = state.view == view,
                    onClick = { viewModel.selectView(view) },
                    text = { Text(tabLabelOf(view, state), maxLines = 1) },
                    modifier = Modifier.testTag(UiTags.tasksTab(view.key)),
                )
            }
        }

        if (state.view.addable) {
            QuickAdd(state = state, viewModel = viewModel)
        }

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
            isRefreshing = state.isLoading && state.tasks.isNotEmpty(),
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.error != null -> ErrorState(state.error.orEmpty(), onRetry = viewModel::refresh)
                state.isLoading && state.tasks.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
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
                else -> TaskList(state = state, viewModel = viewModel, onTaskClick = onTaskClick)
            }
        }
    }
}

@Composable
private fun QuickAdd(state: TasksUiState, viewModel: TasksViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = viewModel::onDraftChange,
            placeholder = { Text(addHintOf(state.view)) },
            singleLine = true,
            enabled = !state.isAdding,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { viewModel.addTask() }),
            modifier = Modifier.weight(1f).testTag(UiTags.TASKS_ADD_INPUT),
        )
        IconButton(
            onClick = viewModel::addTask,
            enabled = state.draft.isNotBlank() && !state.isAdding,
            modifier = Modifier.testTag(UiTags.TASKS_ADD_SUBMIT),
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
            DashboardTaskItem(
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
