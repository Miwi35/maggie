package com.maggie.app.ui.screens.bilan

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

class FakeBilanDoneStore(initial: LocalDate? = null) : BilanDoneStore {
    val day = MutableStateFlow(initial)
    override val doneDay: Flow<LocalDate?> = day

    override suspend fun markDone(day: LocalDate) {
        this.day.value = day
    }
}
