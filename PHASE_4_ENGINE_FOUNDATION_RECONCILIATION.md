# Phase 4 -- Engine / Foundation Reconciliation Plan

Author role: AI 2. Status: **plan only**. No production code changed, nothing merged, nothing force-pushed, `main` untouched.

| | |
|---|---|
| Hardening branch | `phase-4-engine-hardening` @ `158f16947461fef0a7c983949f61e90beeb15dda` (CI green, run `16ebcfa6`, all 4 jobs, 36 engine tests) |
| Foundation branch | `phase-4-measurement-foundation` @ `fdf54408d06ed568f1e2202eb75da46f6024fa7b` (CI run `1c3da56b`, run-level outcome `succeeded`; per-job status not re-queried this session) |
| Merge base | `727b2a91d2724735c3f22965ca72cf4311202370` (`phase-3b-measurement-engine`, last green Phase 3B state) |
| `main` | `e3f70a4a` -- carries Phase 3A only. Neither the engine, the classifier nor `NetworkClient` is on `main`. |
| This document's branch | `phase-4-engine-foundation-reconciliation`, parent = `fdf54408` (the recommended integration base) |

## 0. Verdict

**The reconciliation can safely proceed, in stages.**

* The mechanical merge is small: **1 textual conflict hunk in 1 file, plus 1 non-textual compile break in the same file**. Git reports only the first; the second is silent (section 3).
* Every validated hardening behavior can be preserved (section 6). Four hardening tests and one fixture test change *shape* if decision A is taken (section 5.A); none lose their intent.
* Stage 1 (mechanical merge) and stage 2 (NetworkState defaults) need no owner decision beyond agreement from the owner of `NetworkState.kt`. Stage 3 (Unclassified mapping + defect logging) needs decisions DD-1 to DD-4 (section 10) confirmed first.
* **Nothing in this document was compiled or run.** The trial merge was performed in a local scratch clone with `git merge`, resolved by script, and diffed. No Gradle is available in this sandbox (Maven Central, Google Maven and the Gradle distribution host return 403). All "expected green" statements are predictions that CI must confirm.

## 1. Method and evidence

* Fresh clone, `git fetch --all`, tips verified at the start of this task (they moved since the earlier hardening report: foundation gained `3123132`, `89337fc`, `545a961`, `fdf5440`).
* Trial merge: `git merge --no-commit --no-ff origin/phase-4-measurement-foundation` on a scratch branch at hardening `158f169`. Result: `Auto-merging` both shared files; `CONFLICT (content)` in `LatencyMeasurementEngine.kt` (1 hunk); the test file merged automatically. Scratch branch deleted; nothing pushed.
* Conflict resolution was then *simulated by script* (keep hardening side; update the helper) and diffed against hardening's engine to obtain the exact delta in section 3.
* Call-site inventory: `git grep` over the **entire tracked tree** (134 files, all `*.kt`/`*.kts`/`*.java`/`*.md`, all source sets including `androidTest`, all modules including `app`) in the merged tree, then repeated against the trees of `phase-4-android-network-state`, `phase-4-test-gate` and `phase-4-android-platform-spec`. GitHub code search does not index this repository (also recorded in the foundation notes), so a full clone is the only complete method.
* CI facts were read from CircleCI (`list_runs`, `list_workflow_jobs`, `list_job_tests`), not from branch notes.
* Contract text quoted below is from `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md` (`phase-4-cross-cutting-decisions`) and the gate wording from `PHASE_4_TEST_GATE_SPECIFICATION.md` (`phase-4-test-gate`).

## 2. What each branch changed relative to the merge base

| | Files | Lines |
|---|---|---|
| Foundation | 21 | +1167 / -30 |
| Hardening | 3 | +694 / -30 |
| **Touched by both** | **2** | `LatencyMeasurementEngine.kt`, `LatencyMeasurementEngineTest.kt` |

Foundation-only files (no hardening change; merge cleanly by construction): `NetworkState.kt`, `NetworkStateMapper.kt`, `MeasurementFailure.kt`, `LatencyMeasurement.kt`, `MeasurementStage.kt`, `ProbeEvidence.kt`, `MeasurementMethod.kt`, `DerivedJitterStats.kt`, `MeasurementEndpointConfig.kt`, `ReferenceLatencyProbeExecutor.kt` (test source set), `NetworkHistoryRepositoryTest.kt`, `ReferenceLatencyProbeExecutorTest.kt`, `NetworkStateMapperTest.kt`, `MeasurementBoundaryTest.kt`, `MeasurementFailureTaxonomyTest.kt`, `DerivedJitterStatsTest.kt`, `MeasurementMethodTest.kt`, `MeasurementEndpointConfigTest.kt`, `PHASE_4_MEASUREMENT_FOUNDATION_NOTES.md`.

Hardening-only files: `PHASE_4_ENGINE_HARDENING_NOTES.md`.

## 3. Conflict map

