package com.aeriva.core.common

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Repositories and use cases depend on this instead of
 * kotlinx.coroutines.Dispatchers directly, so tests can substitute a
 * single-threaded test dispatcher for every coroutine context at once
 * instead of each class needing its own dispatcher constructor
 * parameters. Per architecture doc Section 2.3/engineering standards:
 * this is infrastructure every feature module needs, which is why it
 * lives in core:common rather than being duplicated per module.
 */
interface AerivaDispatchers {
    val main: CoroutineDispatcher
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
}
