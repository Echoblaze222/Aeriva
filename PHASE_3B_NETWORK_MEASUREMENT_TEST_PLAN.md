# AERIVA Phase 3B -- Network Measurement Test Plan

Test-foundation document and accompanying test infrastructure only.
Per this task's explicit scope, this does not implement the production
measurement engine, jitter/packet-loss/throughput domain types, UI,
Supabase, community intelligence, or `WorkManager` scheduling. Every
technical claim below is either verified directly against this
repository (commands actually run, files actually read), a specific
current source from this task's required technology research, or
explicitly marked as this document's own recommendation.

## 0. Relationship to prior Phase 3 documents

Written after re-reading `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`
and `PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md` in full, at `main`
commit `e3f70a4a13e63b782c61601abadc2c36f65dbd73` (this task's stated
HEAD, confirmed via `git log -1` against the checked-out repository
before any change in this branch). This document does not re-litigate
either -- it assumes their findings and states explicitly, per section,
where it extends them versus where it only confirms them still hold.

A second, separate branch (`phase-3b-android-measurement`, one commit,
`fc004b6`) already exists on `origin`, adding a
`MeasurementCapabilityClassifier` and its own report. This document and
this branch (`phase-3b-measurement-tests`) are independent of that work
-- this task's own instructions scope it to the *testing foundation*,
not the capability classifier, and neither branch was merged into the
other. Reviewers comparing the two: that branch is not re-described
here beyond this note.

## 1. Current-technology research (this task's explicit requirement)

Verified via web search against current sources, not assumed from
training data, specifically because this repository already pins
several dependency versions ahead of what any static knowledge cutoff
would reliably know (Kotlin 2.3.21, `kotlinx-coroutines` 1.11.0, AGP
8.13.2):

