package com.maggie.app.data.repository

import com.maggie.app.data.api.ApiRefusalException
import com.maggie.app.data.auth.AuthRepository
import com.maggie.app.data.mercure.MercureService
import com.maggie.app.data.mercure.MercureTopics
import com.maggie.app.data.mercure.coalesced
import com.maggie.app.data.model.HomeDay
import com.maggie.app.data.model.HomeTasks
import com.maggie.app.data.model.HomeTasksComposer
import com.maggie.app.util.DateRanges
import com.maggie.app.util.rethrowCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the « Tâches » card of the Accueil reads (MAG-588): the tasks and the routines, from their own resources, composed
 * client-side by [HomeTasksComposer]; and the topics of both, which say the answer moved.
 */
class TasksCardRepository(
    private val taskRepository: TaskRepository,
    private val routineRepository: RoutineRepository,
    private val mercureService: MercureService,
    private val authRepository: AuthRepository,
) {
    /**
     * The card for [day], its reads running together. A read answered 404 (the module is absent) gives an empty card,
     * which the Accueil does not draw; any other failure is a failure.
     */
    suspend fun read(day: HomeDay, today: LocalDate, now: LocalTime): Result<HomeTasks> = try {
        Result.success(
            coroutineScope {
                when (day) {
                    HomeDay.TODAY -> {
                        val zone = DateRanges.zone
                        val open = async { taskRepository.getTodayTasks() }
                        val done = async {
                            taskRepository.getCompletedBetween(
                                after = today.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                                before = today.plusDays(1).atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                                limit = HomeTasksComposer.SLOTS,
                            )
                        }
                        val routines = async { routineRepository.getDay(today).getOrThrow() }
                        HomeTasksComposer.today(open.await(), done.await(), routines.await(), now)
                    }
                    HomeDay.TOMORROW -> {
                        val tomorrow = today.plusDays(1)
                        val open = async { taskRepository.getOpenDueOn(tomorrow, TOMORROW_PAGE) }
                        val routines = async { routineRepository.getDay(tomorrow).getOrThrow() }
                        HomeTasksComposer.tomorrow(open.await(), routines.await())
                    }
                }
            },
        )
    } catch (e: ApiRefusalException) {
        if (e.status == NOT_FOUND) Result.success(HomeTasks(day = day.apiValue)) else Result.failure(e)
    } catch (e: Exception) {
        Result.failure<HomeTasks>(e).rethrowCancellation()
    }

    /** One value each time a task or a routine changes server-side, bursts merged; read [read] again on each. */
    fun changes(): Flow<Unit> = flow {
        val userId = authRepository.getUserId() ?: return@flow
        emitAll(
            listOf(MercureTopics.TASKS, MercureTopics.ROUTINES, MercureTopics.ROUTINE_CHECKS)
                .map { mercureService.subscribe(MercureTopics.userScoped(userId, it)).catch { /* SSE reconnects automatically */ } }
                .merge()
                .coalesced()
                .map { },
        )
    }

    private companion object {
        const val TOMORROW_PAGE = 100
        const val NOT_FOUND = 404
    }
}
