# AERIVA Phase 3B -- Engine Test Gate Report

Independent gatekeeper review of AI 2's `LatencyMeasurementEngine`
(`phase-3b-measurement-engine` @ `727b2a9`), performed on a new branch
built from `main`, per this task's explicit instructions. Neither
`phase-3b-measurement-tests` nor `main` was modified to produce this
report. `LatencyMeasurementEngineTest.kt` (AI 2's own test file) was
brought in unmodified and is not edited anywhere in this branch; every
new test is in a separate file
(`LatencyMeasurementEngineGateTest.kt`), added independently.

## What was inspected

`git ls-remote origin phase-3b-measurement-engine` returned
`727b2a91d2724735c3f22965ca72cf4311202370` -- the dependency-integration
push this task said to wait for had landed. Fetched and read directly:
`LatencyMeasurementEngine.kt` (production, 319 lines) and
`LatencyMeasurementEngineTest.kt` (AI 2's own tests, 333 lines), both in
full, plus the full diff of every other file the branch touches against
its stated canonical sources.

## Fidelity check: canonical seams were not forked

Before writing any test, every dependency this engine brings in was
diffed against its actual canonical source, not assumed correct because
the commit messages said so:

- `NetworkClient.kt`, `FakeNetworkClient.kt`, `DerivedLatencyStats.kt`,
  `ReferenceLatencyProbeExecutor.kt`, `MutableClock.kt` --
  **byte-identical** to `origin/phase-3b-measurement-tests`. No second
  `NetworkClient` was created; this task's explicit instruction not to
  create one is satisfied by inspection, not merely by the engine
  compiling against something with the same name.
- `MeasurementCapabilityClassifier.kt` -- **one substantive change**
  from `origin/phase-3b-android-contract`'s version: the Wi-Fi-capability
  comment now cites `NetworkCapabilities.getTransportInfo()` +
  `FLAG_INCLUDE_LOCATION_INFO` instead of the deprecated
  `WifiManager.getConnectionInfo()`. **This is exactly the defect this
  session's own `PHASE_3B_VALIDATION_AND_TEST_REPORT.md` found and
  classified REQUIRES FIX BEFORE INTEGRATION.** It has been fixed. The
  fix cites a `PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md` that
  **does not exist in this repository's history on any branch**
  (checked via `git log --all` and `git ls-tree -r` on every branch
  involved) -- the correction itself is right, but its citation is an
  orphaned reference to a document nobody pushed. Documented below as a
  DOCUMENTATION-ONLY traceability gap, not a code defect (the code
  comment is correct regardless of whether its cited source is
  reachable).

## Production code review (`LatencyMeasurementEngine.kt`)

Read directly, not inferred from its own class KDoc's claims:

- Never catches a non-`TimeoutCancellationException` cancellation --
  confirmed by reading `measure()`'s single `catch` clause. Genuine
  cancellation propagates unchanged and no value is ever returned for a
  cancelled attempt.
- Consults `MeasurementCapabilityClassifier` before every attempt, via
  an injectable function defaulting to the real classifier -- not
  hardcoded, not bypassed.
- Declines to attempt (`NoNetwork`) when `context.networkState.available`
  is false, before ever touching the network client -- matches
  `ReferenceLatencyProbeExecutor`'s and Phase 3A's
  observation-vs-failure distinction exactly.
- Uses `System.nanoTime()` (injectable, `elapsedNanos: () -> Long`) for
  the probe's own duration, and wall-clock `Instant` only for
  `measuredAt` -- this resolves Phase 3A Section 18's open item 2
  (monotonic-vs-wall-clock) for this engine specifically, with a
  concrete, sound justification (an NTP/DST adjustment mid-probe
  corrupting an `Instant`-subtraction duration). Not required to be
  resolved by this phase's instructions, and resolving it does not
  conflict with anything in scope -- noted as a REQUIRES DECISION item
  only in the sense that this specific resolution should be confirmed
  as the intended one when jitter/packet-loss/throughput engines are
  eventually built against the same pattern, not because anything here
  is wrong.
- `measureSeries` maps over requests with a plain `.map { measure(it) }`
  -- genuinely sequential in the coroutine sense (confirmed by this
  report's own new test, not merely by reading the code), consistent
  with its own KDoc's claim and this phase's "no unnecessary coroutine
  scopes" instruction.
- No `NetworkQuality.Measured(...)` is constructed anywhere in this
  file -- confirmed both by reading (zero occurrences) and by this
  report's own new test (Gap 2 below).
- **Architectural concern, not a defect**: `measureSeries` has no upper
  bound on `requests.size` anywhere in its production code. Nothing in
  any phase document requires one, so this is not asserted as wrong --
  but an unbounded caller-supplied list run fully sequentially inside a
  single suspend call is worth a deliberate decision (a cap, or an
  explicit "callers are responsible for bounding this") before this
  engine is driven by anything less disciplined than a hand-written
  test, particularly once a real scheduler exists. Flagged as
  ARCHITECTURAL CONCERN, not REQUIRES FIX.

## Independent test additions (`LatencyMeasurementEngineGateTest.kt`)

AI 2's own 14-test suite was read line by line before writing anything
new, specifically to avoid duplicating coverage that already exists. It
already covers, well: successful measurement, unavailable network,
capability-unavailable (both the default and an injected classifier),
transport failure (connection refused, TLS, network-changed, checked
together for distinct mapping), timeout, cancellation, malformed
payload, multi-sample aggregation, insufficient evidence (both
all-failed and empty-list), dispatcher usage (proof-by-hang), and
no-duplicate-execution. Three genuine gaps remained, found by comparing
its assertions against the production code's actual lines rather than
its own docstring's claims about itself:

1. **`valueMillis`'s real arithmetic had zero coverage.** Every test in
   AI 2's file uses that file's own default `elapsedNanos = { 0L }`, so
   `(elapsedNanos() - startNanos) / NANOS_PER_MILLI` was `0 / 1_000_000.0
   = 0.0` in literally every existing test -- an off-by-1000 unit error,
   an integer-division truncation, or a sign error in that exact line
   would have passed every test in the branch unchanged.
   `valueMillis_reflectsInjectedElapsedNanosSeam_notAlwaysZero` scripts
   a real, non-round pair (0 -> 1,500,000 ns) specifically so a
   truncation bug would produce a visibly wrong result (1.0) rather than
   a coincidentally-close one. **Result: PASSED on first CI run** --
   this is now positive evidence the conversion is correct, not merely
   unexamined.
2. **Nothing proved the engine never fabricates/overwrites
   `NetworkQuality`.** This phase's instructions explicitly named this
   ("no accidental generic NetworkQuality score generation").
   `measure_neverFabricatesOrMutatesEstimatedQuality` passes a
   `NetworkQuality.Measured(score = 77, label = "pre-existing-marker")`
   through and asserts the *same instance* comes back untouched.
   **Result: PASSED.**
3. **Sequential-vs-concurrent execution was claimed but never
   discriminated.** AI 2's own `measureSeries` test would pass
   identically whether requests ran one at a time or all at once.
   `measureSeries_executesRequestsSequentially_notConcurrently` scripts
   three 100ms delays and asserts the test scheduler's virtual time
   advanced by exactly 300ms (concurrent execution would show ~100ms).
   **Result: PASSED** -- confirms the KDoc's claim is actually true of
   the code, not just asserted by it.

Plus one test that documents rather than asserts a requirement:
`measureSeries_withManySamples_aggregatesAllOfThem_noCapEnforced` (50
requests, all accepted) demonstrates the no-cap architectural concern
above concretely rather than leaving it as an unverified claim.

## What was deliberately not added

- No second `NetworkClient`, no new mocking framework, no Android
  permission change, no `WorkManager`, no UI, no Supabase -- none of
  these were touched, consistent with this task's explicit boundaries.
- No production code was modified. The one thing that came close --
  the classifier's Wi-Fi comment -- was already fixed by AI 2's own
  integration commit before this branch existed; this report reviews
  that fix, it does not make it.
- No test in AI 2's own file was edited, weakened, or removed.

## CI evidence (real, not assumed)

Branch `phase-3b-engine-test-gate`, two commits: `de257f4` (AI 2's
pushed integration brought in unmodified) and `5e97017` (this report's
own four new tests added). CircleCI run `8a138bf2` (workflow
`5e7dc60e`) against `5e97017`:

| Job | Outcome |
|---|---|
| `build` | succeeded |
| `static_checks` | succeeded |
| `unit_tests` | succeeded |
| `connected_android_test` | succeeded |

`unit_tests`' artifact upload confirms both `LatencyMeasurementEngineTest.xml`
and `LatencyMeasurementEngineGateTest.xml` were generated and uploaded
alongside every pre-existing test class -- the new tests actually ran,
not merely compiled. No failure occurred on this branch's first push;
unlike `phase-3b-measurement-tests`' own history, no fix iteration was
needed here.

## Classification summary

| Finding | Classification |
|---|---|
| `NetworkClient`/`FakeNetworkClient`/`DerivedLatencyStats.from`/`ReferenceLatencyProbeExecutor` reused unmodified, no second `NetworkClient` | **TEST PASSED** (fidelity confirmed by diff, not assumption) |
| `MeasurementCapabilityClassifier` Wi-Fi citation fix (deprecated API corrected) | **TEST PASSED** / defect this session found earlier is now fixed |
| Fix cites a nonexistent `PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md` | **DOCUMENTATION ONLY** (traceability gap, not a code defect) |
| Cancellation/timeout separation, capability short-circuit, no-network short-circuit | **TEST PASSED** |
| Monotonic-duration/wall-clock-timestamp split | **TEST PASSED**, resolves a previously-open Phase 3A decision soundly |
| `valueMillis` arithmetic (previously untested with real values) | **MISSING TESTABILITY SEAM, NOW CLOSED** -- gap found, test added, passed |
| No accidental `NetworkQuality` fabrication | **TEST PASSED** |
| `measureSeries` genuinely sequential | **TEST PASSED** |
| `measureSeries` has no maximum-sample-count bound | **ARCHITECTURAL CONCERN** -- not a defect, worth a deliberate decision before a less-disciplined caller drives this engine |
| CI (all 4 jobs, real run) | **TEST PASSED**, evidence: CircleCI run `8a138bf2` |
| Physical-device/real-socket/real-DNS/real-TLS behavior | **ENVIRONMENTAL/CI LIMITATION** -- this sandbox and CircleCI's emulator both remain incapable of validating real-network values; unchanged from every prior report in this phase |

## Branch, commits, files changed

- Branch: `phase-3b-engine-test-gate`
- Commits: `de257f4` (base import, unmodified), `5e97017` (four new
  tests, this report's own work)
- Files changed by this report's own commit: one new file,
  `network/monitor/src/test/kotlin/com/aeriva/network/monitor/measurement/LatencyMeasurementEngineGateTest.kt`
- Not merged to `main`. `phase-3b-measurement-tests` untouched.
