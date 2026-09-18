# AERIVA Phase 3B -- Independent Validation and Test Report

Independent audit, not a construction task. Written by a different role
(AI 4, validation/reliability engineer) than the one that built
`phase-3b-measurement-tests` (also this session, Role 1) -- flagged
explicitly wherever this report evaluates that earlier work, since
self-review is weaker evidence than independent review and is named as
such throughout. Every finding below is evidence-first: a git command
actually run, a CircleCI job actually queried, a file actually read, or
a current official source actually fetched this session -- not carried
over from any prior document's word alone. Where evidence could not be
obtained in this environment, that is stated plainly as NOT EXECUTED or
NOT VERIFIED, not glossed over.

## Executive summary

Three branches were in scope; one does not exist. **AI 2's measurement
engine (`phase-3b-measurement-engine`, claimed commit `fe4651b`) is not
reachable from `origin` -- confirmed directly with `git ls-remote`,
which returned nothing.** The token-revocation story is corroborated by
this hard evidence, not just AI 2's own report. There is no engine code
in this repository to audit. This is the single BLOCKING finding of
this report for the "measurement engine" component of Phase 3B
specifically -- everything else audited below is either sound or has
non-blocking findings.

AI 3's work (`phase-3b-android-measurement` @ `fc004b6`, then
`phase-3b-android-contract` @ `a67b74d`) is thorough and, on independent
re-verification against current official Android documentation, mostly
accurate. **One genuine, citable defect was found this pass that AI 3's
own research missed**: the contract's Wi-Fi capability row cites
`WifiManager.getConnectionInfo()` as the mechanism to use, but that
method has been deprecated since API 31 (Android 12) in favor of
`WifiInfo` retrieval via `NetworkCapabilities.getTransportInfo()` --
and the modern path has its own additional requirement
(`NetworkCallback.Builder.setIncludeLocationInfo(true)`) that is not
mentioned anywhere in the contract and, if missed, would silently
return masked SSID/BSSID values even with the permission correctly
granted. Everything else independently re-checked this pass (the
`TelephonyCallback.CellInfoListener` dual-permission requirement, the
DNS-responsiveness reclassification, the domain-model compatibility
claims) held up.

Phase 3A's domain model is unchanged and, on direct re-inspection, its
tests genuinely exercise boundaries (exhaustive `when`, exact-instant
edge cases) rather than merely executing constructors -- one real,
concrete example of exactly that failure mode was found and already
fixed: `DerivedLatencyStats` had no construction function and no tests
at all before this session's Role 1 work.

The test foundation (`phase-3b-measurement-tests`) is validated by real
CircleCI evidence, not self-assessment: three actual runs, two of which
caught genuine bugs in the test code itself (both now fixed, root
causes preserved in commit history). All four CI jobs pass on its
current head. Its self-review is in the Test Foundation Review section
below, written with the same skepticism applied to the other branches.

**Nothing here blocks the test-foundation or Android-contract work
already merged into their own branches from standing as-is.** What
blocks *Phase 3B measurement-engine implementation* specifically is (a)
AI 2's inaccessible branch and (b) the Wi-Fi API defect, both listed in
Required Fixes.

## Repository state (re-verified this pass)

- `main` HEAD: `e3f70a4a13e63b782c61601abadc2c36f65dbd73` -- confirmed
  unchanged (`git fetch --prune` + `git log`), matching every prior
  document's claim. **Not touched by this report or any branch audited
  in it.**
- `origin/phase-3b-measurement-tests` -- exists, HEAD `8cbab34` (doc-only
  commit on top of code-complete `35f3fcb`). This session's own Role 1
  work.
- `origin/phase-3b-android-measurement` -- exists, HEAD `fc004b6`.
- `origin/phase-3b-android-contract` -- exists, HEAD `a67b74db27dc4cbb8a0ff959c9cc0491f5de74f7`,
  confirmed based on `fc004b6` exactly (`git merge-base` output matches
  the claimed base precisely).
