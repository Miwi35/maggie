package com.maggie.app.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs [block] one at a time: a call made while it runs does not start a second copy, it
 * schedules one more run after the current one (however many calls came in). Call from one thread.
 */
class SingleFlight(
    private val scope: CoroutineScope,
    private val block: suspend () -> Unit,
) {
    private var job: Job? = null
    private var again = false

    fun run() {
        if (job?.isActive == true) {
            again = true
            return
        }
        job = scope.launch {
            do {
                again = false
                block()
            } while (again)
        }
    }
}
