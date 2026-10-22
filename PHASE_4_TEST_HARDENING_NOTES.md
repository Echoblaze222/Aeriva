# Phase 4 test-hardening notes

Branch `phase-4-test-hardening`, based on `phase-4-controlled-integration` @ `52c26ceb915d`.
Test-only: no production Kotlin, manifest, Gradle, dependency, permission, wiring, endpoint, credential or CI file is changed.

## 1. Verification status (read this first)

| Claim | Status |
|---|---|
| New and changed tests compile and pass under the real Gradle build (`./gradlew test testDebugUnitTest`) | **NOT VERIFIED.** The authoring environment cannot reach `services.gradle.org`, Maven Central or Google Maven (egress proxy returns 403), so Gradle could not resolve its distribution or any dependency. The first CI run on this branch is the first real Gradle run. |
| New and changed JVM tests pass under a local shim | **Yes** (details in section 2). Shim results are not Gradle results. |
| Instrumented test changes compile / run | **NOT RUN.** No Android SDK or emulator. Type-checked only against hand-written stubs of `Context`, `ApplicationProvider`, `AndroidJUnit4`, `AndroidNetworkMonitor` and `AndroidLogcatLogger`. |
| Mutation results | Real: each "killed" below is an actual failing run under the shim, against a scratch copy. They show the tests detect the defect under the shim, not under Gradle. |

## 2. The shim (not part of the repository)

* Compiler: Kotlin 2.0.21 (`kotlin-compiler-embeddable` bundled in the local Gradle 8.14.3 distribution). The project pins Kotlin 2.3.21. No test uses a feature newer than 2.0.
* Libraries: JUnit 4.13.2 (same as the project pin), kotlinx-coroutines-core 1.6.4 (project pins 1.11.0).
* `kotlinx-coroutines-test` was unavailable, so a ~60-line stand-in provided `runTest`, `currentTime`, `advanceTimeBy` and `runCurrent` with the documented semantics (`advanceTimeBy` runs tasks strictly before the target time; `runCurrent` runs tasks due now). The kotlinx README does not state the exact-boundary behaviour; no new test depends on it (every checkpoint is at least 1 ms past the event it waits for).
* Calibration: the 24 pre-existing reducer and pipeline tests and all 74 pre-existing `core:model` tests pass unchanged under the shim before any change was applied, and the pre-fold-debounce mutant (P1) is killed by 8 of the 24 original tests, so the shim is able to fail where the real library would.
* Only `core:model` (all tests) and the pure-Kotlin part of `network:monitor` (`NetworkEventReducerTest`, `NetworkStatePipelineTest`) were run. Other modules were not.

Shim results: `core:model` 89 tests, 0 failures (was 74); `network:monitor` reducer+pipeline 32 tests, 0 failures (was 24).

## 3. What changed

### Jitter (`core:model`)
* `DerivedJitterStatsInvariantTest.outOfOrderFailedSample_stillRejectsTheWholeSeries` replaced. The old series `[S1 t=1, FAIL t=9, S3 t=5]` returned null with or without the order guard (the failure already broke adjacency), so it could not detect the guard being removed. The new series has a valid ordered pair plus an out-of-order failed sample, and includes an in-order control proving the same samples yield a result.
* Note: `DerivedJitterStatsTest.outOfOrderSeries_isRejected` was **already** discriminating (it fails without the guard); it is unchanged.
* New `DerivedJitterStatsHardeningTest`:
  * order: last pair reversed in an otherwise valid 4-pair series; reversal in the middle;
  * invalid samples (NaN, +Infinity, -Infinity, negative): valid-invalid-valid gives null; two consecutive invalid samples give null; invalid between two runs gives exactly 2 pairs, mean, lineage `[1,2,4,5]`, sampleCount 4, range, confidence; invalid first / last sample; only-invalid series; zero latency is valid; invalid followed by failed;
  * confidence at exactly 2, 3, 9 and 10 pairs, plus a samples-vs-pairs check (3 samples = 2 pairs, 10 samples = 9 pairs).
  * Every result assertion is preceded by a non-null assertion; no `if (stats != null)` guards.

**Invalid-sample contract.** Adopted rule: an invalid sample breaks adjacency like a failed sample and is never removed to bridge its neighbours. The current implementation already satisfies it (`previous = null` on an invalid reading). There is **no production mismatch** and no production-fix recommendation for this item.

**Confidence tiers.** Pinned from the code and its KDoc (`MINIMUM_PAIRS = 3`, `MEDIUM_PAIRS = 10`): below 3 pairs `Insufficient`, 3 to 9 `Low`, 10 or more `High`. `Confidence.Medium` is never produced by jitter (unlike `Confidence.of`). The Decision D4-4 text itself is not in this repository (it lives on `phase-4-cross-cutting-decisions`); the tests rely on the in-repo code comments and `PHASE_4_JITTER_TEST_GATE_REPORT.md`, which call the 3 and 10 cutoffs provisional (JT-19, gate G3). Thresholds are unchanged. These tests make a future change to the cutoffs a visible, deliberate act.

