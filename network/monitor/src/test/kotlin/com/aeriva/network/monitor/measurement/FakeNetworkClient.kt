package com.aeriva.network.monitor.measurement

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Hand-written fake, not a mocking library -- same convention as
 * [com.aeriva.core.security.FakeSecureKeyValueStore] and
 * [com.aeriva.core.database.FakeNetworkStateHistoryDao]
 * (PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md Section 1/3 explicitly
 * names this as the pattern to extend, not a mocking library to
 * introduce).
 *
 * Calls are scripted in advance via [enqueueSuccess]/[enqueueFailure]/
 * [enqueueHang], consumed strictly in order -- "this call takes exactly
 * N milliseconds and then returns/fails" as a fixture, not real timing
 * (Section 3's explicit deterministic-delay/failure-injection
 * requirement). [delayMillis] is virtual time when the calling test
 * runs under `runTest`/a `TestDispatcher` -- no real waiting happens in
 * CI regardless of how large a scripted delay is.
 *
 * [callCount]/[completedCallCount]/[cancelledCallCount] exist
 * specifically so a test can assert on cleanup/resource-leak behavior
 * (objectives 12-14): a well-behaved caller that cancels a probe should
 * show `cancelledCallCount == 1` for that call, and
 * `callCount == completedCallCount + cancelledCallCount + failedCallCount`
 * should always hold -- no call should simply vanish unaccounted for.
 */
class FakeNetworkClient : NetworkClient {

    private val scriptedCalls = ArrayDeque<ScriptedCall>()

    var callCount = 0
        private set
    var completedCallCount = 0
        private set
    var cancelledCallCount = 0
        private set

    /** Every [probe] call's `target` argument, in invocation order --
     * lets a test assert *what* was probed, not just how many times. */
    val recordedTargets = mutableListOf<String>()

    fun enqueueSuccess(payload: ByteArray, delayMillis: Long = 0) {
        scriptedCalls.addLast(ScriptedCall(delayMillis, NetworkClientOutcome.Success(payload)))
    }

    fun enqueueFailure(outcome: NetworkClientOutcome, delayMillis: Long = 0) {
        scriptedCalls.addLast(ScriptedCall(delayMillis, outcome))
    }

    /**
     * A call that never resolves on its own -- the only way to
     * deterministically exercise a real timeout/cancellation path
     * without real-time flakiness: the caller must be the one that
     * eventually times out or cancels this, exactly like a real hung
     * socket would require an external timeout to end it.
     */
    fun enqueueHang() {
        scriptedCalls.addLast(ScriptedCall(delayMillis = Long.MAX_VALUE, outcome = null))
    }

    override suspend fun probe(target: String): NetworkClientOutcome {
        callCount++
        recordedTargets += target
        val scripted = scriptedCalls.removeFirstOrNull()
            ?: throw AssertionError(
                "FakeNetworkClient.probe(\"$target\") called more times than scripted " +
                    "-- call count $callCount has no matching enqueue*() in this test. " +
                    "Thrown as AssertionError (an Error), not IllegalStateException, so " +
                    "LatencyMeasurementEngine's catch (e: Exception) never swallows a " +
                    "test-authoring mistake into Unclassified -- see this repository's " +
                    "PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md Section 5.A."
            )

        var cancelled = false
        try {
            if (scripted.delayMillis > 0) {
                delay(scripted.delayMillis)
            }
            val outcome = requireNotNull(scripted.outcome) {
                "FakeNetworkClient.probe(\"$target\") reached the end of an enqueueHang() call " +
                    "-- it should have been cancelled or timed out before this point"
            }
            completedCallCount++
            return outcome
        } catch (cancellation: CancellationException) {
            // Never swallow cancellation -- a fake that ate this
            // exception would hide exactly the "does the code leak a
            // result after cancellation" bug objectives 9/12/14 exist to
            // catch. Counted, then rethrown.
            cancelled = true
            throw cancellation
        } finally {
            if (cancelled) cancelledCallCount++
        }
    }

    private data class ScriptedCall(val delayMillis: Long, val outcome: NetworkClientOutcome?)
}
