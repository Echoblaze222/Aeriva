# AERIVA Phase 3 -- Network Measurement Test Strategy

Planning and test-foundation document only. This defines how the
future network measurement engine will be tested and the minimum
seams needed to make that possible. It does not implement the
measurement engine, does not build UI, and does not add any dependency
this document doesn't explicitly justify. Every technical claim below
is either verified against the current repository or a specific
current source, or explicitly marked as this document's own
recommendation rather than a fact.

## 0. Relationship to PHASE_2_ANDROID_PLATFORM_AUDIT.md

This document was written after re-reading the merged Phase 2 audit
(`main`, via PR #1) in full, and deliberately does not re-litigate its
platform findings -- it assumes them. Specifically carried forward
without re-verifying:

- The OBSERVATION/MEASUREMENT/ESTIMATION/PREDICTION/RECOMMENDATION
  distinction (Phase 2 audit Section 7) -- this document's Section 7
  is about *testing* that distinction stays real in code, not
  re-establishing why it matters.
- Every active-measurement capability (latency, jitter, packet loss,
  throughput) is app-level socket work, not a privileged Android API
  (Phase 2 audit Section 3/7) -- so the *test* strategy for it is
  primarily about controlling sockets/time/results deterministically
  (Section 3 below), not about mocking a privileged system service.
- `WorkManager` is not yet a dependency and this document does not add
  it "because it might be useful" -- per this task's explicit
  instruction, matching the Phase 2 audit's own Section 11 (add it
  only before AERIVA goal G specifically, with a real scheduled job
  validated on a physical device).
- `VpnService` is not necessary for any current goal (Phase 2 audit
  Section 11) -- this document does not include a VPN-testing section
  because there is no VPN to test.
- The physical-device-only findings (real Wi-Fi/cellular values, real
  Doze timing, real OEM battery behavior -- Phase 2 audit Section 13)
  are inputs to Section 4 below, not re-derived here.

## 1. Current repository state relevant to test strategy

(Verified by inspecting the checked-out repository directly, current
as of `main` at commit `b79ddcc`.)

**Test dependencies actually present:** `junit:junit:4.13.2`,
`androidx.test.ext:junit:1.3.0` (plus the separate
`androidx.test.runner` artifact for `AndroidJUnitRunner`),
`kotlinx-coroutines-test` (version-matched to the production
`kotlinx-coroutines` version, `1.11.0`). **Not present:** any mocking
library (MockK, Mockito), Robolectric, Truth/AssertK, or Turbine. This
matters directly for Section 6's testability-architecture
recommendations: the existing codebase's own convention is
**hand-written fakes, not a mocking framework** -- see
`FakeSecureKeyValueStore`, `FakeNetworkStateHistoryDao`, and
`TestAerivaDispatchers`, all in test source sets, all implementing the
same production interface their real counterpart implements. This
strategy follows that existing convention rather than introducing a
mocking library the codebase has consistently avoided so far.

**Existing seams already established in the codebase** (not proposed
by this document -- found by reading the code, and the model for what
Section 6 recommends extending, not replacing):

1. **`AerivaDispatchers`** (`core:common`) -- an interface
   (`main`/`io`/`default` `CoroutineDispatcher` properties) that
   production code depends on instead of `kotlinx.coroutines.Dispatchers`
   directly, with `TestAerivaDispatchers` substituting a single
   `TestDispatcher` for all three in tests. This is the dispatcher seam
   already used repo-wide; the measurement engine should depend on it
   too, not invent a second one.
2. **Injected clock, as a plain function type, not an interface** --
   `AndroidNetworkMonitor` holds `private val now: () -> Instant =
   Instant::now`, overridable via constructor parameter in tests. No
   dedicated `Clock` interface exists in this codebase, and this
   document does not propose introducing one where the existing
   `() -> Instant` pattern already works -- see Section 6 for where
   this seam needs to extend to the measurement engine specifically.
