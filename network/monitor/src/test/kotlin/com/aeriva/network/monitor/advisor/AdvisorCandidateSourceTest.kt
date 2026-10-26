package com.aeriva.network.monitor.advisor

import com.aeriva.core.logging.NoOpLogger
import com.aeriva.core.model.TransportType
import com.aeriva.core.model.advisor.CandidateNetwork
import com.aeriva.core.model.advisor.CandidateNetworkId
import com.aeriva.network.monitor.PlatformCallbackRegistrar
import com.aeriva.network.monitor.PlatformCallbackSink
import com.aeriva.network.monitor.RawCapabilitiesSnapshot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Drives [AdvisorCandidateSource] through fake registrars. Networks are
 * single letters ("A", "B"); capabilities are the strings "wifi", "cell" and
 * their "-unvalidated" forms, mapped to snapshots by [snapshot].
 *
 * What this covers: the list follows callback events, the default network is
 * tracked without a late "lost" clearing a newer default, a full callback
 * buffer makes the source stale and restarts both registrations, a failed
 * registration makes it stale and retries, and stale clears on the first fresh
 * list or, with no network at all, after the settle time.
 *
 * What this does not cover: any real platform behavior. Whether Android
 * re-reports every network on re-registration, and how fast, is documented
 * behavior of registerNetworkCallback that no JVM test can show. It needs a
 * device.
 */
class AdvisorCandidateSourceTest {

    private class FakeRegistrar(private val failFirst: Int = 0) : PlatformCallbackRegistrar<String, String> {
        val sinks = mutableListOf<PlatformCallbackSink<String, String>>()
        var registerCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun register(sink: PlatformCallbackSink<String, String>): AutoCloseable {
            registerCalls++
            if (registerCalls <= failFirst) throw IllegalStateException("too many callbacks")
            sinks += sink
            return AutoCloseable { closeCalls++ }
        }
    }

    private fun snapshot(name: String): RawCapabilitiesSnapshot {
        val validated = !name.endsWith("-unvalidated")
        val transport = if (name.startsWith("cell")) TransportType.CELLULAR else TransportType.WIFI
        return RawCapabilitiesSnapshot(
            hasInternet = true,
            isValidated = validated,
            isNotMetered = transport == TransportType.WIFI,
            transports = setOf(transport),
            rawCapabilityNames = if (validated) setOf("INTERNET", "VALIDATED") else setOf("INTERNET")
        )
    }

    private fun source(all: FakeRegistrar, default: FakeRegistrar) = AdvisorCandidateSource<String, String>(
        allNetworks = all,
        defaultNetwork = default,
        idOf = { CandidateNetworkId(it[0].code.toLong()) },
        snapshotOf = ::snapshot,
        logger = NoOpLogger,
        now = { Instant.EPOCH },
        restartDelayMillis = 1_000L,
        retryDelayMillis = 5_000L,
        settleMillis = 2_000L
    )

    @Test
    fun theListFollowsCallbackEvents_andMarksTheDefaultNetwork() = runTest {
        val all = FakeRegistrar()
        val default = FakeRegistrar()
        val lists = mutableListOf<List<CandidateNetwork>>()
        val job = launch { source(all, default).observe().toList(lists) }
        runCurrent()

        all.sinks.single().onAvailable("A")
        all.sinks.single().onCapabilitiesChanged("A", "wifi")
        default.sinks.single().onAvailable("A")
        runCurrent()
        job.cancel()

        val only = lists.last().single()
        assertEquals(TransportType.WIFI, only.transport)
        assertTrue(only.validated)
        assertTrue(only.isDefault)
    }

