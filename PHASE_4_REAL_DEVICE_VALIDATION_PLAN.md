# PHASE 4: REAL-DEVICE VALIDATION AND BENCHMARK PLAN

Author: AI 6 (Claude). Date written: 2026-09-19.
Scope: validation and design only. No production code, permission, WorkManager, Supabase, UI, merge or main change is made by this document.
Base branch: `phase-3b-measurement-engine` @ `727b2a91d2724735c3f22965ca72cf4311202370` (chosen because it is the only branch where the classifier, `NetworkClient`, `DerivedLatencyStats.from` and `LatencyMeasurementEngine` coexist and compile-fix commits have landed). `main` is `e3f70a4a13e63b782c61601abadc2c36f65dbd73` and was not touched.

## Label legend (used on every non-trivial claim)

| Label | Meaning |
|---|---|
| VERIFIED FACT | Read directly from code in this repo, or from official documentation fetched in this session (source listed in Section 28). |
| PRIOR-DOC FACT | Stated in an earlier AERIVA document or commit message and not independently re-verified by this session. |
| TEST REQUIREMENT | A test that must be run and passed before a claim is allowed. |
| EXPECTED RESULT | What should be observed if the implementation is correct. A hypothesis, not a result. |
| ASSUMPTION | Believed true, not verified. Must be verified or removed before it is relied on. |
| LIMITATION | A hard constraint on what can be claimed or tested. |
| OPEN DECISION | Needs a product or engineering decision. No number is invented for it here. |

No test in this document has been executed. No hardware result exists. Any number that appears is either quoted from existing code or explicitly labeled.

---

## 1. Executive summary

AERIVA wants trustworthy network measurements on real Android devices, especially on volatile, expensive and congested networks. This plan defines how that trust will be earned. It is deliberately conservative: a measurement that cannot be validated is labeled as an estimate or is not produced.

Key conclusions from inspecting the actual repository (details in Section 2):

1. **There is nothing to validate on a real device yet.** `NetworkClient` is an interface with only a test fake. No production socket, HTTP or DNS implementation exists on any branch. The first real-device latency number cannot exist until one does. (VERIFIED FACT)
2. **The manifest blocks all active probes.** Only `ACCESS_NETWORK_STATE` is declared (in `network/monitor`). `INTERNET` is not declared anywhere, so `MeasurementCapabilityClassifier` returns `NotReliablyAvailable` for LATENCY, JITTER, PACKET_LOSS, THROUGHPUT, DNS_RESPONSIVENESS and HTTPS_REACHABILITY on a real device today. `:app` does not depend on `network:monitor`. (VERIFIED FACT)
3. **The domain model cannot represent most of what must be validated.** Only latency is modeled, with average, min, max. There is no jitter, median, percentile, packet-loss or throughput type. `MeasurementFailure` has no DNS-failure, captive-portal, redirect or unknown case. The HTTPS states requested for this plan (AVAILABLE, UNAVAILABLE, UNKNOWN, CAPTIVE PORTAL, TIMEOUT, TLS FAILURE, DNS FAILURE) cannot all be expressed today. (VERIFIED FACT)
4. **The engine's timing starts before the dispatcher hop** and does not decompose DNS, TCP and TLS time (Section 7). The number it produces is an application-level elapsed time, not a network round-trip time, and must be named accordingly.
5. **Packet loss cannot be directly measured by a stock-Android app over TCP.** This plan defines an honest ladder: no loss claim first, then a clearly named "unanswered probe rate" over a sequenced UDP probe against a server AERIVA controls, validated with injected loss of known rate (Section 9).
6. **CI cannot validate radio behavior.** CircleCI runs one API 30 x86_64 emulator job. There is no physical-device lab. Everything radio-, OEM-, battery- and carrier-related needs hardware that does not currently exist in the pipeline (Section 22).
7. **A correction to a prior document:** `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md` claims `FLAG_INCLUDE_LOCATION_INFO` is being removed in favor of a `NetworkCallback.Builder`. That is wrong. The flag exists since API 31, the constructors are `NetworkCallback()` and `NetworkCallback(int flags)`, and no Builder exists. The "will be removed" notice came from Microsoft .NET binding docs. `PHASE_3B_ANDROID_CONTRACT_FINAL_REVIEW.md` (AI3) reached the same conclusion. (VERIFIED FACT, Section 28)

The plan defines 10 capability validation tracks, a device and Android-version coverage floor, network and special-condition matrices, statistical rules, safety and data-budget gates, per-capability production-readiness evidence, an execution order, and a list of open decisions. Blocking prerequisites are listed in Section 24.

---

## 2. Current Phase 3 validation baseline

### 2.1 Repository state inspected (2026-09-19)

