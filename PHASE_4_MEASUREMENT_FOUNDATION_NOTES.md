PHASE 4 MEASUREMENT FOUNDATION: IMPLEMENTATION NOTES

Branch: phase-4-measurement-foundation
Base: phase-3b-measurement-engine @ 727b2a91d2724735c3f22965ca72cf4311202370
(the correct Phase 3B integration state, per this change's own
instructions -- not main, which does not yet carry the classifier,
NetworkClient interface or engine). main is untouched by this change.

Built against PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md
(branch phase-4-cross-cutting-decisions @ 8a56dc70), read in full before
writing any code. Decision references below (D1 to D5, DN-n) are that
document's own numbering.

SCOPE ACTUALLY BUILT: slice S1 (JVM-only migration) in full, plus the
D2-4 endpoint configuration seam that S2 needs. Nothing in this change
requires OkHttp, INTERNET, or any other dependency or permission -- it
compiles and tests entirely as plain Kotlin, matching the contract's own
"Ordering rule: S1 and S2 can proceed now" (Section 10).

WHAT WAS IMPLEMENTED

core:model (Android-free):
- MeasurementStage.kt: new enum, Decision D5-3.
- ProbeEvidence.kt: ProbeEvidence, AddressFamily, ConnectionState,
  PhaseTimings, Decision D4-1.
- MeasurementMethod.kt: the six registered method-id constants, Decision
  D3-7/D4-2.
- DerivedJitterStats.kt: JitterDefinition and DerivedJitterStats.from(),
  Decision D4-3/D4-4, with its own confidence function over pair count
  (not latency's function reused, per D4-4's explicit prohibition).
- MeasurementFailure.kt: extended to the full Decision D5-4 taxonomy
  (DnsFailure, EndpointFailure/TlsFailure/InvalidResponse each gaining a
  defaulted `kind` parameter, UnexpectedRedirect, CaptivePortalSuspected
  with CaptivePortalEvidence, NoResponseFromEndpoint,
  BlockedByDevicePolicy, Unclassified). Timeout became
  Timeout(stage: MeasurementStage), the one breaking change in this set.
- LatencyMeasurement.kt: Succeeded and Failed each gained a trailing,
  defaulted `evidence: ProbeEvidence? = null` (Decision D4-1) -- additive,
  not breaking.
- NetworkState.kt: gained captivePortalReported, vpnPresent,
  blockedByDevicePolicy (Decision D4-8). See the addendum below --
  these ended up defaulted to false after real CI caught a call site
  this session missed, not left non-defaulted as first written.

network:monitor:
- NetworkStateMapper.kt: computes captivePortalReported (from the
  existing CAPTIVE_PORTAL raw capability name) and vpnPresent (from the
  existing transports set) with no new RawCapabilitiesSnapshot field
  needed. Gained a `blocked: Boolean = false` parameter on
  buildNetworkState so a future change can wire the real platform
  callback without a second signature change (see deferred work below).
- MeasurementEndpointConfig.kt: the Decision D2-4 seam. `current` is
  `null` and stays null in this change -- see stop conditions below.
