package com.aeriva.network.monitor.advisor

import com.aeriva.core.logging.AerivaLogger
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.network.monitor.PlatformCallbackRegistrar
import com.aeriva.network.monitor.PlatformEvent
import com.aeriva.network.monitor.RawCapabilitiesSnapshot
import com.aeriva.network.monitor.platformEventFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Platform independent core of the Network Advisor candidate monitor. It
 * turns two platform registrations (every network, and the default network)
 * into the list of candidates that [foldCandidateListFlow] already folds, and
 * adds the one thing the platform will not do on its own: say when events may
 * have been lost.
 *
 * Stale handling (Agent 6 spec decision D1, ADVISOR_UI_SPEC.md section 10):
 * [stale] becomes true when a callback event was rejected by a full buffer, or
 * when a registration failed. Elapsed time never makes it stale, because
 * callbacks fire on change and silence on a stable connection is normal.
 *
 * Recovery is a restart, not a lookup. Registering a network callback makes the
 * platform report every network that currently matches again, so cancelling the
 * collection (which unregisters) and collecting again rebuilds the full list
 * from nothing, with no synchronous platform call inside a callback. [stale]
 * clears when the first fresh list arrives. If a restart yields no network at
 * all, no event ever arrives, so after [settleMillis] the source reports an
 * empty list and clears [stale]. [settleMillis] is a placeholder, not a
 * measured platform timing (UNCONFIRMED).
 *
 * Known limit: the first list after a restart can be partial while the
 * platform is still reporting networks. The recommendation gate's dwell time
 * is what keeps a partial list from producing a visible recommendation.
 */
internal class AdvisorCandidateSource<N, C>(
    private val allNetworks: PlatformCallbackRegistrar<N, C>,
    private val defaultNetwork: PlatformCallbackRegistrar<N, C>,
    private val idOf: (N) -> CandidateNetworkId,
    private val snapshotOf: (C) -> RawCapabilitiesSnapshot,
    private val logger: AerivaLogger,
    private val now: () -> Instant = Instant::now,
    private val restartDelayMillis: Long = DEFAULT_RESTART_DELAY_MILLIS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    private val tag: String = TAG
) {

    private val mutableStale = MutableStateFlow(false)
    private val dropped = AtomicLong(0)

    /** True while the candidate list may be out of date. */
    val stale: StateFlow<Boolean> = mutableStale.asStateFlow()

    /** Events rejected by a full callback buffer since this source was created. */
    val droppedEventCount: Long
        get() = dropped.get()

    private sealed interface Item {
        data class Candidates(val list: List<CandidateNetwork>) : Item
        data object Overflow : Item
        data object Settled : Item
    }

    fun observe(): Flow<List<CandidateNetwork>> = flow {
        mutableStale.value = false
        while (true) {
            val overflow = Channel<Unit>(Channel.CONFLATED)
            val pauseMillis = try {
                collectRound(overflow, this)
                restartDelayMillis
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                logger.w(tag, "Candidate monitoring failed, will retry: ${failure.message}")
                retryDelayMillis
            } finally {
                overflow.cancel()
            }
            mutableStale.value = true
            delay(pauseMillis)
        }
    }

    private suspend fun collectRound(overflow: Channel<Unit>, out: FlowCollector<List<CandidateNetwork>>) {
        val items: Flow<Item> = merge(
            foldCandidateListFlow(rawEvents { overflow.trySend(Unit) }, now).map { Item.Candidates(it) },
            overflow.receiveAsFlow().map { Item.Overflow },
            flow { delay(settleMillis); emit(Item.Settled) }
        )
        items.takeWhile { it !is Item.Overflow }.collect { item ->
            when (item) {
                is Item.Candidates -> {
                    out.emit(item.list)
                    mutableStale.value = false
                }
                Item.Settled -> if (mutableStale.value) {
                    out.emit(emptyList())
                    mutableStale.value = false
                }
                Item.Overflow -> Unit
            }
        }
    }

    private fun rawEvents(onOverflow: () -> Unit): Flow<RawCandidateEvent> = merge(
        platformEventFlow(allNetworks, logger, tag) { event ->
            recordDrop(event)
            onOverflow()
        }.mapNotNull { allNetworksEvent(it) },
        defaultNetworkEvents(onOverflow)
    )

    private fun allNetworksEvent(event: PlatformEvent<N, C>): RawCandidateEvent? = when (event) {
        // No capabilities travel with onAvailable. The platform follows it with
        // onCapabilitiesChanged, and the tracker lists a network only once it
        // has a snapshot, so nothing is looked up synchronously here.
        is PlatformEvent.Available -> RawCandidateEvent.Available(idOf(event.network), null)
        is PlatformEvent.CapabilitiesChanged ->
            RawCandidateEvent.CapabilitiesChanged(idOf(event.network), snapshotOf(event.capabilities))
        is PlatformEvent.BlockedStatusChanged ->
            RawCandidateEvent.BlockedStatusChanged(idOf(event.network), event.blocked)
        is PlatformEvent.Lost -> RawCandidateEvent.Lost(idOf(event.network))
        PlatformEvent.Unavailable -> null
    }

    /**
     * The default network callback can report the new default before it reports
     * the old one lost. A late "lost" for a network that is no longer the
     * default must not clear the new default, so a loss only counts when it is
     * for the network this collection last saw become the default.
     */
    private fun defaultNetworkEvents(onOverflow: () -> Unit): Flow<RawCandidateEvent> = flow {
        var current: CandidateNetworkId? = null
        platformEventFlow(defaultNetwork, logger, tag) { event ->
            recordDrop(event)
            onOverflow()
        }.collect { event ->
            when (event) {
                is PlatformEvent.Available -> {
                    val id = idOf(event.network)
                    current = id
                    emit(RawCandidateEvent.DefaultChanged(id))
                }
                is PlatformEvent.Lost -> {
                    val id = idOf(event.network)
                    if (current == id) {
                        current = null
                        emit(RawCandidateEvent.DefaultChanged(null))
                    }
                }
                else -> Unit
            }
        }
    }

    private fun recordDrop(event: PlatformEvent<N, C>) {
        if (dropped.incrementAndGet() == 1L) {
            logger.w(tag, "Callback channel full, dropped ${event::class.simpleName}; restarting registration")
        }
    }

    private companion object {
        const val TAG = "AdvisorCandidateSource"
        const val DEFAULT_RESTART_DELAY_MILLIS = 1_000L
        const val DEFAULT_RETRY_DELAY_MILLIS = 5_000L
        const val DEFAULT_SETTLE_MILLIS = 2_000L
    }
}