| Item | Value | Status |
|---|---|---|
| `main` | `e3f70a4` (Phase 3A merge, PR #2) | VERIFIED FACT. No Phase 3B or 3C code on main. |
| `phase-4-measurement-architecture` | `e3f70a4` (same as main) | VERIFIED FACT. No commits beyond main. |
| `phase-3b-measurement-engine` | `727b2a9` | VERIFIED FACT. Engine, classifier, NetworkClient, fake, harness all present. Fix commits `3973c09` (core:common dependency) and `727b2a9` (missing test import) landed 2026-09-19. |
| `phase-3b-engine-test-gate` | `4ed691f` | VERIFIED FACT. Adds `LatencyMeasurementEngineGateTest` (3 gap tests plus 1 demonstration test). |
| `phase-3b-engine-integration-audit` | `20dd1c5` | VERIFIED FACT. Documentation only. |
| `phase-3b-android-contract-final-review` | `9d37ce5` | VERIFIED FACT. Documentation only. |
| `phase-3b-repository-state-reconciliation` | `29e01e3` | VERIFIED FACT. Its engine-branch findings are stale (engine branch moved from `d1ea16f` to `727b2a9`). |
| `phase-3c-android-validation-matrix` | `07c0ab4` | VERIFIED FACT. Documentation only. Contains the FLAG_INCLUDE_LOCATION_INFO error above. |

### 2.2 Build and SDK configuration (VERIFIED FACT)

- AGP 8.13.2, Kotlin 2.3.21, coroutines 1.11.0, JVM 17.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26` (`app/build.gradle.kts`, `network/monitor/build.gradle.kts`).
- Modules: `:app`, `:core:common`, `:core:model`, `:core:result`, `:core:logging`, `:core:database`, `:core:preferences`, `:core:security`, `:network:monitor`.
- Test stack: JUnit4, `androidx.test.ext:junit`, `androidx.test:core`, `androidx.test:runner`, `kotlinx-coroutines-test`. No mocking library, Robolectric or Turbine. Convention is hand-written fakes.

### 2.3 Manifests and permissions (VERIFIED FACT)

- `app/src/main/AndroidManifest.xml`: no `uses-permission`, no launcher activity.
- `network/monitor/src/main/AndroidManifest.xml`: `ACCESS_NETWORK_STATE` only.
- Not declared anywhere: `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_WIFI_STATE`, `READ_PHONE_STATE`.

### 2.4 What the current code actually does (VERIFIED FACT, read from source)

| Component | Behavior relevant to validation |
|---|---|
| `AndroidNetworkMonitor` | Uses `registerDefaultNetworkCallback`. Observes the default network only. Loss and unavailable events are never debounced. Availability and capability events are debounced 300 ms by default. |
| `NetworkStateMapper` | `available = true` whenever a snapshot exists, regardless of `validated`. Transport priority is VPN, then Ethernet, Wi-Fi, Cellular. A VPN over Wi-Fi is reported as `VPN`, hiding the underlying transport. `estimatedQuality` is always `Unavailable`. |
| `MeasurementCapabilityClassifier` | Pure function of (capability, sdkInt, granted permissions). `sdkInt` is unused in branch logic (dead parameter, per AI3). LATENCY, JITTER, HTTPS_REACHABILITY need `INTERNET` and return `Supported`. DNS_RESPONSIVENESS returns `Estimated`. PACKET_LOSS and THROUGHPUT return `SupportedWithLimitations`. Wi-Fi needs fine location. Cellular needs `READ_PHONE_STATE` and fine location. |
| `NetworkClient` | `suspend fun probe(target: String): NetworkClientOutcome`. Outcomes: `Success(payload)`, `ConnectionRefused`, `TlsHandshakeFailed`, `NetworkChangedMidCall`. No DNS failure, HTTP status, redirect, captive portal or timing-breakdown case. No production implementation. |
| `LatencyMeasurementEngine.measure` | Classifies LATENCY; declines with `CapabilityUnavailable` only for `NotReliablyAvailable`; declines with `NoNetwork` only when `context.networkState.available == false`; otherwise records `startedAt = now()` and `startNanos = elapsedNanos()` **before** `withContext(dispatchers.io) { withTimeout(5000 ms default) { probe } }`. |
| Result mapping | `Success` with payload size exactly 8 bytes becomes `Succeeded(valueMillis = (elapsedNanos() - startNanos) / 1_000_000.0, sampleCount = 1)`. Any other payload size becomes `Failed(InvalidResponse)`. Timeout becomes `Failed(Timeout)`. Only `TimeoutCancellationException` is caught, so caller cancellation propagates and returns no value. |
| `measureSeries` | Runs requests sequentially with no inter-sample interval control and **no sample-count cap**. Calls `DerivedLatencyStats.from` over successes. |
| `DerivedLatencyStats.from` | Average, min, max only. Rejects negative, NaN and infinite. Returns null only when zero valid samples. One or two valid samples return a non-null value with `Confidence.Insufficient`. |
| `Confidence.of` | Insufficient below 3 samples, Low below 10, Medium if inconsistent, else High. Consistency means (max - min) <= 0.5 x average. The code itself calls these thresholds illustrative. |
| `MeasurementNetworkContext` | Supplied by the caller, not read live by the engine. `wifiRssi` and `cellularSignalStrength` are nullable and unpopulated. |

### 2.5 Observations that create validation obligations

1. The engine never inspects `networkState.validated`, captive portal or metered state. A probe on an unvalidated or captive network proceeds. (TEST REQUIREMENT in Sections 12 and 15.)
2. If a real `NetworkClient` throws (for example an `IOException`) instead of returning an outcome, `measure` does not catch it, and it propagates as an exception. The interface contract requires outcomes, so a contract test suite for any implementation is mandatory (Section 19).
3. `LatencyMeasurementRequest.method` is an unenforced free-text label (default `"tcp-round-trip"`) even though no transport exists. It must be tied to what is actually measured (Section 7).
4. The engine's `when` on classification only checks `NotReliablyAvailable`. If the classifier later returns `Estimated` or `SupportedWithLimitations` for LATENCY, the engine proceeds silently. (LIMITATION, non-blocking today.)
5. `measureSeries` has no cap on sample count, so it cannot itself enforce a data or battery budget.

### 2.6 Test and CI evidence baseline

| Layer | What exists | Status |
|---|---|---|
| JVM unit tests | `MeasurementCapabilityClassifierTest`, `NetworkStateMapperTest`, `TransportConstantMapperTest`, `FakeNetworkClientTest`, `ReferenceLatencyProbeExecutorTest`, `LatencyMeasurementEngineTest` (14 tests per commit message), `LatencyMeasurementEngineGateTest` (on test-gate branch) | Files VERIFIED FACT. Pass results are PRIOR-DOC FACT (from commit messages and the reconciliation report). This session could not query CircleCI. |
| Instrumented tests | `AndroidNetworkMonitorInstrumentedTest` and core:database, core:security instrumented tests | PRIOR-DOC FACT. Ran on API 30 `google_apis` x86_64 emulator. |
| CircleCI | `.circleci/config.yml` jobs: `build`, `unit_tests`, `static_checks`, `connected_android_test`. Image `cimg/android:2026.07`. Emulator system image `android-30;google_apis;x86_64`. | VERIFIED FACT (config read). |
| GitHub Actions | Present in `.github/` | PRIOR-DOC FACT: free minutes exhausted until 2026-10-01. |
| Physical device testing | None ever performed | PRIOR-DOC FACT (Phase 1 report). |

### 2.7 What Phase 3 validated versus what it did not

Validated only at JVM level against fakes (subject to the PRIOR-DOC caveat): capability-to-permission mapping, latency arithmetic given an injected clock, timeout and cancellation reaction, failure-case distinctness, aggregation arithmetic.

Not validated at all: any real socket, DNS, TLS, radio, OS callback ordering under real transitions, battery, data use, OEM behavior, any Android version above 30, any numeric latency accuracy.

---

## 3. Device matrix

### 3.1 Principle

Coverage is defined by attributes that plausibly change measurement behavior, not by model names. A single device is evidence about that device, never about Android or an OEM (prior 3C rule, retained).

### 3.2 Required attribute coverage

| Attribute | Why it matters | Required coverage |
|---|---|---|
| Performance tier: low, mid, high | CPU scheduling and timer jitter affect application-level timing; low-RAM devices kill background processes sooner. | At least one physical device per tier. Tier boundaries are an OPEN DECISION (D-01). |
| OEM skin | Battery management, permission UIs, connectivity handling and RSSI reporting differ by OEM (PRIOR-DOC FACT: Phase 2 audit calls OEM behavior the least standardized area). | At least three distinct OEM skins, including one close to stock Android and at least one known for aggressive background restriction. |
| Android version | See Section 4. | Per Section 4 floor. |
| Modem generation and vendor | Radio state promotion, handoff behavior and reported signal units may differ. | At least two distinct modem or chipset families where determinable from the device teardown or `dumpsys telephony.registry` output. Mark "undeterminable" if unknown. (ASSUMPTION that this is determinable.) |
| Wi-Fi capability | Bands supported (2.4, 5, 6 GHz) and Wi-Fi generation change link behavior. | At least one 2.4/5 GHz-only device and one with newer Wi-Fi generation, if the fleet allows. |
| SIM configuration | Dual-SIM and eSIM change which cellular network is default and how cell info is reported. | At least one dual-SIM device tested with each SIM as data SIM. |
| Physical form | Radios differ between phones and tablets. | Phones only in the first release (D-02). |

### 3.3 Example device categories (EXAMPLES ONLY, not a purchase list)

| Category | Example | Purpose |
|---|---|---|
| Near-stock Android | A Pixel-class device | Baseline behavior close to AOSP documentation |
| Large-vendor skin | A Samsung-class One UI device | Large user base, own battery and permission behavior |
| Regional-market skins | Transsion-family (Tecno, Infinix, itel), Xiaomi-family, Oppo-family devices | ASSUMPTION: commonly sold in Nigeria and other African markets. The actual list must come from AERIVA's own market data, not from this document (D-02). |

### 3.4 Minimum device coverage before production release

OPEN DECISION D-03 for exact counts. Proposed floor (ASSUMPTION, for product review): every tier is covered, at least three OEM skins are covered, every Android version in the Section 4 floor is covered by at least one physical device, and no capability is released as production-ready on the strength of fewer than two physically distinct devices that agree within the accepted uncertainty. A capability that is validated on only one device may be released only as "experimental, single-device evidence".

---

## 4. Android-version matrix

`minSdk = 26` (Android 8.0). `targetSdk` and `compileSdk` are 36 (Android 16). CI emulator is API 30 only.

| API / Android | Relevant platform behavior | Basis | Validation obligation |
|---|---|---|---|
| 26 to 27 (8.0, 8.1) | Floor of supported range. Doze and App Standby present since API 23. | VERIFIED FACT (Doze doc: applies to API 23+). | Physical device needed at low end. Decide whether measurement features are supported below a higher floor (D-04). |
| 28 (9) | `LinkProperties.isPrivateDnsActive()` and `getPrivateDnsServerName()` added. | VERIFIED FACT (API diff page). | Private DNS test cases require API 28 or newer. |
| 29 (10) | `getAllCellInfo()` without a fresh update request may return cached data. | PRIOR-DOC FACT (AI3 contract). | TEST REQUIREMENT: verify freshness behavior on device. |
| 30 (11) | Only emulator API level in CI. | VERIFIED FACT. | Emulator results here say nothing about API 31+ location redaction. |
| 31 (12) | `NetworkCallback(int flags)` and `FLAG_INCLUDE_LOCATION_INFO` added. Location info in `NetworkCapabilities` is redacted by default unless the flag is set. `WifiManager.getConnectionInfo()` deprecated. `TelephonyCallback` introduced. | First two VERIFIED FACT. Last two PRIOR-DOC FACT. | Wi-Fi characteristics tests (Section 13) must cover flag set and not set. |
| 32 to 33 (12L, 13) | `NEARBY_WIFI_DEVICES` exists. Prior docs say it does not replace fine location for SSID. | PRIOR-DOC FACT. | TEST REQUIREMENT: confirm on a 13+ device. |
| 34 (14) | Foreground service type declarations enforced. | PRIOR-DOC FACT. | Only relevant if a foreground service is ever added (D-05). |
| 35 (15) | Foreground service execution caps for some types. | PRIOR-DOC FACT. | Same as above. |
| 36 (16) | Job runtime quota adjusted by standby bucket, top state and foreground service, for WorkManager, JobScheduler, DownloadManager. `setImportantWhileForeground` no longer works. Local Network Protection testable. | First two VERIFIED FACT. Local Network Protection PRIOR-DOC FACT (inapplicable to AERIVA's scope per AI3, since no capability targets local addresses). | Background scheduling tests (Section 16) on 16. |
| 37 (17) | `ACCESS_LOCAL_NETWORK` runtime permission and opportunistic ECH apply only to apps targeting 37. Enterprise page also mentions Certificate Transparency by default (details not verified here). | VERIFIED FACT for the first two. CT detail is ASSUMPTION. | Not applicable while targetSdk is 36. Add as a regression track when targeting 37. |

Coverage floor: one physical device in each of these bands: 26-29, 30, 31-33, 34, 35, 36. Whether 37 hardware is added now is D-06. Emulator-only coverage is never sufficient for a band (LIMITATION).

---

## 5. Network matrix

Each row must be executed on real networks where the condition is naturally producible, and reproduced in a controlled lab where a synthetic impairment is possible. The controlled method is a Linux router or hotspot with `tc netem` (delay, jitter, loss, reordering, rate limit) and a controlled AERIVA test server. (ASSUMPTION: `tc netem` behaves as documented on the chosen Linux host. TEST REQUIREMENT: calibrate the impairment box itself with an independent reference before trusting it, Section 7.5.)

### 5.1 Wi-Fi

| Scenario | How to produce | Record |
|---|---|---|
| Excellent | Close to AP, low channel utilization, wired backhaul | RSSI, band, link speed, channel utilization if available |
| Weak | Distance or attenuation. Real weak signal, not netem | RSSI, retries if visible |
| Congested | Multiple clients generating load on same AP | Concurrent load description, AP client count |
| High latency | netem delay on router egress. Also natural high-latency backhaul | Injected value versus observed |
| Unstable | Periodic AP power cycle or channel change. Deliberate netem flap | Event timestamps |
| Metered | Mark the Wi-Fi network metered in device settings | `NOT_METERED` absent |
| Captive portal | Real portal network or lab portal that intercepts HTTP/HTTPS | `CAPTIVE_PORTAL` present, `VALIDATED` absent |
| Strong signal, poor internet | Good RSSI, upstream shaped or blackholed | RSSI good while probes fail or slow |

### 5.2 Cellular

| Scenario | How to produce | Record |
|---|---|---|
| Excellent, weak | Real locations only. Emulator cannot produce this | Signal metrics, network type |
| Congested, unstable | Real busy-hour and mobility scenarios. Not reproducible on demand | Time, location, carrier |
| High latency | Real conditions. Optional netem on a tethered path | Path description |
| Different generations | Force network-type preference in device settings where the OEM allows it (2G/3G/4G/5G availability differs by carrier and region) | Type reported by API versus device UI |
| SIM and carrier differences | Each available carrier SIM, same location and time window | Carrier, SIM slot, roaming flag |
| Roaming | Only if a roaming SIM is available | Roaming state, metered state |
| Mobile data disabled | Disable in settings with Wi-Fi off | No default network expected |

### 5.3 Transitions

Wi-Fi to cellular, cellular to Wi-Fi, loss to recovery, available but unusable internet, and rapid alternation. Detailed in Section 15.

### 5.4 Nigerian and wider African conditions (test scenarios, not claims about all users)

These are scenarios to include in field testing. No statistics are asserted here.

| Scenario | Why include | Test approach |
|---|---|---|
| Highly variable cellular quality within one day and location | Distinguish real network variance from AERIVA noise | Repeated sessions across times of day at fixed locations (Section 21) |
| Congested mobile networks at busy hours | Latency and loss-like symptoms rise | Paired busy and quiet windows, same location and device |
| Intermittent connectivity | Frequent unavailable, recovery, partial results | Measurement discarded or flagged, never silently averaged (Section 15) |
| Expensive mobile data | Data budget is a product-critical safety property | Section 17 gates; throughput off by default on metered |
| Weak Wi-Fi and shared routers | Many clients on one AP, poor backhaul | Section 5.1 congested and weak rows |
| Power interruptions | Router or ISP restarts mid-measurement | Loss to recovery, captive portal reappearing after router reboot |
| Captive portals (hotels, venues, shared hotspots) | Common gate before internet | Section 12 captive portal cases |
| Rural versus urban | Different technology generations and backhaul | Field sessions in both, same protocol |
| Carrier differences | Resolver, NAT, proxying and throttling policies differ | Same test on each carrier; Section 11 resolver identification |
| Mobile-data bundles that meter or throttle by traffic type | ASSUMPTION: some plans may treat traffic differently. Unverified. | Compare probe results by endpoint and protocol on each carrier (D-07) |

---

## 6. Capability validation matrix

### 6.1 Capabilities

Tier column uses the Phase 3A boundary: OBSERVATION, MEASUREMENT, ESTIMATION, PREDICTION, RECOMMENDATION.

| # | Capability | Tier today | Domain type exists | Production `NetworkClient` needed | Primary reference for validation | JVM | Emulator | Physical device | Current blocker |
|---|---|---|---|---|---|---|---|---|---|
| 1 | Latency | MEASUREMENT (application-level) | Yes (avg, min, max) | Yes | Wire capture and known-delay server | Arithmetic, orchestration | Loopback and host path only | Numeric meaning | No client, no INTERNET |
| 2 | Jitter | MEASUREMENT (derived) | No | Yes | Same capture, offline computation of IPDV and PDV | Once type exists | Same as latency | Numeric meaning | No type, no client |
| 3 | Packet loss | MEASUREMENT WITH LIMITATIONS (name TBD) | No | Yes (UDP variant) | netem injected loss of known rate | Once type exists | Host path only, lossless | Yes | No type, no client, no server |
| 4 | Throughput | MEASUREMENT WITH LIMITATIONS | No | Yes | Controlled server logs, iperf3, NDT7 candidate | Once type exists | Meaningless | Yes | No type, no client, no budget code |
| 5 | Network stability | OBSERVATION (derived) | Partial (via monitor) | No | Callback timelines | Spread arithmetic | Callback lifecycle | Correlation with real instability | Definition of "stability" not yet fixed (D-08) |
| 6 | Network transitions | OBSERVATION | Partial | No | Callback event log | Reaction to fake event | `adb` simulated toggles | Real handoff | None for observation |
| 7 | Wi-Fi characteristics | OBSERVATION | Field exists, unpopulated | No | Router-reported values, `dumpsys wifi` | Permission mapping | Not meaningful | Yes | Permissions not requested |
| 8 | Cellular characteristics | OBSERVATION | Field exists, unpopulated | No | `dumpsys telephony.registry`, field-test mode | Permission mapping | Not meaningful | Yes | Two permissions not requested |
| 9 | DNS responsiveness | ESTIMATION | No | Yes | Authoritative server query logs | Timing logic | Host resolver only | Cache and carrier behavior | No type, no client |
| 10 | HTTPS reachability | MEASUREMENT | No (failure set incomplete) | Yes | Controlled server with fault injection | Loopback fault tests | Host-path reachability | Portal, proxy, carrier TLS | Failure taxonomy gap |

### 6.2 Special conditions

Every row: what to set up, what should be observed, what the measurement layer must do, what classification is expected, and the pass or fail rule.

| Condition | Setup | Expected observation | Expected measurement behavior | Expected classification | Pass or fail |
|---|---|---|---|---|---|
| Airplane mode | Enable airplane mode, all radios off | No default network. `onLost` or no `onAvailable`. | No socket attempted. Returns quickly. | `NoNetwork` (engine outcome), not a `MeasurementFailure` | PASS if decline is immediate and no timeout elapses. FAIL if a slow timeout occurs. |
| Offline (radios on, no upstream) | Wi-Fi connected to AP with upstream cut, or SIM with no data | Network present. `VALIDATED` absent. `INTERNET` present. | Probe may attempt. Must not report success. | Timeout or `EndpointFailure` or DNS failure per stage reached. Should not be called "no network". | PASS if failure kind matches the stage that failed and the observation says not validated. |
| DNS failure | Non-resolving name, and a resolver that blackholes | Name resolution fails | Failure reported at DNS stage | Needs a DNS-specific failure kind (gap G-3). Must not be Timeout unless the resolver truly did not answer in time. | PASS if DNS failure is distinguishable from TCP and HTTP failures. |
| HTTPS failure | Server returns 5xx, expired cert, wrong host cert, untrusted root | See Section 12 | Each fault yields a distinct result | 5xx: `InvalidResponse`. TLS faults: `TlsFailure`. | PASS if each of the fault types maps to a different result. |
| Captive portal | Join a portal network without signing in | `CAPTIVE_PORTAL` present, `VALIDATED` absent (VERIFIED FACT for the semantics) | Probe response is not the expected signed payload | Needs a captive-portal or unexpected-response kind (gap G-3) | FAIL if reported as generic "internet bad" or as a success. |
| VPN | Real third-party VPN active | `TRANSPORT_VPN` present. Underlying transport hidden by `NetworkStateMapper` (VERIFIED FACT). | Probe travels via VPN by default. Result labeled as VPN-path. | Success with a VPN label, never presented as the underlying network's own number | FAIL if a VPN-path result is presented without the label. |
| Private DNS | Off, opportunistic, strict with valid host, strict with invalid host | `isPrivateDnsActive` and `getPrivateDnsServerName` reflect the mode (VERIFIED FACT) | System resolution works or fails per mode. AERIVA must not send its own unencrypted DNS while Private DNS is active (VERIFIED FACT from API doc). | DNS estimation flagged with mode. Strict-invalid yields DNS failure. | FAIL if any raw UDP 53 query leaves the app while Private DNS is active (checked by packet capture). |
| Metered connection | Cellular, or Wi-Fi marked metered | `NOT_METERED` absent | Throughput must not run without explicit consent. Latency probe allowed within budget. | Recommendation-tier decision, not a measurement | FAIL if throughput runs unprompted on metered. |
| Battery saver | Enable battery saver | System may restrict background work | On-demand foreground measurement unaffected. Any future scheduled work may be deferred. | Deferred work reported as deferred, not as failure | PASS if no measurement is silently dropped or fabricated. |
| Doze | `adb shell dumpsys battery unplug`, then `dumpsys deviceidle` stepping (VERIFIED FACT from doc) | Network access suspended in Doze | No measurement attempted from a deferred context. Resume in a maintenance window. | Not a `MeasurementFailure` unless a probe actually started | FAIL if a probe started during Doze is reported as a network failure of the endpoint. |
| Background restrictions | App Standby bucket forced via `am set-inactive` (VERIFIED FACT). Also OEM background limits. | Restricted app cannot reach the network | Same as Doze | Same | Same |
| Foreground operation | App visible, user-initiated | No restriction | Normal | Normal | Baseline for all others |
| Location permission granted | Grant fine location | Wi-Fi and cell details available subject to location toggle | Characteristics populated | `SupportedWithLimitations` | PASS if values are real, not masked. |
| Location permission denied | Deny | Values redacted or unavailable | No characteristics fabricated | `NotReliablyAvailable` for capabilities 7 and 8 | FAIL if masked values such as an unknown SSID sentinel are shown as real data. |
| Location services disabled | Toggle location off with permission granted | System checks the toggle when the include-location-info flag is set (VERIFIED FACT) | Characteristics unavailable | Limitation, not permission error | PASS if reported as unavailable with the right reason. |
| INTERNET permission unavailable | Current repo state | Classifier returns `NotReliablyAvailable` (VERIFIED FACT) | Engine returns `CapabilityUnavailable`, opens no socket | `CapabilityUnavailable` | PASS. Also TEST REQUIREMENT: observe what a real socket does without the permission on a device, and confirm the engine never reaches it. |
| Network permission restrictions | Data Saver, per-app data restrictions, `onBlockedStatusChanged` (VERIFIED FACT that the callback exists) | Network reported blocked for the app | Probe must not be presented as endpoint failure | A "blocked by device policy" outcome (gap G-3) | FAIL if reported as endpoint failure. |
| Poor signal | Real weak-signal location | Low signal metrics. Network may still be `VALIDATED` (VERIFIED FACT that VALIDATED can coexist with poor signal). | High latency, timeouts, partial results | Slow success versus Timeout kept distinct | FAIL if slow success becomes Timeout or the reverse without cause. |
| Rapidly changing connectivity | Toggle Wi-Fi and airplane mode repeatedly | Many callbacks | Any in-flight probe resolves as failure. No success attributed across networks. | `NetworkChangedDuringMeasurement` or Timeout | FAIL if a success spans a transition. |

---

## 7. Latency validation

### 7.1 What is being measured (must be defined before it is validated)

The engine measures **application-level elapsed time** from just before entering the IO dispatcher until the `NetworkClient.probe` outcome is processed. It is not a network round-trip time in the sense of RFC 2681 (VERIFIED FACT from RFC: a wire-level metric for packets across a path). RFC 2681 also lists round-trip weaknesses such as asymmetric paths (VERIFIED FACT). Required naming: results must carry a method identifier that says what was timed (for example: "cold TCP connect plus request-response over TLS" or "warm request-response on a reused connection"). The current free-text default `"tcp-round-trip"` is not sufficient and is not tied to any implemented transport (Section 2.5).

### 7.2 Timing source

| Aspect | Current state | Requirement |
|---|---|---|
| Duration clock | `System.nanoTime` via injectable `elapsedNanos` (monotonic). VERIFIED FACT. | Keep. TEST REQUIREMENT: on a device, verify monotonic behavior across a manual wall-clock change during a probe. |
| Wall clock for `measuredAt` | `Instant.now()` | Keep for ordering and display only. Never for durations. |
| Start point | Before dispatcher switch and before `withTimeout` | TEST REQUIREMENT: quantify the dispatcher hop by running the engine against a `NetworkClient` that returns immediately. The floor is the engine's own overhead. Report it as a known offset or move the start closer to the I/O. |
| End point | After the client returns, inside the same scope | Same test |
| Resolution | Nanoseconds converted to `Double` milliseconds | Verify no integer truncation. The gate test already checks 1.5 ms (PRIOR-DOC FACT). |

### 7.3 Components that must be separated

| Component | Included in cold probe | Included in warm probe | Notes |
|---|---|---|---|
| DNS resolution | Yes, unless cached | No | Cache state must be recorded or controlled (Section 11) |
| TCP connect | Yes | No | One round trip |
| TLS handshake | Yes | No | One or more round trips depending on version and resumption. ASSUMPTION about counts: verify by capture. |
| HTTP request and response | Yes | Yes | Includes server processing |
| Server processing time | Yes | Yes | Controlled server must report its own processing time in the response so it can be subtracted or reported |
| Radio state promotion on first packet after idle (cellular) | Possibly | Possibly | ASSUMPTION: idle-to-active radio promotion can add delay to the first packet. TEST REQUIREMENT: compare first-after-idle with steady-state probes on a physical device. |
| Wi-Fi power save wake delay | Possibly | Possibly | ASSUMPTION. Same test approach. |

TEST REQUIREMENT: the production client must expose per-stage timestamps (DNS done, connect done, TLS done, first byte, last byte) so the engine can report total and components. Until then, only the total may be reported, labeled as such.

### 7.4 Warm versus cold, connection reuse, IP family

| Variation | Test |
|---|---|
| Cold (new connection each probe) versus warm (reused) | Run both. Report separately. Never mix in one aggregate. |
| Connection reuse pool effects | Verify the client does not silently reuse a connection when cold is requested. Server-side connection-accept log is the ground truth. |
| IPv4 versus IPv6 | Force each family against a dual-stack server and record which address was used. On IPv6-only cellular with translation, record the path. ASSUMPTION that translation exists on some carriers. |
| VPN | Repeat the baseline with a VPN active and label results as VPN-path. |

### 7.5 Independent reference and its limits

Do not treat a popular speed-test app as a scientifically equivalent reference. Such an app measures a different thing on a different server with an unpublished method (LIMITATION). It may be used as a sanity check only, never as pass or fail ground truth.

Reference hierarchy (strongest first):

1. **Wire capture.** Packet capture at the server and, where possible, at the hotspot or router serving the device. Compute per-exchange timing from the captured packets: SYN to SYN-ACK, request to first response byte, and so on. This is closest to RFC 2681's intent. The Android emulator can also write pcap files (VERIFIED FACT for emulator networking docs; emulator version 36.5 or later per that page). A physical device generally cannot capture without root or a bridge (LIMITATION).
2. **Known-delay server.** A controlled server that adds a configured, logged delay before responding. The expected client measurement equals path time plus the configured delay. This gives ground truth for the offset and for bias, because the injected delay is known.
3. **Independent tool on the same path.** For example a laptop tethered through the same hotspot running `curl` with its `--write-out` timing variables (`time_namelookup`, `time_connect`, `time_appconnect`, `time_starttransfer`). Same server, same time window. (ASSUMPTION: curl timing variables are as documented on the tester's build. Verify at the time of use.)
4. **Public tools** (sanity only).

TEST REQUIREMENT: calibrate the impairment box itself. Before using `tc netem` as ground truth, measure its actual added delay with a wire capture and record the difference from the configured value.

### 7.6 Measurement uncertainty

| Source | Type | How addressed |
|---|---|---|
| Timer resolution and scheduling | Systematic plus random | Quantify with the immediate-return client (7.2) |
| Dispatcher and coroutine overhead | Systematic offset | Same |
| Server processing | Systematic, server-dependent | Server reports it, subtracted or disclosed |
| Path asymmetry | Cannot be removed in RTT | Disclose (RFC 2681 limitation) |
| Radio and power-state effects | Random, large on cellular | Repeated sessions, first-after-idle flag |
| Cross-traffic and time of day | Random | Blocked repeats (Section 21) |
| Endpoint variability | Random | Controlled endpoint only |

### 7.7 Acceptance for latency (evidence, no invented numbers)

- Every result has a method identifier that states what was timed.
- The engine's own overhead offset is measured on at least one device per performance tier and disclosed.
- On the known-delay server, the distribution of (AERIVA value minus expected value) has a documented median offset and spread, and both are accepted by product owners as fitting the intended use. Numeric bounds are OPEN DECISION D-09, to be derived from pilot data, not chosen in advance.
- Agreement with wire capture is demonstrated across at least two devices and both transport types.
- Failure behavior is validated: slow success versus Timeout versus each failure kind (Section 19).
- Battery and data cost within accepted budgets (Sections 16 and 17).

---

## 8. Jitter validation

### 8.1 Domain-model reality

No jitter type exists (VERIFIED FACT). `DerivedLatencyStats` holds average, min, max, `sourceMeasurementIds` and a `Confidence`. Before jitter can be validated, the team must choose a definition (OPEN DECISION D-10). Validation must match whichever definition is adopted.

### 8.2 Candidate definitions (from IETF)

| Definition | Source | Notes |
|---|---|---|
| IPDV: difference in delay between consecutive packets | RFC 3393 (VERIFIED FACT: referenced in RFC 6673 and RFC 5481) | Signed, depends on consecutive sample ordering |
| PDV: delay minus the minimum delay in the sample, summarized by a percentile | RFC 5481 (VERIFIED FACT: describes both formulations) | Robust to which samples are consecutive |

TEST REQUIREMENT: during validation, compute both IPDV and PDV offline from the raw sample sequence, so the choice of production definition can be made from data.

### 8.3 Sampling design

| Parameter | Requirement | Basis |
|---|---|---|
| Sample count | Not fixed here. Derived from a pilot: measure the between-session spread of the chosen jitter statistic and choose n so the statistic's interval is narrow enough to be useful. | OPEN DECISION D-11 |
| Interval | Must be at least as long as the worst expected round trip so that probes do not queue behind each other, and short enough that conditions are similar across samples. The engine's `measureSeries` currently has no interval control, so one must be added before jitter is meaningful. | Design finding |
| Ordering | Samples must retain send order. `sourceMeasurementIds` is a list, so order preservation must be tested. | TEST REQUIREMENT |
| Failed samples | A failed sample breaks the consecutive chain. Define whether IPDV pairs are formed only from adjacent successes. Document and test. | D-10 |
| Outliers | Never dropped silently. Report robust statistics (median, percentiles) and keep raw values. Outlier rules (if any) must be declared in the method identifier. | Section 21 |
| Minimum evidence | `Confidence.of` currently gives Insufficient below 3 samples. That threshold is illustrative and was designed for latency. Jitter needs its own minimum, derived from the pilot. | D-11 |
| Acceptable variance | Not invented here. Derived from repeatability pilots. | D-12 |

### 8.4 Repeatability

Repeat the same session many times in the same conditions. The session-level jitter statistic must be stable enough for its intended use. "Stable enough" is an OPEN DECISION (D-12), and the plan requires it to be recorded before the capability ships. Also test at least one condition with injected known jitter using netem (after calibrating the box, Section 7.5) and check that the measured jitter tracks the injected jitter in direction and magnitude across several injected levels.

---

## 9. Packet-loss validation (high risk)

### 9.1 What Android can and cannot observe

| Mechanism | Can it show packet loss? | Basis |
|---|---|---|
| TCP application-level request and response | **No, not directly.** The kernel retransmits and hides loss. Loss shows up only as added delay or timeout. | Standard TCP behavior. ASSUMPTION stated as general networking knowledge, verify by injected-loss experiment. |
| TCP kernel counters (retransmits) | Possibly, via socket-level TCP information | UNVERIFIED whether an unprivileged Android app can read it (via NDK) on stock builds. TEST REQUIREMENT T-9.1. |
| ICMP echo | Uncertain | Java `isReachable` uses ICMP if privileged, else a TCP connection to port 7 (Java API text as quoted by several secondary sources). Whether Android's implementation can send unprivileged ICMP is UNVERIFIED. TEST REQUIREMENT T-9.2. No root or raw sockets on stock Android (PRIOR-DOC FACT, consistent with the classifier's own reason string). |
| UDP probes with sequence numbers to a server AERIVA controls | **Yes, for the specific UDP path**, giving an unanswered-probe rate | Design. Result describes UDP treatment on that path, which can differ from TCP treatment (carrier, NAT, QoS). |
| Server-side sequence logs | Yes, separates uplink loss (server never saw it) from downlink loss (server saw it, reply not received) | Design, needs a server. |
| System counters (interface statistics) | Limited, mostly not readable by an app | UNVERIFIED |

LIMITATION: Without server help, root, or raw sockets, AERIVA cannot claim to measure packet loss. `PACKET_LOSS` is classified `SupportedWithLimitations` (VERIFIED FACT), and this plan holds the product to that.

### 9.2 Timeout is not packet loss

A probe that times out means only "no valid reply within the window." Causes include loss, high delay, endpoint down, DNS failure, radio sleep, captive portal blackholing and device policy blocks. `MeasurementFailure.Timeout` must never be converted into a loss statement. TEST REQUIREMENT: a matrix test where each cause is injected separately and the classification is inspected.

### 9.3 False positives and false negatives

| Risk | Example | Test |
|---|---|---|
| False positive loss | Late reply counted as lost because the window was too short | Inject delay slightly above and below the window, verify counted as late not lost |
| False positive loss | Carrier or NAT rate-limits or drops UDP | Compare UDP result with TCP symptom on each carrier |
| False positive loss | Radio idle wake delay | Compare first-after-idle probes |
| False negative loss | TCP retransmission hides loss | Inject loss with netem, observe TCP probe still "succeeds" but slower |
| False negative loss | Server replies from a cached path that bypasses lossy segment | Use the controlled server only |

### 9.4 Honest validation ladder

| Stage | Claim allowed | Evidence required |
|---|---|---|
| A. Now | No packet-loss claim | None. Field stays absent. |
| B. Symptom | "Unanswered probe rate" over a named probe type, with its definition in the result | Injected-loss calibration below, on two or more devices |
| C. Two-sided | Uplink versus downlink unanswered rate using server logs | Server instrumentation validated against netem ground truth |
| D. Loss | May be called packet loss only if calibration shows the estimate tracks injected loss across a range of rates and the residual bias is documented | Full calibration. Even then, phrase as estimated loss for that probe path. |

Calibration method: with the impaired path, inject known loss rates (including zero) over enough probes that an exact binomial interval on the observed unanswered fraction is narrow enough to distinguish rates. Compare estimated and injected rates. Also verify the zero-loss case yields no phantom loss. Required probe count is derived from the interval width needed (D-13).

---

## 10. Throughput validation

### 10.1 Safety requirements (must be true before any throughput code runs on a user device)

| Safeguard | Requirement |
|---|---|
| Consent | Explicit user action or opt-in. No silent first run. |
| Metered default | Off on metered networks unless the user opts in (VERIFIED FACT that `NOT_METERED` absence is the signal). |
| Hard byte budget | The code counts bytes actually transferred and stops at the cap, whatever the payload plan was. |
| Time box | Maximum duration independent of size, so slow links do not run long. |
| Adaptive size | Start small, stop when the estimate stabilizes. Do not use a fixed large payload. |
| Daily cap | Persisted, survives process death. Enforced before the test starts. |
| Battery guard | Refuse when battery saver is on or battery is low. Thresholds are OPEN DECISION D-14. |
| Roaming | Off by default. |
| Explicit state | Budget exhaustion produces a visible, explicit result, never a silent skip or a fabricated value. |
| No retry storms | Retries count against the same budget. |

### 10.2 Accuracy tests

| Test | Method |
|---|---|
| Download and upload separately | Controlled server logs bytes and timestamps per direction |
| Payload size sweep | Find size at which the estimate saturates. Report the size used. |
| Warm versus cold | Separate results (TCP ramp-up differs). |
| Server location | Nearby versus distant controlled server, disclose. |
| CDN and cache effects | Use non-cacheable, unique payloads. ASSUMPTION: a CDN in front would otherwise distort. Prefer no CDN for the reference endpoint. |
| Slow networks | Time box and budget must trigger. Result marked partial, not scaled up. |
| Reference | Controlled server byte and time logs, `iperf3` to the controlled server, and M-Lab NDT7 as a candidate public reference. ndt7 measures application-level goodput over a single TCP connection using WebSockets over TLS (VERIFIED FACT). Using it involves M-Lab's data policy, so privacy review is required first (D-15). |
| Method framework | RFC 6349 (TCP throughput testing framework) is a candidate method reference. Cited from author knowledge, not fetched in this session. Verify before relying. |

### 10.3 Repeatability and duration

Minimum sample duration and repeat count are OPEN DECISION D-16, derived from pilots that show how long it takes the estimate to stabilize on each network type. Not asserted here.

---

## 11. DNS validation

Three separate things must never be merged:

| Category | What it is | Source | Tier |
|---|---|---|---|
| DNS server observation | Which resolvers are configured, whether Private DNS is active and in which mode | `LinkProperties.getDnsServers()`, `isPrivateDnsActive()`, `getPrivateDnsServerName()` (VERIFIED FACT). Strict mode when the name is non-null. Opportunistic when null and active. | OBSERVATION |
| DNS responsiveness measurement | Timing of a query sent by AERIVA directly to a named resolver | Would need AERIVA's own DNS client | MEASUREMENT of that resolver, only when allowed |
| DNS-derived estimation | Timing of a system-resolver lookup | System resolver, subject to caches | ESTIMATION (classifier already says `Estimated`) |

LIMITATION and safety rule: when Private DNS is active, apps must not send unencrypted DNS queries, and in strict mode queries must go to the named host (VERIFIED FACT from the LinkProperties reference). So a raw UDP 53 responsiveness probe is not allowed while Private DNS is active. TEST REQUIREMENT: packet capture confirming no unencrypted DNS leaves the device in Private DNS modes.

Test design:

| Test | Setup | Expected |
|---|---|---|
| Cache miss versus hit | AERIVA-controlled wildcard zone. Unique random label per probe forces a miss. Repeated label tests hits. | Miss slower than hit on average. Authoritative logs show arrival for misses only. |
| Which resolver answered | Authoritative server logs the source resolver address | Reveals carrier versus public resolver, ground truth for "what did we measure" |
| Private DNS modes | Off, opportunistic, strict valid, strict invalid | Strict invalid gives DNS failure. Mode recorded in the result. |
| Carrier DNS | Each carrier SIM | Report per carrier. Do not generalize. |
| Local or router cache | Behind a home router with caching versus without | Disclose |
| Resolver reachability | Block resolver at router | DNS failure distinguishable from HTTP timeout (gap G-3) |
| Timeout | Delay resolver beyond timeout | DNS timeout kind |
| Encrypted DNS | Strict mode with DoT host | Measurement not directly comparable with plaintext, disclose |

LIMITATION: some causes cannot be told apart from the device: slow recursive resolver versus slow upstream authoritative versus OS-level retry. The result must say estimated and must not name a cause it cannot see. Domain gap: no DNS result type and no DNS failure case (G-3).

---

## 12. HTTPS reachability validation

### 12.1 State definitions

Definitions are proposals for the domain model (OPEN DECISION D-17). Each requires specific evidence.

| State | Definition | Evidence required | Representable today |
|---|---|---|---|
| AVAILABLE | DNS resolved, TCP connected, TLS validated, HTTP response received and the response body proves it came from the AERIVA endpoint (for example, a signed or nonce-bound payload) | All stages succeeded | Partly (`Succeeded` after payload check) |
| UNAVAILABLE | A stage failed definitively | Stage identified | `EndpointFailure`, `TlsFailure`, `InvalidResponse` |
| UNKNOWN | Result cannot be determined: cancelled, network changed, blocked by policy, insufficient permissions | Reason recorded | Partly (`Cancelled`, `NetworkChangedDuringMeasurement`) |
| CAPTIVE PORTAL | Response indicates interception by a portal (unexpected redirect or content), often with `CAPTIVE_PORTAL` capability present | Unexpected redirect or body, corroborated by capability flag | **No** |
| TIMEOUT | No response within the window at a named stage | Stage, window | `Timeout` (stage unnamed) |
| TLS FAILURE | Handshake or certificate validation failed | Failure reason | `TlsFailure` |
| DNS FAILURE | Name did not resolve | Resolver error or timeout | **No** |

Rule: never collapse these into "internet bad."

### 12.2 Test matrix

| Case | Method | Expected result |
|---|---|---|
| DNS failure | Non-resolving name | DNS failure (needs new kind) |
| TCP refused | Closed port | `EndpointFailure` |
| TCP blackhole | Dropped SYN | Timeout at connect stage |
| TLS expired, wrong host, untrusted root, unsupported version | Controlled server with each certificate | `TlsFailure` with reason |
| HTTP 200 with wrong body | Server returns different body | `InvalidResponse` |
| HTTP 3xx to another host | Server or portal redirect | Not followed blindly. Classified as redirect or portal (needs new kind). |
| HTTP 4xx and 5xx | Server fault injection | `InvalidResponse` or a status-specific result |
| Slow response | Delay under and over the window | Slow success versus Timeout |
| Captive portal | Real and lab portals | Portal state, corroborated by `CAPTIVE_PORTAL` capability (VERIFIED FACT for its meaning) |
| VPN | Real VPN | Success labeled VPN-path, or failure reflecting the VPN |
| Proxy | System HTTP proxy configured | ASSUMPTION: system proxy settings are exposed in link properties. TEST REQUIREMENT: verify how the client behaves and disclose. |
| Redirect loop or oversize response | Malicious server | Bounded, fails safe (Section 20) |

---

## 13. Wi-Fi characteristics validation

Clearly separate **API-reported information** (what the platform says about the link) from **actual performance** (what traffic experiences). Link speed and RSSI are not throughput or latency.

| Field | Source | Requirement | Validation |
|---|---|---|---|
| SSID, BSSID | `WifiInfo` from `NetworkCapabilities.getTransportInfo()` when the callback is created with `FLAG_INCLUDE_LOCATION_INFO` (VERIFIED FACT for the flag and default redaction) | Fine location and location services on (VERIFIED FACT that the system checks permission and toggle when the flag is set). `ACCESS_WIFI_STATE` also required per prior docs (PRIOR-DOC FACT). | Compare with the known AP's SSID and BSSID. Denied or off state must yield unavailable, not a placeholder shown as data. |
| Band and frequency | `WifiInfo` | Same | Compare with AP configuration (2.4, 5, 6 GHz) on a device that supports each |
| RSSI | `WifiInfo` | Same | Compare with router-side client RSSI and `adb shell dumpsys wifi` output, expect OEM differences in scale or smoothing (ASSUMPTION) |
| Link speed | `WifiInfo` | Same | Compare with router-side rate. Label as link rate, not throughput. |
| Permission states | Matrix: granted, denied, approximate-only, location off | Requirement | TEST REQUIREMENT: how approximate-only location behaves on Android 12 and newer is not verified here |
| Version paths | Below API 31 versus 31 and above | Different API surface | Test both bands (Section 4) |
| OEM differences | RSSI units and update rate | Real devices only | At least three OEM skins |

Emulator Wi-Fi is virtual, so it cannot validate any of this (LIMITATION, Section 18).

---

## 14. Cellular characteristics validation

| Field | Source | Requirement | Validation |
|---|---|---|---|
| Network type | Telephony APIs | Permission needs differ by API level. UNVERIFIED here. TEST REQUIREMENT: determine the exact permission per API level 26 to 36 and record. | Compare with the device status UI and `dumpsys telephony.registry` |
| Signal strength | `TelephonyCallback` or `PhoneStateListener` path | UNVERIFIED for signal strength alone. Cell info listening requires `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` (VERIFIED FACT from AOSP `PhoneStateListener` source). | Compare with field-test or engineering-menu values where the OEM exposes them |
| Cell info | `TelephonyManager.getAllCellInfo()` or cell-info callback | Both permissions. On API 29 and up a plain call may return cached data (PRIOR-DOC FACT). | Freshness test: change location or force an update, observe timestamps |
| Carrier and SIM state | Telephony APIs | Multi-SIM complicates which SIM is data SIM | Test each slot |
| Roaming | Network capability or telephony state | Requires roaming SIM | Only if available |
| Location requirement | Location services on | Same toggle logic as Wi-Fi | Matrix test |
| OEM differences | Reported dBm and level scaling | Real devices only | At least three OEM skins and two modem families |

Emulator cellular is synthetic or absent (LIMITATION). Whether capability 8 is worth its two-permission cost is a product question (D-18).

---

## 15. Transition testing

```
Time ->
Wi-Fi up ---- probe P1 starts ---- Wi-Fi lost ---- cellular up ---- P1 returns?
                    |                   |                |
                    v                   v                v
            engine captures       callback event    callback event
            context C_wifi        (no debounce)     (300 ms debounce)

Required: P1 must not report success attributed to either network.
```

| Transition | Method | Expected | Pass or fail |
|---|---|---|---|
| Wi-Fi to cellular | Leave Wi-Fi range or disable Wi-Fi during a probe | Callbacks `onLost` then `onAvailable` (order recorded, not assumed). In-flight probe fails with a network-changed or timeout result. | FAIL if success is reported spanning the change. |
| Cellular to Wi-Fi | Connect Wi-Fi during a cellular probe | Default network changes. Probe outcome consistent with the network the socket was bound to. | Same |
| Loss to recovery | Airplane toggle, elevator or tunnel | Loss then availability | Post-recovery probe attributed to the new network context |
| Available but unusable | Upstream blackholed | `VALIDATED` eventually absent or lost | Probe fails with the right stage. Engine currently proceeds on `available == true` (Section 2.5). |
| Switching between networks | Two Wi-Fi networks, or Wi-Fi with multiple SIMs | Transport and network identity change | Result context matches the actual path |
| Rapid alternation | Toggle repeatedly | Many events. Watch registered-callback count (100 outstanding per UID ceiling, PRIOR-DOC FACT). | No measurement straddles transitions. No callback leak. |

TEST REQUIREMENTS:
1. **Attribution.** The measurement's `MeasurementNetworkContext` must describe the network the socket actually used. Because the engine takes context from the caller and the monitor watches the default network, a race is possible. The production client should bind sockets to a specific `Network` object (PRIOR-DOC FACT: the VPN documentation describes `Network.bindSocket()` and `bindProcessToNetwork()`), and validation must confirm context and socket network agree.
2. **Detection latency.** Measure the time from the physical event to the callback in the app on each device. Report the distribution. OEM differences are expected (ASSUMPTION).
3. **Debounce interaction.** Availability events are debounced 300 ms while loss events are immediate (VERIFIED FACT). Confirm no probe starts on a stale "available" state.

---

## 16. Battery testing

### 16.1 Modes

| Mode | Description |
|---|---|
| M0 | Idle baseline: app installed, no AERIVA components active |
| M1 | Monitoring only: `AndroidNetworkMonitor` registered, no probes |
| M2 | Occasional measurement (schedule TBD, D-19) |
| M3 | Frequent measurement (stress schedule, not a product default) |
| M4 | Wi-Fi measurement |
| M5 | Cellular measurement |
| M6 | Throughput measurement (only after Section 10 safeguards exist) |

### 16.2 Measurement instruments

| Instrument | Strength | Limitation |
|---|---|---|
| External power monitor at the battery terminals | Best accuracy | Needs bench setup and a device with accessible battery or power rails |
| `dumpsys batterystats` and Battery Historian style analysis, Perfetto power traces where supported | Per-app attribution on device | Support and accuracy vary by device (ASSUMPTION). Not researched in this session. |
| Battery percentage | Universal | Coarse. Only usable for long runs. |

Instrument choice per device is OPEN DECISION D-20. Whatever is chosen must be calibrated by measuring the noise floor of repeated M0 runs.

### 16.3 Protocol

| Item | Requirement |
|---|---|
| Design | Interleave baseline and test runs (for example A B B A) to control drift from temperature, battery state and background activity |
| Duration | Long enough that the expected overhead exceeds the M0 noise floor. The number is derived from a pilot, not fixed here (D-21). |
| Device state | Same OS build, same installed apps, account sync off, fixed brightness, no user interaction |
| Screen | Test both screen-off (Doze applies, VERIFIED FACT that network is suspended in Doze) and screen-on foreground |
| Charging | Unplugged for measurement runs. Charging state recorded. |
| Battery state | Start each run in a similar state-of-charge band, recorded. Same battery health where possible. |
| Temperature | Recorded at start and end. Runs above a tolerance are discarded and repeated. Tolerance is a lab decision. |
| Network | Fixed location and network. Wi-Fi and cellular runs separate. Cellular results are location-dependent, so record signal. |
| Airplane baseline | M0 in airplane mode also recorded to separate radio cost from device idle drain |

### 16.4 Acceptable overhead

Reported as delta over M0, per measurement and per day at the default schedule. The acceptable value is a product decision (OPEN DECISION D-22). No threshold is invented here.

---

## 17. Data-usage testing

### 17.1 Accounting design

Three independent counters must reconcile:

| Counter | Source | Note |
|---|---|---|
| App-level | The client counts bytes it sends and receives, including retries | Excludes lower-layer overhead |
| OS-level | OS traffic counters for the app's UID (candidates: `TrafficStats`, `NetworkStatsManager`). Prior audit says own-app `NetworkStatsManager` needs no special permission (PRIOR-DOC FACT). Neither API was researched in this session. | Includes protocol overhead. Whether headers and retransmissions are counted needs verification (TEST REQUIREMENT). |
| Server-side | Controlled server logs bytes and connections | Ground truth for the payload |

The difference between app-level and OS-level is the overhead. It must be measured, not assumed.

### 17.2 Cases

| Case | What to measure |
|---|---|
| Latency-only probe | Bytes per probe, cold and warm (TCP and TLS setup dominate cold) |
| DNS | Bytes per lookup |
| HTTPS reachability | Handshake plus request |
| Throughput | Bytes actually transferred versus cap |
| Repeated automatic measurements | Bytes per day at the default schedule. Requires a scheduler (not yet decided). |
| Failed measurements | Bytes spent before failure |
| Retries | Cumulative bytes across retries |
| Poor networks | Extra bytes from retransmissions visible at OS level |

### 17.3 Gates

| Gate | Requirement |
|---|---|
| Daily budget | A simulated multi-day run never exceeds the cap. Cap value is OPEN DECISION D-23. |
| Metered behavior | Heavy tests off by default. Light tests allowed only within a smaller budget (D-23). |
| Silent consumption | A test that fails the build if any code path can transfer data without passing through the budget check |
| Cap survives process death | Kill the process mid-run, restart, verify counters persisted |
| Budget exhaustion | Produces explicit, user-visible state |

AERIVA must never silently consume excessive user data. Any gate failure blocks release.

---

## 18. Emulator versus physical-device matrix

Classes: **JVM** (deterministic, no Android), **EMU** (possible on emulator), **EMU-LTD** (possible but limited), **PHYS** (physical device only).

| Capability | JVM | Emulator | Physical device | Reason |
|---|---|---|---|---|
| Capability-to-permission mapping | Full | n/a | Runtime permission dialogs | Pure function |
| Latency arithmetic and orchestration | Full (fake client) | Same | n/a | Deterministic |
| Real socket timing on loopback | Full (real loopback sockets, no Android APIs) | Full | n/a | Loopback is deterministic. Not a network. |
| Latency numeric meaning | No | EMU-LTD (host path, no radio) | PHYS | Radio, scheduling, OEM |
| Jitter and loss numeric meaning | No | EMU-LTD (lossless and jitter-free shaping per third-party source) | PHYS | No real radio impairments |
| Emulator delay and speed shaping | n/a | EMU (`-netdelay`, `-netspeed` exist, VERIFIED FACT) | n/a | Developer aid, not a QA gate |
| Throughput numeric meaning | No | No | PHYS | Virtual network |
| DNS behavior | Failure-path logic on JVM resolver | EMU-LTD | PHYS | Carrier and OS caching |
| TLS and certificate fault handling | Full (loopback TLS server) | EMU | PHYS for carrier interception | Deterministic locally |
| HTTP redirect and portal-like responses | Full (fake server) | EMU | PHYS for real portals | Real portals need venues |
| Captive portal capability flag | No | Practically untestable | PHYS | Needs real portal |
| Network callback lifecycle | No | EMU (already exercised on API 30) | PHYS | OS component |
| Simulated network loss and return | Reaction to fake event | EMU (`adb` toggles) | PHYS for real handoff | Real handoff needs radios |
| Wi-Fi characteristics | Permission mapping only | No | PHYS | Emulator Wi-Fi is virtual |
| Cellular characteristics | Permission mapping only | No | PHYS | Synthetic or absent |
| Permission grant and deny flows | No | EMU (adb grant and revoke) | PHYS for OEM dialogs | OEM UI differs |
| Location-toggle behavior | No | EMU-LTD | PHYS | Needs real providers |
| Doze and App Standby (standard AOSP) | No | EMU (documented adb commands work on virtual devices, VERIFIED FACT) | PHYS for OEM | OEM layers on top |
| OEM battery restrictions | No | No | PHYS | Not in AOSP |
| Battery consumption | No | No | PHYS with instruments | Emulator is not a power model |
| Data accounting reconciliation | Counter logic | EMU-LTD (OS counters exist, values not representative) | PHYS | Overhead depends on real stack |
| VPN interaction | No | Practically untestable | PHYS | Needs real VPN app |
| IPv6 and translation | Address handling | Host-dependent | PHYS on carrier | Carrier feature |
| Process death | Persistence logic | EMU | PHYS | Kill behavior varies by OEM |
| Cancellation and timeout logic | Full | EMU | PHYS | Deterministic in code |

Rule: an emulator or JVM result is never evidence of real-network behavior (retained from Phase 3C).

---

## 19. Failure and cancellation testing

Cancellation must never yield a successful result. This is the highest-priority safety property in this section.

| Case | Method | Expected | Pass or fail |
|---|---|---|---|
| Timeout | Server never replies | `Failed(Timeout)` | FAIL if a value is produced |
| Caller cancellation | Cancel the coroutine mid-probe | `CancellationException` propagates, no result returned (VERIFIED FACT from code: only `TimeoutCancellationException` is caught) | FAIL if any `Succeeded` or `Failed` is returned for a cancelled attempt |
| Cancellation racing a timeout | Cancel at the timeout boundary | Either propagates cancellation or timeout result, never success | FAIL on success |
| Client throws instead of returning an outcome | Production client contract test with injected IOException, SSL exceptions, unknown host, socket reset | Client maps to an outcome, or engine must be changed to handle it (currently it would crash the caller) | FAIL if any exception escapes `measure` uncontrolled |
| Process death | Kill the process mid-measurement | No partial record persisted as a complete result (once persistence exists). On restart no fabricated result. | FAIL on partial record shown as complete |
| Network transition mid-measurement | Section 15 | Network-changed or timeout | FAIL on success spanning it |
| Network loss mid-measurement | Airplane toggle | Failure at the stage reached | Same |
| Duplicate measurement | Same request id twice | Defined behavior (dedupe or distinct). The engine has no dedupe (Section 2.5). | Behavior documented and tested |
| Concurrent measurement | Two `measure` calls at once | No shared-state corruption. Budget accounting correct. Note: the engine allows concurrency by design. | FAIL if budget or results corrupt |
| Permission revocation mid-run | Revoke while probing | Probe outcome not misreported. Next call re-classifies. | FAIL if stale permission state is used |
| Location state change mid-run | Toggle location | Characteristics become unavailable, no masked values shown | FAIL on masked value shown as real |
| VPN change mid-run | Start or stop VPN | Network-changed or path change labeled | FAIL on unlabeled path change |
| Captive portal appearing mid-measurement | Enable portal interception during a run | Portal state, not success | FAIL on success |
| Server unavailable | Server down | `EndpointFailure` or timeout by stage | Kind matches stage |
| Malformed server response | Wrong length, garbage, truncated, oversize | `InvalidResponse`, bounded read | FAIL on success or unbounded read |
| DNS failure | Section 12 | DNS failure kind | FAIL if generic |
| TLS failure | Section 12 | `TlsFailure` | FAIL if generic |

Contract test requirement: a shared `NetworkClient` contract test suite that runs against the fake and against every production implementation, so all implementations satisfy the same outcome and no-throw rules.

---

## 20. Security testing

Only validation requirements are defined here. No security mechanism is added by this task.

| Area | Test | Expected |
|---|---|---|
| TLS certificate validation | Server with expired, wrong-host, self-signed, untrusted-root certificates | Every one rejected. No user-disable path in release builds. |
| Endpoint authentication | Response must prove origin, for example a signed or nonce-bound payload (design decision D-24) | Forged response from another server is rejected as `InvalidResponse` |
| Malicious endpoint | Server returns huge body, slow drip, endless redirect, many connections | Bounded reads, bounded time, bounded redirects. No memory blowup. |
| Captive portal interception | Portal returns HTML with 200 | Not treated as success |
| MITM resistance | Intercepting proxy with a locally trusted CA installed on the test device | Behavior documented. AERIVA's stance on user-installed CAs is decision D-25. ASSUMPTION: default platform behavior may trust user CAs depending on the network security configuration. Verify. |
| Unexpected redirects | Redirect to another host or to cleartext | Not followed unless explicitly allowed |
| Cleartext | Endpoint over HTTP | Blocked by default. Verify against the network security configuration in use. |
| Malformed payloads | Fuzzed lengths and content | No crash, defined failure |
| Replay | Old valid signed response replayed | Rejected if freshness matters (nonce or timestamp), D-24 |
| Sensitive data leakage | Inspect payloads, logs, and any telemetry | No precise location, no SSID or BSSID beyond what the user allowed, no identifiers not needed. Wi-Fi identifiers are location-sensitive (VERIFIED FACT that the system treats them as location-sensitive). |
| Logging | Review logs for probe targets and results | No secrets or precise personal data at release log levels |
| Privacy of the endpoint | Server logs | Retention and identifiers policy defined (D-26) |
| Certificate Transparency and ECH | When targeting 37 | Regression checks (Section 4) |

---

## 21. Statistical and repeatability methodology

### 21.1 Principles

- Report distributions, not single numbers. Keep raw samples.
- Latency is right-skewed with outliers, so use the median and percentiles as primary statistics. The mean is secondary. (Standard practice. RFC 5481 also favors percentile-based delay variation.)
- Do not drop outliers silently. Any rule is part of the method identifier.
- Do not claim statistical certainty. Give an interval with a stated method, and state what the interval does and does not cover.

### 21.2 Sample counts

No universal count exists. Procedure:

1. Run a pilot on each network type: many sessions at the same place and time window.
2. Estimate the within-session spread and between-session spread of each statistic.
3. Choose sample counts per session so the statistic's interval is narrow enough for its intended use.
4. Record the choice and the pilot data as evidence.

The count derived for a stable Wi-Fi may be inadequate for cellular. Counts are per condition (OPEN DECISION D-27).

### 21.3 Confidence and intervals

| Statistic | Interval approach |
|---|---|
| Median | Bootstrap or an exact order-statistic interval |
| Percentiles | Bootstrap. With few samples, high percentiles are unreliable and must not be reported. |
| Unanswered probe rate | Exact binomial interval |
| Jitter statistic | Bootstrap over sessions |

### 21.4 Confidence tiers and their calibration

`Confidence.of` (VERIFIED FACT: 3 and 10 sample cutoffs and a 0.5 spread ratio) is illustrative. TEST REQUIREMENT: calibrate it against pilot data. For results labeled High, check how often a repeat session falls inside the stated interval and whether that rate is acceptable. The acceptable rate is an OPEN DECISION (D-28). Until calibrated, the UI and API must not present these tiers as validated confidence.

### 21.5 Degradation

Confidence must drop when: samples are few, spread is large, a transition occurred, the network was unvalidated, a VPN was active, the sample is stale, or first-after-idle effects were detected. Each rule needs its own test.

### 21.6 Environmental variability

Use blocked designs: alternate conditions in time, so drift affects all conditions equally. Record time, location, carrier, device, temperature, battery state, and neighboring load where possible. Repeat across days. Do not compare a morning Wi-Fi run with an evening cellular run and conclude anything.

### 21.7 Evidence record (every test run must produce one)

| Field | Content |
|---|---|
| Identity | Test id, plan section, date and time, tester |
| Code | Commit SHA, build type |
| Device | Model, OEM skin, Android version and build, patch level, tier, modem info if known |
| Network | Transport, carrier or AP, signal metrics, location, metered flag, VPN and Private DNS state |
| Reference | Reference method and its calibration record |
| Raw data | Every sample, not only aggregates, plus failures with kind |
| Result | Statistics, interval and method, rejected runs and why |
| Verdict | Pass, fail, or inconclusive, against the rule in this plan |

---

## 22. CI versus hardware validation

| Level | What it can run | Status today |
|---|---|---|
| **CI (JVM)** | Classifier mapping, arithmetic, orchestration against fakes, contract tests, loopback-server tests for the real client (deterministic TLS, redirect, malformed, timeout, refuse faults), budget-accounting logic, static checks | Exists for fakes. Loopback server tests need a test dependency or a small in-repo server (D-29). |
| **EMULATOR** | Instrumented callback lifecycle, permission grant flows via `adb`, Doze and App Standby standard transitions, simulated toggles, delay shaping as a developer aid | One job exists (API 30, x86_64, `google_apis`). Other API levels not configured. |
| **PHYSICAL DEVICE LAB** | Wi-Fi and cellular characteristics, OEM behavior, battery, real handoffs, Android versions 31 to 36, real device timing | **Does not exist.** CircleCI cannot validate any of this. Options: own device shelf with a controlled hotspot and server, or a cloud device service (not researched here; ASSUMPTION: datacenter-hosted devices have no real cellular or Nigerian network conditions). Decision D-30. |
| **MANUAL NETWORK TEST** | Real captive portals, real carriers, roaming, field locations, poor-signal sites, power-interruption scenarios | Manual, requires people and locations. |
| **LONG-RUN TEST** | Multi-day battery and data-budget runs, scheduler behavior, process-death behavior, drift | Requires devices and a scheduler that does not exist yet. |

Rules:
1. CircleCI green is never evidence of anything radio-related.
2. Every physical test result is tied to a commit SHA and produces an evidence record (Section 21.7).
3. Physical-lab and manual results are attached to the release checklist, not to CI.

### 22.1 Regression matrix

| Trigger | Must rerun |
|---|---|
| Any `NetworkClient` change | JVM contract tests, loopback fault tests, one physical device smoke run |
| Classifier or manifest change | JVM mapping tests, permission matrix on device |
| Domain type change | JVM arithmetic, calibration re-check |
| targetSdk bump | Section 4 version track for the new version, local-network and TLS behavior checks |
| New OEM or Android version in the fleet | Device matrix rows for that device |
| Budget or scheduler change | Data-usage and battery long runs |

---

## 23. Production-readiness criteria

A capability is production-ready only when every listed evidence item exists in the evidence record format. "Works" is not evidence.

| Capability | Required evidence |
|---|---|
| Latency | Method identifier defined. Timing offset measured on each performance tier. Known-delay server agreement documented. Wire-capture agreement on at least two devices and both transports. Cold and warm reported separately. All failure kinds validated (Section 19). VPN and IPv6 behavior documented. Battery and data within accepted budgets. |
| Jitter | Definition chosen and documented (IPDV or PDV). Domain type exists. Order and failed-sample handling tested. Tracks injected jitter across levels. Repeatability documented. Minimum evidence rule set. |
| Packet loss | Does not ship as "packet loss" until ladder stage D. Until then it ships only as a named unanswered-probe rate with its definition, or not at all. Calibration against injected loss on two or more devices. Zero-loss case shows no phantom loss. |
| Throughput | All Section 10 safeguards exist and are tested. Byte-budget enforcement verified against OS and server counters. Agreement with a reference on more than one network type. Metered default verified. Battery and data acceptable. |
| DNS | Three categories kept separate in the model. Private DNS rules verified by packet capture. Cache miss and hit demonstrated. Estimated label retained. Carrier differences disclosed. |
| HTTPS reachability | All seven states representable. Every fault in the Section 12 matrix produces its distinct result. Real portal tested. Signed or nonce-bound payload verified. |
| Wi-Fi characteristics | Permission and location matrix passed on Android 12 or newer and older. Values match reference on at least three OEM skins. Redacted values never displayed as data. |
| Cellular characteristics | Permission requirements per API level determined. Freshness behavior verified. Values match a reference on at least three OEM skins and two modem families. SIM states covered. |
| Network stability | Definition fixed (D-08). Correlation with injected and real instability shown. |
| Network transitions | All Section 15 cases pass. Attribution verified. Detection latency documented. No callback leaks under rapid alternation. |
| Cross-cutting | INTERNET declared through the decision process. Security tests (Section 20) pass. Cancellation never yields success. Device and version floors met. Open decisions relevant to the capability closed. |

---

## 24. Test execution order

```
P0  Prerequisites and decisions
     |  D-decisions closed for endpoint, INTERNET, domain types, failure taxonomy
     v
P1  JVM: NetworkClient contract tests, arithmetic, budget logic
     v
P2  JVM loopback: real client vs local server (TLS, redirect, malformed, timeout, refuse)
     v
P3  Emulator: callback lifecycle, permission flows, Doze/Standby, simulated toggles
     v
P4  Bench, one physical device: known-delay server, wire capture, engine overhead offset
     v
P5  Impairment lab: netem calibration, then injected delay, jitter, loss, transitions
     v
P6  Multi-device: tiers, OEMs, Android versions, both transports
     v
P7  Field and manual: real carriers, portals, poor signal, Nigerian scenarios (Section 5.4)
     v
P8  Battery and data long runs
     v
P9  Release gate: Section 23 checklist, evidence records reviewed
```

Blocking prerequisites for P1 onward:

| Prerequisite | Status |
|---|---|
| Production `NetworkClient` implementation | Missing |
| `INTERNET` permission decision | Open |
| Controlled reference server and endpoint policy | Missing |
| Domain types for jitter, loss, throughput, DNS | Missing |
| Failure taxonomy extended (DNS, portal, blocked, unknown) | Missing |
| Sample interval control for series | Missing |
| Budget and scheduler design | Missing |
| Physical device shelf and impairment box | Missing |

If any of these cannot be produced, the corresponding capability stays out of production.

---

## 25. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Validation drifts into implementation | Scope creep, unreviewed production code | Non-goals (Section 27) |
| Measuring the endpoint, not the network | Misleading results | Controlled endpoint only |
| Presenting application-level timing as network RTT | Over-claim | Method identifier, naming rules |
| Packet-loss over-claim | Loss of trust | Ladder in Section 9.4 |
| Small device set generalized to all users | False confidence | Coverage floor, single-device labeling |
| Impairment box inaccurate | Bad ground truth | Calibrate first |
| Emulator or CI green mistaken for real validation | Release of unvalidated features | Rules in Sections 18 and 22 |
| Silent data consumption | User harm, cost | Section 17 gates |
| Battery drain | Uninstalls | Section 16 |
| OEM background killing breaks scheduled work | Missing data | Device coverage, honest deferral reporting |
| Confidence tiers treated as validated | Overconfident UI | Calibrate first (Section 21.4) |
| Prior-document errors propagate | Wrong API assumptions | Independent verification, correction noted in Section 1 |
| Carrier treats traffic types differently | UDP or endpoint-specific bias | Cross-protocol comparison per carrier |
| Location permission cost and Play policy | Capability 7 and 8 may be dropped | Product decision (D-18) |
| Cloud device farms lack real networks | Weak coverage claims | Do not count them for cellular |

---

## 26. Open decisions

| ID | Decision |
|---|---|
| D-01 | Definition of device performance tiers |
| D-02 | Device selection driven by AERIVA's own market data. Phones only for first release. |
| D-03 | Exact minimum device counts for release |
| D-04 | Whether measurement is supported on the full minSdk 26 range or a higher floor |
| D-05 | Whether any foreground service will ever be used |
| D-06 | Whether Android 17 hardware and targetSdk 37 tracks are added now |
| D-07 | Whether to test traffic-type-specific treatment by carriers |
| D-08 | Definition of "network stability" |
| D-09 | Numeric latency agreement bounds (from pilot data) |
| D-10 | Jitter definition (IPDV, PDV, or both) and failed-sample handling |
| D-11 | Jitter sample count and minimum evidence |
| D-12 | Acceptable jitter repeatability |
| D-13 | Probe counts for loss calibration |
| D-14 | Battery guard thresholds for throughput |
| D-15 | Whether to use M-Lab NDT7 as a reference (privacy and data-policy review) |
| D-16 | Throughput minimum duration and repeat count |
| D-17 | Final HTTPS state model and domain type additions |
| D-18 | Whether cellular characteristics are worth their permission cost |
| D-19 | Default measurement schedule (blocked on scheduler decision) |
| D-20 | Battery instrumentation per device |
| D-21 | Battery run duration |
| D-22 | Acceptable battery overhead |
| D-23 | Daily data budgets (metered and unmetered) |
| D-24 | Endpoint authentication and freshness scheme |
| D-25 | Stance on user-installed CAs |
| D-26 | Server-side logging and retention policy |
| D-27 | Per-condition sample counts |
| D-28 | Acceptable confidence calibration rate |
| D-29 | Loopback test server approach and dependency |
| D-30 | Physical lab approach (own shelf versus cloud versus both) |

---

## 27. Explicit non-goals

- No implementation of the real measurement engine or a production `NetworkClient`.
- No change to main. No merge.
- No Android permission added (including `INTERNET`).
- No WorkManager, Supabase, UI or scheduler.
- No domain-model change. Required additions are recorded as gaps only.
- No edit to any other AI's branch or document.
- No invented device results, network results, numbers, thresholds or APIs.
- No claim that any test has been executed.
- No endpoint, server or protocol choice (recorded as decisions).
- No claim about the behavior of all users or all networks in Nigeria or Africa.

---

## 28. Official research sources

### 28.1 Fetched and read in this session (VERIFIED FACT basis)

| Topic | Source |
|---|---|
| `NetworkCallback` constructors and `FLAG_INCLUDE_LOCATION_INFO` (API 31) | https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback and https://developer.android.com/reference/kotlin/android/net/ConnectivityManager.NetworkCallback |
| API 31 diff showing the flag and constructor were added | https://developer.android.com/sdk/api_diff/31/changes/android.net.ConnectivityManager.NetworkCallback |
| Platform commit: location info redacted by default, opt-in flag | https://android.googlesource.com/platform/frameworks/base/+/d9c78f69929e%5E%21 |
| Origin of the incorrect "will be removed" notice (Microsoft .NET binding docs, not Android SDK docs) | https://learn.microsoft.com/tr-tr/dotnet/api/android.net.connectivitymanager.networkcallback.flagincludelocationinfo?view=net-android-36.0 |
| `NET_CAPABILITY_VALIDATED`, `CAPTIVE_PORTAL`, `NOT_METERED`, `INTERNET` semantics | https://developer.android.com/develop/connectivity/network-ops/reading-network-state and https://developer.android.com/reference/kotlin/android/net/NetworkCapabilities |
| Private DNS APIs and rules for apps | https://developer.android.com/reference/kotlin/android/net/LinkProperties and https://developer.android.com/sdk/api_diff/28/changes/android.net.LinkProperties |
| Doze and App Standby behavior and adb test commands | https://developer.android.com/training/monitoring-device-state/doze-standby |
| Android 16 behavior changes (JobScheduler quotas) | https://developer.android.com/about/versions/16/behavior-changes-all |
| Android 17 behavior changes (`ACCESS_LOCAL_NETWORK`, ECH) | https://developer.android.com/about/versions/17/behavior-changes-17 |
| Android 17 enterprise page (CT mention, details not verified) | https://developer.android.com/work/versions/android-17 |
| Cell info listening permissions (AOSP source) | https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/telephony/PhoneStateListener.java |
| Emulator network simulator, pcap, and shaping options | https://developer.android.com/studio/run/emulator-networking-advanced and https://developer.android.com/tools/help/emulator |
| Round-trip delay metric and its weaknesses | https://www.rfc-editor.org/rfc/rfc2681 |
| Packet delay variation formulations | https://datatracker.ietf.org/doc/html/rfc5481 |
| Round-trip loss metrics and references to RFC 3393 | https://www.rfc-editor.org/rfc/rfc6673.html |
| ndt7 protocol (application-level goodput over a single TCP connection) | https://github.com/m-lab/ndt-server/blob/main/spec/ndt7-protocol.md and https://www.measurementlab.net/blog/ndt7-introduction/ |

### 28.2 Secondary sources (not authoritative, used only as pointers)

| Topic | Source |
|---|---|
| Emulator shaping is lossless and jitter-free, `tc netem` for real-device impairment | https://www.forasoft.com/blog/article/stimulate-slow-internet-connection-android |
| Java `isReachable` ICMP versus TCP echo fallback, as quoted from the Java API text | https://www.rgagnon.com/javadetails/java-0093.html |

### 28.3 Prior AERIVA documents relied on as PRIOR-DOC FACT (not re-verified)

`PHASE_2_ANDROID_PLATFORM_AUDIT.md`, `PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md`, `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`, `PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md`, `PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md`, `PHASE_3B_ANDROID_CONTRACT_FINAL_REVIEW.md`, `PHASE_3B_REPOSITORY_STATE_RECONCILIATION.md`, `PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md` (with the correction in Section 1), `CIRCLECI_VALIDATION.md`.

### 28.4 Referenced from author knowledge, not fetched in this session

RFC 2680 (one-way loss), RFC 3393 (IPDV, verified only through citations in other RFCs), RFC 6349 (TCP throughput testing framework), `curl --write-out` timing variables, `iperf3`, `tc netem`.

### 28.5 Not researched in this session

Battery instrumentation (Battery Historian, Perfetto, batterystats), `TrafficStats` and `NetworkStatsManager` semantics, cloud device services, Google Play policy on location permissions, per-API-level telephony permission requirements for network type and signal strength, approximate-location behavior for Wi-Fi details, network security configuration defaults for user-installed CAs.