    @Test
    fun aLateLostForAnOldDefault_doesNotClearTheNewDefault() = runTest {
        val all = FakeRegistrar()
        val default = FakeRegistrar()
        val lists = mutableListOf<List<CandidateNetwork>>()
        val job = launch { source(all, default).observe().toList(lists) }
        runCurrent()

        val allSink = all.sinks.single()
        allSink.onAvailable("A")
        allSink.onCapabilitiesChanged("A", "wifi")
        allSink.onAvailable("B")
        allSink.onCapabilitiesChanged("B", "cell")
        val defaultSink = default.sinks.single()
        defaultSink.onAvailable("A")
        defaultSink.onAvailable("B")
        defaultSink.onLost("A")
        runCurrent()
        job.cancel()

        val byTransport = lists.last().associateBy { it.transport }
        assertTrue(byTransport.getValue(TransportType.CELLULAR).isDefault)
        assertFalse(byTransport.getValue(TransportType.WIFI).isDefault)
    }

    @Test
    fun aFullCallbackBuffer_makesTheSourceStale_restartsBothRegistrations_andClearsOnTheFreshList() = runTest {
        val all = FakeRegistrar()
        val default = FakeRegistrar()
        val source = source(all, default)
        val lists = mutableListOf<List<CandidateNetwork>>()
        val job = launch { source.observe().toList(lists) }
        runCurrent()
        assertFalse(source.stale.value)

        val sink = all.sinks.single()
        repeat(BURST) { sink.onCapabilitiesChanged("A", "wifi") }
        runCurrent()

        assertTrue("events were dropped, so the list may be out of date", source.stale.value)
        assertTrue(source.droppedEventCount > 0)
        assertEquals("the collection was cancelled, which unregisters", 1, all.closeCalls)
        assertEquals(1, default.closeCalls)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals("both registrations were made again", 2, all.registerCalls)
        assertEquals(2, default.registerCalls)

        val fresh = all.sinks.last()
        fresh.onAvailable("A")
        fresh.onCapabilitiesChanged("A", "wifi")
        runCurrent()
        job.cancel()

        assertFalse("the first fresh list clears stale", source.stale.value)
        assertEquals(TransportType.WIFI, lists.last().single().transport)
    }

    @Test
    fun aFailedRegistration_makesTheSourceStale_thenRetries() = runTest {
        val all = FakeRegistrar(failFirst = 1)
        val default = FakeRegistrar()
        val source = source(all, default)
        val lists = mutableListOf<List<CandidateNetwork>>()
        val job = launch { source.observe().toList(lists) }
        runCurrent()

        assertTrue(source.stale.value)
        assertEquals(1, all.registerCalls)

        advanceTimeBy(5_001L)
        runCurrent()
        assertEquals("the registration was retried", 2, all.registerCalls)

        val sink = all.sinks.single()
        sink.onAvailable("A")
        sink.onCapabilitiesChanged("A", "wifi")
        runCurrent()
        job.cancel()

        assertFalse(source.stale.value)
        assertEquals(TransportType.WIFI, lists.last().single().transport)
    }

    @Test
    fun aRestartThatFindsNoNetwork_reportsAnEmptyList_andClearsStaleAfterTheSettleTime() = runTest {
        val all = FakeRegistrar()
        val default = FakeRegistrar()
        val source = source(all, default)
        val lists = mutableListOf<List<CandidateNetwork>>()
        val job = launch { source.observe().toList(lists) }
        runCurrent()

        val first = all.sinks.single()
        first.onAvailable("A")
        first.onCapabilitiesChanged("A", "wifi")
        runCurrent()
        assertEquals(1, lists.last().size)

        repeat(BURST) { first.onCapabilitiesChanged("A", "wifi") }
        runCurrent()
        assertTrue(source.stale.value)

        advanceTimeBy(1_001L)
        runCurrent()
        assertTrue("no list has arrived yet, so it is still stale", source.stale.value)

        advanceTimeBy(2_001L)
        runCurrent()
        job.cancel()

        assertFalse(source.stale.value)
        assertTrue("nothing was re-reported, so the list is empty", lists.last().isEmpty())
    }

    private companion object {
        const val BURST = 200
    }
}