- **`runTest`/`StandardTestDispatcher`** remain the current, officially
  recommended coroutine-testing API (introduced in `kotlinx-coroutines-test`
  1.6, unchanged in shape since) -- confirmed against
  `developer.android.com/kotlin/coroutines/test` and
  `developer.android.com/kotlin/coroutines/coroutines-best-practices`.
  Both explicitly recommend constructing any additional `TestDispatcher`
  from the *same* `testScheduler` `runTest` provides ("All TestDispatchers
  should share the same scheduler... to make your tests deterministic")
  -- this plan's test infrastructure follows that exactly (Section 4).
- **`runTest` has a 60-second real-time safety timeout** (raised from an
  earlier, shorter default; configurable via
  `kotlinx.coroutines.test.default_timeout`), confirmed from
  `kotlinlang.org`'s own API docs -- relevant because it means a
  deliberately "hung" fake call (Section 4's `FakeNetworkClient.enqueueHang`)
  cannot itself hang CI forever even if a test's own cancellation/timeout
  logic has a bug; it fails, rather than hanging indefinitely.
- **Turbine** (`app.cash.turbine`) is still actively maintained (last
  release confirmed within the past several months as of this research),
  consistent with `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section
  6 already naming it as a not-yet-added candidate. This plan does not
  add it -- nothing delivered here produces a `Flow`-typed measurement
  result yet, so there is nothing for it to test.
- **Robolectric** was researched specifically as an alternative to real
  instrumented tests for Android-surface testing (it lets Android APIs
  run inside a plain JVM test via a simulated framework, and is the
  standard mechanism behind many AndroidX libraries' own
  local-testing artifacts). **Not added by this plan.** This repository
  has already deliberately drawn its JVM/instrumented boundary around
  fakes for anything below the platform layer and real
  `androidTest`/emulator runs for anything that actually touches
  Android framework classes (`AndroidNetworkMonitorInstrumentedTest`'s
  own comment states this explicitly). Robolectric simulates the
  platform layer *inside* a JVM test, which would blur exactly the
  "engine behavior vs. Android platform behavior" line this task's own
  test philosophy requires tests to keep distinct (Section 6 below) --
  and nothing this phase delivers needs it, since [`NetworkClient`]
  (Section 3) has zero Android dependency by design. Flagged, not
  added, matching this repository's existing decision-recording
  convention for `WorkManager`/Turbine.
- **OkHttp `MockWebServer`** was researched as the "local test server"
  option `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 3 names
  as a future possibility. It is under active development (currently
  mid-migration from the `okhttp3.mockwebserver` package to a
  `mockwebserver3` artifact, per OkHttp's own repository). **Not added
  by this plan**: this repository has no OkHttp dependency at all today,
  and no production `NetworkClient` implementation exists yet (Section 3
  -- only the interface and a fake). Adding OkHttp now would be adding a
  dependency before anything needs it, which this task's instructions
  explicitly prohibit. Recorded here so whoever implements the real
  `NetworkClient` doesn't have to re-research this from zero.
- **Android Gradle Plugin build-managed (virtual) devices**
  (`developer.android.com/studio/test/gradle-managed-devices`) were
  researched as a possible alternative to this repository's current
  `connected_android_test` CircleCI job (which already uses the
  `circleci/android` orb's own emulator management, api-30
  `google_apis` `x86_64`). **Not changed by this plan** -- the existing
  CircleCI job already provides emulator-managed instrumented testing;
  switching mechanisms is an orthogonal CI-infrastructure decision this
  task's scope does not call for revisiting.
- **Physical-device-only guidance reconfirmed**: current official Android
  performance-testing guidance states plainly that emulator results can
  differ significantly from real hardware and recommends physical
  devices for accurate performance measurement -- direct, current
  confirmation of the same conclusion `PHASE_2_ANDROID_PLATFORM_AUDIT.md`
  and `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 4 already
  reached, cited here because this task's instructions specifically
  asked this to be researched, not assumed.

**No new production dependency is added by this plan.** The only
`libs.versions.toml`/build-file changes are Section 4's `java-test-fixtures`
plugin application (a core Gradle plugin, not a version-catalog entry)
and new `testImplementation`/`testFixturesImplementation` lines wiring
already-present artifacts (`kotlinx-coroutines-test`) to a new source
set -- see Section 4 for exactly what changed and why.

## 2. Current repository state re-verified for this task

(Re-inspected directly, not carried over from the Phase 3/3A documents'
own inspections, per this task's own "inspect the actual repository"
instruction.)

- **No measurement engine exists.** Confirmed unchanged since Phase 3A:
  no `NetworkClient` implementation, no jitter/packet-loss/throughput
  domain types, `NetworkQuality` still hard-coded to `Unavailable`.
- **Existing test dependencies, reconfirmed from `gradle/libs.versions.toml`:**
  `junit:junit:4.13.2`, `androidx.test.ext:junit:1.3.0`,
  `androidx.test:runner`/`androidx.test:core:1.7.0`,
  `kotlinx-coroutines-test:1.11.0` (version-matched to production
  `kotlinx-coroutines`). Still no mocking library, no Robolectric, no
  Truth/AssertK, no Turbine -- Section 1's research confirms none of
  that needs to change for this phase's deliverables.
- **`core:model`'s Phase 3A skeleton, read in full:** `LatencyMeasurement`
  (`Succeeded`/`Failed`), `MeasurementFailure` (`Timeout`, `Cancelled`,
  `EndpointFailure`, `TlsFailure`, `InvalidResponse`,
  `NetworkChangedDuringMeasurement`), `Confidence`
  (`Insufficient`/`Low`/`Medium`/`High`, via `Confidence.of(sampleCount,
  consistent)`), `Freshness` (`isStaleAt(now)`, `ageAt(now)`),
  `MeasurementNetworkContext` (wraps `NetworkState`),
  `LatencyEstimation`, `LatencyPrediction`, `ConnectivityRecommendation`.
  `DerivedLatencyStats` existed as a plain data holder with **no
  aggregation function** -- Section 5 below is this plan's one addition
  to it.
- **`network:monitor`'s existing dependency graph, from its
  `build.gradle.kts`:** depends on `core:model`, `core:logging`, and
  `kotlinx-coroutines-android`. Did **not** depend on `core:common`
  before this branch -- `AndroidNetworkMonitor` has never needed
  `AerivaDispatchers` (its `callbackFlow` is inherently async). Section
  4 adds a `testImplementation`-only dependency on `core:common` for
  this reason -- production code in this module is unaffected.
- **`core:common`'s `TestAerivaDispatchers`, before this branch:** lived
  in `core:common`'s own `src/test`, with its own doc comment already
  stating the correct next step if another module ever needed it: "that
  is a testFixtures-source-set change." Section 4 is exactly that
  change, not a new decision.
- **This sandbox still cannot run a Gradle build**: confirmed the same
  network-egress constraint `PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md`
  Section 19 already documented (no Maven Central/Google Maven reachable
  here). See the Validation section for what CircleCI actually confirmed
  for this specific branch.

## 3. Test infrastructure delivered

Per Section 6 of `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`
("minimum seams only... extending existing patterns"), everything below
extends an already-established convention; nothing here is a new
abstraction style.

| File | Source set | What it is |
|---|---|---|
| `network:monitor` `measurement/NetworkClient.kt` | `main` | The one new *production* seam: a small interface ("connect and get a raw outcome back") plus `NetworkClientOutcome`, matching the design doc's "Network client: small interface wrapping the actual socket/HTTP call" exactly. No implementation. |
| `network:monitor` `measurement/FakeNetworkClient.kt` | `test` | Hand-written fake (not a mocking library), following `FakeSecureKeyValueStore`/`FakeNetworkStateHistoryDao` exactly: scripted, ordered, per-call delay/outcome injection, plus call/completion/cancellation counters for leak/cleanup assertions. |
| `network:monitor` `measurement/MutableClock.kt` | `test` | Small mutable holder around the existing `() -> Instant` seam -- not a new `Clock` interface (the design doc explicitly declines to add one). |
| `network:monitor` `measurement/ReferenceLatencyProbeExecutor.kt` | `test` | **Test-only illustrative harness**, not the production engine -- see its own KDoc. Exists solely so the seams above can be proven sufficient against real running tests, for one metric (latency), matching Phase 3A's own "one illustrative type per tier" precedent. |
| `network:monitor` `measurement/ReferenceLatencyProbeExecutorTest.kt` | `test` | The actual proof -- see Section 6's objective-by-objective mapping. |
| `network:monitor` `measurement/FakeNetworkClientTest.kt` | `test` | Meta-test of the fake itself, following the existing `TestAerivaDispatchersTest` precedent (that fake is tested directly too). |
| `core:model` `measurement/DerivedLatencyStats.kt` | `main` | **Modified, not new.** Added `DerivedLatencyStats.Companion.from(measurements, calculatedAt)` -- the one deterministic aggregation function objectives 1 and 5 need to exist against. See Section 5. |
| `core:model` `measurement/DerivedLatencyStatsTest.kt` | `test` | Pure-JVM tests for `from`, following the `NetworkStateMapperTest` precedent (Section 2 of the test strategy doc). |
| `core:common` `TestAerivaDispatchers.kt` | moved `test` &rarr; `testFixtures` | No behavior change -- relocated so `network:monitor`'s tests can reuse it instead of duplicating it. `core:common`'s own `build.gradle.kts` gained the `java-test-fixtures` plugin; its `src/test` is unaffected (the plugin wires it to `testFixtures` automatically). |

**Everything except `NetworkClient.kt` and the `DerivedLatencyStats`
companion lives in a `src/test` source set.** Nothing here is compiled
into the app. `NetworkClient.kt` is an interface with zero method
bodies and zero implementations added -- it changes nothing at runtime
until something implements it.

## 4. Why `network:monitor`, not a new module

Per Phase 3A's own open decision (Section 18, item 6: "where the
eventual Measurement Provider / Network Client seams actually live... is
not decided here"), this plan makes that decision for the test seam
specifically: **`network:monitor`**, extending its existing package
rather than introducing a new Gradle module. Reasoning:

- `network:monitor` is already the only module concerned with active
  network state/probing; a new module for one interface and its test
  fixtures would be structure for its own sake, which Section 6 of the
  test strategy doc explicitly warns against ("no broad architecture
  rewrite is proposed or implied").
- `NetworkClient.kt` has no Android import (matching
  `NetworkStateMapper`'s convention), so placing it in an Android
  library module costs nothing testability-wise -- it is exactly as
  instantiable in a plain JVM test as it would be in a new pure-Kotlin
  module.
- This decision is scoped to the *test seam* specifically, not to where
  a future production `NetworkClient` implementation or a formal,
  multi-metric `MeasurementProvider` interface should live -- that
  remains open, deferred to whoever adds jitter/packet-loss/throughput
  domain types (Section 8).

## 5. `DerivedLatencyStats.from` -- the one new production arithmetic

The only production logic (not test infrastructure) this plan adds,
and why it's in scope despite this task's "do not implement the
complete measurement engine" instruction: objectives 1 (latency
measurement) and 5 (stability) cannot get *real* deterministic test
coverage against *nothing* -- `DerivedLatencyStats` existed as a plain
data holder with no way to produce one from raw measurements. This adds
exactly that, for the one metric that already has full domain types
(latency), and nothing else:

- Filters out negative and non-finite (`NaN`/`Infinity`) values before
  averaging -- Section 2 of the test strategy doc's explicit numerical-
  edge-case requirement ("a probe that somehow reports negative latency
  should be rejected, not averaged in").
- Returns `null` for empty or all-invalid input -- an explicit, defined
  "nothing to aggregate" result, never a division-by-zero/NaN silently
  standing in for one.
- Computes `Confidence` via the *existing* `Confidence.of(sampleCount,
  consistent)` -- does not invent a second confidence mechanism. The
  "consistency" heuristic (spread relative to average, illustrative
  threshold) is marked exactly as illustrative as `Confidence.of`'s own
  thresholds already are.
- Does **not** implement outlier rejection. The test strategy doc
  Section 2 only requires testing outlier rejection "if the
  implementation does any" -- this one doesn't, so
  `DerivedLatencyStatsTest` does not claim to test it, and explicitly
  says so in its own class doc rather than silently omitting the case.

Full test coverage (`DerivedLatencyStatsTest.kt`): empty input, all-
invalid input, a negative sample correctly excluded from the average
without affecting valid entries' IDs, the `Confidence.of` sample-count
boundary (0/2 &rarr; Insufficient, 3-9 &rarr; Low regardless of spread, 10+
split by consistency &rarr; Medium/High), a very-large-but-finite value
producing a finite (not `NaN`) result, and `calculatedAt` being the
caller-supplied parameter rather than anything computed internally.

## 6. Objective-by-objective mapping

Per this task's sixteen named objectives, mapped to what this plan
actually delivers versus what remains blocked on later work that is
explicitly out of this phase's scope:

| # | Objective | Status | Where |
|---|---|---|---|
| 1 | Latency measurement | **Covered** (aggregation arithmetic) | `DerivedLatencyStatsTest` |
| 2 | Jitter calculation | Pattern-transferable, **not implementable yet** | No jitter domain type exists (Phase 3A modeled latency only); Section 5's pattern applies unchanged once one exists |
| 3 | Packet-loss calculation | Pattern-transferable, **not implementable yet** | Same reason as jitter |
| 4 | Throughput measurement | Pattern-transferable, **not implementable yet** | Same reason as jitter |
| 5 | Stability | **Covered** (via `Confidence`/spread) | `DerivedLatencyStatsTest`'s consistency-boundary cases |
| 6 | Network transitions | **Covered at the JVM/reaction level**; real transitions remain physical-device-only | `ReferenceLatencyProbeExecutorTest`'s `NetworkChangedMidCall` case; see Section 7 |
| 7 | Unavailable network | **Covered** | `ReferenceLatencyProbeExecutorTest.measure_whenNetworkUnavailable_...` |
| 8 | Timeout | **Covered** | `ReferenceLatencyProbeExecutorTest.measure_onTimeout_...` |
| 9 | Cancellation | **Covered**, and kept distinct from timeout | `ReferenceLatencyProbeExecutorTest.measure_onCallerCancellation_...` |
| 10 | Concurrent measurements | **Covered** | `ReferenceLatencyProbeExecutorTest.measure_concurrentCalls_...` |
| 11 | Stale measurements | **Covered** (composition with existing `Freshness`) | `ReferenceLatencyProbeExecutorTest`'s `measuredAt`/`Freshness` tests |
| 12 | Duplicate measurements | **Partially covered** -- see explicit caveat below | `ReferenceLatencyProbeExecutorTest.measure_twoIndependentRequests_...` |
| 13 | Cleanup | **Covered** | `ReferenceLatencyProbeExecutorTest.repeatedCalls_...`, `FakeNetworkClientTest` |
| 14 | Resource leaks | **Covered at this layer**; real socket/callback leak checks remain instrumented-test scope once a real client exists | Same as above |
| 15 | Battery/data-budget protection | **Not implementable yet** -- explicitly deferred | See Section 8 |
| 16 | Malformed measurement responses | **Covered** | `ReferenceLatencyProbeExecutorTest.measure_onMalformedPayload_...` |

**Objective 12's explicit caveat:** what's delivered tests that two
*independently requested* measurements are never silently merged or
cached by target/id. It does **not** test retry-produced-duplicate
de-duplication (the test strategy doc Section 5's specific framing: "a
network client that... by design, e.g. a retry... produces two results
for one logical measurement"), because `ReferenceLatencyProbeExecutor`
implements no retry logic at all -- there is nothing that could produce
that scenario yet. This is stated plainly in the test file's own
comment, not silently glossed over.

## 7. Test philosophy compliance

This task's instructions required tests to distinguish deterministic
domain calculations, engine behavior, Android platform behavior, actual
network behavior, and physical-device behavior -- and explicitly
prohibited tests that pretend a real measurement occurred or that use
fake numbers as evidence of real network capability. How this plan's
tests satisfy that:

- **Deterministic domain calculations** (`DerivedLatencyStatsTest`,
  existing `ConfidenceTest`/`FreshnessTest`): pure JVM arithmetic over
  hand-constructed values. No I/O, no coroutines, no clock.
- **Engine behavior** (`ReferenceLatencyProbeExecutorTest`): every test
  uses `FakeNetworkClient` with explicitly scripted outcomes -- no test
  anywhere in this branch opens a real socket or reaches a real host.
  Every assertion is phrased in terms of "given this scripted transport
  outcome, does the orchestration logic react correctly" -- never "this
  measured a real value." No test claims a `LatencyMeasurement.Succeeded`
  produced from a `FakeNetworkClient` reflects real network capability
  anywhere in this branch's code, comments, or this document.
- **Android platform behavior / actual network behavior / physical-device
  behavior**: none of this plan's tests touch `ConnectivityManager`,
  a real socket, or a real device -- by design, since `NetworkClient`
  has zero Android dependency. This is explicitly **not** claimed to be
  covered by this plan; Section 8 states plainly what still needs a
  physical device once a real engine exists.
- **No mocking framework introduced.** Every test double in this branch
  is hand-written, matching the existing convention Section 1's research
  reconfirmed was still current, not a new dependency.

## 8. Physical-device testing still required (none of this performed here)

Restated per this task's explicit instruction, and per Section 1's
current-technology confirmation that emulator results can meaningfully
differ from real hardware for performance measurement specifically --
**none of the following is claimed to have happened**, this is a
specification of what remains, unchanged in substance from
`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 4 and restated
here because this task asked for it explicitly:

- Real Wi-Fi/cellular latency, jitter, packet-loss, and throughput
  values -- emulator networking is virtualized and cannot produce these.
- Real network transitions during an in-flight measurement (Wi-Fi to
  cellular mid-probe) -- this plan's `NetworkChangedMidCall` test only
  proves the *code's reaction* to a simulated signal, not that a real
  transition produces that signal correctly.
- Real Doze/background-execution interaction with an in-flight
  measurement.
- OEM-specific battery/scheduling behavior's effect on measurement
  reliability.
- Radio/environment variation across more than one physical location.

**Distinct from the above, and already running today:** this
repository's `connected_android_test` CircleCI job runs on a CircleCI-
managed *emulator* (api-30, `google_apis`, `x86_64`), not a physical
device. Nothing in this branch adds new instrumented tests, so this job
runs the existing `AndroidNetworkMonitorInstrumentedTest` unchanged --
it validates that this branch's changes didn't break compilation or
existing instrumented tests, not that any new physical-device
requirement was met.

## 9. Also explicitly deferred (not physical-device-only, just out of scope)

- **Battery/data-budget protection (objective 15):** the test strategy
  doc's own Section 5 already scopes this correctly -- testable only as
  "does the scheduling/frequency logic respect the configured budget,"
  and no scheduling logic exists (the `WorkManager`/scheduler decision
  remains open per the Phase 2 audit and Phase 3A Section 18). Nothing
  to test yet; this is not a gap this plan failed to fill, it is a
  dependency this plan does not have.
- **A formal, multi-metric `MeasurementProvider` interface** (the test
  strategy doc Section 6's second seam, distinct from `NetworkClient`):
  not added. `ReferenceLatencyProbeExecutor` plays that illustrative
  role for latency only; a real, multi-metric provider interface is
  premature until jitter/packet-loss/throughput domain types exist to
  define its method signatures against.
- **Jitter/packet-loss/throughput domain types and their aggregation
  functions** -- not added (objectives 2-4). Adding them is domain-
  modeling work in the shape of Phase 3A, not testing-foundation work;
  this plan's contribution is confirming (Section 5) that the pattern
  already established for latency transfers directly once they exist.
- **Retry logic and retry-produced-duplicate de-duplication** (part of
  objective 12) -- not added; see Section 6's explicit caveat.

## Validation

This sandbox cannot run a Gradle build (Section 2). Everything below is
what real, observed CircleCI runs against this branch's actual commits
confirmed -- three runs, not one, because the first two caught genuine
test bugs (both in this new test code, not in anything pre-existing):

| Commit | CircleCI run | `build` | `static_checks` | `unit_tests` | `connected_android_test` |
|---|---|---|---|---|---|
| `961b412` (initial) | `920ee1f7` | pass | pass | **fail** -- `ReferenceLatencyProbeExecutorTest.measure_onCallerCancellation_...`: `Long.MAX_VALUE / 2` passed as a timeout overflowed a nanosecond conversion inside `withTimeout`, firing it immediately | pass |
| `252f919` (fix attempt 1) | `36894ea0` | -- | -- | **fail**, same test, same symptom -- the real cause was `advanceUntilIdle()` running straight through the (now-finite) timeout deadline before `job.cancel()` was reached | -- |
| `35f3fcb` (fix attempt 2) | `cdca2769` | pass | pass | **pass** -- `runCurrent()` instead of `advanceUntilIdle()` was the actual fix | pass |

All four CircleCI jobs pass on `35f3fcb`, the branch's current head.
`unit_tests`' test-results upload confirms every new test class actually
ran: `DerivedLatencyStatsTest`, `FakeNetworkClientTest`,
`ReferenceLatencyProbeExecutorTest`, alongside every pre-existing test
class unchanged. Both failed attempts and their root causes are kept in
this branch's own commit history rather than squashed away -- see
`252f919` and `35f3fcb`'s commit messages for the full diagnosis of
each. This document does not claim a pass on the strength of the code
"looking correct" at any point -- the two real failures above are the
concrete evidence that claim would have been wrong twice.
