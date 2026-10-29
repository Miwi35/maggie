package com.maggie.app.data.car

import com.maggie.app.data.location.CarConnectionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The car's connection, observed once for the whole app (MAG-683): the trip's GPS (MAG-666) and Maggie's
 * messages both ask whether the phone is projecting to Android Auto, and one `CarConnection` answers both.
 *
 * Like its source, it says nothing until the car has answered — a `false` sent first would end the trip service
 * at the very moment the car connects — while [isConnected] reads `false` meanwhile.
 */
class SharedCarConnection(upstream: CarConnectionSource, scope: CoroutineScope) : CarConnectionSource {

    private val state = MutableStateFlow<Boolean?>(null)

    init {
        scope.launch { upstream.connected.collect { state.value = it } }
    }

    override val connected: Flow<Boolean> = state.filterNotNull()

    val isConnected: Boolean get() = state.value == true
}