- LatencyMeasurementEngine.kt, LatencyMeasurementEngineTest.kt,
  MeasurementBoundaryTest.kt: the one mechanical, fully-verified fix each
  needed for MeasurementFailure.Timeout's new stage parameter. Each site
  was located by direct grep against the actual branch content, not
  assumed -- three occurrences total, all updated the same way
  (MeasurementFailure.Timeout(MeasurementStage.Unknown), matching
  Decision D5-10's own text for the engine's outer backstop: "if it
  fires, yields Timeout(Unknown) and a defect log").

Tests added: MeasurementFailureTaxonomyTest (exhaustive-when compile
proof over all twelve cases, plus targeted assertions), DerivedJitterStatsTest
(eleven cases: empty, single-sample, adjacency rules for method/network-
handle/warm-state, ordering rejection, confidence tiering, id ordering),
MeasurementMethodTest (registry structure, confirms the retired
"tcp-round-trip" string is not in it), MeasurementEndpointConfigTest
(confirms no endpoint was invented), and nine new cases appended to the
existing NetworkStateMapperTest (all previous cases kept verbatim and
unchanged).

WHY EndpointFailure/TlsFailure/InvalidResponse GAINING A `kind` PARAMETER
IS NOT A BREAKING CHANGE: each new parameter is trailing and defaulted.
The engine's own three positional construction sites
(EndpointFailure(outcome.reason), TlsFailure(outcome.reason)) were
checked directly and still compile with the new parameter defaulting to
UNSPECIFIED.

DEFERRED, NOT BUILT, AND WHY (this is the stop-condition report this
task asked for)

1. INTERNET permission (Decision D1) and the OkHttp-based production
   NetworkClient (Decision D3), i.e. slice S3, were not built in this
   change, despite this task's own instructions listing INTERNET as
   step 1. Reason: Decision D5-11's NetworkClient contract evolution
   (ProbeRequest, the new deadline/network-pinning/evidence-carrying
   NetworkClientOutcome shape) is a genuine breaking change to the
   existing NetworkClient interface, NetworkClientOutcome's four
   existing cases, FakeNetworkClient, ReferenceLatencyProbeExecutor, and
   the engine's own probe call site -- none of which this task's
   instructions authorized touching ("Preserve the existing NetworkClient
   interface... Preserve existing public APIs unless the contract
   explicitly authorizes a change"). Building OkHttpNetworkClient against
   the *old* four-case NetworkClientOutcome would contradict Decision
   D5-6's own L1 outcome design (DnsFailed, ConnectFailed, TlsFailed,
   HttpUnexpected, ResponseTooLarge, TimedOut(stage), NoReply, Blocked,
   Unclassified). Attempting the full breaking migration in this same
   change, across roughly six existing production and test files, with
   no local Gradle/compiler available in this sandbox to verify any of
   it (confirmed: Maven Central and Google's Maven repository are not
   reachable from this environment, the same constraint every prior
   AERIVA document in this repository already records), was judged too
   large a risk of silently shipping a broken engine against this task's
   own explicit requirement that "existing Phase 3B engine tests remain
   green." This is a TODO/decision marker, not a silent omission: the
   next slice should do the NetworkClient/ProbeRequest migration and the
   OkHttpNetworkClient implementation together, as one reviewable,
   CI-verified change, exactly as Decision D5-11 and Section 10's slice
   plan (S3, S4) already describe. The NetworkState call site this
   session missed (see addendum below) is a concrete demonstration of
   exactly this risk materializing even on the smaller, JVM-only slice
   this change did attempt -- a further reason not to attempt the much
   larger NetworkClient migration in the same pass.

2. Real OkHttp version research WAS done and is recorded here for that
   next slice, so it does not have to repeat it: OkHttp 5.5.0's
   `okhttp-android` artifact declares `minCompileSdk = 37`
   (mvnrepository.com/artifact/com.squareup.okhttp3 and independently
   confirmed via two real-world AAR-metadata build failures against
   `compileSdk 36`, the exact failure class this repository's own
   libs.versions.toml comments already document for coreKtx 1.19.0).
   This repository is pinned to compileSdk 36 (network:monitor's own
   build.gradle.kts). OkHttp 5.4.0 (released 2026-06-09) is the newest
   release whose `okhttp-android` artifact is compatible with
   compileSdk 36. Neither version was added to libs.versions.toml or
   any build.gradle.kts in this change -- OD-7 (dependency admission)
   was not separately recorded as approved, and D3.11's admission
   checklist has other unverified items this session could not confirm
   (R8/ProGuard consumer rules, exact APK size delta, the merged-manifest
   AndroidX Startup provider delta).

3. INTERNET is a real, user-facing, security-relevant manifest change.
   Decision D1-8 itself says "The permission is added only after OD-1 is
   recorded (Section 12)" and Section 10's own ordering rule says slices
   that add a permission or dependency "wait for its owner gate." No
   commit or document in this repository separately records OD-1 as
   approved. Adding it in this change, bundled with an unrelated JVM-only
   migration, would also make it harder to review and revert
   independently if OD-1 is later declined (Decision D1-11: "If OD-1 is
   refused, Phase 4 becomes observation-only... No rework is needed" --
   true only if the permission was never bundled with unrelated work in
   the first place).

4. blockedByDevicePolicy is a real field on NetworkState now, but
   AndroidNetworkMonitor.kt does not yet call
   ConnectivityManager.NetworkCallback.onBlockedStatusChanged. Wiring it
   requires converting toNetworkState from a pure per-event mapper into
   a stateful fold (the callback fires independently of capability
   changes, so the monitor needs to remember the last-known blocked
   value across events). NetworkStateMapper.buildNetworkState already
   takes a `blocked` parameter so that follow-up work is a pure addition
   to AndroidNetworkMonitor.kt, not a second signature change here.

5. UdpProbeTrainMeasurement, ThroughputMeasurement, DatagramProbeClient:
   not built. Explicitly out of scope per this task's own quality rules
   ("No throughput implementation yet... No UDP implementation yet").

6. The merged-manifest allowlist CI check (Decision D1-6) and the
   `:app` dependency on `:network:monitor` (Decision D1-5): both are S3
   work, deferred with it.

STOP-CONDITION SUMMARY (as requested by this task)

- Production endpoint URL/host: not invented. MeasurementEndpointConfig.current
  is null.
- INTERNET permission and the dependency it requires (OkHttp): not
  added. OD-1 and OD-7 are not recorded as approved anywhere in this
  repository. Version research for OD-7 is recorded above so the next
  slice starts from evidence, not a guess.
- Everything else in this task's stop-condition list (endpoint hosting,
  endpoint auth, data budget, third-party endpoint authorization, minSdk
  change, telemetry retention, regional strategy, TLS pinning/signing,
  UI naming) was never reached, since this change does not touch the
  endpoint or the client.

