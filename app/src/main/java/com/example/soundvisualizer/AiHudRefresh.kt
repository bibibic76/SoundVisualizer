package com.example.soundvisualizer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge

/** Result notifications bypass the heartbeat; ticks only keep level/age/settings current. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun aiHudRefreshes(
    changes: Flow<Unit>,
    paused: StateFlow<Boolean>,
    heartbeatMs: Long = 100L
): Flow<Unit> {
    require(heartbeatMs > 0)
    return paused.flatMapLatest { sleeping ->
        if (sleeping) emptyFlow() else merge(changes, flow {
            emit(Unit) // Initial subscribe and resume read the current source, not an old event payload.
            while (true) {
                delay(heartbeatMs)
                emit(Unit)
            }
        })
    }.conflate().filter { !paused.value }
}
