package com.maggie.app.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ticks of the Courses activity (MAG-763): each one is shown at once and kept here until the API has it, so a
 * tick made without network is sent when it comes back, once. The last state asked of an article wins.
 * The queue outlives the screen; it does not outlive the process.
 */
class GroceryTickQueue(
    private val repository: GroceryListRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _pending = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Article id → the state asked and not yet received by the API. */
    val pending: StateFlow<Map<String, Boolean>> = _pending

    private val sending = Mutex()

    fun tick(itemId: String, checked: Boolean) {
        _pending.update { it + (itemId to checked) }
        flushLater()
    }

    fun flushLater() {
        scope.launch { flush() }
    }

    /** Sends what waits, one article after the other; a failed one stays for the next call. */
    suspend fun flush() = sending.withLock {
        for ((id, checked) in _pending.value) {
            repository.checkItem(id, checked).onSuccess {
                _pending.update { now -> if (now[id] == checked) now - id else now }
            }
        }
    }

    /** Articles that no longer exist on the list: their tick is dropped. */
    fun retainOnly(existing: Set<String>) {
        _pending.update { now -> now.filterKeys { it in existing } }
    }
}
