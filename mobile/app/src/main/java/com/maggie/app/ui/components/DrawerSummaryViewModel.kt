package com.maggie.app.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maggie.app.data.model.AgendaHomeState
import com.maggie.app.data.model.FinanceHomeState
import com.maggie.app.data.model.GroceryHomeState
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.NutritionHomeState
import com.maggie.app.data.repository.AgendaCardRepository
import com.maggie.app.data.repository.FinanceCardRepository
import com.maggie.app.data.repository.GroceryCardRepository
import com.maggie.app.data.repository.NutritionCardRepository
import com.maggie.app.data.repository.TasksCardRepository
import com.maggie.app.util.DateRanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** The answers the drawer's lines are made of; a module that has not answered (or failed) is null and says nothing. */
private data class Answers(
    val tasks: HomeTasks? = null,
    val agenda: AgendaHomeState? = null,
    val grocery: GroceryHomeState? = null,
    val meals: NutritionHomeState? = null,
    val finance: FinanceHomeState? = null,
)

/**
 * The drawer's « state of the day » (MAG-620): the answers of the Accueil's own repositories, read once, read again
 * when one of them announces a change and each time the drawer opens ([refresh]). A failed read keeps what the line
 * said before; one that never succeeded leaves the line out.
 */
class DrawerSummaryViewModel(
    private val tasks: TasksCardRepository,
    private val agenda: AgendaCardRepository,
    private val grocery: GroceryCardRepository,
    private val meals: NutritionCardRepository,
    private val finance: FinanceCardRepository,
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {
    private val answers = MutableStateFlow(Answers())
    private val _summary = MutableStateFlow(DrawerSummary())
    val summary: StateFlow<DrawerSummary> = _summary

    init {
        watch(tasks.changes()) { readTasks() }
        watch(agenda.changes()) { readAgenda() }
        watch(grocery.changes()) { readGrocery() }
        watch(meals.changes()) { readMeals() }
        watch(finance.changes()) { readFinance() }
        refresh()
    }

    /** Reads every module again and re-dates the lines (« next event » moves with the clock). */
    fun refresh() {
        viewModelScope.launch { readTasks() }
        viewModelScope.launch { readAgenda() }
        viewModelScope.launch { readGrocery() }
        viewModelScope.launch { readMeals() }
        viewModelScope.launch { readFinance() }
    }

    private fun watch(changes: Flow<Unit>, read: suspend () -> Unit) {
        viewModelScope.launch { changes.collect { read() } }
    }

    private fun today() = clock().atZone(DateRanges.zone).toLocalDate()

    private suspend fun readTasks() {
        val now = clock().atZone(DateRanges.zone).toLocalTime()
        tasks.read(HomeDay.TODAY, today(), now).onSuccess { value -> publish { it.copy(tasks = value) } }
    }

    private suspend fun readAgenda() {
        agenda.read(HomeDay.TODAY, today()).onSuccess { value -> publish { it.copy(agenda = value) } }
    }

    private suspend fun readGrocery() {
        grocery.read(today()).onSuccess { value -> publish { it.copy(grocery = value) } }
    }

    private suspend fun readMeals() {
        meals.read(today()).onSuccess { value -> publish { it.copy(meals = value) } }
    }

    private suspend fun readFinance() {
        finance.read().onSuccess { value -> publish { it.copy(finance = value) } }
    }

    private fun publish(change: (Answers) -> Answers) {
        answers.update(change)
        val a = answers.value
        _summary.value = DrawerSummary.of(a.tasks, a.agenda, a.grocery, a.meals, a.finance, clock())
    }
}
