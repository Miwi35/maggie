package com.maggie.app.ui.screens.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.Task
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SubtasksUiState(
    /** The task whose subtasks these are; null when the open task is itself a subtask, or nothing is open. */
    val parentId: String? = null,
    val subtasks: List<Task> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * The subtasks of the task open in the detail sheet, the pane or the edit screen (MAG-404).
 *
 * One level only, as in Google Tasks: a subtask has no section of its own. Each
 * gesture goes to the API at once and the list follows its answer, so the order on
 * screen is always the server's.
 */
class SubtasksViewModel(
    private val taskRepository: TaskRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubtasksUiState())
    val uiState: StateFlow<SubtasksUiState> = _uiState

    private var loadJob: Job? = null

    fun load(task: Task) {
        loadJob?.cancel()
        if (task.isSubtask) {
            _uiState.value = SubtasksUiState()
            return
        }
        // The previous task's list must not flash under this one while it loads.
        if (_uiState.value.parentId != task.id) _uiState.value = SubtasksUiState(parentId = task.id)
        _uiState.update { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            taskRepository.getSubtasks(task.id)
                // Another task opened meanwhile: this answer is not wanted, and neither is its error.
                .rethrowCancellation()
                .onSuccess { subtasks -> _uiState.update { it.copy(subtasks = subtasks, isLoading = false) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message) } }
        }
    }

    /** Ticks or unticks one subtask; the parent stays as it is. */
    fun toggleDone(subtaskId: String, done: Boolean) {
        viewModelScope.launch {
            taskRepository.toggleDone(subtaskId, done)
                .onSuccess(::replace)
                .onFailure(::fail)
        }
    }

    fun add(title: String) {
        val parentId = _uiState.value.parentId ?: return
        if (title.isBlank()) return
        viewModelScope.launch {
            taskRepository.createSubtask(parentId, title.trim())
                .onSuccess(::replace)
                .onFailure(::fail)
        }
    }

    fun moveUp(subtaskId: String) = move(subtaskId, -1)

    fun moveDown(subtaskId: String) = move(subtaskId, +1)

    private fun move(subtaskId: String, offset: Int) {
        val subtasks = _uiState.value.subtasks
        val index = subtasks.indexOfFirst { it.id == subtaskId }
        val neighbour = subtasks.getOrNull(index + offset) ?: return
        if (index < 0) return
        viewModelScope.launch {
            taskRepository.moveSubtask(subtasks[index], neighbour)
                .onSuccess { moved -> moved.forEach(::replace) }
                .onFailure(::fail)
        }
    }

    /** Puts the server's version of [task] in the list, which is then sorted again. */
    private fun replace(task: Task) {
        _uiState.update { state ->
            // An answer that arrives after another task was opened belongs to that other list.
            if (state.parentId == null || task.parent != Task.iriOf(state.parentId)) return@update state
            val others = state.subtasks.filter { it.id != task.id }
            state.copy(subtasks = (others + task).sortedWith(Task.SIBLING_ORDER), error = null)
        }
    }

    private fun fail(e: Throwable) {
        _uiState.update { it.copy(error = e.message ?: "Erreur inconnue") }
    }
}