- `origin/phase-3b-measurement-engine` -- **does not exist.**
  `git ls-remote origin phase-3b-measurement-engine` returned empty
  output. No commit `fe4651b` is reachable from any ref on `origin`.
- `origin/phase-3b-validation` -- this report's own branch, created from
  `main`, `main` unmodified underneath it.

## AI 2 implementation review

**BLOCKING -- nothing to review.** The branch this task names
(`phase-3b-measurement-engine`, commit `fe4651b`) is not present on
`origin`. This was independently confirmed with `git ls-remote`, not
merely assumed from AI 2's own report of a revoked token. Every item
this task's Section 2 asks to check --socket lifecycle, timeouts,
connection failures, DNS/TCP/HTTPS behavior, latency/jitter/packet-loss/
throughput calculation, resource cleanup, dispatcher usage,
cancellation, exception handling, partial failures, empty/malformed
results, concurrent/repeated execution, network-change handling,
metered-network/battery/data implications, and specifically whether
timeout or slow-response is ever silently treated as packet loss -- is
**NOT VERIFIED, reason: no code exists in this repository to inspect.**
This is not a judgment on the quality of AI 2's local, unpushed work,
which this report has no access to and makes no claim about.

## AI 3 implementation review

### Original capability classifier + test (`fc004b6`)

Read in full this pass (both `MeasurementCapabilityClassifier.kt` and
`MeasurementCapabilityClassifierTest.kt`; the 481-line capability report
document itself was cross-referenced via the contract's own citations
and this session's independent web research rather than read
cover-to-cover a second time -- noted here rather than silently implied).

- **Structure: PASS.** Closed `enum class MeasurementCapability` (10
  cases) and a closed `sealed interface CapabilityClassification`
  (`Supported`/`SupportedWithLimitations`/`Estimated`/`NotReliablyAvailable`),
  every non-`Supported` case requiring a `reason: String` -- matches
  this codebase's existing `MeasurementFailure`/`AerivaError`
  convention of never returning an unexplained negative result. No
  Android import -- JVM-testable without instrumentation, matching
  `NetworkStateMapper`'s convention. `classify()`'s `when` has no `else`
  branch -- exhaustive by construction, the same compile-time-enforced
  pattern `MeasurementBoundaryTest` (Phase 3A, see below) uses.
