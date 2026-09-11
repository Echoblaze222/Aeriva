package com.aeriva.core.common

import kotlinx.coroutines.Dispatchers

/**
 * Real dispatchers for production code. Tests should provide their own
 * [AerivaDispatchers] (typically all three properties pointed at a
 * single [kotlinx.coroutines.test.StandardTestDispatcher]) rather than
 * using this class.
 */
class DefaultAerivaDispatchers : AerivaDispatchers {
    override val main = Dispatchers.Main
    override val io = Dispatchers.IO
    override val default = Dispatchers.Default
}
