package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.DayTask
import com.maggie.app.data.reward.Celebration
import com.maggie.app.data.reward.TaskRewards
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Where the Bravo goes: the Focus of the next task, or back to the day. */
sealed interface BravoExit {
    data class Next(val taskId: String) : BravoExit

    data object Day : BravoExit
}

/** A task of the day just ticked, handed from the day or the Focus to the Bravo. */
data class BravoRequest(val taskId: String, val title: String)

data class BravoUiState(
    /** The task ticked; null when no Bravo is open. */
    val taskId: String? = null,
    val title: String = "",
    /** Its reward with the user's animation switch; null while it is read, or when it earned none. */
    val celebration: Celebration? = null,
    /** The day once the tick was taken: its count and « Maintenant », the next task. Null while read or unreadable. */
    val day: DayPlan? = null,
    /** The reward and the day are being read: the circle waits for them, to know whether it may move. */
    val isLoading: Boolean = false,
    val exit: BravoExit? = null,
) {
    val next: DayTask? get() = day?.now?.task
}

/**
 * The Bravo (MAG-508): a task of the day ticked, its reward, the day's count and the next task.
 * The reward and the day are read once the tick has answered; the buzz and the sound come with the
 * reward. Kept with the navigation graph like the Focus, so [open] is idempotent for the task shown.
 */
class BravoViewModel(
    private val taskRepository: TaskRepository,
    private val rewards: TaskRewards,
    private val today: () -> LocalDate = { DateRanges.todayDate() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(BravoUiState())
    val uiState: StateFlow<BravoUiState> = _uiState

    fun open(request: BravoRequest) {
        if (_uiState.value.taskId == request.taskId) return
        _uiState.value = BravoUiState(taskId = request.taskId, title = request.title, isLoading = true)
        viewModelScope.launch {
            val day = async { taskRepository.getDayPlan(today()).getOrNull() }
            val celebration = rewards.celebrate(request.taskId)
            val read = day.await()
            // An answer that comes after the Bravo was closed, or opened on another task, is not this one's.
            _uiState.update { if (it.taskId != request.taskId) it else it.copy(celebration = celebration, day = read, isLoading = false) }
        }
    }

    /** « Suivante »: the Focus of the day's « Maintenant ». */
    fun next() {
        val next = _uiState.value.next ?: return
        _uiState.update { it.copy(exit = BravoExit.Next(next.id)) }
    }

    /** « Pause méritée »: back to the day. */
    fun pause() {
        if (_uiState.value.taskId == null) return
        _uiState.update { it.copy(exit = BravoExit.Day) }
    }

    /** The Bravo was left: nothing is kept for the next one. */
    fun close() {
        _uiState.value = BravoUiState()
    }
}
