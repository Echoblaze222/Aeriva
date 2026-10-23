# Network monitor reliability: seam report

Author: Agent 1 (network monitor reliability engineer)
Branch: `audit/network-monitor-seam-report`
Base: `main` @ `9cff60cd0544` (see "Base note")
Scope: investigation only. No production file changed. Production files read: `AndroidNetworkMonitor.kt`, `ConnectionStateRepository.kt`, `AppContainer.kt`, plus `NetworkEventReducer.kt` (`foldNetworkStateFlow`) for context.

Labels follow decision L7: CONFIRMED (code read or test result or official source), LIKELY (reasoned, not reproduced), UNCONFIRMED (cannot be shown without a device, a seam, or both).

## Base note

The brief names `f53134c193ee` as the base. The GitHub tooling available to this agent can only create a branch from a branch name, not from a raw SHA. The branch was therefore created from `main` @ `9cff60cd0544`. CONFIRMED by `git diff --stat f53134c193ee origin/main`: the tree differs from `f53134c` by exactly two files, the two test files of PR #4 (`ConnectionStateRepositoryUpstreamFailureTest.kt`, `NetworkStateMissingEventRecoveryTest.kt`). Nothing else differs.

## 1. Answer to the leader's question: is `audit/network-reliability-tests` ready to merge as is

