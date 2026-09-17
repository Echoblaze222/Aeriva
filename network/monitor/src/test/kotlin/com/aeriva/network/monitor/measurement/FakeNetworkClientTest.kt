package com.aeriva.network.monitor.measurement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Follows the same precedent as
 * [com.aeriva.core.common.TestAerivaDispatchersTest]: the fake itself is
 * directly tested, not just used, so a test relying on it elsewhere can
 * trust its scripted behavior is what it claims to be.
 */
class FakeNetworkClientTest {

    @Test
    fun probe_returnsScriptedOutcomes_inOrder() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(byteArrayOf(1, 2, 3))
            enqueueFailure(NetworkClientOutcome.ConnectionRefused("refused"))
        }

        val first = client.probe("a")
        val second = client.probe("b")

        // ByteArray properties don't get structural equals from Kotlin's
        // data class generation (it compares array references, not
        // contents) -- compare via .toList() rather than relying on
        // NetworkClientOutcome.Success's own equals() for the payload.
        val success = first as NetworkClientOutcome.Success
        assertEquals(listOf<Byte>(1, 2, 3), success.payload.toList())
        assertTrue(second is NetworkClientOutcome.ConnectionRefused)
        assertEquals(listOf("a", "b"), client.recordedTargets)
        assertEquals(2, client.callCount)
        assertEquals(2, client.completedCallCount)
    }

    @Test
    fun probe_callingMoreTimesThanScripted_failsLoudly_notSilently() = runTest {
        val client = FakeNetworkClient().apply { enqueueSuccess(byteArrayOf(1)) }

        client.probe("a")

        try {
            client.probe("b")
            fail("expected an error for an unscripted call")
        } catch (expected: IllegalStateException) {
            // A silently-empty/default response here would hide a test
            // author's own scripting mistake -- this must fail loudly.
        }
    }

    @Test
    fun probe_delayIsVirtualTime_noRealWaitingRegardlessOfScriptedLength() = runTest {
        val client = FakeNetworkClient().apply {
            enqueueSuccess(byteArrayOf(1), delayMillis = 10_000L)
        }

        // If this were real time, a 10-second delay would make this test
        // itself slow; under runTest's virtual time it completes
        // immediately regardless.
        client.probe("a")
    }

    @Test
    fun probe_cancelledMidHang_countsAsCancelled_notCompleted_andRethrows() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val dispatcher = StandardTestDispatcher(testScheduler)

        val deferred = async(dispatcher) { client.probe("a") }
        advanceUntilIdle()
        deferred.cancel()
        deferred.join()

        assertTrue(deferred.isCancelled)
        assertEquals(1, client.cancelledCallCount)
        assertEquals(0, client.completedCallCount)
    }

    @Test(expected = CancellationException::class)
    fun probe_cancelledMidHang_rethrowsCancellation_neverSwallowsIt() = runTest {
        val client = FakeNetworkClient().apply { enqueueHang() }
        val dispatcher = StandardTestDispatcher(testScheduler)

        val deferred = async(dispatcher) { client.probe("a") }
        advanceUntilIdle()
        deferred.cancel()
        deferred.await()
    }
}
