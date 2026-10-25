package com.aeriva.network.monitor

import com.aeriva.core.logging.NoOpLogger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Drives [platformEventFlow], the real callbackFlow used by
 * [AndroidNetworkMonitor], through a fake registrar. Covers the four behaviors
 * the reliability report asked for: trySend failure, registration throwing,
 * unregistration on cancellation, and repeated registration.
 */
class PlatformEventFlowTest {

    private class FakeRegistrar(
        private val failure: RuntimeException? = null
    ) : PlatformCallbackRegistrar<String, String> {
        val sinks = mutableListOf<PlatformCallbackSink<String, String>>()
        var registerCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun register(sink: PlatformCallbackSink<String, String>): AutoCloseable {
            registerCalls++
            failure?.let { throw it }
            sinks += sink
            return AutoCloseable { closeCalls++ }
        }
    }

    @Test
    fun eventsAreDeliveredInCallbackOrder() = runTest {
        val registrar = FakeRegistrar()
        val received = mutableListOf<PlatformEvent<String, String>>()
        val job = launch { platformEventFlow(registrar, NoOpLogger, "T").toList(received) }
        runCurrent()

        val sink = registrar.sinks.single()
        sink.onAvailable("A")
        sink.onCapabilitiesChanged("A", "caps")
        sink.onBlockedStatusChanged("A", false)
        sink.onLost("A")
        sink.onUnavailable()
        runCurrent()
        job.cancel()

        assertEquals(
            listOf<PlatformEvent<String, String>>(
                PlatformEvent.Available("A"),
                PlatformEvent.CapabilitiesChanged("A", "caps"),
                PlatformEvent.BlockedStatusChanged("A", false),
                PlatformEvent.Lost("A"),
                PlatformEvent.Unavailable
            ),
            received
        )
    }

    @Test
    fun registrationThrowing_failsTheFlow_andNothingIsUnregistered() = runTest {
        val registrar = FakeRegistrar(failure = IllegalStateException("too many callbacks"))

        try {
            platformEventFlow(registrar, NoOpLogger, "T").toList()
            fail("the registration failure must reach the collector")
        } catch (expected: IllegalStateException) {
            assertEquals("too many callbacks", expected.message)
        }

        assertEquals(1, registrar.registerCalls)
        assertEquals("nothing was registered, so nothing may be unregistered", 0, registrar.closeCalls)
    }

    @Test
    fun cancellingTheCollector_unregistersExactlyOnce() = runTest {
        val registrar = FakeRegistrar()
        val job = launch { platformEventFlow(registrar, NoOpLogger, "T").toList() }
        runCurrent()
        assertEquals(1, registrar.registerCalls)
        assertEquals(0, registrar.closeCalls)

        job.cancel()
        runCurrent()

        assertEquals(1, registrar.closeCalls)
    }

    @Test
    fun eachCollectionRegistersItsOwnSink_andClosingOneLeavesTheOtherDelivering() = runTest {
        val registrar = FakeRegistrar()
        val first = mutableListOf<PlatformEvent<String, String>>()
        val second = mutableListOf<PlatformEvent<String, String>>()
        val flow = platformEventFlow(registrar, NoOpLogger, "T")
        val firstJob = launch { flow.toList(first) }
        val secondJob = launch { flow.toList(second) }
        runCurrent()

        assertEquals(2, registrar.registerCalls)
        assertNotSame(registrar.sinks[0], registrar.sinks[1])

        firstJob.cancel()
        runCurrent()
        assertEquals(1, registrar.closeCalls)

        registrar.sinks[1].onLost("A")
        runCurrent()
        secondJob.cancel()

        assertEquals(listOf<PlatformEvent<String, String>>(PlatformEvent.Lost("A")), second)
        assertTrue(first.isEmpty())
    }

    @Test
    fun aSendRejectedByAFullBuffer_isReported_withTheNewestEventsDropped() = runTest {
        val registrar = FakeRegistrar()
        val failed = mutableListOf<PlatformEvent<String, String>>()
        val received = mutableListOf<PlatformEvent<String, String>>()
        val job = launch {
            platformEventFlow(registrar, NoOpLogger, "T") { failed += it }.toList(received)
        }
        runCurrent()

        val sink = registrar.sinks.single()
        repeat(BURST) { sink.onCapabilitiesChanged("A", "c$it") }
        sink.onLost("A")
        runCurrent()
        job.cancel()

        assertTrue("a burst larger than the buffer must report failures", failed.isNotEmpty())
        assertEquals("the final Lost is the newest event, so it is the one dropped", PlatformEvent.Lost("A"), failed.last())
        assertEquals(
            "every event is either delivered or reported, never lost silently",
            BURST + 1,
            received.size + failed.size
        )
    }

    @Test
    fun aLateCallbackAfterCancellation_isNotCountedAsAFailure() = runTest {
        val registrar = FakeRegistrar()
        val failed = mutableListOf<PlatformEvent<String, String>>()
        val job = launch {
            platformEventFlow(registrar, NoOpLogger, "T") { failed += it }.toList()
        }
        runCurrent()
        val sink = registrar.sinks.single()

        job.cancel()
        runCurrent()
        sink.onLost("A")

        assertTrue(failed.isEmpty())
    }

    private companion object {
        const val BURST = 200
    }
}