| # | Kind | File | Where | Detected by | Resolution |
|---|---|---|---|---|---|
| **X1** | Textual | `LatencyMeasurementEngine.kt` | the `try { withContext ... } catch` block in `measure` | Git | Keep the hardening structure (`withTimeoutOrNull ... ?: timeoutFailure(...)` and the `catch (foreign: TimeoutCancellationException)`). Discard foundation's 4-line side. Foundation's intent (`Timeout(MeasurementStage.Unknown)`) is carried by X2. |
| **X2** | **Semantic / compile-breaking, no Git signal** | `LatencyMeasurementEngine.kt` | hardening's new private helper `timeoutFailure()` constructs `MeasurementFailure.Timeout` **as an object**. Foundation made `Timeout` a `data class Timeout(stage)`. | Reading the merged file; the helper is a hunk foundation never touched, so Git merges it silently | Change to `MeasurementFailure.Timeout(MeasurementStage.Unknown)`. The `MeasurementStage` import is already present in the auto-merged file. After X1 is resolved this is the **only** bare `Timeout` object reference left in the tree. |
| B1 | Auto-merged, verified | `LatencyMeasurementEngineTest.kt` | 3 hunks: `MeasurementStage` import; `availableContext` NetworkState fixture gains the 3 explicit fields (line 60 in the merged tree); `assertEquals(Timeout(MeasurementStage.Unknown), ...)` in the existing timeout test | Git (clean) | None needed. Verified: all 4 hardening assertions about `Timeout` use `is MeasurementFailure.Timeout`, so they are shape-independent. The hardening test file constructs `NetworkState(` exactly once (the fixture), which foundation's edit already fixes. |
| B2 | Auto-merged | `LatencyMeasurementEngine.kt` | import block | Git (clean) | None. |
| B3 | Behavioral (appears only after decision A) | see 5.A | 3 hardening engine tests + 1 hardening-adjacent fixture test | Analysis | Tests change shape, see 5.A and section 9. |
| B4 | Behavioral (appears only if 5.C is taken) | `NetworkState.kt` | removing the `= false` defaults | Analysis | No call site relies on them (section 7). |

**Conflict count: 1 textual (X1) + 1 semantic compile break (X2) = 2 mandatory resolutions, both in `LatencyMeasurementEngine.kt`.** B1/B2 are clean auto-merges. B3/B4 are consequences of optional-but-recommended decisions, not merge conflicts.

Exact resolved delta versus hardening's engine (produced by the scripted scratch resolution; this is the complete diff):

```diff
@@ imports
 import com.aeriva.core.model.measurement.MeasurementNetworkContext
+import com.aeriva.core.model.measurement.MeasurementStage
@@ private fun timeoutFailure(...)
     ): LatencyMeasurement.Failed = LatencyMeasurement.Failed(
         request.id, request.context, startedAt, request.method,
-        MeasurementFailure.Timeout
+        MeasurementFailure.Timeout(MeasurementStage.Unknown)
     )
```

## 4. File-by-file merge plan

Recommended direction: **merge hardening into a branch created from the foundation tip** (reasons in section 12). Stage numbers refer to the commit sequence in section 11.

| File | Action | Stage | Notes |
|---|---|---|---|
| `network/monitor/.../measurement/LatencyMeasurementEngine.kt` | Merge; resolve X1, X2 | 1 | Only file needing hand resolution. Later modified again in stage 3 (A, G). |
| `network/monitor/.../measurement/LatencyMeasurementEngineTest.kt` | Auto-merge | 1 | Later: 4 tests reshaped, new tests appended, `engine()` helper gains a logger parameter (stage 3). Line 581 pins the retired `"tcp-round-trip"` string; change to compare with the request's own method (section 9, N9). |
| `core/model/.../NetworkState.kt` | Take foundation; then stage 2 removes defaults | 1, 2 | Foundation-owned file: coordinate with AI 1 before stage 2. |
| `core/model/.../measurement/MeasurementFailure.kt`, `LatencyMeasurement.kt`, `MeasurementStage.kt`, `ProbeEvidence.kt`, `MeasurementMethod.kt`, `DerivedJitterStats.kt` | Take foundation | 1 | No hardening change. |
| `network/monitor/.../NetworkStateMapper.kt` | Take foundation | 1 | Also modified by `phase-4-android-network-state` (downstream, section 12). |
| `network/monitor/.../measurement/MeasurementEndpointConfig.kt` | Take foundation | 1 | `current` stays `null`. No endpoint invented. |
| `network/monitor/src/test/.../ReferenceLatencyProbeExecutor.kt` + `...Test.kt` | Take foundation | 1 | Foundation already changed both `Timeout` sites. Untouched by hardening. |
| `core/database/src/test/.../NetworkHistoryRepositoryTest.kt` | Take foundation | 1 | Explicit 3-field fixture. |
| Other foundation tests (`NetworkStateMapperTest`, `MeasurementBoundaryTest`, `MeasurementFailureTaxonomyTest`, `DerivedJitterStatsTest`, `MeasurementMethodTest`, `MeasurementEndpointConfigTest`) | Take foundation | 1 | |
| `network/monitor/src/test/.../FakeNetworkClient.kt` + `FakeNetworkClientTest.kt` | Modify | 3 | Over-call must stay loud once the engine catches `Exception` (5.A). |
| `PHASE_4_MEASUREMENT_FOUNDATION_NOTES.md`, `PHASE_4_ENGINE_HARDENING_NOTES.md` | Keep both; amend | 4 | Hardening notes contain two now-stale statements (foundation "CI-red"; "Unresolved: Unclassified / no defect log") and a merge-notes section that this plan supersedes. |
| `PHASE_4_ENGINE_FOUNDATION_INTEGRATION_NOTES.md` | New | 4 | Records what was actually done and the CI evidence. |

Not touched by the reconciliation: `NetworkClient.kt`, `NetworkClientOutcome`, `MeasurementCapabilityClassifier.kt`, `AndroidNetworkMonitor.kt`, all Gradle files, the manifest. No OkHttp, no `INTERNET`, no production `NetworkClient`.

## 5. Explicit resolutions A to G

### A. Unclassified exception mapping -- **required now** (stage 3)

