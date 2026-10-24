package com.maggie.app.ui.screens.home

import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeRoutine
import com.maggie.app.data.model.HomeSaga
import com.maggie.app.data.model.HomeTask
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.repository.RoutineRepository
import com.maggie.app.data.repository.TaskRepository
import com.maggie.app.data.repository.TasksCardRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.LocalDate
import java.time.LocalTime

/**
 * The « Tâches » card over faked repositories (MAG-561): the real [TasksCardViewModel], a card the repository
 * would compose for each day, and the pushes on tasks and routines as a flow the test emits on.
 */
class HomeTasksFakes(
    val today: LocalDate = LocalDate.of(2026, 10, 23),
    val now: LocalTime = LocalTime.of(10, 0),
) {
    val cards = mockk<TasksCardRepository>()
    val taskRepository = mockk<TaskRepository>()
    val routineRepository = mockk<RoutineRepository>()
    val pushes = MutableSharedFlow<Unit>()

    init {
        every { cards.changes() } returns pushes
        answers(HomeDay.TODAY, TODAY_CARD)
        answers(HomeDay.TOMORROW, TOMORROW_CARD)
    }

    fun answers(day: HomeDay, card: HomeTasks) {
        coEvery { cards.read(day, today, now) } returns Result.success(card)
    }

    fun viewModel() = TasksCardViewModel(cards, taskRepository, routineRepository, today = { today }, now = { now })

    companion object {
        /** 3 sur 5, one of them late; a subtask, a saga, two routines, one more task not listed. */
        val TODAY_CARD = HomeTasks(
            editable = true,
            tasks = listOf(
                HomeTask("t1", "Appeler le garage", done = false, overdueDays = 2),
                HomeTask("t2", "Réserver le train", done = false),
                HomeTask("t2a", "Choisir l'horaire", done = false, parentId = "t2"),
                HomeTask("t3", "Payer la cantine", done = true),
            ),
            done = 1,
            total = 4,
            more = 2,
            overdue = 1,
            routines = listOf(
                HomeRoutine("r1", "Boire un verre d'eau", anchor = "wake", time = "07:30", checked = false),
                HomeRoutine("r2", "Vitamines", anchor = "afterBreakfast", time = "08:00", checked = true),
            ),
            saga = HomeSaga("Déménagement", done = 2, total = 6),
        )

        val TOMORROW_CARD = HomeTasks(
            day = "tomorrow",
            editable = false,
            tasks = listOf(HomeTask("t9", "Rendez-vous dentiste")),
            done = 0,
            total = 1,
            routines = listOf(HomeRoutine("r1", "Boire un verre d'eau", anchor = "wake", time = "07:30")),
        )

        val EMPTY_CARD = HomeTasks()
    }
}
