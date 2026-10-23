package com.maggie.app.ui.screens.tasks

import com.maggie.app.data.repository.RoutineRepository
import io.mockk.coEvery
import io.mockk.mockk

/** A routines API that cannot be reached: the screens under test have no band. */
internal fun offlineRoutines(): RoutineRepository = mockk {
    coEvery { getDay(any()) } returns Result.failure(IllegalStateException("offline"))
}
