# AERIVA Phase 3B -- Android Measurement Capability Report

Research and platform-boundary document. Answers the single question
this phase was scoped around: what can AERIVA realistically measure on
modern Android without VPN functionality or unsupported privileged
access. Written from a fresh, independent read of the repository (`main`
at `e3f70a4a13e63b782c61601abadc2c36f65dbd73`) and fresh official-source
research -- not carried over uncritically from
`PHASE_2_ANDROID_PLATFORM_AUDIT.md`. Where this document's independent
research agrees with that audit, it says so and cites its own sources
rather than merely repeating the claim. Where it found something
stricter, missing, or newly changed, that is called out explicitly in
Section 3 ("Findings that refine or correct the Phase 2 audit").

## 1. Repository state actually found

Re-verified by direct inspection, not assumed from prior documents:

- `main` HEAD at task start: `e3f70a4a13e63b782c61601abadc2c36f65dbd73`,
  clean working tree.
- Modules unchanged since the Phase 2 audit: `app`, `core:common`,
  `core:model`, `core:result`, `core:logging`, `core:database`,
  `core:preferences`, `core:security`, `network:monitor`.
- **Correction to a Phase 1-era assumption:** `PHASE_1_VALIDATION_REPORT.md`
  (2026-09-11) stated no Gradle wrapper was committed. That is no longer
  true -- `gradlew`, `gradlew.bat`, and `gradle/wrapper/` are present in
  this checkout today, and `.circleci/config.yml`'s own comments confirm
  they were deliberately added and manually verified. This does not
  change this sandbox's own build capability (Section "Validation"
  below) -- Maven Central and Google's Maven repository are still
  outside this container's network allowlist -- but it means CI (both
  `.circleci/config.yml` and `.github/workflows/ci.yml`) can run a real
  build from this exact wrapper, which this sandbox cannot.
- `network:monitor`'s manifest still declares only
  `ACCESS_NETWORK_STATE`. **`INTERNET` is still not declared anywhere in
  the repository.** This is a direct, concrete blocker for every
  active-measurement capability below (latency, jitter, packet loss,
  throughput, DNS, HTTPS reachability all need a live socket) -- not
  requested by this task, and not added by this report, but stated
  plainly here because it is the single most load-bearing fact for
  Phase 3B's own main question.
- `network:monitor`'s existing code (`AndroidNetworkMonitor`,
  `NetworkMonitor`, `ConnectionStateRepository`, `NetworkStateMapper`,
  `RawCapabilitiesSnapshot`, `TransportConstantMapper`) is unchanged
  from the Phase 2 audit's own description: one
  `registerDefaultNetworkCallback` registration, observation-only, no
  active probing, no Wi-Fi/cellular signal reading.
- `core:model`'s `measurement` package (`Confidence`, `Freshness`,
  `MeasurementFailure`, `MeasurementNetworkContext`, `LatencyMeasurement`,
  `LatencyEstimation`, `LatencyPrediction`, `DerivedLatencyStats`,
  `ConnectivityRecommendation`) already exists, added in Phase 3A, with
  its own tests (`ConfidenceTest`, `FreshnessTest`,
  `MeasurementBoundaryTest`). This is the domain-model boundary Phase 3B
  builds a platform-capability answer *for* -- Phase 3B does not modify
  any of these types.
- No `WorkManager` dependency. No Supabase dependency. No `VpnService`
  usage anywhere. Confirmed by direct inspection, not carried over.

## 2. Research sources used this pass

Fetched fresh for this document (current as of this writing, September
2026), independently of what `PHASE_2_ANDROID_PLATFORM_AUDIT.md` cites:

- `developer.android.com` reference docs and cross-referenced
  third-party citations of them for:
  `ConnectivityManager.registerNetworkCallback`/`requestNetwork` (100
  outstanding-requests-per-UID limit, `TooManyRequestsException`),
  `TelephonyCallback.CellInfoListener` and `TelephonyManager.getAllCellInfo()`
  permission requirements, `ConnectivityDiagnosticsManager` and
  `ConnectivityDiagnosticsManager.registerConnectivityDiagnosticsCallback`
  (including its own eligibility restriction -- Section 3 below),
  Android 10 privacy changes (`getAllCellInfo()` and related APIs gated
  behind location permission), foreground-service-type documentation
  (`develop/background-work/services/fgs/service-types`,
  `.../fgs/changes`, `about/versions/15/behavior-changes-15`).
