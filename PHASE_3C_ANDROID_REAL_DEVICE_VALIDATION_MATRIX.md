# AERIVA Phase 3C -- Android Real-Device Validation Matrix

Definitive physical-device validation matrix for AERIVA's network
measurement system. A specification of what must be tested and how to
interpret each result -- not a test run, not an implementation, and not
a re-audit of any other role's work beyond the narrow "are the Android
API assumptions still accurate" check this task requires.

## 0. Repository and branch state (re-verified this session)

- `main` HEAD: `e3f70a4a13e63b782c61601abadc2c36f65dbd73` -- confirmed
  unchanged, not touched by this document.
- This branch (`phase-3c-android-validation-matrix`) is based on
  `phase-3b-android-contract-review` @
  `27c0f2f9e3866e97fbcb0fd347a333231535eb12` (this session's own most
  recent, fact-checked Android work).
- Other Phase 3B branches inspected this session (read via `git show
  origin/<branch>:<path>`, **not merged, not checked out over this
  branch's own work**):
  - `phase-3b-measurement-tests` (`8cbab34`) -- test-foundation branch:
    `NetworkClient`/`FakeNetworkClient` seam, `ReferenceLatencyProbeExecutor`
    (illustrative, test-only), `DerivedLatencyStats.Companion.from`
    (the one new production aggregation function that phase added).
    Real, CircleCI-confirmed (three actual runs cited by commit and
    CI-run ID in that document, two of which caught genuine bugs).
  - `phase-3b-measurement-engine` -- **exists on `origin` as of this
    session** (a prior state of this repository, reflected in AI 4's
    own validation report, found it absent via `git ls-remote`; it is
    present now). Contains a real `LatencyMeasurementEngine.kt`
    (production, not test-only) that explicitly depends on, and does
    not fork or redefine, this session's own
    `MeasurementCapabilityClassifier` and the test-foundation branch's
    `NetworkClient`/`DerivedLatencyStats.from` -- confirmed by reading
    its own KDoc and imports directly.
  - `phase-3b-validation` -- AI 4's independent validation report.
    **Directly relevant to this task's "investigate whether existing
    API assumptions are outdated" instruction**: that report
    independently found the same `WifiManager.getConnectionInfo()`
    deprecation this session's own `phase-3b-android-contract-review`
    branch had already found and corrected, **and added a sharper
    detail this session's own correction missed** -- see Section 16
    (API-assumption re-investigation) below. This is treated as a
    citable, independently-arrived-at finding to re-verify, not copied
    uncritically.
- **No branch in this repository has been merged into `main`.** This
  document does not change that.

## 1. Purpose

To let a future engineer (or a session with physical-device access)
know, for each of AERIVA's ten scoped measurement capabilities, exactly
what evidence source is required before a result can be trusted, what
condition each test targets, and -- critically -- to prevent a
JVM-fake-backed test result or an emulator result from being silently
treated as proof of real-world behavior. Every test entry below is
explicit about which of five result kinds it produces:
**OBSERVATION** (the platform reports a fact), **MEASUREMENT** (AERIVA
directly and controllably measured something), **ESTIMATION** (an
indirect, confounded proxy for something AERIVA cannot directly
measure), **PREDICTION** (a forward-looking inference from past
measurements), or **RECOMMENDATION** (an action suggested from the
above) -- matching `core:model`'s own Phase 3A tier boundary
(`LatencyMeasurement`/`LatencyEstimation`/`LatencyPrediction`/
`ConnectivityRecommendation`) rather than inventing a parallel
taxonomy.

## 2. Test environment

| Environment | What it can run | What it cannot produce |
|---|---|---|
| **Plain JVM (`src/test`)** | Deterministic domain arithmetic (`DerivedLatencyStats.from`, `Confidence.of`, `Freshness`), engine orchestration logic against `FakeNetworkClient` (timeout/cancellation/concurrency reaction, per the test-foundation branch), `MeasurementCapabilityClassifier`'s pure permission-mapping logic | Anything touching `android.*` classes; any real socket, DNS, or radio behavior |
| **Emulator (`src/androidTest`, this repo's `connected_android_test` CircleCI job, api-30 `google_apis` `x86_64`)** | Real `ConnectivityManager`/`NetworkCallback` registration and lifecycle against a virtualized network (already exercised by `AndroidNetworkMonitorInstrumentedTest`); compilation/behavioral regression checks for anything Android-framework-dependent | Real Wi-Fi/cellular radio values, real signal strength, real captive portals, real OEM battery behavior, real multi-network handoffs, real carrier DNS caching -- current official Android performance-testing guidance states plainly that emulator results can differ significantly from real hardware for exactly this class of measurement (re-confirmed this session, matching `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13 and the test-foundation branch's own Section 1 finding) |
| **Physical device** | The only environment that can produce a real value for capabilities 7-8 at all, and the only environment where capabilities 1-4/9-10's *numeric* results mean anything about real-world AERIVA use | Nothing this matrix scopes -- this is the ceiling, not a gap |
| **Multiple physical devices/locations/carriers** | The only environment that can distinguish "true of this specific device/radio/OEM/carrier" from "true of Android in general" -- required for any claim in the OEM-risk matrix (Section 9) or the Android-version matrix (Section 8) to generalize beyond the one unit tested | A single device, however thoroughly tested, is evidence about that device -- not about AERIVA's real user population |

## 3. Emulator limitations (stated once, referenced throughout)

Specific, not generic, per this task's own emphasis:

- **Wi-Fi**: emulator Wi-Fi is virtual. `WifiInfo`/`NetworkCapabilities.getTransportInfo()`
  either returns synthetic/placeholder values or nothing meaningful,
  depending on emulator configuration -- never a real SSID/BSSID/RSSI.
- **Cellular**: emulator cellular is synthetic or entirely absent.
  `TelephonyManager.getAllCellInfo()`/`TelephonyCallback.CellInfoListener`
  return empty or fixed placeholder data, never real signal strength or
  real cell identity.
- **Radio-level packet loss/jitter/poor signal**: an emulator's network
  path runs over the host machine's real network stack but with no
  radio layer at all -- it can reproduce host-network conditions (e.g.
  the host machine's own Wi-Fi being slow), but never a real cellular
  radio's loss/jitter characteristics, and it cannot reproduce "poor
  signal" as a distinct condition from "host network is briefly slow."
- **Captive portal**: the emulator's host network is very unlikely to be
  captive-portal-gated in a CI environment; this condition is
  practically untestable on an emulator, not merely lower-fidelity.
- **OEM battery restrictions/Doze/App Standby edge cases**: the emulator
  runs stock AOSP behavior (or close to it, per the `google_apis` image
  used by this repository's CI), not any OEM's modified battery
  management -- Section 9's entire OEM-risk matrix is, by construction,
  untestable on an emulator.
- **What the emulator IS reliable for**: API-level compilation/behavioral
  correctness (does `registerDefaultNetworkCallback` still fire the
  right lifecycle callbacks on this Android version), and Doze/App
  Standby's *documented, standard* transitions specifically (these are
  AOSP-level, not OEM-level, so an emulator running stock behavior can
  exercise the standard state machine, distinct from OEM-added
  restrictions layered on top of it).

## 4. Physical-device requirements

Restated once here, referenced by capability/condition below rather than
repeated per row:

- At least one physical device per major currently-relevant Android
  version this project targets (12 through 16, per Section 8).
- At least one Wi-Fi network and one cellular connection (ideally on a
  SIM with a real data plan, not Wi-Fi-only) per device under test.
- At least one location where signal is reliably strong and one where it
  is reliably poor/marginal, for the strong-signal/poor-signal
  conditions (Section 7) to be reproducible at all -- a lab with only
  strong signal cannot exercise those rows.
- Access to a captive-portal network (many public/hotel/airport Wi-Fi
  networks qualify) for the captive-portal condition.
- At least one device from a battery-aggressive OEM skin (per
  `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13's own finding that this
  is the least-standardized area in the platform) for the OEM-risk
  matrix (Section 9) to say anything beyond "untested."
- A controlled, AERIVA-operated (or otherwise known-behavior) endpoint
  to probe against, for latency/throughput/HTTPS-reachability results
  to be interpretable at all -- probing an arbitrary third-party
  endpoint conflates AERIVA's own measurement with that endpoint's own
  variability, which no device, however real, can disentangle.

None of the above is claimed as available in this sandbox or as having
been executed. This section specifies prerequisites, not completed
work.

## 5. Capability matrix

Extends `PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md`
Section 3 with the validation-specific columns this task requires. Tier
column uses this document's five-way taxonomy (Section 1).

| # | Capability | Tier | JVM-validatable | Emulator-validatable | Physical-device-required for | Wi-Fi required | Cellular required | Permissions required | OEM-dependent |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Latency | MEASUREMENT | Engine orchestration logic (timeout/cancellation/concurrency reaction against `FakeNetworkClient`) | Compilation/lifecycle only | The numeric result meaning anything about real-world AERIVA use | No (either) | No (either) | `INTERNET` | Radio-timing variance is OEM/chipset-dependent; the measurement mechanism itself is not |
| 2 | Jitter | MEASUREMENT (derived) | Same pattern as latency, once a jitter domain type exists (not yet -- Section 6's finding) | Same as latency | Same as latency, plus: jitter specifically needs *repeated* real-world samples to mean anything, so a single physical-device session with too few samples is as uninformative as an emulator run | No (either) | No (either) | `INTERNET` | Same as latency |
| 3 | Packet loss | MEASUREMENT WITH LIMITATIONS | Same pattern as latency, not yet implemented | Same as latency | The loss/slow-response distinction (Section 11) is only meaningful under real network stress, which an emulator cannot reproduce | No (either) | No (either) | `INTERNET` | Same as latency |
| 4 | Throughput | MEASUREMENT WITH LIMITATIONS | Same pattern, not yet implemented | Real transfer rate is meaningless over an emulator's virtualized network | Entirely | No (either) | No (either) | `INTERNET` | Real link speed is heavily chipset/OEM/carrier-dependent |
| 5 | Network stability | OBSERVATION (derived) | `DerivedLatencyStats`'s consistency/spread logic, given hand-constructed input | `AndroidNetworkMonitorInstrumentedTest` already exercises the underlying callback lifecycle | Whether the derived signal correlates with real-world instability | No (either) | No (either) | `ACCESS_NETWORK_STATE` (already declared) | Low -- this is platform-reported, not radio-measured |
| 6 | Network transitions | OBSERVATION | `ReferenceLatencyProbeExecutorTest`'s `NetworkChangedMidCall` proves *code reaction* to a simulated signal, not that a real transition produces that signal correctly | Emulator can simulate network-down/network-up via `adb`, exercising the callback path, but not a *real* Wi-Fi<->cellular radio handoff | A real handoff, especially one that happens mid-probe | Yes (one side of the transition) | Yes (other side) | Same as #5 | Handoff timing/behavior is real-device/carrier-dependent |
| 7 | Wi-Fi characteristics | OBSERVATION | Permission-boundary logic only (`MeasurementCapabilityClassifier`) | **No** -- emulator Wi-Fi is virtual (Section 3) | Entirely -- the only way to get a real SSID/BSSID/RSSI value | Yes | No | `ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE`; current API path additionally needs the location-info opt-in on the `NetworkCallback` (Section 16) | High -- confirmed by `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13 as the least-standardized area in the platform |
| 8 | Cellular characteristics | OBSERVATION | Permission-boundary logic only | **No** -- emulator cellular is synthetic/absent (Section 3) | Entirely | No | Yes | `READ_PHONE_STATE` + `ACCESS_FINE_LOCATION` (both, jointly -- Section 16's re-confirmation) | High -- same reason as #7, plus real multi-SIM/eSIM/carrier variance |
| 9 | DNS responsiveness | ESTIMATION | Timing-logic pattern only, once implemented | Emulator DNS path differs from real carrier/OS caching (Section 3) | Real caching behavior specifically -- the reason this is ESTIMATION, not MEASUREMENT | No (either) | No (either) | `INTERNET` | Carrier-level DNS caching varies; OS-level does not (AOSP-standard resolver behavior) |
| 10 | HTTPS reachability | MEASUREMENT | Engine pattern only, once implemented | Emulator can validate reachability logic against a real internet-routable test server (host machine has real network egress) -- **the one active-measurement capability where emulator results are meaningfully closer to real-device results**, because it doesn't depend on radio-level timing precision the way latency/jitter/throughput do | Real captive-portal interception and real carrier-level TLS interception/proxying specifically | No (either) | No (either) | `INTERNET` | Low for the mechanism; captive-portal/proxy interception is carrier/venue-dependent, not OEM-dependent |

## 6. API-assumption re-investigation (this task's explicit requirement)

Investigated fresh this session, not assumed correct from any prior
document:

- **Re-confirmed, no further defect found**: `TelephonyCallback.CellInfoListener`'s
  dual `READ_PHONE_STATE` + `ACCESS_FINE_LOCATION` requirement;
  `ConnectivityDiagnosticsManager`'s silent-no-op eligibility
  restriction; the `INTERNET`-permission gap (still not declared in the
  manifest, confirmed by direct inspection this session); the DNS
  responsiveness ESTIMATION-tier classification (re-confirmed correct
  and unchanged in `MeasurementCapabilityClassifier.kt`, read directly
  this session).
- **One further refinement found beyond this session's own prior
  correction** -- see Section 16, since it is specifically about
  investigating whether the *already-corrected* Wi-Fi finding itself
  needs a further correction. Kept as its own section rather than
  folded in here because it directly answers this task's "investigate
  whether existing API assumptions... are outdated or incorrect"
  instruction with a concrete, citable answer, and because it involves
  cross-checking another role's (AI 4's) independent finding against
  fresh research rather than a first-time discovery.
- **No incompatibility found in `core:model`'s Phase 3A types** against
  anything this validation matrix requires. Not modified, per this
  task's explicit instruction.

## 7. Network-condition matrix

For each condition: purpose, prerequisites, environment, setup,
expected observation, expected measurement, acceptable uncertainty,
failure interpretation, reproducibility, OEM-dependence. Threshold
values are marked `NOT YET ESTABLISHED` throughout, per this task's
explicit instruction not to invent numbers.

**Wi-Fi connected**
- Purpose: baseline for every capability under a stable, expected-common
  connection type.
- Prerequisites: device on a known-stable Wi-Fi network.
- Environment: physical device (Section 4).
- Setup: connect, allow the connectivity callback to settle, then run
  each capability's probe.
- Expected observation: `NetworkState`/`NetworkCapabilities` report
  `TRANSPORT_WIFI`, `NET_CAPABILITY_VALIDATED` (current official
  `NetworkCapabilities` semantics, re-confirmed this session: "indicates
  that the network provides actual access to the public internet when
  it is probed").
- Expected measurement: latency/jitter/packet-loss/throughput/DNS/HTTPS
  results, all MEASUREMENT- or ESTIMATION-tier per Section 5.
- Acceptable uncertainty: `NOT YET ESTABLISHED`.
- Failure interpretation: any capability failing here, with `NET_CAPABILITY_VALIDATED`
  present, is a strong signal the failure is AERIVA-side (bug, wrong
  endpoint) rather than network-side.
- Reproducibility: high, on a stable network.
- OEM-dependent: low.

**Cellular connected**
- Purpose: baseline for the connection type most subject to real-world
  variance (Section 5's OEM/radio notes).
- Prerequisites: device on a real cellular data connection (not Wi-Fi).
- Environment: physical device.
- Setup: disable Wi-Fi, confirm `TRANSPORT_CELLULAR`, run each
  capability's probe.
- Expected observation: `TRANSPORT_CELLULAR`, `NET_CAPABILITY_VALIDATED`;
  metered status typically true (`NET_CAPABILITY_NOT_METERED` typically
  absent) -- current official docs: "a network is classified as metered
  when the user is sensitive to heavy data usage... due to monetary
  costs, data limitations, or battery performance issues."
- Expected measurement: same shape as Wi-Fi, expected (not guaranteed)
  higher variance.
- Acceptable uncertainty: `NOT YET ESTABLISHED`.
- Failure interpretation: a failure here that does not reproduce on
  Wi-Fi is evidence of a real cellular-path issue (carrier, radio,
  location), not necessarily an AERIVA defect.
- Reproducibility: lower than Wi-Fi -- genuinely dependent on carrier,
  location, and time.
- OEM-dependent: high.

**Wi-Fi -> cellular transition** / **cellular -> Wi-Fi transition**
- Purpose: exercise capability 6 (network transitions) and any in-flight
  measurement's reaction to a mid-probe network change (the pattern
  `ReferenceLatencyProbeExecutorTest`'s `NetworkChangedMidCall` already
  covers at the JVM/fake level).
- Prerequisites: both Wi-Fi and cellular available and toggleable on the
  same device.
- Environment: physical device (Section 5, row 6: emulator can simulate
  the *signal* via `adb`, not the real handoff).
- Setup: start a long-enough-to-span-the-transition probe, toggle Wi-Fi
  off (or move out of Wi-Fi range) mid-probe, observe.
- Expected observation: `onLost`/`onAvailable` fire in the platform-
  reported order; `NetworkChangedDuringMeasurement`
  (`MeasurementFailure`'s existing case, `core:model`, Phase 3A)
  produced for any measurement genuinely interrupted by the transition.
- Expected measurement: none claimed to survive the transition
  meaningfully -- a measurement that started on one network and
  finished on another should be discarded or explicitly flagged, not
  silently attributed to either network.
- Acceptable uncertainty: `NOT YET ESTABLISHED`.
- Failure interpretation: a measurement that reports success while
  silently spanning a real transition is a defect (Task-adjacent to the
  hardening discipline `PHASE_3B_MEASUREMENT_ENGINE_HARDENING.md`-class
  work already requires elsewhere).
- Reproducibility: moderate -- real handoff timing varies by device/
  carrier/location.
- OEM-dependent: yes (handoff behavior).

**Airplane mode**
- Purpose: exercise the "no connectivity at all" path cleanly (distinct
  from "poor signal," which still has a real, if bad, connection).
- Prerequisites: none beyond the device itself.
- Environment: physical device or emulator (both can toggle this
  reliably).
- Setup: enable airplane mode, attempt each capability's probe.
- Expected observation: no active `Network`; `ConnectivityManager`
  reports no default network.
- Expected measurement: every active-measurement capability should fail
  fast with an unavailable-network result (the pattern
  `ReferenceLatencyProbeExecutorTest.measure_whenNetworkUnavailable_...`
  already covers at the JVM/fake level) -- not attempt a socket and
  time out slowly.
- Acceptable uncertainty: none -- this is a binary, platform-reported
  state.
- Failure interpretation: a slow timeout here instead of a fast
  unavailable-network short-circuit is a defect.
- Reproducibility: high.
- OEM-dependent: low.

**No connectivity (Wi-Fi/cellular off, not airplane mode)**
- Purpose: distinguish airplane mode's explicit signal from the case
  where radios are simply off/out of range -- these can behave
  differently at the platform level.
- Prerequisites/environment/setup: same as airplane mode, but via
  individually disabling Wi-Fi and cellular data rather than the
  airplane-mode toggle.
- Expected observation/measurement/failure interpretation: same as
  airplane mode, but this condition specifically checks that AERIVA
  doesn't rely on airplane-mode-specific signals it might not receive
  here.
- Acceptable uncertainty: none.
- Reproducibility: high.
- OEM-dependent: low.

**Captive portal**
- Purpose: exercise the specific, real distinction between "no internet"
  and "network up, but gated" -- directly relevant to Section 11's
  HTTPS-vs-DNS-vs-unreachable discipline.
- Prerequisites: access to a real captive-portal network (Section 4).
- Environment: physical device -- practically untestable on an emulator
  (Section 3).
- Setup: connect to the portal-gated network without completing the
  portal sign-in, run HTTPS-reachability and latency probes against
  AERIVA's own controlled endpoint (not the portal itself).
- Expected observation: `NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL`
  present, `NET_CAPABILITY_VALIDATED` absent -- current official
  `NetworkCapabilities` semantics, re-confirmed this session ("indicates
  that the network has a captive portal when it is probed" /
  `NET_CAPABILITY_VALIDATED`: "a network behind a captive portal... 
  doesn't have this capability").
  `NetworkCapabilities.hasCapability(NET_CAPABILITY_CAPTIVE_PORTAL)`
  is itself an OBSERVATION AERIVA can read at no extra permission cost
  (part of the already-declared `ACCESS_NETWORK_STATE` surface).
- Expected measurement: an HTTPS probe against AERIVA's own endpoint
  should receive a portal's redirect/interception response, not the
  expected AERIVA response -- this is real, useful evidence
  distinguishable from "endpoint down" or "DNS failed," per Section 11.
- Acceptable uncertainty: `NOT YET ESTABLISHED`.
- Failure interpretation: an implementation that reports this as a
  generic connection failure (rather than distinguishing it from true
  unreachability) has not satisfied Section 11's discipline.
- Reproducibility: moderate -- depends on finding/maintaining access to
  a real portal-gated network.
- OEM-dependent: low (this is an AOSP-level capability signal).

**Metered network**
- Purpose: verify throughput (and any future scheduling) respects the
  already-exposed `NetworkState.metered` field.
- Prerequisites: a cellular connection or a Wi-Fi network explicitly
  marked metered by the user.
- Environment: physical device (real metered-status signaling) or
  emulator (can simulate the capability flag, per current official
  `NetworkRequest`/`NetworkCapabilities` guidance's own example using
  `NET_CAPABILITY_NOT_METERED`).
- Setup: connect to a metered network, attempt a throughput probe.
- Expected observation: `NetworkCapabilities.NET_CAPABILITY_NOT_METERED`
  absent.
- Expected measurement: throughput probe should default to
  not-running/opt-in-only on a metered network (Section 4's contract
  requirement, restated here as a test condition) -- OBSERVATION of the
  metered flag driving a RECOMMENDATION-tier "don't run this now"
  decision, not a MEASUREMENT itself.
- Acceptable uncertainty: none for the flag read; `NOT YET ESTABLISHED`
  for any data-cost threshold.
- Failure interpretation: a throughput probe running unprompted on a
  metered network is a defect against the contract, regardless of
  whether the measurement itself succeeds.
- Reproducibility: high (the flag is platform-reported).
- OEM-dependent: low.

**Poor signal** / **Strong signal**
- Purpose: the two ends of the real-world variance range for
  latency/jitter/packet-loss/throughput.
- Prerequisites: Section 4's requirement for at least one location of
  each.
- Environment: physical device only -- neither condition is producible
  on an emulator (Section 3).
- Setup: run the full capability suite at each location.
- Expected observation: for capabilities 7-8, real, degraded/strong
  RSSI/signal-strength values (when the relevant permissions are
  granted).
- Expected measurement: for capabilities 1-4, expected (not guaranteed)
  correlation between weak signal and worse latency/jitter/loss/
  throughput -- this is a hypothesis to test, not an assumed fact.
- Acceptable uncertainty: `NOT YET ESTABLISHED` for any specific
  correlation strength or threshold.
- Failure interpretation: no meaningful "failure" state for this
  condition itself -- it's a data-gathering condition, not a pass/fail
  check, except insofar as a capability crashes or hangs specifically
  under degraded signal (which would be a real defect).
- Reproducibility: low -- signal strength is highly location/time/
  carrier-dependent, the single hardest condition in this matrix to
  reproduce exactly.
- OEM-dependent: high (reported RSSI values are OEM/radio-firmware-
  dependent, `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13).

**Network temporarily disappearing**
- Purpose: a network that drops and returns within a short window,
  distinct from a permanent loss or a deliberate transition.
- Prerequisites: ability to briefly disable/re-enable a radio, or a
  location with known brief real dropouts (e.g. an elevator, a tunnel).
- Environment: physical device for a real dropout; emulator can simulate
  the signal sequence via `adb` for code-reaction testing only.
- Setup: begin a probe, briefly kill and restore connectivity within the
  probe's own timeout window.
- Expected observation: `onLost` followed by `onAvailable` (or
  `onCapabilitiesChanged`) in quick succession.
- Expected measurement: the in-flight measurement should resolve as a
  clean failure (timeout or `NetworkChangedDuringMeasurement`), never a
  false success.
- Acceptable uncertainty: `NOT YET ESTABLISHED` for what "brief" means
  numerically.
- Failure interpretation: a false success here is a direct violation of
  the "cancellation/timeout must not produce a misleading successful
  result" discipline this task and the measurement-engine hardening
  task both require.
- Reproducibility: moderate with airplane-mode toggling; low for a
  "natural" real dropout.
- OEM-dependent: moderate (how quickly the platform reports the drop
  varies).

**DNS failure**
- Purpose: exercise capability 9's failure path distinctly from a slow
  DNS response or a reachable-but-erroring endpoint.
- Prerequisites: a hostname guaranteed not to resolve (e.g. a
  reserved/invalid TLD), or a network with broken DNS configured.
- Environment: either JVM (once a real `NetworkClient` exists -- the
  resolution failure itself is a standard `java.net.UnknownHostException`
  path, reproducible without any Android dependency) or physical device
  for real carrier-DNS-broken scenarios.
- Setup: probe a non-resolving hostname.
- Expected observation/measurement: a distinct `MeasurementFailure` case
  (`EndpointFailure` or a DNS-specific case, per Section 11's
  discipline) -- never conflated with a connection timeout or an HTTP
  error.
- Acceptable uncertainty: none for the failure-kind distinction itself.
- Failure interpretation: DNS failure reported as a generic timeout is a
  defect.
- Reproducibility: high (a non-resolving hostname resolves the same way
  everywhere).
- OEM-dependent: low for the mechanism; real carrier-DNS-outage
  scenarios are carrier-dependent and not reliably reproducible at all.

**HTTPS failure**
- Purpose: exercise capability 10's failure path -- and specifically the
  contract's own required distinction (Section 6, capability 10 row):
  an HTTP 5xx from a reachable server is not the same failure as
  unreachability or TLS failure.
- Prerequisites: a controlled endpoint that can be made to return a 5xx,
  and separately one with a broken/expired TLS certificate.
- Environment: JVM (once a real client exists, both scenarios are
  reproducible without Android dependency) or physical device for real
  carrier/proxy TLS interception.
- Setup: probe each scenario separately.
- Expected observation/measurement: 5xx maps to `InvalidResponse`
  (`core:model`'s existing case -- "a response was received and was
  itself the failure signal," per the contract Section 6); TLS failure
  maps to `TlsFailure` (also existing); true unreachability maps to
  `EndpointFailure`/`Timeout`. All three must remain distinct results,
  never collapsed.
- Acceptable uncertainty: none for the distinction itself.
- Failure interpretation: any collapsing of these three into one
  generic "HTTPS failed" result is a defect against both this document
  and the existing domain model's own case set.
- Reproducibility: high for the JVM-level scenarios; moderate for real
  carrier TLS interception (some carriers/networks do this, most don't).
- OEM-dependent: low.

**High latency**
- Purpose: exercise the case where a probe succeeds but is slow, kept
  distinct from timeout (Section 11).
- Prerequisites: a network/path with genuinely high latency (a distant
  server, a deliberately slow test endpoint, or a real poor-signal
  location).
- Environment: physical device for real high-latency conditions; JVM
  can simulate a fake-client-injected delay for the engine's *reaction*.
- Expected observation/measurement: a `Succeeded` result with a large
  `valueMillis`, not a `Failed`/`Timeout` result -- these must remain
  distinct outcomes.
- Acceptable uncertainty: `NOT YET ESTABLISHED` for any "this counts as
  high" threshold.
- Failure interpretation: a slow-but-successful probe being reported as
  a timeout (or vice versa) is exactly the "timeout semantics
  accidentally becoming packet-loss semantics" failure mode this task
  and the measurement-engine hardening task both explicitly warn
  against.
- Reproducibility: moderate (network-path-dependent) to high (with a
  deliberately slow controlled endpoint).
- OEM-dependent: low.

**Timeout**
- Purpose: the genuine "no response within the configured window" case.
- Prerequisites: an endpoint or network condition that reliably exceeds
  the configured timeout.
- Environment: JVM (`FakeNetworkClient.enqueueHang`, already exercised
  by the test-foundation branch) for the engine-reaction proof; physical
  device for a real network-induced timeout.
- Expected observation/measurement: `MeasurementFailure.Timeout`, distinct
  from `Cancelled` (caller-initiated) and from a genuine packet-loss
  signal (Section 11).
- Acceptable uncertainty: none for the distinction; `NOT YET ESTABLISHED`
  for the timeout duration itself (a product/engineering decision, not
  a fact this matrix asserts).
- Failure interpretation: `Timeout` and `Cancelled` conflated, or either
  silently reinterpreted as packet loss, is a defect.
- Reproducibility: high on JVM; moderate on a real device (depends on
  finding a genuinely unresponsive real endpoint/condition).
- OEM-dependent: low.

**Rapid network transitions**
- Purpose: multiple transitions in quick succession -- the stress case
  for capability 6 and for the 100-outstanding-requests-per-UID ceiling
  (`ConnectivityManager`, re-confirmed current this session and in
  every prior Phase 3B document).
- Prerequisites: ability to toggle connectivity rapidly (airplane mode
  on/off in quick succession, or a location with real rapid handoffs).
- Environment: physical device for real rapid handoffs; emulator/`adb`
  toggling for code-reaction stress testing.
- Expected observation: `AndroidNetworkMonitor`'s deliberate
  zero-debounce-on-loss behavior (already documented in that class)
  should still report every real transition, not coalesce them away.
- Expected measurement: no measurement should straddle two transitions
  silently; any in-flight probe caught in a rapid-transition window
  should resolve as `NetworkChangedDuringMeasurement` or fail cleanly.
- Acceptable uncertainty: `NOT YET ESTABLISHED` for "how rapid" before a
  different handling strategy would be needed.
- Failure interpretation: a callback-registration approaching the
  100-per-UID ceiling under rapid transitions, or a measurement
  producing a stale/misattributed result, are both defects.
- Reproducibility: moderate.
- OEM-dependent: yes -- real handoff speed and platform-reporting
  latency both vary by OEM/radio.

**VPN active**
- Purpose: exercise the interaction Section 5 (this document, capability
  rows) and the contract's own Section 5 (physical-device contract)
  already flag -- a user's own third-party VPN affects every socket-
  level capability by default.
- Prerequisites: a real, user-installed VPN app, active during testing.
- Environment: physical device -- VPN behavior is not meaningfully
  emulator-testable given the emulator's own already-virtualized network
  stack.
- Setup: activate a real VPN, run the full capability suite.
- Expected observation: `NetworkCapabilities.TRANSPORT_VPN` present on
  the active network (current official `NetworkCapabilities`
  transport-constant list, re-confirmed this session includes
  `TRANSPORT_VPN`); AERIVA's own socket traffic routes through the VPN
  by default per current official VPN documentation ("apps call methods
  such as `ConnectivityManager.bindProcessToNetwork()` or
  `Network.bindSocket()`" to opt out -- re-confirmed this session), so
  absent that opt-out, results reflect the VPN path, not the underlying
  Wi-Fi/cellular path directly.
- Expected measurement: latency/throughput results measured *through*
  the VPN, not of the underlying network -- this must be documented as
  what the result actually represents, not silently presented as "the
  network's" latency.
- Acceptable uncertainty: `NOT YET ESTABLISHED`.
- Failure interpretation: presenting a VPN-routed result as if it were
  the underlying network's own characteristic (without noting the VPN
  is active) would misrepresent what was actually measured -- a
  MEASUREMENT-tier claim about the wrong thing is not a measurement of
  the thing claimed.
- Reproducibility: moderate -- depends on the specific VPN app/protocol
  used.
- OEM-dependent: low (VPN routing is an AOSP-level `VpnService`
  mechanism); the *third-party VPN app's own* behavior is not
  AERIVA-controlled at all.

**Location permission denied** / **Location permission granted**
- Purpose: directly exercise `MeasurementCapabilityClassifier`'s own
  `WIFI_CHARACTERISTICS`/`CELLULAR_CHARACTERISTICS` branches -- the one
  part of this entire matrix that is fully JVM-testable today, since
  the classifier takes `grantedPermissions` as a plain `Set<String>`
  parameter.
- Prerequisites: none for the JVM case (already covered by
  `MeasurementCapabilityClassifierTest.kt`, re-read directly this
  session -- `wifiCharacteristics_withFineLocation_isSupportedWithLimitations()`,
  `wifiCharacteristics_withoutFineLocation_isNotReliablyAvailable()`,
  and the cellular dual-permission test). Physical device for the real
  runtime-permission-dialog flow.
- Environment: JVM for the classification logic itself (already
  validated, no new test needed here); physical device for the real
  user-facing grant/deny flow and for confirming a *provider* (once one
  exists) actually consults the classifier before attempting a probe.
- Expected observation: classifier returns `NotReliablyAvailable` when
  denied, `SupportedWithLimitations` when granted (already-verified
  behavior).
- Expected measurement: none attempted when denied -- a provider that
  attempts a Wi-Fi/cellular read despite a `NotReliablyAvailable`
  classification, and gets masked values back (`WifiManager.UNKNOWN_SSID`
  per current `WifiInfo` reference docs, re-confirmed this session)
  would be a defect the classifier exists specifically to prevent.
- Acceptable uncertainty: none for the classification logic (already
  deterministic and tested); real-world user grant-rate is out of
  scope for this matrix.
- Failure interpretation: masked-value results silently treated as real
  data is the specific failure mode this row guards against.
- Reproducibility: high (both JVM and physical-device permission states
  are directly controllable).
- OEM-dependent: low for the mechanism; some OEM permission-management
  UIs add extra steps/dialogs, which is a UX consideration out of this
  matrix's scope.

**Required permission denied** (`INTERNET` scenario, once declared)
- Purpose: exercise the classifier's `NotReliablyAvailable` path for
  every `INTERNET`-gated capability -- **currently only reachable in
  theory**, since `INTERNET` is not yet declared in the manifest
  (confirmed this session).
- Status: **blocked on the still-open manifest decision** (contract
  Section 15, open question 1). Recorded here as a required future test,
  not performed.

**OEM background restrictions** / **Doze** / **Battery saver**
- Purpose: exercise whether an in-flight or scheduled measurement
  survives (or is correctly abandoned under) background execution
  restrictions.
- Prerequisites: physical device(s) across at least one aggressive OEM
  skin (Section 4); Doze specifically is testable via `adb shell dumpsys
  deviceidle` force-idle commands on any device/emulator running stock
  behavior.
- Environment: Doze -- emulator can exercise the *standard* AOSP state
  machine (Section 3's own carve-out); OEM-specific restrictions and
  battery saver's real user-facing behavior -- physical device only,
  and only that OEM's own device for that OEM's own behavior.
- Expected observation: current official Doze documentation's own
  behavior (network access restricted in Doze; standard maintenance
  windows) -- not independently re-derived in this document since it
  is unchanged, standard AOSP behavior already well-documented and not
  in dispute.
- Expected measurement: an on-demand, foreground, user-initiated
  measurement (Section 7 of the contract) should be entirely unaffected
  by Doze/battery saver, since it never runs in the background in the
  first place -- this is the current design's own protection, not
  something requiring new code. Any *future* background/periodic
  measurement (still not designed -- Section 7 of the contract) would
  need this row re-tested against that design once it exists.
- Acceptable uncertainty: `NOT YET ESTABLISHED`.
- Failure interpretation: N/A today (no background measurement exists
  to fail); becomes directly relevant only once one is designed.
- Reproducibility: high for standard Doze (via `adb`); low to moderate
  for real OEM-specific restrictions (`PHASE_2_ANDROID_PLATFORM_AUDIT.md`
  Section 13's own "least-standardized area" finding, restated here).
- OEM-dependent: Doze itself, low (AOSP-standard); OEM restrictions on
  top of it, high.

## 8. Android-version matrix

| Version | Emulator image available in this repo's CI | Physical-device coverage required | What specifically needs re-testing per version |
|---|---|---|---|
| 12 (31) | Not the current CI image (api-30) -- would need a separate managed-device/emulator config | Yes, at least once | `TelephonyCallback` (introduced this version) behaves as documented; Wi-Fi location-info-inclusion behavior (Section 16) first applies here |
| 13 (33) | Same as above | Yes | `NEARBY_WIFI_DEVICES` exists but, re-confirmed this session, does not substitute for `ACCESS_FINE_LOCATION` on the `WifiInfo`/SSID path -- worth a physical confirmation that this project's minSdk (26) fallback path (fine location) still works correctly on a 13+ device that also has the newer permission available, to catch any accidental over-reliance on the newer permission |
| 14 (34) | Same as above | Yes | Foreground-service-type declaration enforcement (not currently triggered by any capability here, but worth confirming a future FGS addition would actually be caught by this OS version if it forgot the declaration) |
| 15 (35) | Same as above | Yes | `dataSync`/`mediaProcessing` FGS execution cap; not currently triggered |
| 16 (36) -- this project's compileSdk/targetSdk | Not confirmed this session whether CI's current api-30 image has been updated; **open item, not verified** | Yes | Background-job quota extension to jobs started from a foreground service (not currently triggered); Local Network Protections (confirmed this session to be inapplicable to any of the ten capabilities, since none target local-network addresses -- worth a physical re-confirmation once LNP's phased rollout (25Q2-26Q2, per the contract) reaches full enforcement, purely as a regression check, not because any current design is expected to be affected) |

**This project's own minSdk is 26** (re-confirmed by direct inspection
this session, unchanged from every prior document) -- every version row
above is "does this *additional* platform behavior, introduced at this
version, work as documented," not "does AERIVA work at all on this
version," since minSdk 26 already predates every version in this table.

## 9. OEM-risk matrix

Per `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13's own finding,
re-confirmed and not re-litigated here: OEM behavior is the single
least-standardized area in the entire platform. This matrix records
*which* capabilities/conditions carry OEM risk, not *what* any specific
OEM does (that requires the physical-device testing this document
specifies, not documentation research).

| Risk area | Capabilities/conditions affected | Why | Testable without hardware? |
|---|---|---|---|
| Aggressive background killing | Doze/battery-saver row (Section 7); any future background measurement | OEM battery-management skins vary widely and are not part of AOSP | No |
| Reported RSSI/signal-strength accuracy and units | Wi-Fi/cellular characteristics (7-8); poor/strong signal rows | Radio firmware and OEM reporting layers vary | No |
| Network-transition/handoff timing | Network transitions (6); rapid transitions row | Radio and connectivity-manager-integration behavior varies | No |
| Permission-dialog flow/extra steps | Location-permission rows | Some OEM skins add extra permission-management UI | Partially (UX, not core to this matrix) |
| Doze/App-Standby *bucket assignment* speed | Any future background-scheduled measurement | How quickly an OEM assigns an app to a restrictive standby bucket varies | No |
| Multi-SIM/eSIM cellular reporting | Cellular characteristics (8) | Real carrier/SIM-slot behavior varies by device and region | No |

## 10. Failure-injection matrix

Cross-references Section 7's individual condition entries; this section
exists to give the engine-hardening-adjacent discipline (matching
`PHASE_3B_MEASUREMENT_ENGINE_HARDENING.md`-class work) a single table
view of every distinct failure kind that must remain distinguishable, so
a future implementation cannot quietly merge two of these into one.

| Injected condition | Must produce | Must NOT be conflated with |
|---|---|---|
| DNS non-resolution | A DNS-specific/`EndpointFailure` result | Connection timeout; HTTP error |
| Connection refused | `EndpointFailure` | DNS failure; timeout |
| TLS handshake failure | `TlsFailure` | HTTP error (a response was never received); DNS failure |
| HTTP 5xx | `InvalidResponse` (a response WAS received) | Unreachability of any kind |
| Slow-but-eventual response | A `Succeeded` result with high `valueMillis` | `Timeout` |
| No response within configured window | `Timeout` | `Cancelled`; packet loss (Section 11) |
| Caller cancellation | `Cancelled` | `Timeout` |
| Network genuinely changes mid-probe | `NetworkChangedDuringMeasurement` | A silent success attributed to either network |
| Captive portal interception | A distinguishable "reached a portal, not the real endpoint" result (Section 7's captive-portal row) | Generic unreachability; HTTP error from the real endpoint |
| Malformed/truncated response | A distinct malformed-response result (already covered at the JVM/fake level by the test-foundation branch's `measure_onMalformedPayload_...`) | A successful measurement; a generic `EndpointFailure` |

## 11. Measurement-validity rules

Stated as rules, not as a claim that any current implementation already
enforces all of them (this document does not implement anything):

1. **A capability classified `NotReliablyAvailable` by
   `MeasurementCapabilityClassifier` must never produce a result at
   all** -- not a low-confidence one, not an estimated one. Absence of a
   result is itself the correct, honest output.
2. **A `SupportedWithLimitations` or `Estimated` result must carry its
   own stated limitation/reason** -- both types in
   `CapabilityClassification` (this session's own prior work) already
   require this at the type level (`reason: String`, non-optional);
   this rule restates it as a validation requirement, not a new
   constraint.
3. **Timeout and packet loss must never be inferred from each other.**
   Restated from Section 11's own discipline and Section 10's failure-
   injection table: an unanswered probe within a timeout window is
   evidence of *one of* "lost" or "slow," never confidently labeled as
   either without protocol-level evidence AERIVA's socket-level probes
   (Section 3B contract, capability 3) do not have.
4. **A JVM-fake-backed test result is never evidence about real network
   behavior.** Restated from the test-foundation branch's own Section 7
   ("Test philosophy compliance") -- this document adds no new rule
   here, only re-affirms it as binding on this matrix's own physical-
   device rows.
5. **An emulator result for capabilities 7-8 is never evidence at all**
   (not "weak evidence" -- Section 3 establishes the emulator produces
   synthetic/absent data for these, not merely lower-fidelity real
   data).
6. **A result measured through an active VPN must be labeled as such**,
   per Section 7's VPN-active row -- it is a real measurement of a real
   thing (the VPN-tunneled path), but presenting it as the underlying
   network's own characteristic without that label misrepresents what
   was measured.
7. **ESTIMATION-tier results (DNS responsiveness) must never be
   presented with the same confidence framing as MEASUREMENT-tier
   results** -- this is the same discipline the classifier fix in
   `phase-3b-android-contract-review` already enforces at the type
   level; restated here as the reason that fix mattered for validation
   purposes specifically, not just documentation accuracy.

## 12. Reproducibility rules

1. A test condition's reproducibility rating (Section 7, stated per
   condition) governs how many independent runs are needed before a
   result is trusted -- **no specific run count is asserted here**
   (`NOT YET ESTABLISHED`), but the ordering is stated: "high"
   reproducibility conditions (Wi-Fi connected, airplane mode, timeout,
   DNS failure) need fewer independent confirmations than "low"
   reproducibility conditions (poor/strong signal, real natural network
   dropouts).
2. Any claim about OEM-dependent behavior (Section 9) is evidence about
   the *specific device tested*, never about "Android" or "OEM behavior
   in general," until the same result is reproduced on a second,
   independent device of a different OEM.
3. Any claim about a specific Android version's behavior (Section 8) is
   evidence about that version specifically, and must not be assumed to
   generalize forward or backward without its own confirmation --
   Android version behavior changes are not always monotonic or
   cumulative in the way a naive "newer implies superset of older"
   assumption would suggest (this document's own Section 8, Android 16
   Local Network Protections, is itself an example of a genuinely new,
   non-additive restriction).
4. A result obtained once, on one device, in one location, at one time
   of day, is a single data point -- Section 5's "reliability
   limitations" language throughout already states this per capability;
   this rule generalizes it as a standing requirement for the entire
   matrix.

## 13. Evidence requirements

For a test in this matrix to be considered executed (not merely
planned), the following must exist and be checkable by someone other
than whoever ran it -- matching the evidentiary standard the
test-foundation branch's own Validation section already models (actual
commit SHAs, actual CI run IDs, actual observed failures with root
causes, not "looks correct"):

- The exact device model, Android version, and OEM skin used.
- The exact network condition (carrier, Wi-Fi network, signal
  strength/location if relevant) at the time of the test.
- The exact commit SHA of the code under test.
- The raw result (not just a pass/fail label) -- for a `Succeeded`
  measurement, the actual `valueMillis`/equivalent; for a `Failed`
  result, which specific `MeasurementFailure` case.
- For any claim that generalizes beyond one run: the number of
  independent runs and their individual results, not just an aggregate.
- For any OEM-risk or Android-version claim: enough of the above to let
  a reader distinguish "this device" from "this OEM" from "this Android
  version" from "Android in general."

**No evidence meeting this bar exists yet for any physical-device row in
this matrix**, since no physical-device testing has occurred as part of
this document. This section specifies the bar, not a completed
checklist.

## 14. Test execution checklist

For whoever eventually runs this matrix against real hardware:

- [ ] Confirm the exact commit under test and record its SHA (Section
  13).
- [ ] Confirm `INTERNET` (and any other still-undeclared permission
  needed for the specific capability under test) has actually been
  added to the manifest -- several rows in this matrix are currently
  untestable because it hasn't been (Section 7's "Required permission
  denied" row).
- [ ] Work through Section 7 condition-by-condition, recording Section
  13's evidence requirements for each.
- [ ] Work through Section 8's Android-version matrix on at least one
  physical device per version listed.
- [ ] Work through Section 9's OEM-risk matrix on at least one device
  per identified risk area, per Section 4's OEM-device requirement.
- [ ] For every result, apply Section 11's measurement-validity rules
  before recording it as valid evidence -- explicitly reject (and
  record as rejected, not silently discard) any result that violates
  one of those rules.
- [ ] Do not mark any row "validated" on the strength of a single run,
  per Section 12's reproducibility rules.
- [ ] Report results back against this exact document's structure, so
  future readers can trace each result to the specific row it answers.

## 15. Open decisions

Restated from prior documents where this matrix depends on them, not
re-litigated:

1. Whether/when `INTERNET` is added to the manifest (contract Section
   15, item 1) -- blocks several rows in Section 7 from being testable
   at all today.
2. The still-open scheduler/`WorkManager` decision (contract Section 7)
   -- blocks the Doze/battery-saver row's "expected measurement" content
   from being anything beyond "N/A today."
3. Whether capability 8 (cellular characteristics) remains worth
   pursuing given its now-confirmed two-permission cost (contract
   Section 15, item 3) -- if decided against, most of Section 8's
   TelephonyCallback-specific version-testing becomes moot.
4. Whether `PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md`'s Wi-Fi
   correction should itself be further corrected per Section 16 below
   -- a new open item this document surfaces, not inherited from a
   prior one.
5. Which CircleCI-managed emulator image(s) this project's CI actually
   uses today for Android version coverage beyond api-30 -- **not
   verified this session** (this sandbox cannot query CircleCI, Section
   "Validation" below); Section 8's version matrix depends on knowing
   this to say which versions are even emulator-testable in this
   project's existing CI, distinct from physical-device-testable.

## 16. Deferred work

- Real execution of every row in Section 7 against physical hardware --
  the entire point of this document, explicitly not performed here.
- A jitter/packet-loss/throughput-specific version of Section 5's
  capability-matrix detail once those `core:model` types exist (Section
  6's re-confirmed finding: they still do not).
- Any numeric threshold this document deliberately left `NOT YET
  ESTABLISHED` -- establishing them is exactly the physical-device
  testing this matrix exists to specify, not something a documentation
  pass can responsibly assert.
- A second-OEM, second-carrier confirmation pass for every OEM-risk-
  matrix row (Section 9), per Section 12's reproducibility rules --
  single-device testing, once it happens, is necessary but not
  sufficient to close these rows.

## Further Wi-Fi API refinement (this task's explicit "investigate
outdated assumptions" requirement)

`phase-3b-android-contract-review`'s own fact-check (this session's
prior work) corrected `WifiManager.getConnectionInfo()`'s deprecation
and pointed to `ConnectivityManager.registerNetworkCallback(...)` with
`NetworkCallback.FLAG_INCLUDE_LOCATION_INFO`. AI 4's independent
validation report (`phase-3b-validation`, read this session) reached the
same core finding independently and added a detail worth re-verifying:
that the modern path's location-info opt-in is exposed via
`NetworkCallback.Builder.setIncludeLocationInfo(true)`, not (or not
only) the `FLAG_INCLUDE_LOCATION_INFO` constant this session's own
correction cited.

**Re-verified fresh this session, not accepted on AI 4's word alone**:
current official reference documentation for
`ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO` itself
carries the following notice on its current binding/reference pages:
"This constant will be removed in the future version. Use
`NetworkCallbackFlags` enum directly instead of this field" -- i.e. the
flag-constant surface this session's own prior correction cited is
itself being superseded, and `NetworkCallback.Builder`'s
`setIncludeLocationInfo(boolean)` method (or the underlying
`NetworkCallbackFlags`-based construction it wraps) is the more current
recommended surface. **This means `phase-3b-android-contract-review`'s
own correction, while directionally right (moving off the deprecated
`WifiManager.getConnectionInfo()`), cited an API surface
(`FLAG_INCLUDE_LOCATION_INFO`) that is itself marked for removal.**

This is recorded here as a further, concrete finding this task's
"investigate whether existing API assumptions... are outdated" mandate
specifically asks for -- surfaced by cross-checking an independent
role's (AI 4's) finding against fresh research, not by this document's
own first-time discovery. Per this task's own instructions ("do not
silently rewrite unrelated code" and this task's "prefer documentation
only" code guidance), **no code or prior document is edited by this
branch** -- the correction is recorded here, for whoever next touches
`MeasurementCapabilityClassifier.kt`'s `WIFI_CHARACTERISTICS` comment or
the contract's Wi-Fi rows to fold in, since the underlying permission
requirement (`ACCESS_FINE_LOCATION` + `ACCESS_WIFI_STATE`) is
**unaffected either way** -- this is a citation-precision issue three
layers deep (deprecated method -> its first replacement -> that
replacement's own now-superseded flag surface), not a classification or
permission defect.

## Validation

**No production code changed.** This branch adds documentation only, per
this task's explicit preference and its "only add code if a tiny pure
test helper is absolutely necessary" instruction -- no such helper was
found necessary, since every JVM-testable claim in this matrix is
already covered by existing tests
(`MeasurementCapabilityClassifierTest.kt`,
`DerivedLatencyStatsTest.kt`-class, `ReferenceLatencyProbeExecutorTest.kt`-class,
all read directly this session, not assumed) and this document's own
contribution is the physical-device specification those tests
explicitly cannot cover.

No Gradle build or test run was executed or is claimed. Same unchanged
sandbox network constraint as every prior Phase 3B/3C document in this
repository (Maven Central/Google Maven outside this container's
allowlist). No test file exists in this branch to report a pass/fail on.

## Summary

Every one of the ten scoped capabilities now has an explicit JVM/
emulator/physical-device boundary, a stated tier (OBSERVATION/
MEASUREMENT/ESTIMATION), and an explicit list of what real hardware is
required to validate versus what documentation research alone can
establish. Twenty-six network/permission/OS conditions are specified
with purpose, setup, expected result, and failure interpretation, with
every numeric threshold honestly marked `NOT YET ESTABLISHED` rather
than invented. One further, concrete Android-API refinement was found
by cross-checking AI 4's independent validation finding against fresh
research: this session's own prior Wi-Fi API correction
(`FLAG_INCLUDE_LOCATION_INFO`) cited a surface that is itself now marked
for removal in favor of `NetworkCallback.Builder.setIncludeLocationInfo(...)`
-- recorded, not silently fixed, per this task's own scope. No
production code, permission, domain type, or `WorkManager`/VPN/Supabase
dependency was added. Nothing was merged to `main`. Phase 3D was not
begun.