RECOMMENDED NEXT ENGINEERING TASK

Get OD-1 (INTERNET) and OD-7 (OkHttp, pin 5.4.0 per the research above,
or explicitly re-verify 5.5.0 if the project moves to compileSdk 37/AGP
9.4 first, per Decision D1-8/Section 10's ordering rule) recorded as
owner decisions, then do slice S3 as one change: NetworkClient/
ProbeRequest migration (Decision D5-11), OkHttpNetworkClient (Decision
D3-10), the permission adapter (Decision D1-4), the INTERNET
declaration with its allowlist CI check (Decision D1-6), and the
consequential updates to FakeNetworkClient, ReferenceLatencyProbeExecutor
and the engine's own probe call site -- together, so CI proves the whole
seam compiles and the existing engine tests still pass in the same
change that breaks their old shape, rather than in two separately-broken
steps.


ADDENDUM: CI FEEDBACK AND FIX (first real CI run)

The first push (commit 9a28cc6) was checked against real CircleCI, not
assumed green. Result: build, static_checks, and connected_android_test
all succeeded (the module graph compiles and the instrumented suite
passed); unit_tests failed with a genuine compile error this session
had not caught: core/database/src/test/kotlin/com/aeriva/core/database/NetworkHistoryRepositoryTest.kt
constructs a NetworkState(...) fixture that this change had not located
-- core:database was not among the modules checked for NetworkState
construction sites before adding its three new non-defaulted
parameters.

Fix: captivePortalReported, vpnPresent, and blockedByDevicePolicy on
NetworkState now default to false, rather than requiring every call
site to supply them explicitly. NetworkStateMapper's two real branches
still compute genuine values and do not rely on the default -- only
call sites elsewhere in the codebase that predate this change (test
fixtures in other modules) benefit from it. This is a broader, safer
fix than patching the one discovered call site, since GitHub's code
search does not yet index this repository (returned zero results for
a direct query) and this sandbox cannot reach Maven Central to run a
real cross-module Gradle build to enumerate every call site with
certainty; defaulting the fields removes the whole class of missed-call-
site risk rather than relying on having found all of them by search.

A second CircleCI run against the fixed commit should be checked before
this branch is treated as done; see this task's own final report for
that run's actual result.
