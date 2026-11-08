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
 * The ticks of the Courses activity (MAG-763) and its « Manquant » marks (MAG-764): each one is shown at once and
 * kept here until the API has it, so one made without network is sent when it comes back, once. The last state
 * asked of an article wins. The queue outlives the screen; it does not outlive the process.
 */
class GroceryTickQueue(
    private val repository: GroceryListRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _pending = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Article id → the state asked and not yet received by the API. */
    val pending: StateFlow<Map<String, Boolean>> = _pending

    private val _pendingMissing = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Article id → missing or not, asked and not yet received by the API. */
    val pendingMissing: StateFlow<Map<String, Boolean>> = _pendingMissing

    private val sending = Mutex()

    fun tick(itemId: String, checked: Boolean) {
        _pending.update { it + (itemId to checked) }
        flushLater()
    }

    fun markMissing(itemId: String, missing: Boolean) {
        _pendingMissing.update { it + (itemId to missing) }
        flushLater()
    }

    fun flushLater() {
        scope.launch { flush() }
    }

    /**
     * Sends what waits, one article after the other; a failed one stays for the next call. The API refuses to mark
     * a ticked article missing, and the screen only marks one it shows unticked: so an article marked missing is
     * unticked first, whatever its last tick says, and its tick waits until the mark is received.
     */
    suspend fun flush() = sending.withLock {
        val marking = _pendingMissing.value.filterValues { it }.keys
        sendTicks { id, checked -> !checked && id !in marking }
        for (id in marking) {
            repository.checkItem(id, false).onSuccess { _pending.update { now -> if (now[id] == false) now - id else now } }
        }
        for ((id, missing) in _pendingMissing.value) {
            repository.markMissing(id, missing).onSuccess {
                _pendingMissing.update { now -> if (now[id] == missing) now - id else now }
            }
        }
        val refused = _pendingMissing.value.filterValues { it }.keys
        sendTicks { id, checked -> id !in refused && (checked || id in marking) }
    }

    private suspend fun sendTicks(which: (String, Boolean) -> Boolean) {
        for ((id, checked) in _pending.value) {
            if (!which(id, checked)) continue
            repository.checkItem(id, checked).onSuccess {
                _pending.update { now -> if (now[id] == checked) now - id else now }
            }
        }
    }

    /** Articles that no longer exist on the list: their tick is dropped. */
    fun retainOnly(existing: Set<String>) {
        _pending.update { now -> now.filterKeys { it in existing } }
        _pendingMissing.update { now -> now.filterKeys { it in existing } }
    }
}
