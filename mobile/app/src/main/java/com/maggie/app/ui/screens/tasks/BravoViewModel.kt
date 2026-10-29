package com.maggie.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.DayPlan
import com.maggie.app.data.model.Stars
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

/** A task of the day just ticked, handed from the day or the Focus to the Bravo. */
data class BravoRequest(val taskId: String, val title: String)

data class BravoUiState(
    /** The task ticked; null when no Bravo is open. */
    val taskId: String? = null,
    val title: String = "",
    /** Its reward with the user's animation switch; null while it is read, or when it earned none. */
    val celebration: Celebration? = null,
    /** The day once the tick was taken, for its count. Null while read or unreadable. */
    val day: DayPlan? = null,
    /** The month's constellation once the tick was taken, the new star in it; null while read or unreadable. */
    val stars: Stars? = null,
    /** The reward and the day are being read: the circle waits for them, to know whether it may move. */
    val isLoading: Boolean = false,
    /** « Retour à ma journée » was chosen: the screen goes back to the day. */
    val leaves: Boolean = false,
)

/**
 * The Bravo (MAG-508): a task of the day ticked, its reward, the day's count.
 * The reward and the day are read once the tick has answered; the buzz and the sound come with the
 * reward, and the month's constellation with its new star. Kept with the navigation graph like the Focus, so [open] is idempotent for the task shown.
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
            val stars = async { taskRepository.getStars().getOrNull() }
            val celebration = rewards.celebrate(request.taskId)
            val read = day.await()
            val month = stars.await()
            // An answer that comes after the Bravo was closed, or opened on another task, is not this one's.
            _uiState.update { if (it.taskId != request.taskId) it else it.copy(celebration = celebration, day = read, stars = month, isLoading = false) }
        }
    }

    /** « Retour à ma journée ». */
    fun back() {
        if (_uiState.value.taskId == null) return
        _uiState.update { it.copy(leaves = true) }
    }

    /** The Bravo was left: nothing is kept for the next one. */
    fun close() {
        _uiState.value = BravoUiState()
    }
}