- **`TelephonyCallback.CellInfoListener` requires both
  `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` -- independently
  re-verified this pass** against current official reference
  documentation (`developer.android.com/reference/android/telephony/TelephonyCallback.CellInfoListener`,
  Added in API level 31: "Requires Manifest.permission.READ_PHONE_STATE
  and Manifest.permission.ACCESS_FINE_LOCATION"). **PASS**, matches the
  classifier and the contract exactly.

### New contract document (`a67b74d`, `PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md`, 549 lines, read in full)

**DNS classification correction (Supported -> Estimated): PASS.** The
defect described (DNS grouped under the same `Supported` branch as
latency/jitter/HTTPS) is real and independently confirmed present in
the pre-fix `fc004b6` classifier by direct reading, not taken on faith.
The fix is minimal (one `when` arm split out, no other branch touched)
and the reasoning is sound: a timed DNS lookup is genuinely confounded
by OS/carrier/resolver caching and cannot honestly be presented as a
direct measurement. The two new regression tests
(`dnsResponsiveness_withInternetPermission_isEstimated_notSupported`,
`dnsResponsiveness_withoutInternetPermission_isNotReliablyAvailable`)
directly target the fixed behavior and follow the existing per-capability
test pattern already used for every other entry. No existing test was
modified or removed.

**Permission requirements: PASS**, with one exception below.
`ACCESS_FINE_LOCATION` for Wi-Fi and jointly with `READ_PHONE_STATE` for
cellular are both independently confirmed correct against current
reference docs (Section above; Wi-Fi confirmed via the Android 10
privacy changes documentation and current `WifiManager` reference pages
found this pass).

**DEFECT (REQUIRES FIX BEFORE INTEGRATION): `WifiManager.getConnectionInfo()`
is deprecated (API 31+), and the contract does not say so.**
Independently found this pass, not present in any prior document:
current official Android platform/API-diff sources confirm
`WifiManager.getConnectionInfo()` was deprecated starting in API level
31 (Android 12). The current recommended mechanism is `WifiInfo` via
`NetworkCapabilities.getTransportInfo()`, obtained either through a
`NetworkCallback` (`onCapabilitiesChanged`) or synchronously via
`ConnectivityManager.getNetworkCapabilities(Network)`. Critically, to
receive the location-sensitive fields (SSID/BSSID) through that path at
all, the caller must register the `NetworkCallback` with
`setIncludeLocationInfo(true)` (the `FLAG_INCLUDE_LOCATION_INFO`
behavior) -- otherwise those fields come back masked
(`02:00:00:00:00:00`/`<unknown ssid>`) even with `ACCESS_FINE_LOCATION`
granted. None of this is mentioned in the contract's Section 3 table or
its Section 7 API-lifecycle table (which describes `getConnectionInfo()`
as "a single synchronous call" needing "no persistent registration" --
the modern, non-deprecated path is actually callback-shaped, which
would also fit this codebase's existing `AndroidNetworkMonitor`
`NetworkCallback` pattern better than a separate synchronous call
would). The underlying permission conclusion (`ACCESS_FINE_LOCATION`
required) is unaffected -- this is an outdated-API finding, not a wrong-
permission one, but it is exactly the kind of thing this task's research
requirement exists to catch, and it was missed. **Classification: REQUIRES
FIX BEFORE INTEGRATION** -- not blocking today (nothing has been
implemented against it yet), but the contract should be corrected before
anyone implements capability 7 against it, specifically to add the
`setIncludeLocationInfo(true)` requirement, which is easy to miss
silently.

**Android 16 Local Network Protections: ACCEPTABLE WITH FOLLOW-UP.**
The contract's "25Q2-26Q2 phased rollout" language is accurate and
traces directly to Google's own `about/versions/16/behavior-changes-16`
page, which frames it exactly that way. Independently re-searched this
pass, current sources (Android Developers Blog, `about/versions/17/behavior-changes-17`,
dated within the last several months) show the **mandatory-enforcement
phase specifically lands with Android 17** (apps targeting API 37+ have
local-network access blocked by default; the `ACCESS_LOCAL_NETWORK`
permission is enforced), not simply "later in Android 16." This doesn't
change the contract's actual conclusion -- AERIVA targets no
local-network addresses, so this remains inapplicable either way -- but
naming Android 17 specifically would be more precise than the current
quarter-only framing, especially since Android 17 was in public beta as
of the sources found this pass. Non-blocking; a documentation precision
item.

**Not independently re-verified this pass (time-scoped this review
toward the highest-stakes/highest-drift-risk claims): NOT VERIFIED.**
`ConnectivityDiagnosticsManager`'s carrier-app/VPN/Wi-Fi-Suggester
eligibility restriction (inherited from the Phase 2 audit, restated as
"re-confirmed" by the contract) and `PeriodicWorkRequest`'s 15-minute
minimum interval were not independently re-searched this session. Both
are long-standing, low-drift-risk platform facts and neither was
flagged as suspicious by anything found this pass, but this report does
not claim to have re-verified them and says so rather than implying
otherwise.

**Domain-model compatibility (contract Section 6): PASS**, verified
directly against this session's own firsthand reading of every relevant
`core:model` type during Role 1 (not re-derived from the contract's
description of them). `MeasurementNetworkContext` does already have
optional `wifiRssi`/`cellularSignalStrength`-shaped slots waiting,
unpopulated. `MeasurementFailure`'s existing cases (`Timeout`,
`EndpointFailure`, `TlsFailure`, `InvalidResponse`,
`NetworkChangedDuringMeasurement`) do cleanly support the "HTTP 5xx is
not the same as unreachable" distinction the contract claims. No
incompatibility found -- matches the contract's own conclusion.

