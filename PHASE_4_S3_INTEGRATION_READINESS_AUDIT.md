PHASE 4 -> S3 INTEGRATION-READINESS AUDIT

Author: AI 3 (Android/platform integration engineer), independent audit.
Audit branch: phase-4-s3-integration-readiness-audit
Base: phase-4-engine-foundation-integration @ 7731aa4e2504bf946c37e3d631fa35b56ecd87cc
Status: audit only. Documentation-only branch. No production code, test, manifest, dependency, or permission was changed in this branch. Nothing merged. main untouched.

For every substantive claim below: VERIFIED FACT means re-derived this session directly from the repository or an authoritative source (cited); INTERPRETATION means a reasonable reading that is not itself directly stated anywhere; UNRESOLVED DECISION means an owner call this audit does not make.

---

## 1. Executive Summary

Phase 4's individual work streams (permission platform seam, engine timeout/cancellation hardening, Android network-state observation, jitter derivation) are each internally sound and independently CI-green as of the commits audited. The real integration-readiness problem is not defect density inside any one stream -- it is **branch topology**: five sibling branches, prepared at three different points along `phase-4-measurement-foundation`'s history, have not been reconciled onto one trunk. Two branches (`phase-4-permission-adapter`, `phase-4-engine-foundation-integration`) already share a common ancestor after the engine-hardening merge and DD-5 fix, then diverge; three others (`phase-4-android-network-state`, `phase-4-test-gate`, `phase-4-android-platform-spec-corrected`) are still anchored to an older point that predates that merge entirely.