* Contract: D5-9 "`measure` never throws for any outcome, except genuine caller cancellation"; D5-7 "Anything else -> `Unclassified(exceptionClass)`"; D5-4 `Unclassified` "always a defect signal", target rate zero.
* Today (both branches): an exception from the client propagates out of `measure`. Test-gate findings F-6 and rows EN-08/NC-01 record this as open.
* `Unclassified` exists only on the foundation side, which is why hardening could not implement it (its notes say so). After the merge it can be done with no `NetworkClient` change and no domain change.
* Plan for `measure`, catch order matters (`CancellationException` is an `Exception`):
  1. `catch (foreign: TimeoutCancellationException)` -- existing hardening branch: `ensureActive()`; if the caller was cancelled, rethrow. **Change:** if the caller is still active this is a client that leaked its own timeout; return `Failed(Unclassified("TimeoutCancellationException"))` + defect log (see B; this replaces hardening's `Timeout` mapping for this one case).
  2. `catch (e: CancellationException)` -- `ensureActive()` (rethrows if the caller was cancelled); otherwise a non-caller cancellation escaped the client: `Failed(Unclassified(e::class.simpleName))` + defect log. Never let a non-caller `CancellationException` leave `measure`: it would silently cancel the caller's coroutine and look like a normal cancellation.
  3. `catch (e: Exception)` -- `Failed(Unclassified(e::class.simpleName ?: "Unknown"))` + defect log. **`Error` is deliberately not caught** (DD-1).
* The non-monotonic-clock check (`check(elapsed >= 0)`) currently throws `IllegalStateException`. Under D5-9 it must become an explicit outcome. Recommended: a private exception type `NonMonotonicElapsedClockException` thrown at the same place and mapped by rule 3 to `Unclassified("NonMonotonicElapsedClockException")`. It stays impossible to produce a `Succeeded` with a negative value.
* **Mapping without logging would be the silent failure the hardening work was written to prevent.** A must ship together with the logging seam from G. They are one commit pair (stage 3a/3b).
* Interaction with `SecurityException` (D5-7 row: decline `CapabilityUnavailable` + defect log, test-gate EN-06): this is **not** part of A. It needs the permission adapter and is tracked under G as required before S3.
* Consequences for existing tests (all intent-preserving):
  * `measure_onUnexpectedClientException_propagates_andNeverBecomesAMeasurement` -> expects `Measured(Failed(Unclassified("IllegalStateException")))`, never `Succeeded`, and one defect log.
  * `measureSeries_onUnexpectedClientException_abandonsSeries_withoutPartialResult` -> the series **continues** and returns all outcomes in order (D5-9); the exception no longer abandons the series.
  * `measure_withNonMonotonicElapsedClock_failsLoudly_insteadOfReportingNegativeLatency` -> expects `Failed(Unclassified("NonMonotonicElapsedClockException"))` and one defect log.
  * `measure_whenClientThrowsItsOwnTimeout_whileCallerIsActive_returnsTimeoutFailure_notSilentCancellation` -> expects `Failed(Unclassified("TimeoutCancellationException"))` (see B).
  * `FakeNetworkClientTest.probe_callingMoreTimesThanScripted_failsLoudly_notSilently` and `FakeNetworkClient.probe`: over-call currently throws `IllegalStateException` via `error(...)`. With the engine catching `Exception`, a test author's scripting mistake would be swallowed into `Unclassified` instead of failing the test. Change the fake to throw `AssertionError` (an `Error`, not caught by the engine) and update its test.

### B. `Timeout(stage)` integration -- **resolved by stage 1 (mechanical) and stage 3 (semantics)**

* Foundation's `MeasurementStage` KDoc: `Unknown` "is reserved for the engine's own outer backstop timeout (Decision D5-10)". D5-10: backstop firing "yields `Timeout(Unknown)` and a defect log".
* Rules after reconciliation:
  1. **Engine's own deadline expires** -> `Timeout(MeasurementStage.Unknown)`. This is the only source of `Unknown`. (Stage 1, via X2.)
  2. **Caller's own `withTimeout` fires** -> never a `MeasurementFailure`; the cancellation propagates. (Hardening behavior, unchanged.)
  3. **Client leaks its own `TimeoutCancellationException` while the caller is active** -> `Unclassified("TimeoutCancellationException")`, **not** `Timeout`. Rationale: (i) a compliant client returns `TimedOut(stage)` instead of throwing (D5-10, D5-7); (ii) mapping it to `Timeout(Unknown)` would make the reserved "backstop fired" value ambiguous; (iii) the task requirement "foreign timeout must not become `MeasurementFailure.Timeout`" is then true for both kinds of foreign timeout. Until stage 3 lands, the mechanical merge leaves this path returning `Timeout(Unknown)`; stages 1 and 3 should therefore be reviewed and merged together (section 11).
* Whether the own-deadline expiry is a *defect* is phase-dependent, see G.
* No code may construct `Timeout` with a stage other than `Unknown` before the client reports real stages (S3/S4).

### C. NetworkState constructor compatibility -- **stage 2, recommend removing the defaults**

Facts:

* At `545a961` (CI run `0642d7ee`, succeeded) `captivePortalReported`, `vpnPresent`, `blockedByDevicePolicy` had **no defaults** and every constructor call site was explicit and green.
* `fdf5440` then added `= false` to all three. Its stated reason (foundation notes addendum): the sandbox could not enumerate every call site, so defaults "remove the whole class of missed-call-site risk".
* That premise no longer holds. Section 7 is a complete inventory: **6 constructor sites** in the whole repository, all explicit today, in every Phase 4 branch checked (test-gate, android-network-state, android-platform-spec: fixtures are explicit).
* Defaults make **"not observed" indistinguishable from "observed false"**. For `blockedByDevicePolicy` and `captivePortalReported` that value drives engine decisions (D5-2 decline on blocked; D5-5 two-signal captive-portal rule). A future production call site that forgets to compute them would silently report "not blocked, no portal, no VPN": a fabricated observation, the same defect class the hardening branch removes from the engine. Removing the defaults turns that omission into a compile error.
* Recommendation: **restore the non-defaulted primary constructor** (revert the `NetworkState.kt` half of `fdf5440`; keep its documentation improvement where accurate). Keep `NetworkStateMapper.buildNetworkState(..., blocked: Boolean = false)`: that parameter is a function default for the offline/no-signal case, is what `phase-4-android-network-state` and test-gate row CX-05 already assume, and is not a data-class constructor default.
* This is a change to a foundation-owned file: needs AI 1's agreement (DD-5). If declined, the fallback is to keep the defaults **and** add an architecture guard test (in the style of `ArchitectureGuardTest`) that fails if any production source under `src/main` constructs `NetworkState(` outside `NetworkStateMapper` / `NetworkState.unknown`, so the risk is bounded by a test rather than by a comment.
* Ordering: stage 2 is independent of the merge and could equally precede it. It is placed after stage 1 so that the mechanical merge is reviewed in isolation.

### D. Test fixture compatibility -- **stages 1 and 3**

| Fixture | Issue | Action |
|---|---|---|
| `LatencyMeasurementEngineTest.availableContext` | Needs the 3 explicit fields if stage 2 removes defaults | Already present after the stage 1 auto-merge |
| `ReferenceLatencyProbeExecutorTest` NetworkState fixture | Same | Already explicit (foundation) |
| `NetworkHistoryRepositoryTest` NetworkState fixture | Same | Already explicit (foundation) |
| `engine()` helper in the engine test | Gains `logger` (stage 3a). Hardening already widened its client type and added `now`, `dispatchers` | Add one parameter with a recording fake as default in a single new test helper; do not add a second factory |
| Recording `AerivaLogger` fake | No fake exists; only main-source `NoOpLogger`/`AndroidLogcatLogger` | Add one small test fixture in `network/monitor/src/test` |
| `FakeNetworkClient` over-call | see 5.A | `AssertionError`; update `FakeNetworkClientTest:59` |
| Hardening tests that pin `"tcp-round-trip"` (engine test line 581) | Pins the string test-gate F-7/EN-10 wants retired (D3-7) | Assert against `request().method` instead |
| `EndpointFailure("x")`/`TlsFailure("x")` equality assertions (engine test lines 570-571) | Foundation added a defaulted `kind`; equality holds while the engine leaves `kind = UNSPECIFIED` | No change now. They must be revisited when S3/S4 populates `kind` |

### E. Stale measurement context -- **required before S3** (decision and contract shape); implemented in S4; verified at G2

What the engine does today (unchanged by either branch): attaches `request.context` verbatim; checks `available` once, before the probe; holds no state; in a series every sample keeps the context that was captured when *its request was built*. Only a client-reported `NetworkChangedMidCall` detects a network switch.

Why this becomes a hard requirement at S3, not just a note:

1. **New fabrication path introduced by S3.** D5-11 adds `ProbeRequest.networkHandle` (pin the probe to a specific network). If the handle and the `MeasurementNetworkContext` come from different observations, a probe can be pinned to network B and recorded against network A's context. The S3 `ProbeRequest`/context shape must therefore state that **context and handle come from one atomic snapshot and travel together**.
2. **Series window grows.** S4/D4-5 adds an inter-sample interval; N samples now span `N x interval`, making per-sample staleness real rather than theoretical.
3. **D5-5 (captive portal) needs the flag "observed at start, end, both or neither"** (test-gate CP-02). That requires an end-of-probe observation, which the engine cannot make without a network-state source.

Intentionally deferred: giving the engine its own network-monitor dependency to refresh context. Ownership belongs to the orchestrator/scheduler layer that builds requests. The engine keeps attaching what it is given.

Not required now: the merge itself neither improves nor worsens this. Existing hardening tests pin the verbatim-attachment behavior (`measureSeries_attachesEachRequestsOwnContextVerbatim_evenWhenThatContextIsStale`), which documents the limit honestly.

### F. `System.nanoTime()` vs `SystemClock.elapsedRealtimeNanos()` -- **required before real-device validation**

* Both are monotonic. Per Android's `SystemClock` documentation, `System.nanoTime()` follows the uptime clock, which does **not** advance during deep sleep; `elapsedRealtimeNanos()` does.
* For a probe bounded by a seconds-scale deadline the difference only matters if the device sleeps mid-probe (Doze, screen off). That is precisely what device validation exercises, and an undercount there would be a fabricated (too-low) latency.
* Not required now: the engine's clock is already an injectable `() -> Long` seam, and JVM tests need a plain default. Not S3-blocking as code, but S3 defines the production client's clock seam (D3-6 moves interval computation into the client), so **the S3 specification must state that the production wiring injects `SystemClock::elapsedRealtimeNanos`** and the JVM default stays `System::nanoTime`.
* Device-validation item: run probes across screen-off/Doze and confirm reported latency against an external reference.

### G. Defect logging -- split classification

| Part | Classification | Reason |
|---|---|---|
| Logging seam in the engine (`AerivaLogger` from `core:logging`, already a main dependency of `network:monitor`; no new dependency) and defect entries for `Unclassified`, leaked timeout / cancellation, non-monotonic clock | **Required now** (ships with A, stage 3a) | Without it A is a silent mapping |
| Engine deadline expiry logged as a *defect* (D5-10) | **Intentionally deferred to S4** | Until the client owns the deadline (D3-4, S3/S4) the engine's deadline **is** the timeout mechanism. Logging every timeout as a defect would be false noise and would train people to ignore the log. At S4 the semantics flip: client `TimedOut(stage)` is normal; engine backstop firing is a defect. |
| `SecurityException` -> decline `CapabilityUnavailable` + defect log (D5-7, EN-06) | **Required before S3** | S3 adds `INTERNET` and the permission adapter; a manifest/classifier inconsistency must be loud |

Log content rule (DD-3): message = fixed text + exception **class simple name** + request id. No exception message, no throwable, no host/target: `Unclassified` deliberately carries only the class name because messages may contain untrusted server or platform text (D5-4), and `AerivaLogger` callers "pass plain descriptive strings; they do not redact".

## 6. Behavior preservation matrix

Engine tests are named as in CI run `16ebcfa6` (all passing there).

| # | Hardening behavior | Proven by | After merge (stage 1) | After stage 3 |
|---|---|---|---|---|
| 1 | Caller cancellation propagates | `measure_onCallerCancellation_propagates_andEmitsNoOutcome`, `measureSeries_onCancellationMidSeries_stopsAtCancelledProbe_andRunsNoFurtherProbes`, `measure_whenCallerIsAlreadyCancelled_evenOnDeclinePath_throwsCancellation_notNoNetwork` | Preserved (X1/X2 keep `ensureActive()`) | Preserved; new catch order must rethrow caller cancellation (test N2) |
| 2 | Foreign timeout never becomes `Timeout` | `measure_whenCallersOwnTimeoutFires_propagatesCancellation_insteadOfReturningTimeoutFailure`, `measureSeries_whenCallersOwnTimeoutFires_propagatesCancellation_andDeliversNoPartialResult` (caller's timeout) | Preserved for the caller's timeout. Client-leaked timeout still maps to `Timeout(Unknown)` (interim) | Client-leaked timeout -> `Unclassified` (5.B); test reshaped |
| 3 | No dispatcher queue time in latency | `measure_doesNotCountDispatcherQueueingTowardProbeLatency` | Preserved (start reading inside the io context is untouched) | Preserved. S4/D3-6 later moves the interval into the client; this becomes `engineElapsed` semantics |
| 4 | Negative elapsed rejected | `measure_withNonMonotonicElapsedClock_failsLoudly_insteadOfReportingNegativeLatency` | Preserved (throws `IllegalStateException`) | Preserved in intent, outcome shape changes to `Failed(Unclassified(...))` + log; never a `Succeeded` |
| 5 | `calculatedAt` represents the completed series | `measureSeries_derivedStatsAreCalculatedAfterTheirSourceMeasurements` | Preserved (`calculatedAt ?: now()` after the loop). `DerivedLatencyStats.from` is unchanged by foundation | Preserved. S4/D4-5 will edit `measureSeries` again; keep this test |
| 6 | `timeoutMillis` must be positive | `constructor_rejectsNonPositiveTimeout` | Preserved | Preserved (construction-time, outside D5-9) |
| 7 | Hung probes cancelled | `measure_onTimeout_returnsMeasuredFailedTimeout_andCancelsClientCleanly`, `measureSeries_withTimeouts_cancelsEachProbe_continuesSequentially_andLeavesNothingRunning`, `measure_whenValidPayloadWouldArriveAfterDeadline_returnsTimeout_neverSucceeded`, `measure_whenClientIgnoresCancellationAndReturnsValidPayloadLate_stillReportsTimeout` | Preserved (assert `Timeout(Unknown)` / `is Timeout`) | Preserved |
| 8 | `measureSeries` sequential | `measureSeries_runsProbesSequentially_inRequestOrder`, `measureSeries_withMixedOutcomes_keepsRequestOrder_andAggregatesOnlySuccesses` | Preserved | Preserved; series now also continues past `Unclassified` |
| 9 | No fabricated `NetworkQuality` | `engine_neverPopulatesNetworkQuality_onAnyOutcomePath` | Preserved. Foundation's mapper still emits `Unavailable` | Preserved. test-gate adds guards (`networkQualityMeasured_isNeverConstructedByProductionCode`, CX-08) |
| 10 | Failure reasons stay distinct | `measure_transportFailureReasons_areCarriedThrough_andFailedResultKeepsRequestIdentity`, `measure_everyFailureCause_mapsToItsOwnDomainCase_andNoneIsSucceeded` | Preserved (`kind` defaults to `UNSPECIFIED`, equality holds) | Preserved; add cases for `Unclassified` being distinct (N1) |
| 11 | No leaked probes/jobs | `FakeNetworkClient` completed/cancelled counters asserted in the tests above; `runTest` leak detection | Preserved | Preserved |

Foundation behavior to preserve:

| Foundation item | Proven by | Risk from the merge |
|---|---|---|
| `MeasurementStage` (7 values) | `MeasurementFailureTaxonomyTest` | None. Engine uses only `Unknown`. |
| `ProbeEvidence` / `LatencyMeasurement.evidence` (trailing, default `null`) | `DerivedJitterStatsTest`, `MeasurementBoundaryTest` | None. Engine constructs results without evidence; hardening's construction sites are named/positional-compatible. |
| `MeasurementMethod` registry | `MeasurementMethodTest` (asserts `"tcp-round-trip"` is *not* registered) | None for the merge. The engine's request default is still the retired string (test-gate F-7, closes S4). |
| `DerivedJitterStats` | `DerivedJitterStatsTest` (11) | None. Note: `phase-4-test-gate` carries 9 deliberately red jitter invariant tests (section 12). |
| Expanded `MeasurementFailure` taxonomy | `MeasurementFailureTaxonomyTest` (exhaustive `when`) | `Unclassified` becomes reachable from the engine in stage 3 (that is the point). |
| `NetworkState` observation fields | `NetworkStateMapperTest` (14), `NetworkHistoryRepositoryTest` | Stage 2 removes constructor defaults only; fields, mapper and semantics unchanged. |
| `MeasurementEndpointConfig` | `MeasurementEndpointConfigTest` | None. `current` stays `null`. |
| `Timeout(stage)` | taxonomy test; engine test line 172 equality | X2 |

Not yet exploited (deliberately unchanged, tracked for S4): engine leaves `EndpointFailureKind`/`TlsFailureKind`/`InvalidResponseKind` at `UNSPECIFIED` (e.g. wrong payload length could be `WRONG_LENGTH`); engine never sets `evidence`.

## 7. Complete repository call-site inventory

Complete = `git grep` across all tracked files (134) in the merged tree, cross-checked against the trees of `phase-4-test-gate`, `phase-4-android-network-state`, `phase-4-android-platform-spec`. Line numbers are from the pre-resolution merged tree and drift after edits.

### 7.1 `NetworkState(` constructor call sites (excluding the class declaration): **6**

| # | File:line | Explicit 3 fields today? | Affected by C? |
|---|---|---|---|
| 1 | `core/model/src/main/.../NetworkState.kt:55` (`unknown(at)`) | yes | no |
| 2 | `network/monitor/src/main/.../NetworkStateMapper.kt:41` (offline branch) | yes (`blocked` passed through) | no |
| 3 | `network/monitor/src/main/.../NetworkStateMapper.kt:56` (online branch) | yes (computed) | no |
| 4 | `core/database/src/test/.../NetworkHistoryRepositoryTest.kt:23` | yes | no |
| 5 | `network/monitor/src/test/.../ReferenceLatencyProbeExecutorTest.kt:51` | yes | no |
| 6 | `network/monitor/src/test/.../LatencyMeasurementEngineTest.kt:60` | yes (from foundation's hunk) | no |

Other `NetworkState` users found (none constructs it, so none is affected by C): `AndroidNetworkMonitor`, `ConnectionStateRepository`, `NetworkMonitor`, `MeasurementCapabilityClassifier`, `RawCapabilitiesSnapshot`, `NetworkClient`, `AerivaLogger` (mention in KDoc), `AerivaDatabase`, `NetworkHistoryRepository`, `NetworkStateHistoryDao`/`Entity`/`Record`, `FakeNetworkStateHistoryDao`, `NetworkStateHistoryDaoTest` (androidTest), `AndroidNetworkMonitorInstrumentedTest` (androidTest), `DerivedLatencyStats`, `Freshness`, `MeasurementNetworkContext`, `FakeNetworkClient`, `MeasurementCapabilityClassifierTest`, `NetworkStateMapperTest`, `DerivedJitterStatsTest`, `DerivedLatencyStatsTest`, `MeasurementBoundaryTest`. The `app` module has **no** reference.

Persistence check: `NetworkStateHistoryEntity` stores `transport, available, validated, metered, recordedAtEpochMillis` only. The three new fields are not persisted, so no Room migration is involved. History therefore cannot reconstruct them (intentionally deferred, no owner yet).

`NetworkStateMapper.buildNetworkState(` callers: `AndroidNetworkMonitor.kt:84, 92, 100`; `NetworkStateMapperTest` (14 tests). `blocked` is defaulted at the function level and stays so.

### 7.2 `MeasurementFailure.Timeout` references

| Site | Form | Status after resolution |
|---|---|---|
| `MeasurementFailure.kt:58` | declaration `data class Timeout(val stage)` | foundation |
| `LatencyMeasurementEngine.kt` (foundation side of X1, line ~205) | construction with `Unknown` | discarded in X1 (superseded by helper) |
| `LatencyMeasurementEngine.kt:258` (`timeoutFailure()`) | **bare object reference** | **X2, must change** |
| `LatencyMeasurementEngine.kt:120` | KDoc reference | fine |
| `ReferenceLatencyProbeExecutor.kt:75` (test source set) | `Timeout(Unknown)` | foundation, fine |
| `ReferenceLatencyProbeExecutorTest.kt:92` | `assertEquals(Timeout(Unknown), ...)` | foundation, fine |
| `LatencyMeasurementEngineTest.kt:172` | `assertEquals(Timeout(Unknown), ...)` | foundation hunk, fine |
| `LatencyMeasurementEngineTest.kt:352, 374, 388, 698` | `is MeasurementFailure.Timeout` | shape-independent |
| `MeasurementBoundaryTest.kt:37` | `Timeout(Unknown)` | foundation |
| `DerivedJitterStatsTest.kt:49` | `Timeout(Request)` | foundation |
| `MeasurementFailureTaxonomyTest.kt:20, 45, 63` | stage-aware | foundation |

Bare `Timeout` object references (the form that breaks when `Timeout` gains a parameter), verified by re-running the search against each branch tree:

* **Hardening tree alone: 4** -- the engine's `timeoutFailure()` helper, `LatencyMeasurementEngineTest.kt:168`, `ReferenceLatencyProbeExecutor.kt:74`, `ReferenceLatencyProbeExecutorTest.kt:88`.
* **Foundation tree alone: 0** (the only other hit, `MeasurementFailureTaxonomyTest.kt:63`, is a constructor call followed by a cast).
* **Merged tree after auto-merge: exactly 1** -- the engine helper. Foundation fixed the three test-tree sites; git cannot see that the helper needs the same fix because foundation never touched that hunk. That single reference is X2.

The test-gate's own sweep ("zero bare unparameterized `Timeout` remaining") is true for foundation and false for the merged tree until X2 is applied.

### 7.3 Other inventoried items

| Item | Result |
|---|---|
| `LatencyMeasurementEngine(` construction | 1 (`LatencyMeasurementEngineTest.kt` `engine()` helper). No production construction exists. |
| `measureSeries(` callers | none outside the engine's own tests |
| `exhaustive when` over `MeasurementFailure` | only `MeasurementFailureTaxonomyTest` (core:model). No consumer of `MeasurementFailure` exists outside `core:model` and `network:monitor`. |
| `"tcp-round-trip"` | `LatencyMeasurementEngine.kt:350` (request default), `ReferenceLatencyProbeExecutor.kt:62`, `DerivedLatencyStatsTest:162`, `MeasurementBoundaryTest:28,36`, `MeasurementMethodTest:40` (asserts unregistered), hardening test `LatencyMeasurementEngineTest:581` (pins it) |
| `NetworkClient` / `NetworkClientOutcome` | unchanged in both branches: 4-case outcome, `probe(target: String)`. `ProbeRequest`: 0 occurrences anywhere. |
| `AerivaLogger` implementers | `NoOpLogger`, `AndroidLogcatLogger` (main). No test fake exists. |
| Build/permissions | No `INTERNET`, no OkHttp, in either branch. |

## 8. Tests that must remain green

JVM unit tests in the merged tree (`@Test` annotation count by grep, not a CI count): **151** across 19 files, plus 6 instrumented (`androidTest`). The gate for every stage is: CI green on all 4 jobs (`build`, `static_checks`, `unit_tests`, `connected_android_test`) and these suites specifically:

| Suite | @Test | Why it matters |
|---|---|---|
| `LatencyMeasurementEngineTest` | 36 | all hardening behavior; 4 reshape in stage 3 |
| `MeasurementFailureTaxonomyTest` | 4 | exhaustive `when` over the full taxonomy (compile proof for `Unclassified`) |
| `DerivedJitterStatsTest` | 11 | foundation |
| `MeasurementMethodTest` | 4 | registry |
| `MeasurementBoundaryTest` | 4 | `Timeout(stage)` and stat boundaries |
| `MeasurementEndpointConfigTest` | 1 | no endpoint invented |
| `NetworkStateMapperTest` | 14 | 9 new foundation cases plus originals kept verbatim |
| `NetworkHistoryRepositoryTest` | 5 | NetworkState fixture; failed CI on `9a28cc6` |
| `ReferenceLatencyProbeExecutorTest` | 12 | reference executor; `Timeout(Unknown)` |
| `FakeNetworkClientTest` | 5 | fixture; one test reshapes in stage 3 |
| `DerivedLatencyStatsTest`, `FreshnessTest`, `ConfidenceTest` | 10, 5, 4 | Phase 3A |
| `MeasurementCapabilityClassifierTest`, `TransportConstantMapperTest` | 11, 6 | classifier / mapper |
| remaining `core:*` suites (`TestAerivaDispatchersTest` 1, `DataStoreAerivaPreferencesTest` 4, `AerivaResultTest` 5, `EncryptedPreferencesSecureStorageTest` 9) | 19 | untouched |

## 9. New tests required after reconciliation

All are JVM tests in the existing engine test class unless stated. None needs a network or a new dependency.

| ID | Stage | Proves |
|---|---|---|
| N1 | 3b | unexpected `Exception` -> `Failed(Unclassified(<simple name>))`; never `Succeeded`; distinct from every other failure case; `evidence` stays `null` |
| N2 | 3b | catch order: caller cancellation and caller `withTimeout` still propagate with the new `catch (Exception)`; client-thrown non-caller `CancellationException` while the caller is active -> `Unclassified`, not silent cancellation |
| N3 | 3b | `Error` (`AssertionError`) from the client is **not** caught |
| N4 | 3b | `measureSeries` continues past an `Unclassified` sample, preserves order, still calls `DerivedLatencyStats.from` over only the successes |
| N5 | 3a/3b | recording logger: exactly one defect entry per `Unclassified`/leaked timeout/non-monotonic clock; message contains class name and request id only (no exception message, no throwable); **zero** entries for normal outcomes and for own-deadline timeouts (until S4) |
| N6 | 3b | non-monotonic clock -> `Failed(Unclassified("NonMonotonicElapsedClockException"))`, never a negative `Succeeded` (reshaped hardening test) |
| N7 | 3b | client-leaked `TimeoutCancellationException`, caller active -> `Unclassified("TimeoutCancellationException")`; engine's own deadline -> exactly `Timeout(Unknown)` (equality, not `is`) |
| N8 | 3b | `FakeNetworkClient` over-call throws `AssertionError`, and the engine does not convert it (couples with N3) |
| N9 | 3a | engine test no longer pins `"tcp-round-trip"`: asserts against `request().method` |
| N10 | 2 | architecture-style guard (test-gate style) that `NetworkState`'s primary constructor has no default values, so the C decision is enforced by a test. Alternative if defaults are kept: guard that production code constructs `NetworkState` only in the mapper and `unknown()` |
| N11 | 1 | no new test; the stage 1 gate is the existing suite |

Deliberately **not** added now (belong to later slices): blocked/`NoEndpointConfigured` declines, `evidence`/`kind` population, client-owned deadline and backstop-as-defect (S4), production clock wiring (S3), context handle/snapshot rule (S3/S4), `SecurityException` decline (before S3).

## 10. Unresolved design decisions

| ID | Question | Recommendation | Needed before |
|---|---|---|---|
| DD-1 | D5-9 says "any outcome"; test-gate EN-08 says "any Throwable". Catch `Exception` or `Throwable`? | `Exception` only. Catching `OutOfMemoryError`/`LinkageError`/`AssertionError` and recording them as a measurement would hide process-level failure. Amend the EN-08 wording. | stage 3b |
| DD-2 | Client-leaked timeout or non-caller cancellation: `Unclassified` or `Timeout(Unknown)`? | `Unclassified` (5.B). | stage 3b |
| DD-3 | Defect log payload | class simple name + request id only; no message, no throwable, no target | stage 3a |
| DD-4 | Logger parameter: required or defaulted to `NoOpLogger`? | **Required, no default**, so a silent engine cannot be constructed by accident. Tests pass a recording fake. | stage 3a |
| DD-5 | Remove the `NetworkState` defaults (5.C)? | Yes. Needs AI 1 / owner agreement. Fallback documented in 5.C. | stage 2 |
| DD-6 | Representation of a non-monotonic clock | private exception mapped to `Unclassified`; not a public domain type | stage 3b |
| DD-7 | S3 rule that context and `networkHandle` derive from one atomic snapshot (E) | Adopt; state it in the S3 `ProbeRequest` specification | before S3 |
| DD-8 | Production clock (F) | `SystemClock::elapsedRealtimeNanos` injected at Android wiring; JVM default unchanged | S3 spec; verify at device validation |
| DD-9 | Who owns persistence of the three new `NetworkState` fields (history cannot reconstruct them) | Deferred; no consumer needs it yet | none |

## 11. Recommended commit sequence

New branch `phase-4-engine-foundation-integration`. Every commit is CI-gated (4 jobs green) before the next.

1. **Stage 1: mechanical merge.** `git merge --no-ff 158f16947461fef0a7c983949f61e90beeb15dda`. Resolve X1 (keep hardening structure) and X2 (helper -> `Timeout(MeasurementStage.Unknown)`). Diff of the resolution must equal the two-hunk delta in section 3. *Gate:* all suites in section 8 green; 36 engine tests green; no other file differs from either parent.
2. **Stage 2: `NetworkState` defaults removed** (DD-5; revert of the constructor part of `fdf5440`). One file plus notes. Add N10. *Gate:* full CI; proves no call site relied on the defaults.
3. **Stage 3a: defect-reporting seam.** Add required `AerivaLogger` engine parameter, recording fake, `engine()` helper parameter, N9. No behavior change yet. *Gate:* full CI.
4. **Stage 3b: D5-9.** `Exception` mapping to `Unclassified`, foreign/non-caller cancellation handling, non-monotonic clock exception, `FakeNetworkClient` -> `AssertionError`, reshape 4 engine tests + 1 fixture test, add N1-N8. *Gate:* full CI; the reshaped tests are the only tests whose assertions change; everything in the preservation matrix rows 1, 3, 5-11 unchanged.
5. **Stage 4: documentation.** `PHASE_4_ENGINE_FOUNDATION_INTEGRATION_NOTES.md`; correct the two stale statements in the hardening notes; amend the foundation notes' addendum for stage 2; record E/F/G classifications from section 5.

Stages 1 and 3b must not be released separately downstream: between them the client-leaked-timeout path returns `Timeout(Unknown)`, contradicting `MeasurementStage`'s reservation of `Unknown`. Stages 1 and 2 are independent of each other; either order works.

Why stage 1 is a *merge commit* and not a cherry-pick: hardening's history (red tests at `05ea438`, fix at `158f169`) is the evidence that each defect was real. A merge keeps that history intact and bisectable.

## 12. Base commit and downstream effects

**Exact base commit for the integration branch: `fdf54408d06ed568f1e2202eb75da46f6024fa7b`** (tip of `phase-4-measurement-foundation`), then merge `158f16947461fef0a7c983949f61e90beeb15dda`. Their common ancestor is `727b2a91d2724735c3f22965ca72cf4311202370`.

Why foundation as the base rather than hardening:

* Foundation owns the domain model that three other branches were built on. `phase-4-android-network-state` (`c0ad893`), `phase-4-test-gate` (`dfe8fa2`) and `phase-4-android-platform-spec` (`458e32e`) all branch from `545a9612`, which is an ancestor of `fdf5440`. They therefore sit *under* the recommended base and combine with it without conflicts in the model.
* Hardening touches only the engine, its test and one doc. Bringing it to the foundation costs one merge; bringing the foundation and three more branches to hardening would multiply that.

Downstream branches (all unmerged; not modified by this task):

| Branch | Own changes vs `545a961` | Expected interaction with the integration branch |
|---|---|---|
| `phase-4-android-network-state` | `AndroidNetworkMonitor.kt`, `NetworkEventReducer.kt`, `NetworkStateMapper.kt`, 2 tests | No overlap with the engine files. Its mapper still constructs `NetworkState` with explicit fields, compatible with stage 2. It removes the "blocked defaults false because unwired" caveat; the mapper's function-level default stays. |
| `phase-4-test-gate` | new test files only (`ArchitectureGuardTest`, matrix tests, `DerivedJitterStatsInvariantTest`) | No overlap. **Its own commit message states 9 jitter invariant tests are deliberately red against `DerivedJitterStats.from`.** Do not merge that branch into the integration line until those defects are fixed or the tests are gated; this is outside the reconciliation. Several of its blocked rows (TM-03, TO-05) are now covered by hardening; update the spec status after stage 1. |
| `phase-4-android-platform-spec` | documents only | none |
| `phase-4-s3-implementation-authorization` | documents only; states OD-1 (`INTERNET`) and OD-7 (OkHttp) are owner actions still open | unaffected. This plan adds no permission and no dependency. |

`main` is not a base: it carries none of Phase 3B. Getting Phase 3B/4 onto `main` is a separate owner decision and is not part of this plan.

## 13. Explicitly out of scope and unchanged

No OkHttp, no `INTERNET`, no production `NetworkClient`, no `ProbeRequest`, no endpoint, no throughput, no UDP, no UI, no WorkManager. No branch merged, no force push, `main` untouched. The only artifacts created by this task are this document and its branch.