**Boundary hidden requirements: PASS.** Reviewed the contract's own
Section 10 checklist and Task list directly -- nothing in it *adds*
`WorkManager`, a foreground service, `INTERNET`, location permissions,
Supabase, UI, or VPN; every mention of these is an explicitly-deferred
checklist item, consistent with this task's boundaries. No hidden
requirement found.

### CI evidence for `a67b74d` (real, queried this pass)

AI 3's own document states plainly that no Gradle build or test was
executed and that CI's result was unseen at time of writing. **That gap
is now closed with real evidence**: CircleCI run `dea0e52a` (triggered
automatically on push, before this report's branch existed) --
`build`, `unit_tests`, `static_checks`, and `connected_android_test` **all
succeeded**. This confirms the DNS fix and its two new tests actually
compile and pass, not merely that they look correct.

## Phase 3A domain model review

Read directly this pass: `Confidence`, `Freshness`, `LatencyMeasurement`,
`DerivedLatencyStats`, `LatencyEstimation`, `LatencyPrediction`,
`ConnectivityRecommendation`, `MeasurementFailure`,
`MeasurementNetworkContext`, plus their test files
(`ConfidenceTest`, `FreshnessTest`, `MeasurementBoundaryTest`,
`DerivedLatencyStatsTest`).

- **Constructor/API consistency: PASS.** Every measurement-tier type
  takes its clock reading (`measuredAt`/`calculatedAt`/`predictedAt`)
  as an explicit constructor parameter -- none calls `Instant.now()`
  internally. Consistent across every type checked.
- **Nullability: PASS.** `DerivedLatencyStats` had no way to be
  constructed from raw data before this session (see Test Foundation
  Review) -- a real gap, now filled, returning `null` explicitly for
  empty/all-invalid input rather than a divide-by-zero or a fabricated
  zero-confidence struct.
- **Units: PASS.** Every numeric field name carries its unit
  (`valueMillis`, `averageMillis`) -- no bare `Double`/`Long` timing
  value anywhere checked.
- **Time representation: PASS.** `java.time.Instant` used consistently
  everywhere checked; no raw epoch `Long` mixed in.
- **Network context semantics: PASS.** `MeasurementNetworkContext`
  wraps the single existing `NetworkState` type rather than defining a
  competing connectivity snapshot -- confirmed by direct reading, and
  independently corroborated by the contract's Section 6 finding
  (audited above) reaching the same conclusion separately.
- **Failure semantics: PASS.** `MeasurementFailure` is a closed sealed
  type (`Timeout`, `Cancelled`, `EndpointFailure`, `TlsFailure`,
  `InvalidResponse`, `NetworkChangedDuringMeasurement`) -- distinct
  cases for distinct failure modes, which is precisely the "timeout is
  not packet loss, slow is not lost" discipline this task's Section 2
  asks about. No engine exists yet to check for the actual conflation
  bug in practice (that requires AI 2's inaccessible work), but the
  *type* makes the conflation a compile-time-visible choice rather than
  a string comparison, which is the right foundation.
- **Confidence semantics: PASS**, and explicitly self-documented as
  illustrative rather than final (both in `Confidence.of`'s own KDoc and
  independently in this session's `DerivedLatencyStats.from` addition) --
  not overclaimed as validated production thresholds.
- **Freshness semantics: PASS.** `FreshnessTest`'s
  `isStaleAt_exactlyAtExpiry_isNotStale` is a genuine boundary test (the
  exact-instant edge), not just a constructor-execution test --
  confirmed by direct reading of the test file, not inferred.
- **Estimation-vs-measurement / prediction-vs-observation: PASS**, and
  concretely enforced, not merely described in prose --
  `MeasurementBoundaryTest` uses exhaustive `when` expressions (no
  `else` branch) over `LatencyMeasurement`, `LatencyEstimation`, and
  `ConnectivityRecommendation` specifically so that collapsing two tiers
  into one type would fail to compile. This is real evidence tests
  exercise meaningful boundaries here, confirmed by reading the test
  file directly rather than trusting its own class-doc claim.

**One real, now-fixed gap found this pass, attributable to this
session's own earlier Role 1 work, not to Phase 3A's original authors**:
`DerivedLatencyStats` existed with zero tests and no way to construct
one from raw measurements before this session added
`DerivedLatencyStats.from` and `DerivedLatencyStatsTest`. Recorded here
for completeness of the domain-model review, not hidden because it was
this session's own prior work.

## Test architecture review (repository-wide, not limited to either audited branch)

- **No mocking framework anywhere in this repository** -- confirmed by
  direct inspection of every `build.gradle.kts`/`libs.versions.toml`
  touched or read this session. Consistently fakes-based
  (`FakeSecureKeyValueStore`, `FakeNetworkStateHistoryDao`,
  `FakeNetworkClient`).
- **No `Thread.sleep`, no real socket/network/URL usage** in any test
  file this session touched or read -- confirmed by direct grep across
  `core/model`, `core/common`, and `network/monitor`'s test source sets,
  zero matches.
- **Virtual-time/cancellation misuse: found, and it happened in this
  session's own work, caught by real CI, not by code review.** Two
  concrete, real instances, both already fixed:
  1. `withTimeout(Long.MAX_VALUE / 2)` overflowed a nanosecond
     conversion internally, making a timeout intended to "never fire"
     fire immediately.
  2. `advanceUntilIdle()` used where `runCurrent()` was needed --
     `advanceUntilIdle()` ran straight through an intentionally-distant
     timeout deadline before a test's own `job.cancel()` was ever
     reached, silently converting a cancellation test into a
     already-covered timeout test.

     Both are exactly the kind of "Kotlin virtual time misrepresenting
     real time/cancellation semantics" failure mode this task's Section
     3 asks to look for. Since neither survived to this branch's final,
     CI-green commit, they are not currently BLOCKING, but they are
     documented here in full because they are a genuine, concrete
     illustration of the risk category -- see the Test Foundation
     Review section for the full account and evidence.
- **No new instrumented (`androidTest`) tests were added by either
  audited branch.** The existing single instrumented test
  (`AndroidNetworkMonitorInstrumentedTest`) continues to pass unchanged
  on both -- confirmed via CircleCI's `connected_android_test` job
  succeeding on both `35f3fcb` and `a67b74d`.

## Test foundation review

Self-review of this session's own `phase-3b-measurement-tests` work,
held to the same skepticism as AI 2/AI 3's work above, not a
rubber-stamp.

- **`NetworkClient`/`FakeNetworkClient`: PASS.** Zero Android
  dependency (JVM-testable), scripted delay/outcome injection, call/
  completion/cancellation counters for leak assertions. Correctly keeps
  timeout enforcement as the *caller's* responsibility
  (`withTimeout`), not the client's -- structurally prevents a future
  engine from conflating "the client reported a failure" with "the
  caller decided this took too long," which is the right foundation
  for avoiding the timeout=packet-loss conflation this task warns
  about, even though no packet-loss concept exists yet to actually
  test that conflation against.
- **`ReferenceLatencyProbeExecutor`: PASS, does not become a second
  production implementation.** Confirmed by its own file path
  (`network/monitor/src/test/...`, not `src/main`) -- it is not
  compiled into the app, and nothing in `src/main` references it.
- **Both real bugs found via actual CI runs, not review, with full
  root-cause documentation preserved in commit history rather than
  squashed** -- see Test architecture review above. This is the
  evidence-driven discipline this whole report is trying to hold every
  branch to, demonstrated against this session's own work first.
- **Objective 12 (duplicate measurements) -- ACCEPTABLE WITH
  FOLLOW-UP, explicitly caveated already.** The test foundation itself
  states plainly (both in `PHASE_3B_NETWORK_MEASUREMENT_TEST_PLAN.md`
  and in the test file's own comment) that it does not test
  retry-produced-duplicate de-duplication, because no retry logic
  exists anywhere to produce that scenario. Honest, not glossed over.
- **Objective 15 (battery/data-budget) -- DOCUMENTATION ONLY,
  correctly deferred.** No scheduler exists; nothing to test.
- **`testFixtures` migration: PASS, module boundaries intact.**
  `network:monitor`'s production `implementation` block is unchanged;
  the new `core:common` dependency is `testImplementation`-only.
  Confirmed both by direct diff review and by the fact that
  `connected_android_test` (which builds the release/debug app, not
  just tests) succeeded on CI -- a real boundary break would have
  failed that job, not just the unit-test job.
- **Is this foundation appropriate for validating a real measurement
  engine once one exists? Partially, and this is stated as a limit, not
  a strength.** It validates that the *seam pattern*
  (interface + fake + injected dispatcher/clock) is sufficient to
  deterministically test timeout, cancellation, concurrency, cleanup,
  and malformed-response handling on the JVM. It cannot and does not
  validate real socket/DNS/TCP/HTTPS behavior, because no such
  implementation exists in this repository to run it against --
  consistent with AI 2's branch being inaccessible. Whoever eventually
  implements the real engine is not required to reuse
  `NetworkClient`/`ReferenceLatencyProbeExecutor` verbatim, only to
  achieve equivalent, actually-run test coverage against whatever seam
  they do use.

## Android measurement contract review

(Consolidates the AI 3 section above into the classification format
this section specifically requires.)

| Finding | Classification |
|---|---|
| DNS classification fix (Supported -> Estimated) + 2 regression tests | **PASS** |
| `TelephonyCallback.CellInfoListener` dual-permission claim | **PASS** (independently re-verified against current reference docs) |
| `WifiManager`/cellular permission requirements themselves | **PASS** |
| `WifiManager.getConnectionInfo()` cited without noting API-31 deprecation + missing `setIncludeLocationInfo(true)` requirement | **DEFECT / REQUIRES FIX BEFORE INTEGRATION** |
| Android 16 LNP applicability conclusion (does not affect AERIVA) | **PASS** |
| Android 16 LNP quarter-only framing (enforcement phase is actually Android 17) | **ACCEPTABLE WITH FOLLOW-UP** (precision only, conclusion unaffected) |
| `ConnectivityDiagnosticsManager` eligibility claim | **NOT VERIFIED** (not independently re-searched this pass) |
| `PeriodicWorkRequest` 15-minute floor claim | **NOT VERIFIED** (not independently re-searched this pass; low risk) |
| Domain-model compatibility (Section 6) | **PASS** |
| Distinction between measurement/observation/estimate/prediction/recommendation | **PASS** (uses Phase 3A's existing type-level distinction, doesn't invent a new one) |
| Hidden requirement / boundary violation check | **PASS** -- none found |
| Open item: `NetworkQuality.Measured(score, label)` lineage | **REQUIRES DECISION** -- confirmed still open, correctly not resolved by this contract (Phase 3A Section 18 item 1, unchanged) |
| Open item: scheduler (`WorkManager`-vs-other) | **REQUIRES DECISION** -- confirmed still open (test strategy doc Section 6, unchanged), correctly restated rather than resolved |
| Open item: `INTERNET` permission sequencing | **REQUIRES DECISION** -- contract's own checklist states it should be added "as its own isolated change, before or alongside the first provider that needs it"; not added by this contract, correctly |
| Open item: location-permission justification | **REQUIRES DECISION** -- unchanged from Phase 2 audit Section 10's "specific, reviewable justification" requirement; not resolved here, correctly |
| CI evidence for `a67b74d` | **PASS** -- all 4 jobs green, real CircleCI run `dea0e52a` |

## Failure and edge-case matrix

No measurement engine exists in this repository (AI 2's branch is
inaccessible), so almost every row below describes *expected* behavior
per the domain model and contract's own stated design, not *observed*
behavior -- marked accordingly. Rows are the task's own list.

| Scenario | Expected behavior per domain model / contract | Verified how |
|---|---|---|
| No network | `MeasurementCapabilityClassifier`/context check should decline to attempt, per contract Section 10 checklist item "consults capability classifier before attempting" | Design-level only -- NOT EXECUTED, no engine to run |
| Wi-Fi/cellular disconnect mid-measurement | `MeasurementFailure.NetworkChangedDuringMeasurement` exists specifically for this | Type exists (verified) -- NOT EXECUTED against real behavior |
| Network transition mid-measurement | Same as above | Same |
| DNS failure / timeout | `MeasurementFailure` cases exist (`Timeout`, `EndpointFailure`) but no DNS-specific engine exists to confirm correct mapping | NOT VERIFIED |
| TCP connect timeout/refusal | Same as above | NOT VERIFIED |
| TLS failure | `MeasurementFailure.TlsFailure` exists, kept distinct from `EndpointFailure` by design (contract Section 3 HTTPS row) | Type exists -- NOT EXECUTED |
| HTTP failure / slow-but-successful response | Contract explicitly requires these stay distinct (5xx -> `InvalidResponse`, unreachable -> `EndpointFailure`/`Timeout`) -- no engine exists to confirm this is actually implemented that way | NOT VERIFIED |
| Partial measurement completion | Domain model supports per-measurement `Succeeded`/`Failed`, no partial-batch type reviewed this pass | NOT VERIFIED |
| Measurement cancellation | Test foundation proves the *pattern* works for one illustrative metric (this session's own `ReferenceLatencyProbeExecutorTest`) | PASS for the pattern; NOT VERIFIED for a real engine |
| Doze / battery restrictions | Contract Section 8 correctly defers; no scheduler exists | Correctly deferred, not testable yet |
| Metered connection | `NetworkState.metered` already exists and is cited correctly by the contract as the single source of truth | Field exists (verified) -- NOT EXECUTED |
| Roaming, VPN, captive portal, IPv4/IPv6-only, dual-stack, rapid transitions | All explicitly named in the contract's Section 5 physical-device list as requiring real hardware | Correctly identified as out of this environment's reach; NOT EXECUTABLE HERE |
| High latency/jitter, packet loss, low bandwidth | No jitter/packet-loss domain types exist yet (Phase 3A modeled latency only) | NOT APPLICABLE YET |

## Platform validation matrix

| Capability | JVM | Emulator | Physical device | Real network required | Permissions required | Known limitations |
|---|---|---|---|---|---|---|
| Latency/jitter (mechanism) | Yes (this session's test foundation proves it) | Partial (mechanism only) | Yes, for a meaningful value | Yes, for real value | `INTERNET` | Endpoint/method-specific, not general |
| Packet loss | Mechanism only, no domain type yet | Partial | Yes | Yes | `INTERNET` | Cannot cleanly distinguish lost from slow without raw sockets |
| Throughput | Mechanism only, no domain type yet | Partial | Yes | Yes | `INTERNET` | Highest data/battery cost; small payloads dominated by setup overhead |
| Network stability/transitions | Yes (already implemented, `AndroidNetworkMonitorInstrumentedTest`) | Yes | No (pure callback observation) | No | `ACCESS_NETWORK_STATE` | None beyond "reports what the platform reports" |
| Wi-Fi characteristics | No | No (virtual Wi-Fi) | **Yes, specifically** | No (local read) | `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE` | Real SSID/RSSI is real-radio-only; API itself deprecated (this report's own finding) |
| Cellular characteristics | No | No (synthetic/absent signal) | **Yes, specifically** | No (local read) | `READ_PHONE_STATE` + `ACCESS_FINE_LOCATION` | Real values OEM/radio-dependent; API 29+ poll may be cached |
| DNS responsiveness | Mechanism only | Partial | Partial (real caching behavior) | Yes | `INTERNET` | Confounded by multi-layer caching (now correctly classified Estimated) |
| HTTPS reachability | Mechanism only | Yes | Partial (real proxy/captive-portal behavior) | Yes | `INTERNET` | Single-endpoint result only |

## Permission/platform findings

Consolidated from the Android measurement contract review above:
`INTERNET`, `ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`,
`ACCESS_WIFI_STATE` are all correctly documented as eventually required
and correctly **not added** by either audited branch. The one concrete
platform-API finding (deprecated `getConnectionInfo()`) is listed in
Required Fixes.

## Security/privacy findings

Reviewed for what the design *would* expose once implemented, since
nothing is implemented yet to inspect directly:

- `MeasurementNetworkContext.wifiRssi`/`cellularSignalStrength` remain
  `null` in the current domain model -- confirmed by direct reading.
  Contract Section 10 checklist correctly requires this stay true until
  the permission decision is made in writing. **No current exposure.**
- IP addresses: any future active probe necessarily reveals the
  target's IP to AERIVA's own socket stack (inherent to any
  network-measurement feature) -- not a new risk this contract
  introduces, but worth naming since it wasn't explicitly called out in
  either document reviewed.
- Precise location: gated correctly behind `ACCESS_FINE_LOCATION`,
  not requested by anything currently.
- Timestamps as a tracking signal: `LatencyMeasurement`/`Freshness`
  types carry `Instant` timestamps by design; whether these are ever
  transmitted off-device (Supabase sync, explicitly out of scope for
  Phase 3B) is not yet designed, so this is a forward flag, not a
  current finding.

## Performance/battery findings

No benchmark numbers invented, per this task's instruction. The
contract's own cost ordering (latency/jitter cheapest, packet loss
next, throughput most expensive) is a reasonable engineering
expectation, not measured evidence, and the contract itself labels it
that way. No scheduler exists, so there is no actual frequency to
evaluate yet -- **NOT EXECUTED, nothing to measure**.

## CI/build evidence

| Branch | Commit | Build | Static checks | Unit tests | Connected (emulator) test |
|---|---|---|---|---|---|
| `phase-3b-measurement-tests` (initial) | `961b412` | pass | pass | **fail** (test bug, fixed) | pass |
| `phase-3b-measurement-tests` (attempt 1) | `252f919` | -- | -- | **fail** (same test, different bug) | -- |
| `phase-3b-measurement-tests` (final) | `35f3fcb` | pass | pass | **pass** | pass |
| `phase-3b-android-contract` | `a67b74d` | pass | pass | pass | pass |
| `phase-3b-measurement-engine` | `fe4651b` (claimed) | **NOT EXECUTED -- branch does not exist on origin** | -- | -- | -- |

Both green results above are from CircleCI runs this report directly
queried (`dea0e52a` for the contract branch, `cdca2769` for the final
test-foundation commit), not assumed from a push succeeding.

## Required fixes (blocking or near-blocking)

1. **AI 2's branch must actually be pushed and reachable before any
   measurement-engine review can happen.** Currently nothing exists to
   fix, audit, or integrate.
2. **`PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md`'s Wi-Fi
   row should be corrected** to cite the current
   `NetworkCapabilities.getTransportInfo()` path instead of the
   deprecated `WifiManager.getConnectionInfo()`, and to state the
   `setIncludeLocationInfo(true)` requirement explicitly -- before
   anyone implements capability 7 against the contract as currently
   written.

## Follow-up items (non-blocking)

- Name Android 17 explicitly in the LNP section for precision (does not
  change the "inapplicable to AERIVA" conclusion).
- Independently re-verify `ConnectivityDiagnosticsManager` eligibility
  and `PeriodicWorkRequest`'s 15-minute floor against current docs (not
  done this pass, low risk).
- Once a real `NetworkClient` implementation exists, write the
  retry-produced-duplicate de-duplication test this test foundation
  explicitly deferred (objective 12's caveat).

## Tests not executable in the current environment

Everything in the Failure/edge-case matrix and Platform validation
matrix marked NOT EXECUTED/NOT EXECUTABLE HERE above, plus anything
requiring a physical device, multiple OEMs, real cellular/Wi-Fi
networks, or a captive portal -- consistent with this sandbox's own
documented network/hardware constraints, restated here rather than
re-derived.

## Integration readiness conditions

Phase 3B measurement-engine work cannot proceed to integration until:
(1) AI 2's implementation is actually reachable on `origin` and
independently reviewed against this same evidence standard, and (2) the
Wi-Fi API defect above is corrected in the contract before anything is
implemented against it. The test foundation and the Android contract's
non-Wi-Fi findings are not blockers to continuing other Phase 3B work in
parallel.