3. **Pure mapping functions kept import-free of the Android framework**
   -- `NetworkStateMapper` (`network:monitor`) takes plain data
   (`RawCapabilitiesSnapshot`, a `Boolean`, an `Instant`) and returns
   `NetworkState`, with no `android.*` import in the file at all (that
   file's own comment states this is deliberate: "this is where every
   'what does this actually mean' decision lives, so it is the thing to
   unit test, not the ConnectivityManager glue"). This is the direct
   precedent for Section 6's "measurement provider produces raw
   samples; a separate pure function scores/aggregates them" split.
4. **Fakes-over-mocks for storage/data-source seams** --
   `FakeSecureKeyValueStore` and `FakeNetworkStateHistoryDao` are
   hand-written, in-memory implementations of the same interface
   (`SecureKeyValueStore`, the DAO interface) their production
   counterpart implements, living in the test source set. This is the
   pattern Section 6 extends to a `NetworkClient` seam.

**A directly relevant, already-encountered lesson already recorded in
this codebase:** `AndroidNetworkMonitorInstrumentedTest`'s own comment
documents that this project already hit, and fixed, exactly the
coroutine-testing failure mode this task's instructions ask about
generally ("coroutine cancellation, concurrency"): a test using
`runTest`'s virtual-time scheduler cannot advance for a real
`ConnectivityManager` callback firing on a real system thread -- it
hangs until the real-time `withTimeout` fires, then fails with a
virtual-time timeout message that is actively misleading about the
real cause. The fix was using `runBlocking` (real time) for any test
that waits on a real Android system callback, and `runTest` (virtual
time) only for tests that don't. This exact rule governs Section 2's
approach to any future instrumented test that waits on a real system
event (a real socket response, a real callback) and is not this
document's invention -- it is this codebase's own already-proven fix,
being generalized.

**No measurement engine exists yet.** `NetworkQuality` is hard-coded to
`Unavailable`; there is no latency/jitter/packet-loss/throughput code,
no `NetworkClient` abstraction, and no confidence-scoring logic
anywhere in the repository. Everything in this document describes how
that future code *will* be tested, not how existing code is tested
(Sections 1's existing-seam inventory aside).

## 2. Deterministic JVM/unit tests

Everything in this section runs on the JVM, with no Android framework
dependency and no real network I/O -- following the existing
`NetworkStateMapperTest`/`AerivaResultTest` pattern of testing pure
functions directly.

- **Latency/jitter/packet-loss/throughput scoring and aggregation:**
  once raw samples exist (a list of round-trip times, a count of
  sent-vs-received probes, a measured byte count over a measured
  duration), the *arithmetic* that turns them into a score is pure
  function logic with no I/O -- test it exhaustively with hand-constructed
  sample lists, the same way `NetworkStateMapperTest` tests
  `NetworkStateMapper` with hand-constructed `RawCapabilitiesSnapshot`
  values today.
- **Confidence scoring:** must be tested against sample counts/spreads
  low enough to be meaningfully uncertain and high enough to be
  confident, with explicit test cases at both ends and at whatever
  threshold the implementation defines -- boundary values, not just
  "one small case and one large case."
- **Sample windows and aggregation:** test window boundaries
  explicitly (a sample exactly at a window edge, a window with zero
  samples, a window with exactly one sample) -- these are exactly the
  off-by-one-prone cases a hand-picked "normal" test case won't catch.
- **Outliers:** if the implementation does any outlier rejection
  (discarding a single wildly-high latency sample from an otherwise
  tight cluster, for instance), the rejection logic itself needs
  dedicated tests with a deliberately-planted outlier, not just
  "normal" data that happens not to trigger it.
- **Invalid/empty/partial samples:** an empty sample list, a sample
  list containing only failures, a sample list mixing successes and
  failures -- each needs its own test asserting a *specific*, defined
  result (a `Failure`/`null`/explicit "insufficient data" outcome, not
  whatever the arithmetic happens to produce when handed an empty
  list, e.g. division by zero or a NaN silently propagating).
- **Numerical edge cases:** zero, negative-would-be-invalid inputs (a
  probe that somehow reports negative latency should be rejected, not
  averaged in), very large values (a timeout represented as a very
  large latency number shouldn't silently dominate an average the way
  a real long-tail sample would), and floating-point summation order
  sensitivity if aggregation uses floating point at all.

**None of this needs Android, a real clock, or a real socket** -- it
needs hand-constructed input data and the pure scoring/aggregation
function, following the `NetworkStateMapper` precedent from Section 1.

## 3. Controlled network testing (no public-internet dependency)

This is the layer between "pure arithmetic on hand-constructed samples"
(Section 2) and "real device, real radio" (Section 4) -- testing the
*process* of taking a measurement, without either mocking away all the
realistic failure modes or depending on real, uncontrolled network
conditions.

- **Local test servers:** for throughput/latency probes that need
  something to actually connect to, a local server bound to
  `127.0.0.1` (or an in-process fake, depending on what the eventual
  `NetworkClient` interface looks like -- Section 6) removes the public
  internet as a dependency entirely, while still exercising real socket
  I/O and real timing. This is standard practice, not a novel
  recommendation, and is compatible with running inside a JVM unit
  test *or* an instrumented test, depending on which layer is under
  test.
- **Deterministic fixtures:** a fixed, versioned set of "this exact
  sequence of round-trip times represents this exact scenario" inputs,
  checked into the test source set, so a scoring-logic regression is
  caught by re-running the same fixture, not by a flaky real-network
  run producing different numbers each time.
- **Fake clocks:** extends the existing `() -> Instant` seam (Section
  1) into the measurement engine specifically -- any code computing
  jitter, a sample window, or a timeout duration needs to take its time
  source as an injected function/parameter, not call `Instant.now()` or
  `System.currentTimeMillis()` directly, so a test can advance time
  deterministically instead of using real `Thread.sleep`/timeouts.
- **Injected network clients / fake measurement providers:** the
  `NetworkClient` seam (Section 6) needs a fake implementation that can
  be told, per test, exactly what to return -- a fixed latency, a
  simulated timeout, a simulated connection refusal, a simulated
  partial read -- following the `FakeSecureKeyValueStore`/
  `FakeNetworkStateHistoryDao` pattern already in this codebase
  (Section 1), not a mocking library.
- **Deterministic delays/failures:** the fake network client above
  needs to support configurable, deterministic delay and failure
  injection specifically so timeout/cancellation/retry logic (Section
  5) can be tested without real timing flakiness -- "this call takes
  exactly 3 seconds and then fails" as a test fixture, not "this call
  usually completes quickly but sometimes doesn't, on whatever machine
  runs CI."
- **No dependency on the public internet, anywhere in this layer.**
  This is a testability requirement, not just a CI-reliability
  preference: a measurement engine's own *test suite* silently
  depending on a real external endpoint being reachable would make test
  failures ambiguous between "the code is broken" and "the network
  happened to be unavailable when CI ran" -- exactly the kind of
  observation/measurement conflation Section 7 exists to prevent, now
  applied to the test suite's own reliability.

## 4. Physical-device testing

Per the Phase 2 audit's own Section 13 findings, carried forward here
rather than re-derived: the following cannot be validated in a JVM
test or on an emulator, and this document does not claim any of them
have been tested -- this is a specification of what physical-device
testing must cover once the measurement engine exists, not a report of
results.

- **Real Wi-Fi and real cellular measurement values** -- actual
  latency/jitter/packet-loss/throughput numbers on real radios, since
  emulator networking is virtualized and does not reproduce real
  radio behavior (Phase 2 audit Section 3/13).
- **Real network transitions during an in-flight measurement** -- what
  happens when a latency probe is in-flight and the device's active
  network changes from Wi-Fi to cellular mid-probe. This is explicitly
  the gap `AndroidNetworkMonitorInstrumentedTest`'s own comment already
  identifies as untested for basic connectivity observation (Section
  1) -- it applies with more force to an in-flight *measurement*,
  where a transition mid-probe could produce a result that's
  technically a completed measurement but describes a network path
  that no longer exists by the time the result is used.
- **Radio/environment variation** -- signal strength varying by
  physical location, and the resulting effect on measurement
  stability/reliability, cannot be simulated; it requires testing in
  more than one real physical environment.
- **Doze, background execution, and battery restrictions on
  measurement accuracy specifically** -- not just "does background work
  run at all under Doze" (a Phase 2 concern), but "does a measurement
  that starts, gets deferred by Doze mid-flight, and resumes later
  produce a valid result or a corrupted one" -- a Phase-3-specific
  question Phase 2's audit didn't need to ask because Phase 2 has no
  in-flight operations to defer.
- **OEM-specific behavior's effect on measurement reliability** --
  Phase 2 flagged OEM battery-optimization behavior as inherently
  non-enumerable in a single pass (Phase 2 audit Section 12); the same
  caveat applies here and is not resolved by this document.

## 5. Concurrency, lifecycle, and failure-mode coverage

Explicit coverage for each item this task's instructions named,
mapped to where it's actually tested (JVM/controlled vs.
instrumented/physical) rather than treated as one undifferentiated
list:

| Concern | Where it's tested | Notes |
|---|---|---|
| Timeout | JVM, via fake network client (Section 3) | Deterministic injected delay past a defined timeout threshold |
| Cancellation / coroutine cancellation | JVM, `runTest` + `TestDispatcher` | A measurement coroutine cancelled mid-flight must not leak the underlying socket/resource or emit a result after cancellation |
| Concurrency | JVM primarily; instrumented for real-thread confirmation | Two measurements requested concurrently must not corrupt shared aggregation state; if the design serializes them instead, that serialization itself needs a test |
| Duplicate results | JVM | A network client that (by bug or by design, e.g. a retry) produces two results for one logical measurement must have defined, tested de-duplication behavior, not silently double-count |
| Stale results | JVM, via fake clock (Section 3) | A result timestamped before the current sample window must be identifiably stale, not silently blended into a fresh aggregation |
| Network changes during measurement | Primarily physical device (Section 4); JVM can test the *code's reaction* to a simulated mid-measurement network-changed event via the fake client/connectivity-state seam | Split between "does the code handle this signal correctly" (JVM-testable) and "does this signal even arrive correctly from a real transition" (physical-device-only) |
| Offline/unavailable networks | JVM, via fake connectivity-state seam (Section 6) | Must produce a defined, explicit result distinguishing "measured: none" from "not yet measured" -- directly the OBSERVATION-vs-MEASUREMENT distinction in Section 7 |
| Cleanup | JVM (resource/leak assertions on the fake client) + instrumented (real socket/callback cleanup) | Mirrors the existing dispatcher/callback-unregistration pattern already covered for `AndroidNetworkMonitor` |
| Repeated measurements | JVM | Running the same measurement twice in immediate succession must not corrupt or double-apply state from the first |
| Invalid results | JVM (Section 2's numerical-edge-case coverage) | |
| Partial results | JVM (Section 2) | |
| Endpoint failures | JVM, via fake client returning a simulated connection failure (Section 3) | |
| TLS/security failures | JVM, via fake client returning a simulated handshake failure -- **not** a real TLS handshake test, which would need a real or local-test-server certificate and belongs to Section 3's "local test server" tier if pursued at all | This audit-adjacent document does not require implementing real TLS-failure integration tests; a fake client's ability to *report* a TLS-shaped failure is what needs unit coverage |
| Battery/data-cost limits | Primarily a design/budget concern (Phase 2 audit Section 9), testable at the unit level only as "does the scheduling/frequency logic respect the configured budget," not as an actual battery measurement, which is physical-device-only | |
| Race conditions | JVM, via a `TestDispatcher` deliberately interleaving coroutines, plus any physical-device confirmation deemed necessary once real concurrency patterns are known | |
| Result ordering | JVM | If multiple results can arrive out of submission order (e.g. from concurrent probes), ordering guarantees (or explicit lack thereof) need a test that would fail if ordering silently changed |

## 6. Testability architecture -- minimum seams only

Per this task's explicit instruction, this is the **minimum** set of
seams needed, extending existing patterns (Section 1) rather than
introducing new abstraction styles. No broad architecture rewrite is
proposed or implied.

| Seam | Form | Precedent it extends | Why it's needed |
|---|---|---|---|
| Clock | Plain function type, `() -> Instant` (or equivalent), constructor-injected | `AndroidNetworkMonitor`'s existing `now: () -> Instant` | Sample windows, jitter calculation, and staleness checks (Section 5) all need a controllable time source; this is the same pattern already proven in this codebase, not a new interface |
| Measurement provider | Small interface (one method per measurement type it's asked to perform, returning a raw result/failure) | Same shape as `SecureKeyValueStore`/DAO interfaces already in the codebase | Separates "how a probe is actually performed" (production: real sockets) from "what a probe returned" (test: a fake returning a configured result) -- this is what Section 3's fake network client implements |
| Network client | Small interface wrapping the actual socket/HTTP call a measurement provider issues | Same fakes-over-mocks convention as `FakeSecureKeyValueStore` | The layer Section 3's deterministic-delay/failure injection actually lives on; kept separate from "measurement provider" so provider-level logic (retries, result shaping) can be tested independently of transport-level fakes |
| Connectivity state | Whatever `AndroidNetworkMonitor`/`NetworkStateMapper` already expose (`NetworkState`, `Flow<NetworkState>`) -- **not a new seam**, a consumer of the existing one | `network:monitor`'s already-built `AndroidNetworkMonitor` | The measurement engine needs to know current connectivity to decide whether/how to measure (Section 5's offline-handling row); it should depend on the Phase 2 monitor's existing output type, not define its own competing connectivity model |
| Scheduler | Deferred -- **explicitly not resolved by this document** | N/A | Depends entirely on the `WorkManager`-vs-something-else decision the Phase 2 audit deferred (Phase 2 audit Section 6/11); designing a scheduler seam now would be speculating ahead of that decision, which this task's instructions explicitly prohibit ("do not add WorkManager merely because it might be useful") |
| Data source (persisting results) | Extends the existing DAO/repository pattern already used for `NetworkStateHistoryEntity` | `core:database`'s existing DAO + `FakeNetworkStateHistoryDao` | Measurement results need the same tested persistence pattern connectivity history already has; this is explicitly *not* a new persistence mechanism, just the existing one accepting a new entity type once that type is designed |

**Test-only code genuinely required, and why, kept to the minimum:**
fake implementations of the Measurement Provider and Network Client
interfaces above (following the `FakeSecureKeyValueStore` precedent),
living in each module's `src/test` source set. No test-only production
hooks, no test-only build flavors, and no reflection-based test
scaffolding are proposed -- the corruption-test reflection workaround
elsewhere in this codebase (`core:security`) was a last resort for a
specific undocumented Android internal with no public API, not a
pattern to generalize; nothing in the measurement engine's design
requires anything similar.

**Candidate test dependency, not yet added, requiring the same kind of
explicit decision as `WorkManager`:** `app.cash.turbine` (current
stable `1.2.1`, actively maintained, compatible with this project's
`kotlinx-coroutines-test` 1.11.0) for deterministically testing the
measurement engine's `Flow`-based outputs, the same way this codebase's
existing `Flow<NetworkState>` output would benefit from it. This is
named as a recommendation this task's own instructions require flagging
rather than silently adding -- it is a test-only dependency (no
production classpath impact) and is not decided here.

## 7. Preserving the OBSERVATION/MEASUREMENT/ESTIMATION/PREDICTION/RECOMMENDATION boundary in tests

This is a **testing requirement**, not a restatement of the Phase 2
audit's Section 7 definition (which this document assumes -- Section
0 above):

- Each category needs test coverage proving the *data model itself*
  keeps them distinct -- e.g., a test asserting that a value produced
  by the estimation path cannot be read back out through whatever
  accessor/type represents a directly-measured value, not just a test
  asserting the arithmetic for each category is individually correct.
- A specific, concrete regression this section exists to prevent:
  a future refactor collapsing all five categories into one
  `qualityScore: Double` field, because that would compile and might
  even produce numerically-reasonable-looking output while destroying
  the distinction this task's instructions call a "critical boundary."
  A test that would fail the moment such a collapse happened (e.g.,
  asserting the existence of separate, distinctly-typed fields/sealed-class
  cases for each category, not just testing the values within one
  generic type) is exactly the kind of test this section is asking for.
- This is directly testable at the JVM level (Section 2) once the data
  model exists -- it requires no Android dependency, no real network,
  and no physical device.

## 8. Explicit non-goals of this document (per task instructions)

Restated explicitly, since this task's instructions were specific
about them and a planning document is the place to make scope
boundaries impossible to miss on a later read:

- Does not implement the production measurement engine.
- Does not build measurement UI.
- Does not add Supabase, VPS infrastructure, community intelligence,
  monetization, or VpnService.
- Does not add `WorkManager` -- the scheduler seam (Section 6) is
  explicitly left unresolved pending that separate decision.
- Does not add any dependency beyond naming Turbine as a candidate
  (Section 6), which is not itself added by this document.
- Does not claim physical-device validation has occurred (Section 4 is
  a specification of what must be tested, not a report that it was).

## Executive summary

The measurement engine's test strategy is layered: pure arithmetic
(scoring, aggregation, edge cases) is fully JVM-testable today, once
that code exists, using the same pure-function convention
`NetworkStateMapper` already establishes. The process of taking a
measurement (timeouts, retries, cancellation, concurrency) is testable
deterministically via fake Measurement Provider and Network Client
seams, following this codebase's existing fakes-over-mocks convention
rather than introducing a mocking library. Real radio behavior, real
network transitions mid-measurement, and real Doze/OEM interaction
with an in-flight measurement remain physical-device-only, consistent
with and extending the Phase 2 audit's own findings. The riskiest
architectural mistake this document identifies is not a missing test
category -- it's the OBSERVATION/MEASUREMENT/ESTIMATION/PREDICTION/
RECOMMENDATION boundary quietly collapsing into one generic score
during a future refactor (Section 7), which is why that section asks
for a test that would specifically catch that collapse, not just tests
of each category's arithmetic in isolation.
