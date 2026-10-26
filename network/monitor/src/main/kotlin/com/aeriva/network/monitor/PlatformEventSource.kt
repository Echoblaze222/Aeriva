package com.aeriva.network.monitor

import com.aeriva.core.logging.AerivaLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android-free seam around the platform callback registration, so the
 * callbackFlow behavior (delivery, trySend failure, registration failure,
 * unregistration on cancellation, one registration per collection) can be
 * exercised on the plain JVM. See NETWORK_MONITOR_RELIABILITY_SEAM_REPORT.md,
 * section 3. [N] is the network handle type and [C] the capabilities type;
 * the Android adapter in [AndroidNetworkMonitor] binds them to
 * `android.net.Network` and `android.net.NetworkCapabilities`.
 */
internal interface PlatformCallbackSink<N, C> {
    fun onAvailable(network: N)
    fun onCapabilitiesChanged(network: N, capabilities: C)
    fun onBlockedStatusChanged(network: N, blocked: Boolean)
    fun onLost(network: N)
    fun onUnavailable()
}

internal fun interface PlatformCallbackRegistrar<N, C> {
    /**
     * Registers [sink] with the platform and returns the handle that
     * unregisters it. May throw; if it does, nothing is registered and no
     * unregistration is attempted.
     */
    fun register(sink: PlatformCallbackSink<N, C>): AutoCloseable
}

/** Raw platform callback events, one-to-one with the callback methods used. */
internal sealed interface PlatformEvent<out N, out C> {
    data class Available<N>(val network: N) : PlatformEvent<N, Nothing>
    data class CapabilitiesChanged<N, C>(val network: N, val capabilities: C) : PlatformEvent<N, C>
    data class BlockedStatusChanged<N>(val network: N, val blocked: Boolean) : PlatformEvent<N, Nothing>
    data class Lost<N>(val network: N) : PlatformEvent<N, Nothing>
    data object Unavailable : PlatformEvent<Nothing, Nothing>
}

/**
 * Cold flow of platform events. Each collection registers once through
 * [registrar] and unregisters when the collection ends. The callback never
 * blocks: it uses `trySend`. A send rejected because the buffer is full is
 * reported to [onSendFailed] (the event is dropped, as before this seam
 * existed). A send rejected because the channel is already closed (a late
 * callback after cancellation) is not a failure and is not reported.
 */
internal fun <N, C> platformEventFlow(
    registrar: PlatformCallbackRegistrar<N, C>,
    logger: AerivaLogger,
    tag: String,
    onSendFailed: (PlatformEvent<N, C>) -> Unit = {}
): Flow<PlatformEvent<N, C>> = callbackFlow {
    fun deliver(event: PlatformEvent<N, C>) {
        val result = trySend(event)
        if (result.isFailure && !result.isClosed) onSendFailed(event)
    }

    val sink = object : PlatformCallbackSink<N, C> {
        override fun onAvailable(network: N) = deliver(PlatformEvent.Available(network))

        override fun onCapabilitiesChanged(network: N, capabilities: C) =
            deliver(PlatformEvent.CapabilitiesChanged(network, capabilities))

        override fun onBlockedStatusChanged(network: N, blocked: Boolean) =
            deliver(PlatformEvent.BlockedStatusChanged(network, blocked))

        override fun onLost(network: N) = deliver(PlatformEvent.Lost(network))

        override fun onUnavailable() = deliver(PlatformEvent.Unavailable)
    }

    logger.i(tag, "Registering default network callback")
    val registration = registrar.register(sink)

    awaitClose {
        logger.i(tag, "Unregistering default network callback")
        registration.close()
    }
}