It has already been merged. CONFIRMED by `git merge-base --is-ancestor 7e3a6ef origin/main`: `7e3a6ef` is an ancestor of `main`, via merge commit `9cff60c` (PR #4). Nothing remains to merge from that branch.

Review of its content, for the record:

- Test-only, two files, no production, Gradle, manifest or UI file touched. CONFIRMED by the diff above.
- `NetworkStateMissingEventRecoveryTest` models an omitted `Available` by not emitting it into `foldNetworkStateFlow`. It does not drive a failed `trySend`, and its own KDoc says so. Accurate and honest.
- `ConnectionStateRepositoryUpstreamFailureTest` pins that the repository neither catches, logs nor retries an upstream failure and that `state` freezes. Its KDoc correctly separates what is asserted (JVM) from what is inferred (process termination in the real app, not proven).
- Both are characterization tests. They will need deliberate revision when hardening lands. That is intended and documented in their KDoc.
- Gap not closed by them: neither can reach the real `NetworkCallback` code, so none of risks R1, R2, R3, R4 below is exercised through `AndroidNetworkMonitor` itself. Section 3 is the seam that closes that.

CI status of the merged state is the leader's to read (post-merge run on `9cff60c`). This agent did not read it.

## 2. Risk register

Line numbers are in `AndroidNetworkMonitor.kt` at `9cff60c` unless stated.

| Id | Risk | Label | Evidence | Reproducing test |
|---|---|---|---|---|
| R1 | Every `trySend` result is ignored (lines 77, 84, 98, 102, 106). A failed send drops the event silently, with no log and no recovery signal. | CONFIRMED that results are ignored and that a failure is silent. CONFIRMED mechanism: a non-successful `trySend` means the element was not delivered. UNCONFIRMED that it ever happens on a device. | Code reading. kotlinx.coroutines `SendChannel.trySend` documentation. | JVM replica of the exact shape: `CallbackFlowTrySendOverflowCharacterizationTest` (this branch). It cannot drive the production callback, see section 3. |
| R2 | Which event is dropped on overflow is the NEWEST, so the final truth (`Lost`, the latest `CapabilitiesChanged`) is what is lost while the oldest are kept. | CONFIRMED on the replica shape (SUSPEND overflow, default buffer). | `callbackFlow` default capacity is `Channel.BUFFERED`, which is 64 for the SUSPEND overflow strategy (kotlinlang.org API reference for `Channel.Factory.BUFFERED`). | Same test, first case: accepted sends are a prefix, rejected a suffix, consumer receives the accepted ones in order. |
| R3 | Consequence of a dropped `Lost`: the pipeline keeps reporting the lost network as connected until some later `Available` arrives. A dropped `Available(B)` makes all later events of B ignored until the next `Available`. | CONFIRMED for the reducer side (the second is already pinned by `NetworkStateMissingEventRecoveryTest`, line 61). CONFIRMED for the first on the replica pipeline. | `NetworkEventReducer` contract: only `Available` establishes a tracked network. | `aLostEventRejectedByAFullBuffer_leavesThePipelineReportingTheLostNetworkAsConnected`, with a control that reports the loss when nothing overflows. |
| R4 | Whether the buffer can actually fill on a device. Pending events need to exceed 64 while the collector is not draining. Possible slow points: the `.map(::toRawNetworkEvent)` stage does a synchronous binder call per `Available` (line 144), and the collector runs on `Dispatchers.Default` (`AppContainer.kt` line 30). Downstream `debounce` consumes upstream through its own channel, so a slow terminal collector does not by itself back-pressure the callback channel. | UNCONFIRMED. Probability reasoned as low, not measured. | Code reading only. | None possible without a device stress run. Not claimed. |
| R5 | `registerDefaultNetworkCallback` can throw. Platform documents a `RuntimeException` when the app already has too many callbacks registered (limit of 100 outstanding requests per UID, shared between callbacks). In `AndroidNetworkMonitor` the throw happens inside the `callbackFlow` block (line 111), before `awaitClose` (line 114), so nothing is registered and no unregister is attempted. The exception fails the flow. `ConnectionStateRepository` does not catch it (lines 38 to 50), so with `Eagerly` it fails the sharing coroutine and, in the app where `appScope` has no handler (`AppContainer.kt` line 30), is INFERRED to reach the uncaught-exception handler. | CONFIRMED that the throw is documented and that the repository does not catch (code, plus merged JVM test). LIKELY that it is reachable only by leaking registrations or by many collectors, since the app has one registration by design. UNCONFIRMED that the process terminates (still inferred, not demonstrated, as the merged test KDoc says). | AOSP `ConnectivityManager` Javadoc change and Android reference for `ConnectivityDiagnosticsManager` (limit 100 per UID, shared). | Instrumented, seam free: `AndroidNetworkMonitorRegistrationFailureInstrumentedTest` (this branch). Result recorded in the final report, not here. |
| R6 | `observe()` creates one registration per collection (line 74 runs per collector). Duplicate registration of the SAME callback instance cannot happen because a new anonymous callback is created per collection (line 75). Many registrations can: each extra `observe()` collector adds one against the 100 per UID allowance. Today only `ConnectionStateRepository` collects (line 38), plus instrumented tests. | CONFIRMED that same-instance duplication is structurally impossible and that collectors multiply registrations (code). UNCONFIRMED what the platform does for a re-registered same instance (not reachable from this code). | `grep` of `observe()` call sites: one production caller. | Covered by R5's instrumented test only indirectly. A deterministic count needs the seam. |
| R7 | Unregistration on cancellation (lines 114 to 117). If `unregisterNetworkCallback` throws inside `awaitClose` it would propagate out of the cancelled collection. A leaked registration would shrink the 100 allowance over time. | LIKELY fine: `awaitClose` always runs on cancellation, and the instrumented test `observe_canBeCollectedAgainAfterTheFirstCollectionIsCancelled` shows repeated collection works, but that test would not notice a single leak. UNCONFIRMED that unregistration actually releases. | Code reading. | A deterministic test needs the seam (count register and unregister calls). A seam free leak counter exists in principle (fill the allowance, leave a few free, cycle the monitor) but the platform releases requests asynchronously, which makes it timing sensitive. Not committed, to avoid a flaky test. |
| R8 | `connectivityManager` may be null (line 66). Then registration is skipped with a warning (lines 111 and 112), `awaitClose` still suspends, and the flow emits nothing forever. The repository then keeps the unknown placeholder. | UNCONFIRMED as a real device condition. CONFIRMED as code behavior. | Code reading. The `?:` branch fires only for a null manager because registration returns Unit. | None needed for the seam. Mentioned so the seam keeps this branch reachable. |
| R9 | Delivery is on the platform's callback thread while the channel is consumed on `Dispatchers.Default`. `trySend` is thread safe. Event order is the order of `trySend` calls, which follows the platform's callback order. | LIKELY safe. | `ProducerScope` is a channel. | Not separately tested. |

Not a risk, recorded to prevent a false alarm: line 111 uses `?:` on a Unit result. It reads oddly but behaves as intended (R8).

## 3. The smallest test seam

Goal: drive the real callback shape on the JVM, deterministically, to exercise `trySend` failure, registration throwing, unregistration on cancellation and duplicate registration. A `ConnectivityManager.NetworkCallback` cannot be constructed on the plain JVM in this module (stated by the merged test KDoc; this agent did not independently verify it). So the seam must remove Android types from the callbackFlow block.

Proposal (NOT applied; production files are read only for this agent). One new internal file plus a small edit of `AndroidNetworkMonitor.kt`:

```kotlin
// network/monitor/src/main/kotlin/com/aeriva/network/monitor/PlatformEventSource.kt
internal interface PlatformCallbackSink<N, C> {
    fun onAvailable(network: N)
    fun onCapabilitiesChanged(network: N, capabilities: C)
    fun onBlockedStatusChanged(network: N, blocked: Boolean)
    fun onLost(network: N)
    fun onUnavailable()
}

internal fun interface PlatformCallbackRegistrar<N, C> {
    /** Registers the sink with the platform. Returns the handle that unregisters it. May throw. */
    fun register(sink: PlatformCallbackSink<N, C>): AutoCloseable
}

internal sealed interface PlatformEvent<out N, out C> { /* the five existing cases, generic */ }

internal fun <N, C> platformEventFlow(
    registrar: PlatformCallbackRegistrar<N, C>,
    onSendFailed: (PlatformEvent<N, C>) -> Unit = {},
    logger: AerivaLogger
): Flow<PlatformEvent<N, C>> = callbackFlow { /* same body as today, using the sink */ }
```

`AndroidNetworkMonitor` keeps `toRawNetworkEvent` and `toSnapshot` unchanged. Its `platformEvents()` becomes a thin adapter: build a registrar whose `register` creates the `ConnectivityManager.NetworkCallback` delegating to the sink, calls `registerDefaultNetworkCallback`, and returns an `AutoCloseable` that calls `unregisterNetworkCallback`.

What each requested behavior becomes on the JVM with a fake registrar:

- `trySend` failure: the fake keeps the sink, the test calls it more than 64 times while the collector is parked, and asserts `onSendFailed` fired for the newest events. This is the test that today can only be shown on a replica.
- `registerDefaultNetworkCallback` throwing: the fake registrar throws from `register`. Assert the flow fails with that exception, `close` was never called, and nothing is left registered.
- Unregistration on cancellation: the fake counts `register` and `close` calls. Cancel the collector, assert exactly one `close`.
- Duplicate registration: collect twice, assert two distinct sink instances and two registrations, and that closing one leaves the other delivering. Documents R6.

Why this is the smallest: no new dependency, no new permission, no behavior change if the adapter is a pure move of existing lines, and `NetworkEventReducer` and `foldNetworkStateFlow` are untouched. It touches only the code that creates the callback and the channel. Size estimate: about 60 new lines and about 25 moved lines. This is an estimate, not a measurement.

A seam free alternative was used where possible: the registration failure case is reachable without any seam by exhausting the real allowance on a device or emulator (`AndroidNetworkMonitorRegistrationFailureInstrumentedTest`). That covers R5 end to end but not R1, R6 or R7, which need the seam.

A mocking library would also give access to the callback without a seam, but it is a new dependency (decision L6) and cannot construct the Android callback class on the JVM without further tooling, so it is not recommended.

Approval needed from the Team Leader: applying the seam changes `AndroidNetworkMonitor.kt`, which is outside Agent 1 ownership (decision L4 ties the Network Advisor Android adapter to this decision).

## 4. Recommendation on channel semantics

Position: do not change the channel type as the first move. Detect, count, log and resynchronize instead. Never block in a callback. Keep the stale-event protections in `NetworkEventReducer` and both `distinctUntilChanged` stages around `debounce` in `foldNetworkStateFlow`.

Written analysis of the options. Memory growth, event ordering and state consistency are assessed for each.

| Option | Memory growth | Event ordering | State consistency | Verdict |
|---|---|---|---|---|
| Today: default buffer (64), SUSPEND overflow, `trySend` ignored | Bounded at 64 events. | Preserved for accepted events (FIFO). | Broken silently on overflow: the NEWEST events are dropped, so `Lost` or the latest capabilities can be lost (R2, R3). | Keep as the base. Add detection. |
| `Channel.UNLIMITED` | Unbounded in principle. Each `CapabilitiesChanged` holds a `NetworkCapabilities` reference. Growth is only limited by how long the consumer is stalled. Acceptable only if a stall is provably short, which is UNCONFIRMED. | Preserved. | Preserved, because nothing is dropped. | Not recommended without a stall bound. Trades a rare silent inconsistency for a rare memory event, and removes the signal that something is wrong. |
| `Channel.CONFLATED` | Bounded at 1. | Destroyed: only the latest event survives. | Broken by design here. The reducer needs the burst `Available`, `CapabilitiesChanged`, `BlockedStatusChanged` in order (documented in `AndroidNetworkMonitor.kt` class KDoc, audit finding CF-1). Conflation would reintroduce that finding at the channel level. | Rejected. |
| `BufferOverflow.DROP_OLDEST` with a buffer | Bounded. | Gaps in the middle of a burst. The newest is kept, but a dropped `Available` makes later events of that network ignored (R3). | Better on `Lost` (kept), worse on `Available`. Different failure, not a fix. | Rejected as a standalone change. |
| Any blocking send (`send` or `runBlocking`) in the callback | Bounded. | Preserved. | Preserved. | Forbidden. Blocks the platform callback thread. |
| Keep the 64 buffer, detect overflow, then resync | Bounded at 64 plus one flag. | Preserved for accepted events. | Restored: after an overflow the consumer discards its tracked state and rebuilds it from the platform's current view. | Recommended, subject to the notes below. |

Recommended design, in order of effort:

1. Detection (small). Make every `trySend` result observable: on failure, increment a counter, set an overflow flag, and log once through `AerivaLogger`. No behavior change. This needs the seam only if it is to be unit tested.
2. Resynchronization (moderate, needs a reducer decision). When the flag is set, the consumer, outside the callback, reads the platform's current default network and capabilities and feeds the reducer a synthetic `Lost(tracked)` followed by `Available(current, snapshot)`. The synchronous lookup must stay out of the callback body, which the platform documents as race prone and which the class KDoc already cites. This adds a new event source to the reducer contract and therefore needs a Team Leader decision.
3. Only if measurement shows overflow is real: raise the buffer size. A larger bounded buffer is simpler than changing the type and keeps ordering and a hard memory cap. The right size is UNCONFIRMED and needs a device stress result.

The merged `NetworkStateMissingEventRecoveryTest` shows the pipeline already self heals on the next complete `Available` burst. That limits the damage of a dropped `Available` to the time until the next platform change, but it does NOT cover a dropped `Lost` (R3), where there may be no next event for a long time.

## 5. Open items for the Team Leader

1. Approve or reject the seam in section 3. It changes `AndroidNetworkMonitor.kt` and adds one internal file, outside Agent 1 ownership.
2. Decide whether detection (recommendation step 1) is wanted before the Network Advisor A1 adapter reuses this callback pattern (leader decision L4 and spec risk R4).
3. Decide whether resynchronization (step 2) needs a reducer contract change, and who owns it.
4. Decide whether a real device stress run is worth scheduling to settle R4. Emulator results do not answer it.

## 6. Sources

- kotlinx.coroutines API reference, `Channel.Factory.BUFFERED`: default capacity 64 for the SUSPEND strategy, overridable by a JVM system property. https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/-factory/-b-u-f-f-e-r-e-d.html
- kotlinx.coroutines `SendChannel.trySend`: a non-successful result guarantees the element was not delivered. https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/channels/Channel.kt
- AOSP Connectivity module change adding `@throws RuntimeException if the app already has too many callbacks registered` to the `ConnectivityManager` register methods. https://android.googlesource.com/platform/packages/modules/Connectivity/+/cece40543%5E%21/
- Android reference, `ConnectivityDiagnosticsManager`: 100 outstanding requests per UID, shared with `ConnectivityManager` callbacks. https://developer.android.com/reference/kotlin/android/net/ConnectivityDiagnosticsManager
- In repository: `AndroidNetworkMonitor.kt` class KDoc and `NetworkEventReducer.kt` `foldNetworkStateFlow` KDoc (audit finding CF-1), `NETWORK_ADVISOR_PHASE_A_SPEC.md` risk R4 on branch `network-advisor-phase-a-spec`.