- Real production crash reports (a GitHub issue on
  `android/nowinandroid`, a Sentry-Java issue) cross-checked against the
  official `TooManyRequestsException` documentation, following the Phase
  2 audit's own precedent of not trusting reference-doc wording alone
  where a real observed failure is available.
- AOSP source (`source.android.com/docs/core/connect/connectivity-diagnostics-api`)
  for `ConnectivityDiagnosticsManager`'s own eligibility restriction.
- Current (2026) third-party coverage of Android 15/16 foreground-service
  and background-job-quota changes, cross-checked against the official
  `developer.android.com/develop/background-work/services/fgs/changes`
  page rather than relied on alone.
- Direct repository inspection (this session): Section 1 above.

Where a claim below matches long-stable platform behavior already
correctly documented elsewhere in this repository (e.g. Doze/App
Standby's existence, `WorkManager`'s ~15-minute periodic-work floor),
this document does not re-cite it -- it is restated as established fact,
consistent with `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own convention for
not manufacturing a citation for every sentence.

## 3. Findings that refine or correct the Phase 2 audit

Stated explicitly, per this task's instruction not to assume a prior
report is correct:

1. **`TelephonyCallback.CellInfoListener` requires BOTH
   `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION`, not fine location
   alone.** `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 3's cellular row
   states only `ACCESS_FINE_LOCATION`. The current official reference
   documentation for `TelephonyCallback.CellInfoListener` (API 31+, the
   push-based path that audit's own row recommends as push-preferred)
   states plainly: "Requires `Manifest.permission.READ_PHONE_STATE` and
   `Manifest.permission.ACCESS_FINE_LOCATION`." This is a real,
   independently-reproducible difference (multiple current official
   pages state the same "and," not "or"), not a restatement of the same
   finding in different words -- it changes the permission set a future
   cellular-signal feature must request from one runtime permission to
   two. `getAllCellInfo()`'s own Android 10 privacy-changes documentation
   lists it under the location-permission-gated API set without
   independently confirming or denying the `READ_PHONE_STATE` addition
   for that specific call path, so this finding is stated precisely for
   the API this report actually recommends (`TelephonyCallback`, per
   Section 4 below), not generalized further than the evidence supports.
2. **`ConnectivityDiagnosticsManager` is real, but silently unusable for
   an app like AERIVA as currently scoped.** Not mentioned in
   `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 3's capability matrix at
   all (it appears there only as one of three APIs sharing the
   100-request registration pool). This report's own research surfaced
   a materially relevant fact the Phase 2 audit did not: the API's own
   official documentation states "Only apps that offer network
   connectivity to the user should be registering callbacks... Apps
   considered to meet these conditions include: Carrier apps with active
   subscriptions, Active VPNs, WiFi Suggesters. Callbacks registered by
   apps not meeting the above criteria **will not be invoked**." AERIVA
   is none of those three things and has no current goal that would make
   it one. This is not a permission failure that throws a
   `SecurityException` a developer would notice during testing -- the
   registration call itself succeeds, and the callback is simply never
   invoked, silently. Section 4's capability matrix classifies this
   correctly as NOT RELIABLY AVAILABLE for AERIVA specifically, distinct
   from "not available on this API level" or "needs a permission."
3. **Android 16 adds a background-job-quota interaction this task's own
   instructions asked about ("background execution") that predates and
   is independent of the `WorkManager` decision already deferred by
   Phase 2/3A.** Per `developer.android.com/develop/background-work/services/fgs/changes`
   (current as of this research pass): on Android 16+, "Background jobs
   started from a foreground service now must adhere to their respective
   runtime quotas. This includes jobs scheduled directly with
   `JobScheduler`, as well as jobs created by other libraries like
   `WorkManager` or `DownloadManager`." This does not change any
   conclusion already reached (no goal currently needs a foreground
   service; `WorkManager`'s addition is still deferred, per Phase 2 audit
   Section 11 and Phase 3 test strategy Section 0) -- it is recorded
   here as a fact this report's research turned up, current-technology
   research being this task's own explicit requirement, not something
   this report is acting on.
4. **Everything else this report independently re-checked
   (`registerNetworkCallback`'s 100-per-UID ceiling and
   `TooManyRequestsException`; `dataSync`'s Android 15 six-hour cap;
   Android 14's foreground-service-type declaration requirement) matches
   `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s findings.** Stated as agreement
   arrived at independently, via this report's own citations in Section
   2, not as "trusted because a prior document said so."

## 4. Capability classification

Per this task's required categories and four-way classification.
"Method / API" cites the mechanism a real implementation would use.
"Evidence" is this report's own research (Section 2), not inherited
from Phase 2.

| # | Capability | Classification | Method / API | Evidence |
|---|---|---|---|---|
| 1 | **Latency** | SUPPORTED WITH LIMITATIONS | App-level TCP/UDP/HTTP round-trip timing via plain sockets (`java.net.Socket`/`HttpURLConnection`/OkHttp-equivalent). No raw ICMP without root on stock Android. | Not a privileged API -- Android does not gate an app's ability to open a socket and time a round trip. Limitation is architectural, not permissive: needs `INTERNET` (not yet declared -- Section 1), a real endpoint choice, and timeout/cancellation design (Section "Timeout/cancellation" below), not a platform restriction. |
| 2 | **Jitter** | SUPPORTED WITH LIMITATIONS | Derived (statistical) from a series of latency measurements -- no distinct API. | Same foundation as latency; accuracy is a function of sample count and cadence, both engineering choices, not platform gates. |
| 3 | **Packet loss** | SUPPORTED WITH LIMITATIONS | UDP probe train, counting responses within a timeout window; no dedicated Android API. | Real capability, but this report's own re-derivation agrees with the Phase 2 audit: without root/raw sockets, "lost" and "merely slow" are not cleanly distinguishable, and a probe train costs more (battery/data) than a single round trip -- both real constraints requiring conservative frequency policy, not a platform block. |
| 4 | **Throughput** | SUPPORTED WITH LIMITATIONS | Timed transfer of a known payload size against a controlled endpoint. | Highest-cost measurement in this table by a wide margin (real data transferred, real battery drawn); must default to infrequent, opt-in, Wi-Fi/unmetered-aware. This is a responsible-use design constraint this report is restating, not inventing. |
| 5 | **Network stability** | SUPPORTED | `ConnectivityManager.registerDefaultNetworkCallback` (already implemented -- `AndroidNetworkMonitor`), optionally combined with the derived-value layer (`core:model`'s `measurement` package, already scaffolded) for a stability signal over time. | Already-shipping code path; only `ACCESS_NETWORK_STATE` (already declared) is required for the observation layer itself. A *derived* stability score over repeated measurements inherits whichever underlying capability (1-4) it is built from. |
| 6 | **Network transitions** | SUPPORTED | Same `NetworkCallback` (`onAvailable`/`onLost`/`onCapabilitiesChanged`), already implemented with deliberate zero-debounce on loss events. | Already-shipping code path, `ACCESS_NETWORK_STATE` only. |
| 7 | **Wi-Fi characteristics** (SSID/BSSID/RSSI) | NOT RELIABLY AVAILABLE (today) / SUPPORTED WITH LIMITATIONS (if `ACCESS_FINE_LOCATION` is ever added) | `WifiManager.getConnectionInfo()` / `WifiInfo`. | `ACCESS_FINE_LOCATION` is required at every currently-supported API level for this data (current official Wi-Fi-permissions documentation) and is not currently requested anywhere in this repository. Classified NOT RELIABLY AVAILABLE as the repository stands today; would become SUPPORTED WITH LIMITATIONS (real-device-only fidelity; user can deny; location services must also be on) only after that permission is individually justified and added -- an explicit non-goal of this report, per its own instructions. |
| 8 | **Cellular characteristics** (signal strength/cell info) | NOT RELIABLY AVAILABLE (today) / SUPPORTED WITH LIMITATIONS (if both permissions are ever added) | `TelephonyCallback.CellInfoListener` (API 31+, push-preferred) or `TelephonyManager.getAllCellInfo()` (poll, with an API-29+ cached-unless-`requestCellInfoUpdate()`-and-rate-limited caveat). | Requires **both** `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` (Section 3, finding 1) -- a stricter bar than the Phase 2 audit stated, and neither is currently requested. Real signal values are additionally real-device/OEM-dependent regardless of permission state. |
| 9 | **DNS responsiveness** | ESTIMATED | App-level timed `InetAddress`/socket-level DNS resolution -- no dedicated Android API. | Not a direct measurement of "DNS server performance" -- OS- and carrier-level DNS caching mean a timed lookup is an indirect proxy, not a controlled measurement of the resolver itself. Correctly modeled as an ESTIMATION-tier signal in this domain's own terms (`core:model`'s existing OBSERVATION/MEASUREMENT/ESTIMATION boundary), not a MEASUREMENT-tier one, even though technically it's an app-level timed operation like latency. |
| 10 | **HTTPS / network reachability** | SUPPORTED | A timed HTTPS request/response against a known, controlled endpoint (success/failure plus round-trip time) -- `HttpURLConnection`/socket-level TLS handshake and response. | Directly measurable, directly interpretable (reachable or not, and how long it took) -- not an estimate, not gated by anything beyond `INTERNET`. |

**Explicitly not claimed anywhere in this table, per this task's own
prohibitions:** no measurement of carrier signal quality beyond what the
cited Android APIs themselves expose; no claim that AERIVA can guarantee
lower ping; no claim that software can amplify a radio signal.
`ConnectivityManager.bindProcessToNetwork()`/`requestNetwork()` let
AERIVA route *its own* probe traffic onto a specific already-available
network when more than one is simultaneously up -- this is real and
useful for capabilities 1-4 when Wi-Fi and cellular are both available,
but it is not, and this report does not claim it is, a way to make
Android switch the device's default network or force a carrier handoff.

## 5. Android-version considerations

Confirmed this pass, current as of Android 16 (the project's
compileSdk/targetSdk):

| Version | Relevant to Phase 3B specifically |
|---|---|
| 12 (31) | `TelephonyCallback` replaces the deprecated `PhoneStateListener` path for cellular signal (capability 8). |
| 13 (33) | `NEARBY_WIFI_DEVICES` (`neverForLocation`) exists as a lower-friction alternative for *some* Wi-Fi APIs, but is API-33+-only against this project's minSdk 26 -- would need a fallback path, not a replacement, if ever pursued. Not itself sufficient for full `WifiInfo` (SSID/RSSI) access per current documentation. |
| 14 (34) | Foreground-service type declaration becomes mandatory for any FGS -- not directly triggered by anything in this table (capabilities 1-10 are all instantaneous/short-lived app-level operations, not long-running services), but directly relevant the moment a *scheduling* mechanism for these probes is designed (deferred, per Section 7 below). |
| 15 (35) | `dataSync`/`mediaProcessing` FGS types gain a ~6-hour/24-hour execution cap; `BOOT_COMPLETED` foreground-service launch restrictions tighten. Not triggered by capabilities 1-10 directly, same reasoning as 14 above. |
| 16 (36, this project's compileSdk/targetSdk) | Background jobs started from a foreground service (including `WorkManager` jobs) must adhere to runtime quotas -- Section 3, finding 3. Relevant to the still-deferred scheduler decision, not to whether a single on-demand probe (capabilities 1-4, 9-10) can run. |

No version-specific change was found that blocks any capability in
Section 4's table outright -- version-gating in this table affects
*which exact API path* is used (e.g. `TelephonyCallback` vs.
`PhoneStateListener`), not whether the underlying capability exists.

## 6. Permissions

Restating Section 4's per-capability findings as a single project-wide
list, for direct comparison against `PHASE_2_ANDROID_PLATFORM_AUDIT.md`
Section 5:

- **Currently declared:** `ACCESS_NETWORK_STATE` only (unchanged).
- **Blocking every active-measurement capability (1-4, 9-10) today:**
  `INTERNET` -- normal, install-time, zero user-facing friction, but
  genuinely absent and genuinely required. This is this report's single
  most actionable, low-risk finding.
- **Blocking capability 7 (Wi-Fi characteristics):**
  `ACCESS_FINE_LOCATION` -- runtime, high-scrutiny, individually
  justified per `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 10 (this
  report does not re-litigate that justification requirement, only
  confirms the permission is still the correct and still-unmet gate).
- **Blocking capability 8 (Cellular characteristics):** both
  `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` -- Section 3 finding 1's
  correction to the Phase 2 audit.
- `ACCESS_WIFI_STATE` is the normal permission `WifiManager` itself
  requires for most calls, in addition to (not instead of) the location
  permission above, for the same capability-7 feature, if ever pursued
  -- consistent with, and not contradicted by, the Phase 2 audit.

## 7. Background execution / scheduling

**Explicitly deferred, not decided by this report, matching
`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 6's own
"scheduler seam" deferral and `PHASE_2_ANDROID_PLATFORM_AUDIT.md`
Section 11's `WorkManager`-only-before-goal-G stance:** none of
capabilities 1-4 or 9-10 inherently require a foreground service or
`WorkManager` to run *once*, on demand (e.g. user opens the app and taps
"check now"). Running any of them *periodically without the user
opening the app* is exactly the goal that already-deferred decision
gates, and this report does not add `WorkManager` as a dependency,
consistent with this task's own instruction not to add it unless
research proves it required -- research did not surface a requirement
Phase 2/3A hadn't already found.

## 8. Battery/data implications

Consistent with, and not contradicted by, `PHASE_2_ANDROID_PLATFORM_AUDIT.md`
Section 9: latency/jitter (single small round trips) are cheap enough to
run relatively often; packet loss (a probe train) costs more; throughput
(a real data transfer) costs the most by a wide margin and must default
to infrequent/opt-in/unmetered-aware. DNS-timing and HTTPS-reachability
checks are single small requests, comparable in cost to a latency probe.
No new platform-level battery/data fact was found this pass beyond what
Phase 2 already established -- this section exists to confirm that,
not to introduce a new number.

## 9. Measurement limitations (summary, cross-referencing Section 4)

- **Packet loss** cannot cleanly distinguish "lost" from "very slow"
  without root/raw sockets.
- **DNS responsiveness** is confounded by OS/carrier DNS caching --
  correctly an ESTIMATION-tier signal, not a MEASUREMENT-tier one.
- **Wi-Fi/cellular characteristics** are gated behind permissions not
  currently requested, and even once granted, real radio values are
  real-device/OEM-dependent in ways no emulator or documentation
  research can substitute for (consistent with
  `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13, not re-derived here).
- **Throughput** measures AERIVA's own transfer to a chosen endpoint,
  not a device-wide or carrier-wide throughput ceiling -- a result is
  only ever evidence about the specific path measured, not a general
  claim about "the network."

## 10. Recommended architecture boundary

Extends, does not replace, `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`
Section 6's already-defined seams (Clock, Measurement provider, Network
client, Connectivity state, Data source) and `PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md`'s
already-defined tiers. This report's own contribution, accompanying it
in this same commit (Section 12): a **capability-classification layer**
-- pure Kotlin, no Android import, taking `(sdkInt, grantedPermissions)`
and returning Section 4's classification per capability. This sits
*above* the Measurement Provider seam (it answers "should a provider
even be invoked for this capability right now," not "how does a probe
work") and *below* any future UI/recommendation layer that would need to
explain to a user why a given metric isn't available yet.

## 11. Recommended provider interfaces

**Not implemented by this report** (an explicit non-goal, Section 12) --
named here as the natural next step once Phase 3B's open decisions
(Section 13) are resolved, extending
`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 6's
already-defined "Measurement provider" and "Network client" seam shapes
with the specific per-metric surface this report's research supports:

- A `LatencyProvider`/`ThroughputProvider`/etc.-shaped interface per
  measurement type (Section 4, capabilities 1-4, 9-10), each returning
  `core:model`'s existing measurement-tier types
  (`LatencyMeasurement.Succeeded`/`Failed`, or the equivalent type once
  jitter/packet-loss/throughput get their own `core:model` types beyond
  Phase 3A's illustrative latency-only skeleton -- Phase 3A Section 19
  explicitly scoped itself to one metric, not full coverage).
- Each provider implementation should consult this report's
  `MeasurementCapabilityClassifier` (Section 12) before attempting a
  probe, so a provider never attempts (and never has to separately
  reimplement the logic for refusing) a capability this report already
  classifies as NOT RELIABLY AVAILABLE.

## 12. What accompanies this report

Per this task's own allowance ("if you identify a small, clearly
isolated platform abstraction that should exist in code, you may
implement it on your dedicated branch. Do not implement the complete
measurement engine"):

- `network/monitor/src/main/kotlin/com/aeriva/network/monitor/MeasurementCapabilityClassifier.kt`
  -- `MeasurementCapability` (the 10 categories above, as a closed
  enum), `CapabilityClassification` (the four-way result, as a closed
  sealed type carrying a required `reason` on every non-`Supported`
  case), and `MeasurementCapabilityClassifier.classify(...)`, a pure
  function from `(capability, sdkInt, grantedPermissions)` to Section
  4's classification for that capability. No Android import, following
  `NetworkStateMapper`'s existing established convention in this exact
  module -- `Build.VERSION.SDK_INT` and `checkSelfPermission` results
  are read by a future Android-dependent caller and passed in as plain
  values, not read by this file itself.
- `network/monitor/src/test/kotlin/com/aeriva/network/monitor/MeasurementCapabilityClassifierTest.kt`
  -- JVM unit tests, no instrumentation needed, covering: the
  INTERNET-gated capabilities with and without that permission; the
  packet-loss/throughput "supported with limitations" cases; the
  network-stability/transitions already-supported case;
  Wi-Fi-characteristics single-permission gating; cellular-characteristics
  **both-permissions-required** gating (asserting each single permission
  alone is insufficient, the specific correction in Section 3 finding
  1); and one structural test asserting every `MeasurementCapability`
  case produces a classification, so a future case added to the enum
  without a matching `classify()` branch fails to compile rather than
  silently falling through.

**Explicitly not implemented, per this task's own instructions:** no
measurement engine (no socket opened, no probe sent, no timing logic),
no UI, no Supabase, no `WorkManager` (Section 7), no `INTERNET`
permission added to the manifest (Section 6 states it is required;
adding it is a manifest change this report's own scope -- "a small,
isolated platform abstraction," not "add a permission and start probing"
-- does not cover, and it would be premature ahead of the provider work
in Section 11 that would actually use it).

## 13. Security/privacy considerations

Consistent with, and not contradicted by, `PHASE_2_ANDROID_PLATFORM_AUDIT.md`
Section 10:

- Nothing this report classifies as SUPPORTED or SUPPORTED WITH
  LIMITATIONS (capabilities 1-6, 9-10) touches location or any other
  sensitive permission category -- `INTERNET` and `ACCESS_NETWORK_STATE`
  are both normal, install-time permissions with no runtime consent
  dialog.
- Capabilities 7-8 (Wi-Fi/cellular characteristics) are the only two
  gated by high-scrutiny runtime permissions, and this report does not
  request them or change their justification status -- they remain
  individually-justified, not-yet-met gates, exactly as Phase 2 audit
  Section 10 already established.
- `MeasurementNetworkContext.wifiRssi`/`cellularSignalStrength`
  (`core:model`, added in Phase 3A) remain correctly nullable and
  unpopulated -- this report does not populate them, consistent with
  Phase 3A's own Section 18 open-decision item 4.

## 14. Physical-device validation requirements

Restating and narrowing `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13
to what Phase 3B's own capability table (Section 4) specifically
depends on, not repeating that section's full list:

- Capabilities 1-4 (latency/jitter/packet-loss/throughput): the
  socket-level *mechanism* needs no physical device to validate (a JVM
  or emulator with real network egress is sufficient, per
  `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 4/5's own
  JVM-vs-instrumented split), but real-world values (actual latency over
  real Wi-Fi/cellular, actual throughput ceilings) are only meaningful
  measured on real hardware over a real connection -- a claim about "how
  fast is this probe on an emulator's virtual network" would not be a
  claim about anything a user experiences.
- Capabilities 7-8: real Wi-Fi RSSI and real cellular `CellInfo` values
  are real-radio/OEM-dependent and cannot be produced by an emulator at
  all (Phase 2 audit Section 13, not re-derived here) -- this is the
  single largest physical-device dependency in this entire report.
- Capability 9 (DNS): real-world carrier/OS DNS caching behavior
  (relevant to why this is classified ESTIMATED, not MEASURED) is a
  real-network characteristic that can differ between an emulator's
  network path and a real device on a real carrier -- worth a
  physical-device spot check once a provider exists, though the
  classification itself (ESTIMATED) does not depend on that check.

## 15. Open questions

1. When should `INTERNET` actually be added to the manifest -- as its
   own small, isolated commit ahead of any provider work (Section 11),
   or bundled with the first provider that actually needs it? This
   report states the permission is required (Section 6) but does not
   decide the sequencing.
2. Phase 3A Section 18's open decisions (in particular item 1,
   `NetworkQuality`'s relationship to the richer measurement lineage,
   and item 6, where the Measurement Provider/Network Client seams
   physically live) remain open and are prerequisites for Section 11's
   provider interfaces, unchanged by this report.
3. Given Section 3 finding 1 (cellular characteristics now require two
   permissions, not one), does capability 8 remain worth pursuing at the
   same priority Phase 2's original scoping implied, given the
   now-confirmed higher permission cost? This report states the fact;
   it does not make that prioritization call.
4. Given Section 3 finding 2 (`ConnectivityDiagnosticsManager` is
   silently unusable for AERIVA as currently scoped), is there a future
   AERIVA goal that would make AERIVA an "active VPN" or "Wi-Fi
   suggester" in the platform's own sense -- and if not, should this API
   be explicitly marked out-of-scope in a future document, so it does
   not get silently reconsidered as if it were a general-purpose
   diagnostics API?

## Validation

**No Gradle build or test run was executed by this report, and none is
claimed.** Per this repository's own established, unchanged constraint
(first documented in `PHASE_1_VALIDATION_REPORT.md`, re-confirmed
directly this session): this sandbox's network egress allowlist does
not include Maven Central or Google's Maven repository (confirmed again
this session against this container's own configuration), so no Gradle
invocation here can resolve `kotlin-stdlib`, JUnit, or any AndroidX
dependency, regardless of how small the change is. This is unrelated to,
and not fixed by, this repository now having a committed Gradle wrapper
(Section 1) -- the constraint is this sandbox's network policy, not the
repository's build configuration.

What was actually done to reduce risk given that constraint:

- Brace/parenthesis balance was checked mechanically for both new files
  (matched, 16/16 and 11/11 respectively for the production file;
  45/45 parens for the test file) -- a weak but real, honestly-reported
  signal, not a substitute for compilation.
- Both files were re-read in full after writing, checking every
  reference (`MeasurementCapability.entries`, sealed-type case names,
  constant names) against what the other file actually declares.
- The production file imports nothing beyond the Kotlin standard
  library (no `android.*`, no `core:model`/`core:result` types), so it
  cannot fail to compile due to a cross-module dependency being wrong --
  the only compilation risk is a Kotlin syntax error within the file
  itself, which the brace-balance check and manual re-read above were
  aimed at.
- This project's own CI (`.circleci/config.yml`, confirmed present and,
  per its own comments, using the now-committed wrapper directly) will
  run against this branch once pushed. This report does not claim to
  have seen that result -- `circleci.com`/CircleCI's API is also outside
  this sandbox's network allowlist, so this report cannot check CI
  status from within this session either. **A human (or a future session
  with CircleCI access) must confirm the actual CI result before this
  code is considered validated**, per this task's own instruction not to
  claim success on the strength of code merely "looking correct."

## Executive conclusion

Nothing in AERIVA's stated measurement goals is platform-impossible.
Six of ten capabilities (latency, jitter, packet loss, throughput,
network stability, network transitions) are reachable today with only
the `INTERNET` permission not yet declared as the blocker -- a normal,
zero-friction permission, not a policy or design obstacle. DNS
responsiveness is real but correctly belongs to the ESTIMATION tier this
codebase's own domain model already distinguishes, not the MEASUREMENT
tier. HTTPS/network reachability is directly measurable today under the
same `INTERNET` gate. Wi-Fi and cellular characteristics remain the two
genuinely high-friction capabilities, gated behind runtime location
permissions this report does not request -- and, per Section 3's own
correction, cellular characteristics specifically now need to be
understood as a two-permission ask, not one. The most concrete
new finding this independent pass surfaced beyond Phase 2's own audit is
that `ConnectivityDiagnosticsManager`, while a real platform API, is not
actually usable by an app in AERIVA's current position at all --
silently, not via an exception -- which this report records so it is
never mistaken for a viable path later.