### Reducer (`NetworkEventReducerTest`)
* New: `CapabilitiesChanged` preserves `blocked=true` (and `blocked=false`).
* Wi-Fi to cellular and cellular to Wi-Fi: the previous network is now actually blocked first (asserted as a precondition); the new network must not inherit `blocked`; a late blocked update naming the old network is dropped. The old tests sent `blocked=false` (Wi-Fi case) or nothing (cellular case), so they could not detect inheritance.

### Pipeline (`NetworkStatePipelineTest`)
* Counting clock: `now` is read once for the scan seed and once per raw event (4 reads for a 3-event burst), and final `lastChangedAt` is the third event's tick, proving every raw event is folded before debounce.
* Virtual-time clock: `lastChangedAt` is the fold time (t=500), not the time debounce released the state (t=800); foreign-network events do not advance `changedAt` and emit nothing.
* `Unavailable`: after an established network, after a blocked network (blocked flag not carried into offline), and as the very first event; offline is never debounced. These follow the reducer's documented rule that `Unavailable` always resets and the pipeline's documented rule that `available=false` is never delayed.
* **Not added:** upstream completion. No project contract defines it (the existing file comments only refer to kotlinx's own flush-on-close behaviour). Adding a test would invent semantics.

### Instrumented (`AndroidNetworkMonitorInstrumentedTest`)
* Added invariants that `NetworkStateMapper` guarantees for any connectivity (quality `Unavailable`, diagnostics `NotAvailable`, and the canonical offline shape whenever `available=false`), and a second test that the monitor can be observed again after the first collection is cancelled.
* Deliberately not asserted: which transport, validated, metered, VPN or Wi-Fi/cellular presence; any comparison with `ConnectivityManager`'s own view (races with validation and emulator network changes).
* Limitations not testable robustly in CI: transitions, loss and blocked-status paths (need a controllable network); a device starting with no default network (the platform fires no callback, so `observe()` emits nothing and the existing 10 s timeout would fire).

## 4. Mutation verification

Each mutant was a single exact text replacement applied to a scratch copy of the source (extracted from git, never the working tree), then run against both the original tests (`52c26ceb915d`) and the new tests (this branch). "Killed" means at least one test actually failed.

| Mutant | Original tests | New tests |
|---|---|---|
| M1 remove timestamp-order guard | killed by 1 (`DerivedJitterStatsTest.outOfOrderSeries_isRejected`) | killed by 4 (incl. the replaced test, which the original suite did not catch) |
| M2 bridge over invalid sample | **survived** | killed by 3 |
| M3 no validity filter | killed by 2 | killed by 9 |
| M4 accept negative values | killed by 1 | killed by 7 |
| M5 accept +Infinity | killed by 1 | killed by 7 |
| M6 `MINIMUM_PAIRS` 3 to 2 | **survived** | killed by 3 |
| M7 `MINIMUM_PAIRS` 3 to 4 | **survived** | killed by 1 |
| M8 `MEDIUM_PAIRS` 10 to 9 | **survived** | killed by 2 |
| M9 `MEDIUM_PAIRS` 10 to 11 | **survived** | killed by 1 |
| R1 `CapabilitiesChanged` resets `blocked` | **survived** | killed by 1 |
| R2 `Available` inherits `blocked` | **survived** | killed by 2 (both transition tests) |
| R3 `CapabilitiesChanged` keeps old `changedAt` | **survived** | killed by 2 |
| R4 `Unavailable` keeps `blocked` | **survived** | killed by 1 |
| P1 debounce before fold (the CF-1 regression) | killed by 8 | killed by 14 |
| P2 offline state is debounced | killed by 1 | killed by 4 |

Per-test kill lists were captured during the authoring session and are not stored in the repository. 9 of the 15 mutants survived the original tests; all 15 are killed by the new tests.

## 5. Follow-ups that need an owner decision or a production change (not done here)

1. **Stale callbacks after `Lost` (production fix).** Demonstrated by direct execution: after `Available("A")` then `Lost("A")`, a late `CapabilitiesChanged("A", ...)` returns `network=A, available=true` (the lost network is resurrected), because the foreign-network guard only applies while a network is tracked. A late `BlockedStatusChanged("A", true)` on the offline state yields `available=false, blocked=true`, and the mapper then reports `blockedByDevicePolicy=true` for an offline state. No test was added because any test of the intended behaviour would fail without a production change. Recommendation: decide the intended behaviour, then remember recently lost networks or ignore `CapabilitiesChanged` and `BlockedStatusChanged` when no network is tracked, and add the test with the fix.
2. DP-1 and DP-2 (mixed-method label; null-handle bridging) are untouched and still open.
3. `-0.0` is accepted as a valid jitter reading (`-0.0 >= 0.0` is true). Not pinned either way; flagged only.
4. Jitter confidence cutoffs remain provisional (JT-19, G3).