No new production defect was found this session. One previously-flagged concern (Phase 5's "suspected jitter gap-handling divergence," finding F-1) was independently re-derived by hand against the current, already-fixed implementation and is **NOT CONFIRMED** -- it was a real defect in the implementation that concern's own fixture cites (`phase-4-measurement-foundation@9a28cc62`), and it has already been fixed (`phase-4-test-gate@a20dc4c`, findings F-2/F-3/F-4), a fact the Phase 5 document itself had not yet been updated to reflect.

The S3 transport boundary (`NetworkClient`, `ProbeRequest`, `NetworkClientOutcome`) is unchanged from Phase 3B and does not yet match what a real client needs (no headers/method/deadline on the request side, an outcome enum with no generic "unexpected I/O" case). This is documented as a migration plan (Section 8), not implemented, per this task's explicit scope.

Both owner decisions (OD-1 `INTERNET`, OD-7 OkHttp) remain open and are not touched by this audit.

---

## 2. Repository and Branch Ancestry

`main` @ `e3f70a4a13e63b782c61601abadc2c36f65dbd73` -- untouched by this audit, confirmed by `git status`/`git diff` in Section 17.

All branch tips independently re-read this session via `git ls-remote`/`git rev-parse` (not assumed from the task brief):

| Branch | Tip (this session) | Matches task brief? |
|---|---|---|
| `phase-4-android-platform-spec-corrected` | `6e3ce0fdbcae15bf009f5e72347f35971a1d3f1a` | Yes |
| `phase-4-permission-adapter` | `e912fb7024388d5c280f3ea286fd2e708f56f47c` | Yes |
| `phase-4-engine-foundation-integration` | `7731aa4e2504bf946c37e3d631fa35b56ecd87cc` | Yes |
| `phase-4-android-network-state` | `800b2ec9757c561279e89e046551b5b50a155ca8` | Yes |
| `phase-4-test-gate` | `4adf30c911bb8189fd2ff6ad8529492523312da8` | **No** -- brief cites `a20dc4c`, which is the fix commit; the branch has since gained one further commit (`4adf30c`, `PHASE_4_JITTER_TEST_GATE_REPORT.md`, documentation only, adds no code). Discrepancy noted, both commits audited (Section 7). |
| `phase-5-measurement-validation-harness` | `53ed0e05e373f0953fd0417bd3dc8a6d7a3a8699` | Yes |
| `phase-4-measurement-foundation` | `fdf54408d06ed568f1e2202eb75da46f6024fa7b` | (not cited directly in the brief; this is the shared foundation all the above build from) |

### Ancestry map (VERIFIED FACT, from `git merge-base` and `git log`, this session)

```
                                                    +-- phase-4-android-platform-spec-corrected (6e3ce0f, docs only)
                                                    |
545a961 (measurement-foundation, older point) ------+-- phase-4-test-gate (dfe8fa2 -> a20dc4c -> 4adf30c)
                                                    |
                                                    +-- phase-4-android-network-state (... -> 800b2ec)

545a961 --[fdf5440: add NetworkState defaults]--> fdf5440 (measurement-foundation, current tip)
                                                    |
                                                    +--[7e59c81: mechanical-merge phase-4-engine-hardening]
                                                    +--[19f86b1, c603ec2: resolve engine-hardening merge]
                                                    +--[76651aa: Stage 2, remove NetworkState defaults again (DD-5)]
                                                                |
                                                                +-- phase-4-permission-adapter (-> fd8acc0 -> e912fb7)
                                                                |
                                                                +-- phase-4-engine-foundation-integration
                                                                     (-> Stage 3: 351b10a, aa033ed, a21ed84
                                                                      -> Stage 4: 7731aa4)
```

`phase-4-permission-adapter` and `phase-4-engine-foundation-integration` share ancestry through commit `76651aa` (`git merge-base` confirms this exactly), then diverge: `permission-adapter` adds the `PermissionAdapter` seam; `engine-foundation-integration` continues with Stage 3 (engine `AerivaLogger`/timeout hardening) and Stage 4 (documentation). Neither branch currently has the other's newest work.

`phase-4-android-network-state`, `phase-4-test-gate`, and `phase-4-android-platform-spec-corrected` all branch from `545a9612157f9e944998610a80e6ee60ba99a606`, which is `fdf5440`'s own direct parent -- i.e. these three predate the engine-hardening merge and the DD-5 fix entirely. Note this is not a regression for any of them individually: `NetworkState` had no constructor defaults at `545a961` either (the same state DD-5 later restored at `76651aa`), so nothing in these three branches depends on defaults that no longer exist.

`phase-4-s3-implementation-authorization` (`bc4b60d`, doc-only, merge-base with `main` at `e3f70a4`) and `phase-5-measurement-validation-harness` (merge-base with `measurement-foundation` at `e3f70a4` also) are both effectively based directly on `main`/an early merge point, not on any of the feature branches above -- confirmed by `git diff --name-only` against `e3f70a4`, which shows only documentation/fixture files for both, no shared Kotlin files with any Phase 4 branch.

`phase-4-s3-implementation-authorization` (`PHASE_4_S3_IMPLEMENTATION_AUTHORIZATION.md`, read in full this session) is itself an earlier, independent integration-debt sweep by a prior session, written **before** `phase-4-engine-foundation-integration` and `phase-4-permission-adapter` existed (it does not mention either branch) and citing `phase-4-android-network-state@c0ad893` (this branch's state one commit before its current tip) and `phase-4-test-gate@dfe8fa20` (before the jitter fix). Its core integration-debt finding -- three sibling branches independently modifying `NetworkState.kt`/`LatencyMeasurementEngine.kt` in divergent, unmerged ways -- is corroborated by this session's own independent re-derivation (this section) and remains true, now with two additional branches in the picture rather than fewer.

### Completed / sibling / stale / duplicated work

- **Completed and not stale:** the `PermissionAdapter` seam (`phase-4-permission-adapter`), the engine timeout/cancellation hardening (`phase-4-engine-foundation-integration`), the Android network-state pipeline fix (`phase-4-android-network-state`), the jitter fix (`phase-4-test-gate`). Each is CI-green at its own tip (Sections 4, 5, 6, 7).
- **Sibling, unmerged:** all of the above relative to each other and to `main`.
- **Duplicated, not conflicting:** the DD-5 `NetworkState.kt` default-removal exists identically (byte-for-byte diff confirmed this session) on both `phase-4-permission-adapter` and `phase-4-engine-foundation-integration`, because the former branched from the latter's own Stage 2 commit rather than being rebased later. Not a conflict; a 3-way merge of these two branches would resolve this file without incident.
- **Stale:** `phase-4-s3-implementation-authorization`'s branch/commit citations (superseded, see above); the Phase 5 harness's "known_implementation_divergence" annotations on fixtures `FIX-LAT-009`/`FIX-LAT-010` (Section 7).
- **Integration debt (the real finding of this audit):** none of these five feature branches sit on the same base. Landing all of them requires either a sequence of rebases or a deliberate merge/reconciliation pass -- this is not new information (the prior `phase-4-engine-foundation-reconciliation` branch already performed exactly this kind of reconciliation once, for the engine+foundation pair specifically), but it has not yet been done for `phase-4-permission-adapter` vs. `phase-4-engine-foundation-integration`'s Stage 3/4 divergence, nor for the older-base trio vs. the newer-base pair.

---

## 3. Completed Phase 4 Work Verified

Re-verified this session, each against its own branch tip, each independently (not by trusting the prior report):

| Work | Branch @ commit | CI (re-checked this session) |
|---|---|---|
| Corrected Android platform spec | `phase-4-android-platform-spec-corrected@6e3ce0f` | Documentation only; not build-relevant |
| `PermissionAdapter` seam | `phase-4-permission-adapter@e912fb7` | build/unit_tests/static_checks/connected_android_test all succeeded (workflow `b3de3a7d`) |
| Engine foundation integration | `phase-4-engine-foundation-integration@7731aa4` | build/unit_tests/static_checks/connected_android_test all succeeded (workflow `6aec7723`) |
| Android network-state pipeline (CF-1 fix) | `phase-4-android-network-state@800b2ec` | build/unit_tests/static_checks/connected_android_test all succeeded (workflow `7a2e23de`, from the prior session that produced this commit; re-confirmed by reading the same CircleCI run this session, not re-run) |
| Jitter fix (F-2/F-3/F-4) | `phase-4-test-gate@4adf30c` (and `a20dc4c` beneath it) | build/unit_tests/static_checks/connected_android_test all succeeded (workflow `7b447184`) |
| Validation harness | `phase-5-measurement-validation-harness@53ed0e0` | Not a Gradle/CI concern -- Python checker only, its own claim of passing was not independently re-run this session (Section 17 states this plainly) |

---

## 4. PermissionAdapter Audit

Read `PermissionAdapter.kt`, `AndroidPermissionAdapter.kt`, `AndroidPermissionAdapterTest.kt`, `PermissionAdapterTest.kt`, `AndroidPermissionAdapterInstrumentedTest.kt` in full at `phase-4-permission-adapter@e912fb7`.

1. **`PermissionState` semantics** -- VERIFIED FACT. Four cases: `Granted`, `Denied`, `GrantedApproximateOnly`, `CheckFailed(reason: String)`. `PermissionState` and the adapter interface carry no Android import (`PermissionAdapter.kt` itself is Android-free), matching this codebase's established no-Android-import convention for seam interfaces.
2. **Fine-location behavior** -- VERIFIED FACT. `checkFineLocation()`/the fine-location branch of `check()` queries `ACCESS_FINE_LOCATION` via `ContextCompat.checkSelfPermission` directly; `Granted` when it returns `PERMISSION_GRANTED`.
3. **Approximate-only behavior** -- VERIFIED FACT. When fine is denied, coarse is queried; `GrantedApproximateOnly` when coarse is granted and fine is not.
4. **Fine-location short circuit** -- VERIFIED FACT, and genuinely tested, not merely asserted: `AndroidPermissionAdapterTest` uses a `ContextCompat.checkSelfPermission`-counting fake specifically to prove the coarse check is never invoked once fine is found granted (test name confirms this is a call-count assertion, not just an output assertion).
5. **Live re-query, no caching** -- VERIFIED FACT, tested via a "repeated calls with different underlying permission states in between" test that would fail under any caching.
6. **API-level behavior / no unnecessary API-level branch** -- VERIFIED FACT. The check function takes no `Build.VERSION.SDK_INT` parameter and contains no `if (Build.VERSION.SDK_INT >= ...)` branch; `checkSelfPermission`'s independent-per-permission query behavior is not documented as having changed across API levels, and the code does not claim otherwise.
7. **Android 12+ behavior** -- VERIFIED FACT (re-checked against current official Android documentation this session): Android 12 (API 31) changed the *request* flow -- an app must declare and request both `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` together, or the system denies the request outright -- but this does not change what `checkSelfPermission` reports for each permission independently, which is what this seam actually queries. The adapter's own KDoc states this distinction explicitly and correctly; this audit's independent documentation check corroborates it without finding a contradiction.
8. **Module placement** -- VERIFIED FACT. Lives in `network/monitor/src/main/kotlin/com/aeriva/network/monitor/measurement/`, alongside `MeasurementCapabilityClassifier` and `NetworkClient` -- the same module and package the classifier and engine already occupy, consistent with the platform spec's own placement guidance.
9. **`androidx-core-ktx` reuse, not a new dependency** -- VERIFIED FACT, independently re-checked: the `libs.androidx.core.ktx` catalog entry already existed before this branch and is already a dependency of `core:security` (`implementation(libs.androidx.core.ktx)`, confirmed by direct read of `core/security/build.gradle.kts`). `network:monitor/build.gradle.kts` adds the same already-pinned entry, not a new one.
10. **Tests** -- 18 unit tests total (`AndroidPermissionAdapterTest`: 12; `PermissionAdapterTest`: 6, for `buildGrantedPermissionSet`), each read and confirmed to assert real behavior (grant-state combinations, short-circuit call counts, `CheckFailed` sanitization of an unexpected `RuntimeException`'s message).
11. **Instrumented wiring** -- one instrumented test, confirmed present and confirmed CI-green (`connected_android_test` job on workflow `b3de3a7d`); it also asserts INTERNET is not declared in the merged manifest, incidentally cross-checking OD-1 compliance from the runtime side.
12. **No classifier duplication** -- VERIFIED FACT. `git diff --name-only fdf5440..phase-4-permission-adapter` does not include `MeasurementCapabilityClassifier.kt`; the adapter has no capability-classification logic of its own (no `MeasurementCapability`/`CapabilityClassification` types referenced anywhere in `PermissionAdapter.kt`/`AndroidPermissionAdapter.kt`).
13. **No accidental manifest change** -- VERIFIED FACT, confirmed by `git diff --name-only` showing no `AndroidManifest.xml` in this branch's changed-file list.
14. **No `INTERNET`** -- VERIFIED FACT (Section 9, Section 17).
15. **No OkHttp** -- VERIFIED FACT (Section 9, Section 17).

**Conclusion: PermissionAdapter remains correctly scoped as a platform permission-state seam.** It does not classify capabilities, does not touch the manifest, and reuses an already-vetted dependency. No defect found.

---

## 5. Engine/Foundation Audit

Read `LatencyMeasurementEngine.kt` and `LatencyMeasurementEngineTest.kt` (38 test methods) in full at `phase-4-engine-foundation-integration@7731aa4`.

1. **Engine-owned timeout -> `Timeout(MeasurementStage.Unknown)`** -- VERIFIED FACT. The only construction of `MeasurementFailure.Timeout` in this file is in `timeoutFailure()`, reached only via `withTimeoutOrNull(timeoutMillis) { ... } ?: timeoutFailure(...)` -- i.e. only when this engine's own deadline actually expired. Directly tested by `measure_whenEngineOwnDeadlineExpires_returnsExactlyTimeoutUnknown_neverUnclassified`.
2. **Caller-owned timeout remains caller cancellation** -- VERIFIED FACT. Every catch clause for `TimeoutCancellationException`/`CancellationException` calls `currentCoroutineContext().ensureActive()` first, which rethrows if the *current* coroutine context is itself the one that was cancelled (the caller's own `withTimeout`) before falling through to the `Unclassified` mapping. Tested by `measure_whenCallersOwnTimeoutFires_propagatesCancellation_insteadOfReturningTimeoutFailure` and three related tests.
3. **Leaked client timeout -> `Unclassified`** -- VERIFIED FACT. A `TimeoutCancellationException` that survives the `ensureActive()` check (i.e. the caller is still active) is mapped via `unclassified(..., leakedTimeout = true)`. Tested by `measure_whenClientLeaksItsOwnTimeout_whileCallerIsActive_returnsUnclassified_notFabricatedTimeout`.
4. **Unexpected `Exception` -> `Unclassified`** -- VERIFIED FACT, `catch (e: Exception) -> unclassified(...)`, and a dedicated `NonMonotonicElapsedClockException` case with the same effect.
5. **`Error`/`Throwable` NOT swallowed** -- VERIFIED FACT, verified two ways: (a) reading the catch clauses -- `TimeoutCancellationException`, `CancellationException`, `NonMonotonicElapsedClockException`, `Exception` are the only types caught, and `Error` is not an `Exception` in the Kotlin/Java type hierarchy, so it is not caught by any of these; (b) `measure_onUnexpectedClientException_neverCaughtAsError` directly proves an `AssertionError` from the client propagates out of `measure()` uncaught, not folded into `Unclassified`.
6. **`AerivaLogger` required, no default** -- VERIFIED FACT. `private val logger: AerivaLogger` has no `=` default value in the constructor.
7. **`NetworkState` constructor requirements** -- VERIFIED FACT, and more precisely stated than the task brief's own phrasing: the three fields (`captivePortalReported`, `vpnPresent`, `blockedByDevicePolicy`) had defaults added at `fdf5440` and were then deliberately removed again at `76651aa` (Stage 2, Decision DD-5, "make 'not observed' distinguishable from 'observed false'"). `phase-4-engine-foundation-integration` inherits the no-default state. All `NetworkState(` construction sites in this branch's tree (6 total, confirmed by `git grep`, matching this audit's own independent count) supply all three fields explicitly.
8. **Test coverage** -- 38 tests, covering every claim above plus multi-sample aggregation (`measureSeries`), monotonic-clock duration measurement, dispatcher-queueing exclusion from measured latency, and non-monotonic-clock detection. Read in full; each test's assertions match its name's claim (not merely present).
9. **Context propagation** -- VERIFIED FACT. `measureSeries_attachesEachRequestsOwnContextVerbatim_evenWhenThatContextIsStale` confirms each request's own `MeasurementNetworkContext` is carried through to its result unmodified, including when it is stale relative to a later request in the same series (the engine does not silently "correct" it).
10. **Network handle propagation** -- carried transparently as part of `MeasurementNetworkContext`/`ProbeEvidence`, unchanged by this engine; not independently re-derived beyond confirming the engine does not construct or mutate a handle itself (no `networkHandle` write anywhere in `LatencyMeasurementEngine.kt`).

### SecurityException (deliberately not implemented at this stage)

Per this task's explicit instruction, `SecurityException` handling is **not implemented**. What the future integration needs, documented here rather than built:

- **Where it must occur:** inside `LatencyMeasurementEngine.measure`'s existing exception-handling chain, most naturally as its own `catch (e: SecurityException)` clause positioned before the generic `catch (e: Exception)` (since `SecurityException` is a subtype of `RuntimeException`/`Exception` and would otherwise be silently absorbed into the generic `Unclassified` case, losing the distinction).
- **What contract it must preserve:** the same "never throw for a named outcome, only for genuine caller cancellation" discipline (Decision D5-9) that already governs every other catch clause in this file -- a `SecurityException` is not cancellation and must become an explicit outcome, not propagate.
- **Why not now:** a `SecurityException` from `NetworkClient.probe` is a transport-layer, permission-shaped runtime failure that can only occur once a real socket-backed `NetworkClient` implementation exists (the current `NetworkClient` interface, per Section 8, has no implementation at all -- `FakeNetworkClient` is test-only and never throws `SecurityException` by design). Building the catch clause now would be speculative: what exact `SecurityException` shape a real client throws (missing `INTERNET`? a `VpnService`-related restriction? something else?) is unknown until OD-1/OD-7 are resolved and a real client exists. The Phase 5 evidence schema already reserves `PERMISSION` as one of its nine failure-investigation branches (Section 9 of the harness document) and a `BlockedByDevicePolicy` failure case already exists in `MeasurementFailure` for the adjacent (but distinct) device-policy-block scenario -- so the domain model has room for this when it is needed, without a shape decision being forced today.

**Conclusion: Engine/Foundation audit finds no defect.** All ten checklist items independently verified against the actual code and its own tests.

---

## 6. Android Network-State Audit

Read `AndroidNetworkMonitor.kt`, `NetworkEventReducer.kt` (including `foldNetworkStateFlow`), `NetworkStateMapper.kt`, `NetworkEventReducerTest.kt` (16 tests), `NetworkStatePipelineTest.kt` (8 tests), and `AndroidNetworkMonitorInstrumentedTest.kt` in full at `phase-4-android-network-state@800b2ec`.

### CF-1 structural fix, re-verified

VERIFIED FACT, by direct reading of `observe()`:

```kotlin
override fun observe(): Flow<NetworkState> = foldNetworkStateFlow(
    events = platformEvents().map(::toRawNetworkEvent),
    debounceMillis = debounceMillis,
    now = now
)
```

and `foldNetworkStateFlow` in `NetworkEventReducer.kt`:

```
events -> scan(reducer) -> drop(1) -> map(buildNetworkState) -> debounce -> distinctUntilChanged
```

This is the required shape: raw events reach the `scan`/reducer fold **before** `debounce` is applied anywhere. A repository-wide `git grep -n "\.debounce"` on this branch's tree finds exactly one call site, and it is this one, operating on the `Flow<NetworkState>` produced by `.map { buildNetworkState(...) }` -- not on `Flow<PlatformEvent>` or `Flow<RawNetworkEvent<N>>`. The pre-fix shape (`platformEvents().debounce{...}.map(::toRawNetworkEvent).scan(...)`, debounce upstream of the fold) does not exist anywhere in this tree. This audit did not need to change anything here; it confirms what the prior session's own final report already claimed, independently.

### Scenario-by-scenario re-verification (via `NetworkStatePipelineTest`, which drives the real `observe()` pipeline, not the reducer in isolation)

- **Wi-Fi -> cellular:** `wifiToCellularTransition_newNetworksBurstMakesItActive_oldNetworkNotStuck` -- passes. The new network's burst (`Available`/`CapabilitiesChanged`/`BlockedStatusChanged`, zero-delay) becomes the reported state; the old network's data does not survive.
- **Cellular -> Wi-Fi:** not separately covered by a pipeline-level test (only by `NetworkEventReducerTest.cellularToWifiTransition_freshAvailable_replacesTrackedNetworkCleanly`, reducer-only). Since the reducer treats both transition directions identically (a fresh `Available` for a different network id, no special-casing by transport type), and the pipeline-level Wi-Fi->cellular test already proves the debounce-interaction fix works for *a* transition, this is judged low-risk but is noted as a gap, not claimed as directly tested (Section 14).
- **First connection:** `firstConnection_burstOfAvailableThenCapabilitiesThenBlocked_producesRealConnectedState` -- passes; this is the exact scenario CF-1 broke (a zero-delay burst reaching the reducer intact).
- **Foreign-network events:** `foreignNetworkEvents_duringAndAfterTheEstablishingBurst_areIgnored` -- passes, including a foreign event interleaved *inside* the same zero-delay burst that establishes the real network, proving the reducer's own guard (not debounce timing) is what protects state.
- **Network loss:** `lossOfActiveNetwork_resetsStateWithoutWaitingTheFullDebounceWindow` -- passes; offline is reported within ~2ms of the `Lost` event, not delayed by the debounce window.
- **Blocked status:** `debounce_delaysOnlyStillUsableChatter_neverOfflineOrNewlyBlockedTransitions` -- passes; becoming blocked is reported immediately, matching the loss path's urgency.
- **VPN state:** covered at the reducer level (`NetworkEventReducerTest.vpnAppearingOverExistingTransport_...`, `vpnDisconnecting_...`) but **not** by a pipeline-level (`NetworkStatePipelineTest`) test. Given the reducer-level tests already pass and VPN presence is just another field on the same `CapabilitiesChanged`-derived snapshot the other pipeline tests already prove survives the fold/debounce interaction correctly, this is judged low-risk but, per this task's own instruction to be precise, is recorded as an untested combination, not a confirmed gap in behavior (Section 14).
- **Stale-network protection:** `foreignNetworkEvents_...` (above) and `NetworkEventReducerTest`'s own foreign-network tests both cover this from different layers.

No new defect found. No reducer change made, consistent with this task's instruction not to rewrite this implementation absent a new concrete defect.

---

## 7. Jitter Audit

Read `DerivedJitterStats.kt` and `DerivedJitterStatsInvariantTest.kt` (20 tests) in full at `phase-4-test-gate@4adf30c` (identical code to `a20dc4c`; the one commit between them is documentation-only, confirmed by `git diff --stat a20dc4c 4adf30c` showing only `PHASE_4_JITTER_TEST_GATE_REPORT.md`).

- **Pair eligibility:** same method, both samples' `ProbeEvidence.connectionState == Warm`, equal network handle (including both-null) -- `isAdjacentPair`, matches Decision D4-3.
- **Failed-sample boundaries:** a failed/non-`Succeeded` sample resets `previous = null`, breaking adjacency without being counted itself. Confirmed correct by hand-trace (below) and by `breakThenResume_countsBothRunsCompletely`.
- **Cold-sample behavior:** a differing `method` (e.g. a cold-connection method id) breaks `isAdjacentPair` on the method check alone; `coldBreakThenResume_countsResumedRunCompletely` passes.
- **Method continuity:** `pairMethod` is set from the *first counted pair's* first member, not the first `Succeeded` sample in the series (which may never be part of any pair) -- `reportedMethod_isThatOfTheCountedPairs_notOfTheFirstSuccessfulSample` passes.
- **Network-handle continuity:** equal-handle check, both-null counts as equal; `nullHandleAndNonNullHandle_doNotPair` and `differentNonNullHandles_doNotPair_evenWithMatchingMethodAndState` both pass.
- **Non-finite / negative values:** `isRealReading` rejects non-finite or negative `valueMillis` before it can ever become part of a pair; `nonFiniteSamples_neverProduceNonFiniteStatistics` and `negativeLatencySample_isNotTreatedAsARealReading` pass.
- **Lineage:** `members` is a `LinkedHashMap<Long, Double>` keyed by measurement id, populated in first-seen (send) order, for every member of every counted pair -- not just the first pair in the series. This is the exact fix for finding F-2 (see below).
- **`sampleCount`:** `members.size` -- distinct lineage size, per `property_sampleCount_equalsDistinctLineageSize`.
- **PDV range:** `members.values.max() - members.values.min()`, over the same lineage `sampleCount` counts -- `property_pdvRange_isMaxMinusMinOverEveryCountedSample` passes.
- **Reported method:** as above.

### The Phase 5-flagged concern (finding F-1 / F-2), independently re-derived

Phase 5's `latency-series.json` fixtures `FIX-LAT-009` (`10, 20, FAIL, 30, 40`) and `FIX-LAT-010` (`50, 60, FAIL, 10, 20`) each carry a `known_implementation_divergence` annotation, citing `phase-4-measurement-foundation@9a28cc62` and describing exactly finding F-2 ("the first sample of a later, separate run of pairs... is dropped from `sampleCount`... Confirm by running this fixture against the Kotlin implementation").

This audit hand-traced both fixtures against the **current** implementation (`phase-4-test-gate@4adf30c`), not the old commit the annotation cites:

- `FIX-LAT-009` (`10, 20, FAIL, 30, 40`): trace produces `pairCount=2`, `meanAbsIpdvMillis=10.0`, `sampleCount=4` (members `{10,20,30,40}`), `pdvRangeMillis=30.0` -- this matches the fixture's own `"expected"` block exactly (`pair_count: 2, sample_count: 4, mean_abs_ipdv: 10.0, pdv_range: 30.0`), **not** the divergent behavior the annotation describes.
- `FIX-LAT-010` (`50, 60, FAIL, 10, 20`): trace produces `pairCount=2`, `mean=10.0`, `sampleCount=4` (members `{50,60,10,20}`), `pdvRangeMillis=50.0` -- again matches the fixture's `"expected"` block exactly, not the annotation's predicted divergent `sample_count: 3, pdv_range: 40`.

This is corroborated by `DerivedJitterStatsInvariantTest.breakThenResume_countsBothRunsCompletely` and `coldBreakThenResume_countsResumedRunCompletely`, both of which pass on CI (re-confirmed this session, workflow `7b447184`).

**Classification: NOT CONFIRMED.** The concern describes a real defect that existed in the implementation its own annotation cites (`9a28cc62`) and has since been fixed (`a20dc4c`, findings F-2/F-3/F-4). It is not reproducible against the current implementation. The Phase 5 document's `known_implementation_divergence` annotations and its "not confirmed, needs the Kotlin run" status line (`PHASE_5_MEASUREMENT_VALIDATION_HARNESS.md` line 249) are both now stale -- this audit's hand-trace is exactly the confirmation step that document says is still needed, and it resolves the concern rather than needing a separate Kotlin fixture runner to do so.

**Conclusion: no jitter defect found. Mathematical definition (mean absolute IPDV, Decision D4-3) not reopened, per this task's instruction.**

---

## 8. S3 Interface Boundary

Read `NetworkClient.kt` at `phase-4-measurement-foundation@fdf5440` (unchanged by every branch audited; confirmed by `git grep -l ProbeRequest` returning zero files across all six branches checked, and by re-reading the file's full current content this session).

Current shape:

```kotlin
interface NetworkClient {
    suspend fun probe(target: String): NetworkClientOutcome
}

sealed interface NetworkClientOutcome {
    data class Success(val payload: ByteArray) : NetworkClientOutcome
    data class ConnectionRefused(val reason: String) : NetworkClientOutcome
    data class TlsHandshakeFailed(val reason: String) : NetworkClientOutcome
    data object NetworkChangedMidCall : NetworkClientOutcome
}
```

No `ProbeRequest` type exists anywhere in the repository (VERIFIED FACT, repository-wide search, six branches).

| # | File | Current signature | Required signature (for a real socket-backed client) | Reason | Callers | Tests affected | Needs OD-1? | Needs OD-7? |
|---|---|---|---|---|---|---|---|---|
| 1 | `NetworkClient.kt` | `probe(target: String): NetworkClientOutcome` | `probe(request: ProbeRequest): NetworkClientOutcome` (new `ProbeRequest` type carrying method, headers, expected-response shape, per-attempt deadline) | A bare `String` target cannot express what a real HTTP/TCP probe needs to send (method, headers) or how long it should wait per attempt (today's timeout is entirely `LatencyMeasurementEngine`'s own `withTimeoutOrNull`, which cannot express e.g. "connect timeout" vs. "read timeout" separately, something a real client library exposes natively) | `LatencyMeasurementEngine.measure` (the only caller) | `LatencyMeasurementEngineTest` (all `measure*` tests construct a `LatencyMeasurementRequest`, not a raw target string, so this is a two-hop signature change), `FakeNetworkClientTest`, `ReferenceLatencyProbeExecutorTest` | No | No |
| 2 | `NetworkClientOutcome` | 4 cases (`Success`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall`) | Add a case for "unexpected I/O exception, not one of the above" (today, anything a real client throws that is not one of these four becomes `LatencyMeasurementEngine`'s `Unclassified` via its generic `catch (e: Exception)` -- functionally adequate, but an explicit outcome case would let `NetworkClient` implementations avoid throwing at all, matching the interface's own documented intent that it "suspends until a definite outcome is known") | Today's four cases were designed opposite a hand-written `FakeNetworkClient`, not a real transport library's actual exception surface | Same as above | Same as above, plus every `when` over `NetworkClientOutcome` (currently one, in `LatencyMeasurementEngine.toMeasurement`) would need a new arm | No | No |
| 3 | `LatencyMeasurementEngine.measure` | `catch (e: Exception) -> unclassified(...)` | Add a `catch (e: SecurityException)` before the generic `Exception` catch, mapping to a still-undecided explicit outcome (Section 5) | See Section 5's SecurityException discussion | N/A (engine's own internal exception chain) | New tests only, none removed | No | No |
| 4 | (new file) `OkHttpNetworkClient.kt` | Does not exist | A concrete `NetworkClient` implementation | This is the actual S3 transport work | N/A yet | N/A yet | **Yes** (cannot make an outbound request without the manifest permission) | **Yes** (cannot be built without the owner-approved dependency) |
| 5 | `app/build.gradle.kts` | `dependencies { }` (empty) | `implementation(project(":network:monitor"))` at minimum, once a feature module needs to consume it | `:app` currently has no dependency at all on `:network:monitor` or `:core:model` (confirmed by direct read of the file, comment explicitly states this), which is itself a blocker independent of OD-1/OD-7: even with `INTERNET` declared in `network:monitor`'s manifest, it would not appear in `:app`'s merged manifest without this wiring | N/A | N/A | Not by itself, but moot until OD-1 is resolved | Not by itself |

**Items 1-3 (interface/engine shape) can be done without OD-1 or OD-7** -- they change types and signatures, not runtime networking behavior, and can be developed and tested entirely against `FakeNetworkClient`. **Items 4-5 cannot.** This audit documents the plan; it implements none of it, per this task's explicit prohibition.

---

## 9. INTERNET / OkHttp Owner Decisions

**OD-1 (`INTERNET`):** UNRESOLVED DECISION, untouched by this audit. VERIFIED FACT (re-checked this session): `network/monitor/src/main/AndroidManifest.xml` declares only `ACCESS_NETWORK_STATE`; `app/src/main/AndroidManifest.xml` declares no permissions at all. No `<uses-permission android:name="android.permission.INTERNET" />` exists anywhere in the repository (re-confirmed by `git grep INTERNET` across every branch touched this session -- every hit is either this exact absence being asserted in a comment/test, or an unrelated identifier).

**OD-7 (OkHttp):** UNRESOLVED DECISION, untouched by this audit. No `okhttp`/`com.squareup.okhttp3` entry exists in `gradle/libs.versions.toml` on any branch checked. The technical research finding that OkHttp 5.4.0 (not 5.5.0, which needs compileSdk 37) is the compatible candidate for this repository's compileSdk 36 is treated in this audit exactly as the task instructs: technical research, not authorization, and is not acted on.

---

## 10. Manifest and Dependency Readiness

- **AGP:** `8.13.2` (VERIFIED FACT, `gradle/libs.versions.toml`). Re-checked against current official documentation this session: AGP has had a stable, official Variant/Artifact API surface since version 7.0; `8.13.2` is well within that stable range.
- **compileSdk / targetSdk / minSdk:** `36` / `36` / `26` (VERIFIED FACT, `app/build.gradle.kts` and `network/monitor/build.gradle.kts`, both re-read this session).
- **`SingleArtifact.MERGED_MANIFEST`:** VERIFIED FACT (re-checked against `developer.android.com/reference/tools/gradle-api/.../SingleArtifact`, this session): this constant exists and is documented as "Merged manifest file that will be used in the APK, Bundle and InstantApp packages" -- the correct, official API for reading the merged manifest, as opposed to a guessed `build/intermediates/...` path (which is an implementation detail AGP does not guarantee stable across versions). This audit confirms the recommendation is sound; it does not implement the validation task itself, per this task's explicit instruction.
- **`:app` dependency graph:** empty (Section 8, item 5). This is itself a readiness gap independent of OD-1/OD-7: wiring `:app -> :network:monitor` is still an open integration-timing question, exactly as `phase-4-android-platform-spec-corrected` already states, and this audit does not resolve it.
- **`network:monitor` manifest:** unchanged, `ACCESS_NETWORK_STATE` only.

No manifest or dependency was modified by this audit.

---

## 11. Phase 5 Compatibility

Compared `phase-5-measurement-validation-harness`'s evidence-record JSON schema (`validation/schema/evidence-record.schema.json`, read in full this session) against the current Phase 4 domain model:

**Fully compatible today:**
- `measurement.failure.type` enum (11 values: `Timeout`, `Cancelled`, `DnsFailure`, `EndpointFailure`, `TlsFailure`, `UnexpectedRedirect`, `InvalidResponse`, `CaptivePortalSuspected`, `NoResponseFromEndpoint`, `BlockedByDevicePolicy`, `NetworkChangedDuringMeasurement`, `Unclassified`) -- every one of these already exists as a case of `MeasurementFailure` (VERIFIED FACT, `git grep` of the sealed type's 11 cases matches the schema's 11 values one-to-one).
- `network.transport`, `.validated`, `.metered`, `.captive_portal_reported`, `.vpn_present`, `.blocked_by_policy` -- all map directly to existing `NetworkState` fields.
- `measurement.capability` includes `LATENCY`/`JITTER`, both implemented; the other eight capability names exist in `MeasurementCapability`/`MeasurementCapabilityClassifier` as classified-but-not-yet-measured.

**Concrete gaps (not implemented anywhere in this audit's scope):**
- `network.underlying_transport` -- no equivalent field on `NetworkState`. `resolveTransport`'s VPN-first priority (Section 6 of the prior network-state audit) means the *underlying* transport under a VPN is currently unrecoverable from `NetworkState` alone.
- `network.private_dns_mode`, `network.ip_family` -- neither exists on `NetworkState`. Consistent with, not a new discovery beyond, the already-documented decision that DNS state is deferred (Decision D4-10).
- `network.carrier_label`, `network.wifi_band`, `network.ap_ref` -- these require `WIFI_CHARACTERISTICS`/`CELLULAR_CHARACTERISTICS`, already correctly classified `NotReliablyAvailable` pending `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE`, neither held. Not a new gap; already documented.
- **`PermissionState` (`Granted`/`Denied`/`GrantedApproximateOnly`/`CheckFailed`) has no explicit slot in the evidence schema.** The schema's `device`/`condition` sections do not carry per-permission grant state; only `measurement.outcome_kind = DECLINED_CAPABILITY_UNAVAILABLE` implicitly reflects a permission gap, with no room for `GrantedApproximateOnly` as a distinct, intermediate state. This is a genuine gap between the newly-built `PermissionAdapter` (Section 4) and the Phase 5 schema, worth flagging to both owners before physical-device validation begins.

Not implementing Phase 5, per this task's instruction.

---

## 12. Integration Conflicts and Risks

1. **Branch topology (the headline risk, Section 2).** Five sibling branches, three different base points. Landing all Phase 4 work on `main` requires a deliberate reconciliation pass, not a sequence of independent merges (a naive merge of `phase-4-permission-adapter` and `phase-4-engine-foundation-integration` would likely succeed cleanly on `NetworkState.kt`, per Section 2's byte-for-byte comparison, but has not been attempted or CI-verified together).
2. **`phase-4-android-network-state`/`phase-4-test-gate`/`phase-4-android-platform-spec-corrected` are not rebased onto the post-engine-hardening-merge foundation.** None of the three conflict at the file level with the engine-hardening/DD-5 work (confirmed by `git diff --name-only` cross-checks, Section 2), but none have been CI-verified *together* with it either.
3. **Stale cross-references.** `phase-4-s3-implementation-authorization` and the Phase 5 harness's jitter finding both cite commits that have since been superseded (Sections 2, 7). Neither is incorrect about the state of the repository *at the time each was written*; both are now out of date relative to the current tips.
4. **`:app` has no dependency on `:network:monitor`** (Section 8/10) -- an integration-timing gap independent of, and blocking regardless of, OD-1/OD-7.
5. **`PermissionState` has no home in the Phase 5 evidence schema yet** (Section 11) -- a cross-team gap, not a code defect.

No item above required or received a code change in this audit.

---

## 13. Confirmed Defects

**None.** No new defect was found in any of the five audited work streams. The one concern this audit specifically investigated (Phase 5's jitter finding F-1) is classified **NOT CONFIRMED** against the current implementation (Section 7) -- it was a real, now-fixed defect in an older commit, not a live one.

---

## 14. Deferred Items

- `SecurityException` handling in `LatencyMeasurementEngine` (Section 5) -- deferred until a real `NetworkClient` exists to define its actual shape.
- The S3 interface migration (Section 8, items 1-3) -- documented, not implemented, per this task's scope.
- Cellular -> Wi-Fi transition and VPN state, at the `NetworkStatePipelineTest` (full-pipeline) level specifically -- currently covered only at the `NetworkEventReducerTest` (reducer-in-isolation) level; judged low-risk given the pipeline-level Wi-Fi->cellular and foreign-event tests already exercise the same debounce/fold interaction those two scenarios would exercise, but not directly proven (Section 6).
- Branch reconciliation itself (Section 12) -- an explicit owner/engineering-process decision about merge order, not a task this audit is scoped to perform.
- The `PermissionState`-in-evidence-schema gap (Section 11) -- a Phase 5 document update, not Phase 4 code.

---

## 15. Exact Recommended Next Task

**Reconcile branch topology before any further Phase 4 feature work lands.** Concretely, in order:

1. Rebase (or merge) `phase-4-permission-adapter` onto `phase-4-engine-foundation-integration@7731aa4`'s tip (picking up Stage 3/4), resolving the already-confirmed-identical `NetworkState.kt` change trivially.
2. Separately, rebase `phase-4-android-network-state`, `phase-4-test-gate`, and `phase-4-android-platform-spec-corrected` onto the result of (1), and re-run full CI on each after rebase -- none are expected to conflict at the file level (Section 2), but none have been proven together on CI yet.
3. Only after (1) and (2) both have a single, CI-green combined branch should S3 interface migration (Section 8) begin -- and only the parts of it that do not require OD-1/OD-7 (items 1-3) can start before those owner decisions land.

This is a reconciliation/rebase task, explicitly not authorized to be performed by this audit itself.

---

## 16. Explicit Non-Changes

- `INTERNET` was not added, anywhere.
- OkHttp was not added, anywhere.
- No endpoint was invented.
- `OkHttpNetworkClient` was not implemented.
- `NetworkClient`'s real transport was not implemented.
- `MeasurementCapabilityClassifier` behavior was not modified.
- `PermissionAdapter` was not redesigned.
- `LatencyMeasurementEngine` was not redesigned.
- Jitter (`DerivedJitterStats`) was not redesigned or changed.
- `NetworkEventReducer`/`AndroidNetworkMonitor` were not rewritten.
- No test was weakened to make CI green (no test was touched at all in this audit).
- No owner decision (OD-1, OD-7, DP-1 through DP-6) was silently resolved.
- No branch was merged.
- No branch was force-pushed.
- `main` was not modified.

---

## 17. Verification Evidence

- Every commit SHA cited in this document was independently resolved via `git rev-parse`/`git log` against the live remote this session (Section 2's table), not copied from the task brief without checking.
- Every branch cited exists (`git ls-remote --heads origin`, this session, 32 branches total, all cited branches present).
- CI evidence for `phase-4-permission-adapter@e912fb7` (workflow `b3de3a7d`, all 4 jobs succeeded) and `phase-4-engine-foundation-integration@7731aa4` (workflow `6aec7723`, all 4 jobs succeeded) and `phase-4-test-gate@4adf30c` (workflow `7b447184`, all 4 jobs succeeded) was fetched fresh from CircleCI this session, not assumed from any prior report. `phase-4-android-network-state@800b2ec`'s CI evidence (workflow `7a2e23de`) was produced in the immediately preceding session that created this exact commit and is cited, not re-run, since no code on that branch changed this session.
- The jitter fixture hand-trace (Section 7) was performed by this audit against the actual `DerivedJitterStats.from` source read this session, not against a description of it.
- The `androidx-core-ktx` reuse claim (Section 4, item 9), the `SingleArtifact.MERGED_MANIFEST` claim (Section 10), and the Android 12+ permission-request-flow claim (Section 4, item 7) were each independently checked against a primary or official source this session (direct repository read for the first; `developer.android.com` reference pages for the second and third).
- `git status`/`git diff` on this audit's own branch show exactly one file added (`PHASE_4_S3_INTEGRATION_READINESS_AUDIT.md`) relative to its base (`phase-4-engine-foundation-integration@7731aa4`); no other file changed.
- No `INTERNET` permission, no OkHttp dependency, and no new endpoint were introduced by this audit -- confirmed by the same `git diff` (a documentation-only branch cannot introduce any of these).
- `main`'s tip (`e3f70a4a13e63b782c61601abadc2c36f65dbd73`) was not touched -- this audit branch does not include `main` in its own history beyond the merge-base already shared by every Phase 4 branch.
- **Not run this session, stated plainly rather than implied:** the Phase 5 Python validation checker's "passes" claim was not independently re-executed (no Python fixture-runner execution was performed); this audit's confirmation of the jitter finding was done by manual arithmetic trace against the Kotlin source, not by running the Python tool. No Gradle build was run locally in this session (this sandbox cannot reach Maven Central/Google Maven, consistent with every prior Phase 4 session's own recorded constraint); every CI result cited above was read from CircleCI, not produced by a local build in this session.

---

*End of audit.*
