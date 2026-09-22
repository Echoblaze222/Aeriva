# PHASE 5: MEASUREMENT VALIDATION HARNESS

Author: AI 6. Date written: 2026-09-21.
Base: `phase-4-cross-cutting-decisions` @ `8a56dc70b19b170d9e334e797b132a65ea3be612`.
Companion documents: `PHASE_5_DEVICE_TEST_MATRIX.md` (the status board) and `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md` (fixtures, evidence model, schema).

This document is a validation methodology and test plan. It does not implement measurement, does not modify AI 1's Phase 4 foundation work, does not touch `LatencyMeasurementEngine.kt`, `MeasurementCapabilityClassifier.kt` or any Phase 3A domain type, adds no permission, no dependency, no UI, no WorkManager, no Supabase, and changes nothing on `main`.

Basis labels used throughout, matching the cross-cutting contract's convention: VERIFIED FACT (read from official documentation fetched in this session, sources in section 16), REPO FACT (read from code or configuration in this repository), PRIOR-DOC FACT (stated in an earlier AERIVA document and not re-verified here), ASSUMPTION (unverified), PROVISIONAL (a harness rule a later specification must confirm or replace), OPEN DECISION (owner or design work this document does not resolve).

## 0. Repository and branch state inspected

| Item | Value | Basis |
|---|---|---|
| `main` | `e3f70a4a13e63b782c61601abadc2c36f65dbd73` (Phase 3A only) | REPO FACT |
| `phase-4-cross-cutting-decisions` | `8a56dc70b19b170d9e334e797b132a65ea3be612`, this document's base | REPO FACT |
| `phase-4-measurement-foundation` | `9a28cc62...` (AI 1's branch, read-only reference) | REPO FACT |
| `phase-3b-measurement-engine` | `727b2a91d2724735c3f22965ca72cf4311202370`, source of `LatencyMeasurementEngine`, `MeasurementCapabilityClassifier`, `NetworkClient`, `DerivedLatencyStats`, `Confidence`, `Freshness`, test doubles | REPO FACT |
| CircleCI config | Four jobs: build, unit_tests, static_checks, connected_android_test. The connected job runs one emulator image, `android-30;google_apis;x86_64`. `network` in the emulator console covers ethernet and cellular only, per its own help text. | REPO FACT |
| SDK | `minSdk 26`, `targetSdk 36`, `compileSdk 36` | REPO FACT |
| Existing test doubles | `FakeNetworkClient` (scripted outcomes, counts calls, never swallows cancellation), `MutableClock` (wraps the existing `() -> Instant` seam), `ReferenceLatencyProbeExecutor` | REPO FACT, all at `phase-3b-measurement-engine@727b2a91` |
| AI 1's foundation additions | `MeasurementStage`, `ProbeEvidence`, `MeasurementMethod` registry, `DerivedJitterStats`, extended `MeasurementFailure`, `NetworkState` gains `captivePortalReported`, `vpnPresent`, `blockedByDevicePolicy`, `MeasurementEndpointConfig` (deliberately null) | REPO FACT, `phase-4-measurement-foundation@9a28cc62`, read-only |
| INTERNET permission | Not declared anywhere. Only `ACCESS_NETWORK_STATE` in `network:monitor`. | REPO FACT |
| Production `NetworkClient` | Does not exist on any branch. `FakeNetworkClient` is the only implementation. | REPO FACT |

Nothing above was modified. `phase-4-measurement-foundation` was read for inspection only.

## 1. Validation strategy: layers L0 to L7

| Layer | What it exercises | What it can prove | What it cannot prove |
|---|---|---|---|
| L0 static analysis | Android Lint (REPO FACT: `static_checks` job runs `./gradlew lint`), the manifest allowlist check (contract decision D1-6, not yet built), this document's own checker (`validate_validation_assets.py`) | Code compiles under lint rules, the evidence schema is internally consistent, fixture arithmetic matches documented semantics | Runtime behavior of any kind, including on the JVM |
| L1 JVM unit tests | Deterministic Kotlin logic against fakes and hand-built values (REPO FACT: the existing latency, classifier, mapper, jitter and taxonomy tests all run this way) | Arithmetic, classification mapping, timeout and cancellation boundaries, exhaustive `when` coverage | Anything that needs a real socket, a real Android API, or real timing |
| L2 instrumentation tests (JVM-hosted, no device) | Robolectric-free pure logic that happens to be exercised through an instrumented harness, or, in this document's own usage, the language-neutral checker run against golden fixtures (section 5) | Same ceiling as L1, run through a different harness | Same ceiling as L1 |
| L3 emulator tests | `AndroidNetworkMonitorInstrumentedTest` today (REPO FACT: it proves the monitor wires up to a real `ConnectivityManager` on the API 30 image without crashing, and explicitly does not exercise a transition, per its own doc comment). Simulated toggles via `adb` and the emulator console. | That Android APIs are called correctly and callbacks fire without crashing, under a virtual network stack | Anything about real radios, real Wi-Fi hardware, real OEM software, real carriers, or real battery behavior. The emulator's Wi-Fi and cellular are virtual (VERIFIED FACT for the console's own documented scope: `network` there covers ethernet and cellular only). |
| L4 physical Android device tests | The app on real hardware, no controlled network yet | Real permission dialogs, real OEM background behavior, real API surface responses (signal APIs, Wi-Fi APIs) | Nothing about a specific measurement value's accuracy, because there is no reference yet |
| L5 controlled network tests | A physical device against a controlled reference server (CRS, contract section 3) or the loopback fixture server (LFS), on a network the lab can shape | Correctness of measurement logic against known ground truth (a known injected delay, a known fault) | Real-world variability the lab does not reproduce |
| L6 adverse network tests | Physical device on real degraded networks (weak signal, real congestion, real captive portals) or lab-injected faults at larger scale | That the classification and failure taxonomy behave correctly under conditions that are expensive or slow to reproduce in L5 | A statistically defensible claim about typical adverse-network behavior, which needs many repeated sessions (section 8) |
| L7 long-duration and battery tests | Multi-hour or multi-day runs on physical devices | Battery overhead, data budget adherence, scheduler behavior over time | Anything faster layers already prove; this layer exists only for what needs elapsed real time |

No layer above L3 exists in CI today (REPO FACT). L4 through L7 require a physical device by definition and are marked REQUIRES PHYSICAL DEVICE throughout this document and its companions.

## 2. What already exists and is reused

Before adding anything, the following were checked and confirmed present, so nothing here duplicates them (REPO FACT, `phase-3b-measurement-engine@727b2a91` unless noted):

| Existing helper | Role | Reused as |
|---|---|---|
| `MutableClock` | Deterministic clock wrapping `() -> Instant` | The freshness fixtures (section 5.6) are written to be fed through this exact seam once a Kotlin runner exists. No second clock abstraction is introduced. |
| `FakeNetworkClient` | Scripted `NetworkClient` outcomes, call counting, cancellation-safe | The UDP and HTTPS fixtures are written so a comparable scripted double (not yet built, since `NetworkClient`'s shape is still pre-migration per the cross-cutting contract's D5-11) can consume them later. No second fake is built now. |
| `DerivedLatencyStats.from`, `Confidence.of` | Pure aggregation and confidence tiering | The latency fixture oracle in `validate_validation_assets.py` is a line-for-line transliteration of this logic, not a new design. |
| `Freshness.isStaleAt`, `Freshness.ageAt` | Staleness and age | Reused as-is; fixtures test it, nothing wraps it. |
| `DerivedJitterStats.from` (foundation branch) | Jitter aggregation | Read, not modified. Its adjacency rule is reused as the oracle's rule. A suspected divergence is reported, not patched (section 9). |

What did not exist and is added by this checkpoint: golden fixtures in a language-neutral format, an evidence-record JSON Schema, a checker script, and this set of three documents. All are additive; none replaces an existing Kotlin file, and no existing file is modified.

## 3. Network condition scenarios (NC-A to NC-M)

Every scenario names its setup, expected observation, expected classification, required evidence, and limitations. None has been executed. Status is NOT YET TESTED unless stated otherwise. Scenario IDs are referenced by the device matrix and the evidence schema's `test_case_ref` pattern.

| Scenario | Setup | Expected observation (EXPECTED) | Expected classification | Evidence required | Limitations |
|---|---|---|---|---|---|
| NC-A healthy Wi-Fi | Physical device on a known-good access point, uncongested, wired backhaul | Low, stable latency; `validated` true; `metered` false | `MEASURED_SUCCEEDED` for latency, jitter, HTTPS reachability | Evidence record with device, network and derived fields populated | Still only one access point unless the lab varies it |
| NC-B high latency | LFS or CRS behind an injected fixed delay (calibrated first, per the real-device validation plan section 7.5) | Latency rises by roughly the injected delay plus baseline overhead | `MEASURED_SUCCEEDED`, elevated value | Recorded injected delay in `condition.impairment.added_delay_ms`, recorded observed value | On the emulator, injected delay via console or startup options is a developer aid, not a calibrated reference (see the divergence between `-netdelay` documentation and any independent measurement) |
| NC-C variable latency | Same as NC-B with a jitter distribution injected | Jitter statistic rises with the injected jitter, direction and rough magnitude | `MEASURED_SUCCEEDED`, elevated `DerivedJitterStats` | Injected jitter parameters recorded, observed jitter recorded | The emulator's shaping is documented as a developer feature; whether it is lossless and jitter-free by design is ASSUMPTION pending a primary source (see section 16) |
| NC-D intermittent response | Endpoint or path drops responses unpredictably (lab fault injection, not real network) | Some samples succeed, some fail; no averaging across a failure | Mixed `MEASURED_SUCCEEDED` and `MEASURED_FAILED` samples in one series | Full raw sample sequence, not only the aggregate | Distinguishing "intermittent" from a short burst of real congestion needs many repeated sessions |
| NC-E DNS failure | Reference domain configured to not resolve, or a resolver blackholed at the router | Lookup does not resolve within the client's own timer | `DnsFailure` with kind `NOT_RESOLVED` or `CLIENT_TIMED_OUT` | Private DNS mode recorded, lookup outcome recorded | The device cannot separate a nonexistent name from a resolver failure at this API level (LIMITATION, carried from the real-device validation plan) |
| NC-F timeout | Endpoint accepts the connection and never responds, or blackholes packets after connect | No response within the deadline at a specific stage | `Timeout(stage)` | Stage recorded, deadline configuration recorded | A slow success and a timeout must stay distinct; this scenario alone does not prove that boundary (see NC-F' in section 4) |
| NC-G captive portal | Real or lab portal that intercepts before sign-in | Platform reports the captive-portal capability; the probe's response shows interception (unexpected redirect, wrong body, or a TLS failure) | `CaptivePortalSuspected`, never `Succeeded` | Both signals recorded: `network.captive_portal_reported` and the specific interception evidence | Cannot be reliably produced on the emulator (REQUIRES PHYSICAL DEVICE) |
| NC-H metered network | Wi-Fi marked metered in device settings, or a metered cellular connection | `network.metered` true; heavy capabilities (throughput) do not run without explicit consent | Latency may proceed within budget; throughput declines or requires consent | Metered flag recorded, capability outcome recorded | Data budget values are an owner decision (contract OD-6), not fixed here |
| NC-I VPN | A real third-party VPN app active | `vpnPresent` true; results labeled as VPN-path; underlying transport hidden by the existing mapper (REPO FACT) | `MEASURED_SUCCEEDED` or `MEASURED_FAILED`, labeled VPN-path | VPN state recorded | Cannot be reliably produced on the emulator |
| NC-J network transition | Physical device moves out of Wi-Fi range, or Wi-Fi and cellular are toggled, during an in-flight series | In-flight probe ends as a network-change result or a timeout, never a success attributed across the change; jitter pairs break at the boundary | `NetworkChangedDuringMeasurement` or `Timeout(stage)` for the interrupted sample | Full sample sequence with per-sample network handle, so the break point is visible | Real handoff timing cannot be reproduced on the emulator; simulated toggles only prove the callback path, not real handoff latency |
| NC-K no connectivity | Airplane mode, or a Wi-Fi connection with the upstream fully cut | No default network (airplane mode) or `validated` false with `available` true (cut upstream): these are different conditions and must not be conflated | Airplane mode: an engine-level decline, not a `MeasurementFailure`. Cut upstream: an attempted probe that fails at whatever stage it reaches. | Which of the two conditions was set up, recorded explicitly | The engine today reads only `available`, not `validated` (REPO FACT at `phase-3b-measurement-engine@727b2a91`), and whether AI 1's foundation branch already changed this was not confirmed by this session; treated as a question for the implementation team, not assumed either way |
| NC-L weak cellular | Physical device at a real low-signal site | Elevated latency, more timeouts, possibly more unanswered UDP probes; still no result claimed as "packet loss" | `MEASURED_SUCCEEDED` with a high value, or `Timeout`, or `NoResponseFromEndpoint` for the UDP case, never a loss percentage | Signal metrics recorded where permissions allow, site label (coarse) recorded | Signal-strength reporting needs `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION`, neither of which is declared today (REPO FACT); this scenario cannot record signal detail until the owner decides on those permissions |
| NC-M unstable cellular | Physical device moving through variable-coverage terrain, or repeated brief losses | Alternating success, failure and transition events; no smoothing that hides the instability | Mixed outcomes across a series, transitions recorded | Full raw sequence with timestamps | "Unstable" has no fixed definition yet; this document does not invent one (open item, section 15) |

Scenario NC-F' (slow success versus timeout, referenced above) is not a separate letter; it is a required variant of NC-F where the injected delay is set just under and just over the deadline, to prove the boundary is exact.

## 4. Measurement validation: what each capability check must show

None of the checks below has been executed against real code. Each states the property and how it would be verified once a runner exists (section 9).

| Capability | What must be verified | How (once a Kotlin runner exists) | Non-goal |
|---|---|---|---|
| Latency | `DerivedLatencyStats.from` reproduces the fixture oracle exactly (section 5.6, `latency-series.json`) for every fixture, including the empty, single-sample, negative-value-rejected and all-timeout cases | Feed each fixture's samples through the real function, compare to `expected.latency` | Does not verify real-network accuracy; that is L5/L6 |
| Jitter | `DerivedJitterStats.from` reproduces the oracle for every fixture in `latency-series.json`, with special attention to FIX-LAT-009 and FIX-LAT-010 (section 9) | Same mechanism | Does not verify real-network jitter |
| Unanswered probes | A UDP train implementation (not yet built) reproduces the oracle in `udp-trains.json` for answered, late, duplicate, out-of-order, ignored and unanswered counts, and never reports a non-zero-answered train as a percentage-loss figure | Same mechanism, once the client exists | Never asserts a "packet loss" result; the naming discipline itself is the thing under test |
| Throughput | A throughput implementation (not yet built) reproduces `throughput.json`, including the zero-byte-is-a-failure rule and the byte-cap and time-box termination reasons | Same mechanism, once the client exists | Does not verify real achievable throughput |
| DNS responsiveness | A DNS evidence path reproduces `dns-outcomes.json`'s tier and failure-kind mapping | Same mechanism, once built | Does not claim to measure a resolver directly, per its estimation tier |
| HTTPS responsiveness | The failure-mapping and portal-suspicion logic reproduces every row of `https-outcomes.json`, especially the two-signal rule pairs (FIX-HTTPS-005/006, FIX-HTTPS-011, FIX-HTTPS-017) | Same mechanism, once the mapper exists | Does not verify a real portal's exact HTML |
| Network state | `NetworkStateMapper` (existing, REPO FACT) and its foundation-branch extensions correctly compute `captivePortalReported`, `vpnPresent`, `blockedByDevicePolicy` from raw capability snapshots | Existing and extended unit tests, read but not modified here | Does not verify real platform callback timing; that needs L3 to L4 |
| Freshness | `Freshness.isStaleAt` and `ageAt` reproduce `freshness-confidence.json` exactly, including the boundary case (exactly at expiry is not stale) | Same mechanism | N/A, this is pure arithmetic |
| Confidence | `Confidence.of` reproduces the six confidence fixtures, understood as illustrative and not validated (REPO FACT: the code's own KDoc says so) | Same mechanism | Does not claim these thresholds are correct; only that the code does what it currently says it does |

## 5. Golden test data

Full detail, including every fixture ID, its input and its expected output, is in `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md`, section 2. Summary here:

| Set | File | Count | Covers |
|---|---|---|---|
| Latency and jitter series | `validation/fixtures/latency-series.json` | 13 | The requested healthy (10, 20, 30, 40) and variable (10, 100, 15, 200) sequences, timeout sequences, gap handling, network-change and cold/warm labeling hazards, unordered input |
| UDP probe trains | `validation/fixtures/udp-trains.json` | 8 | Clean, missing, duplicate, reordered, late, no-reply, unknown-sequence, all-late |
| Throughput | `validation/fixtures/throughput.json` | 5 | Completed, byte-cap stop, time-box stop, zero-byte failure, ramp-excluded rate |
| DNS outcomes | `validation/fixtures/dns-outcomes.json` | 5 | Resolved, not resolved, client timeout, strict Private DNS, cache pair |
| HTTPS outcomes | `validation/fixtures/https-outcomes.json` | 19 | Every L1-to-L2 mapping in the cross-cutting contract's decision D5-6, plus the two-signal portal rule |
| Freshness and confidence | `validation/fixtures/freshness-confidence.json` | 4 plus 6 | Staleness boundary, illustrative confidence tiers |

Every fixture file states `"synthetic": true` and a `note` field saying it is not a real measurement. This is enforced, not just stated: the evidence schema requires a `FIXTURE-` prefix on the `test_id` of any record marked synthetic, and rejects that prefix on any record not marked synthetic.

## 6. Evidence model

Full field-by-field mapping is in `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md`, section 3. The model captures test identity, execution metadata, build identity, device identity, network state, the applied condition, the endpoint role (LFS or CRS only, never a production role), the measurement outcome and samples, the expected and observed results, the verdict with an investigation branch, log references, environment, and limitations. It deliberately excludes anything the schema itself forbids (section 12).

## 7. Repeatability

| Aspect | Rule | Status |
|---|---|---|
| Sample count | Not fixed by this document. The real-device validation plan (its own D-11, D-13, D-16, D-27) already defers this to pilot data, and this document does not override that. Any count used in a fixture (for example the ten-sample confidence fixtures) is a PROPOSED TEST PARAMETER for illustrating the current code's behavior, not a claimed product threshold. | OPEN DECISION, carried from the validation plan |
| Warmup | A cold-connection sample and warm-connection samples must never be mixed into one aggregate (FIX-LAT-012 demonstrates why, as rule H-AGG-1 below). A series intended to measure warm-connection latency should include and then discard, or clearly separate, its first cold sample. | Harness rule H-WARM-1 |
| Cooldown | Not defined here. If back-to-back series across different conditions are run without a pause, residual radio state (mobile radio staying in a high-power state) could bias the first sample of the next series. This is flagged as a risk (section 14), not resolved. | OPEN DECISION |
| Ordering | Samples must be recorded and consumed in true send order. The jitter oracle explicitly rejects out-of-order timestamps (FIX-LAT-013). | Harness rule, enforced by the schema's implicit ordering expectation and the checker |
| Inter-sample interval | The existing `measureSeries` has no interval control (REPO FACT, noted as a gap in the real-device validation plan's own section 2.5, item 3, and not yet closed on the foundation branch as read in this session). This document does not add one; it is implementation work for AI 1's track. | Dependency, not resolved here |
| Outliers | Never silently dropped. Report the full raw sequence alongside any aggregate, matching the existing code's own behavior of keeping every sample it uses in `sourceMeasurementIds`. | Harness rule H-OUT-1 |
| Environmental change mid-series | A network change, VPN change, or captive-portal appearance mid-series breaks jitter adjacency and must be visible in the per-sample network handle, matching the existing jitter adjacency rule. | Enforced by the existing jitter rule, reused |

Harness rule **H-AGG-1** (aggregation hazard rule, referenced by FIX-LAT-011 and FIX-LAT-012): latency aggregation across a network change or across mixed cold and warm connection states is not asserted by any fixture as a single number, because doing so would hide a labeling problem behind a plausible-looking average. A latency aggregate's `sourceMeasurementIds` should come from one network and one connection state; if a future implementation aggregates across either, that is a design question for the owner and the implementation team, not something this harness pre-approves by supplying an expected value.

## 8. Physical device protocol

A practical, step-by-step procedure for a lab session. No step has been executed.

1. **Clean install.** Uninstall any prior build (`adb uninstall com.aeriva`, once a package ID exists; REPO FACT: `AerivaApplication` and namespace `com.aeriva.network.monitor` exist, but no launcher activity or application ID for `:app` exists yet per its manifest, so this step is currently blocked on `:app` having an installable build). Install the build under test with `adb install -r <path-to-apk>`.
2. **Permission setup.** Grant or deny the specific permission set under test (`adb shell pm grant <package> <permission>` or the reverse with `revoke`), and record the exact set granted in the evidence record's device and network sections.
3. **Baseline connectivity.** Confirm the device has the intended network active before starting (`adb shell dumpsys connectivity` for a summary, or `adb shell cmd connectivity` where available). Record transport, validated and metered state.
4. **Network capture (optional, lab-only).** If a wire capture is part of the session (real-device validation plan section 7.5), start it on the lab's own isolated segment before any traffic runs. Never capture on a shared or production network.
5. **Measurement execution.** Trigger the capability under test through whatever entry point exists at the time (today, none exists in `:app`; this step is a placeholder until AI 1's client and a way to invoke it are built).
6. **Repeated samples.** Run the series length appropriate to the scenario (section 7). Do not stop early because early samples look stable.
7. **Network transition.** For NC-J, perform the transition mid-series using a stopwatch or a logged timestamp so the transition point can be correlated with the sample sequence afterward.
8. **Adverse condition.** For NC-E through NC-M, apply the specific condition (captive portal, cut upstream, weak-signal site) and confirm via `dumpsys connectivity` or the platform UI that the intended condition is actually in effect before recording samples.
9. **Log collection.** Pull only the filtered logs needed (`adb logcat -d <package tag filters>`), never a full bug report, per section 12. Compute a sha256 of each log file for the evidence record.
10. **Result recording.** Fill in an evidence record conforming to the schema in `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md`, including the verdict and, on any failure, an investigation branch from section 11.
11. **Cleanup.** Stop any capture, restore the device's normal network and permission state, and clear app data if the next session needs a clean baseline (`adb shell pm clear <package>`, or Android Test Orchestrator's `clearPackageData` if instrumentation is used, VERIFIED FACT that this flag exists for that purpose).

## 9. Failure investigation decision tree

```
Test failure
|
+-- Did it fail to compile? --------------------------------> COMPILE
|
+-- Did a JVM unit test fail (no device involved)? ---------> UNIT
|
+-- Did an instrumented/emulator test fail
|   with no real device involved? --------------------------> INSTRUMENTATION
|
+-- Did the app fail to reach the network at all
|   (no default network, or a SecurityException
|   at socket/DNS creation)? --------------------------------> CONNECTIVITY or PERMISSION
|     -- No default network present ------------------------> CONNECTIVITY
|     -- SecurityException / permission-shaped failure ------> PERMISSION
|
+-- Did behavior differ only on one OEM or one device,
|   not on others under the same conditions? ----------------> OEM_BEHAVIOR
|
+-- Did the measurement's own logic look wrong
|   (wrong classification, wrong aggregate, a case
|   that should be distinct collapsed into another)? --------> MEASUREMENT_IMPLEMENTATION
|
+-- Did conditions during the run not match what was
|   configured (network changed unexpectedly, lab
|   equipment malfunctioned, carrier load spiked)? -----------> ENVIRONMENT
|
+-- None of the above cleanly applies -----------------------> UNDETERMINED (record enough
                                                                 detail for a follow-up pass)
```

Rules to prevent false conclusions:

1. A failure is never marked `MEASUREMENT_IMPLEMENTATION` from a single physical-device run. At least one repeat on the same device, and ideally a second device, must reproduce it first; otherwise it is `UNDETERMINED` or `ENVIRONMENT`.
2. A failure is never marked `OEM_BEHAVIOR` without a comparison run on a second, different OEM device under the same conditions.
3. `CONNECTIVITY` and `PERMISSION` are checked before anything else, because most other branches assume the app actually reached the network.
4. `investigation_branch` in the evidence schema uses exactly these nine values (`NOT_APPLICABLE` for the tenth, non-failure case), so a record can be searched by branch later.

## 10. Regression gate

This document proposes gates as a structure; it does not set pass thresholds, because no pilot data exists yet to set them from (matching the real-device validation plan's own refusal to invent numeric thresholds without a basis).

| Gate | Must be true before |
|---|---|
| G-P5 (this checkpoint) | Schema validates, all fixtures validated against their own oracle, all fixture-derived evidence examples validate, negative schema cases are rejected, document cross-references resolve. All satisfied by this checkpoint's own run (section 17). |
| G-P6 (start of implementation validation) | A Kotlin fixture runner exists and every fixture in this checkpoint passes against the real implementation, not just the oracle. `INTERNET` and a production client exist per the cross-cutting contract's slice plan. The suspected jitter divergence (section 9 below, finding F-1) is confirmed or refuted. |
| G-production-pilot | OPEN DECISION. Depends on owner decisions OD-1 through OD-10 in the cross-cutting contract, on the physical device coverage floor being met (real-device validation plan section 3.4, itself still open at D-03), and on battery and data budgets (OD-6) being set and tested. This document does not invent what "enough" coverage means. |

## 11. Test result format

The machine-readable format is the JSON Schema at `validation/schema/evidence-record.schema.json`, detailed in the companion document. It is a file format, not a production database: real evidence records are proposed to live as plain JSON files validated against this schema, not in any service this task would need to stand up.

## 12. Data privacy: what must not be captured

Explicitly excluded, either by omission from the schema (so a field to hold it does not exist) or by an enforced pattern (so a value cannot be entered even in a free-text field's pattern-constrained neighbor):

| Category | How it is excluded |
|---|---|
| Exact user location (coordinates, address) | No such field. `environment.site_label` is a coarse, pattern-constrained label only. |
| SSID or BSSID | No such field. `network.ap_ref` is a lab inventory label with the same pattern, deliberately not a real network identifier. |
| IMEI, serial number, ICCID, IMSI, phone number | No such field anywhere. `device.device_ref` and `network.carrier_label` are explicitly documented as labels, not identifiers, in their schema descriptions. |
| Account identity | No such field. |
| Message contents | Not applicable to this domain; no field for arbitrary user content exists. |
| Unrelated device data | The schema is closed (`additionalProperties: false` throughout), so nothing outside the defined fields can be attached to a record. |
| Full bug reports | `logs[].contains_bugreport` is a schema constant fixed to `false`. A bug report contains system-wide logs from every app (VERIFIED FACT, official bug-report documentation), which is far more than this task's own evidence needs and is explicitly prohibited as an attachment. |
| Raw packet captures with other users' traffic | Section 8, step 4: capture only on an isolated lab segment. The schema has no field for capture content itself, only a `PCAP` log kind referencing a file by path and hash, so any capture that is attached must be reviewed before it is referenced. |

Residual risk: free-text fields (`observed.summary`, `verdict.reason`, `condition.impairment.notes`, `expected.pass_criteria`) are capped at 500 characters (200 for impairment notes) but cannot be content-filtered by a schema. A human reviewer must check them before a record leaves the lab, as stated in the companion document's section 5.

## 13. Automation opportunities

Identified, not built, and explicitly not depending on any unresolved owner decision unless noted:

| Opportunity | Depends on an owner decision? |
|---|---|
| A CI job that runs `validate_validation_assets.py` on every push touching `validation/` | No. Could be added to `.circleci/config.yml` as a new job requiring only `build`, following the existing job-separation convention (REPO FACT). Not added in this checkpoint because it would touch shared CI configuration outside this task's stated scope of independent, isolated work. |
| A Kotlin fixture runner that feeds these JSON fixtures into the real `DerivedLatencyStats`, `DerivedJitterStats`, `Confidence` and `Freshness` functions and asserts equality with `expected` | No, once a Gradle build is reachable. This is the single most valuable next step (section 15). |
| Automatic generation of evidence-record skeletons from a scenario ID, pre-filled with build and device metadata from `adb` | No. Straightforward scripting once L4 sessions begin. |
| Scheduled repeated-session runs for statistical sample-count decisions (real-device validation plan D-11, D-13, D-16, D-27) | Partially: needs a physical lab (validation plan D-30), which is an owner and budget decision. |
| A CI check that a production endpoint hostname never appears in the schema or fixtures (this checkpoint's own lint does this for its own files, section 17) | No. Could be generalized into a repository-wide check. Not done here since it would touch files outside `validation/`. |
| Automated battery and data-budget long runs (L7) | Yes: depends on OD-6 (data budgets) and a decided default measurement schedule (cross-cutting contract's own OD-6 dependency chain), neither of which exists yet. |

## 14. Risks

| Risk | Mitigation in this document |
|---|---|
| Fixtures encode the wrong semantics because they were read from the contract text rather than run against Kotlin | Every fixture cites its `semantics_ref`. The checker's oracle is explicitly labeled as independent of the Kotlin, and a suspected divergence (section 9) is reported rather than silently reconciled. |
| Provisional UDP reply rules (R-U1 to R-U7) are mistaken for the real protocol | Every mention is labeled PROVISIONAL, and the companion schema document states the protocol specification must replace them. |
| A future contributor treats a PROPOSED TEST PARAMETER (like the ramp-exclusion count in FIX-THR-005, or the ten-sample counts in the confidence fixtures) as a validated product threshold | Every such value carries an explicit `provenance` field in the fixture or is called out in prose as illustrative, matching the real-device validation plan's own convention of never inventing thresholds. |
| Residual state between back-to-back lab sessions biases results | Flagged as an open cooldown question (section 7), not silently ignored. |
| A real evidence record accidentally includes personal or location data in a free-text field | Human review step stated explicitly (section 12); the schema cannot catch this by itself. |
| This checkpoint's document lint gives false confidence that "no production hostname" holds repository-wide | The lint only covers files under `validation/` and the three Phase 5 documents; it is not a repository-wide guarantee, and is not claimed as one anywhere in this checkpoint. |

## 15. Open items

| Item | Status |
|---|---|
| Definition of "unstable" for NC-M | Not defined here (OPEN DECISION) |
| Whether the engine reads `validated` (only `available` as of `727b2a91`; unconfirmed for the foundation branch) | Needs confirmation from the implementation team, not assumed by this document either way |
| Cooldown between back-to-back sessions | OPEN DECISION |
| Sample counts, per-condition | Deferred to the real-device validation plan's own pilot-based process (unchanged by this document) |
| Kotlin fixture runner | Recommended next task (section 15's own continuation is section 17's report) |
| CI job for the checker | Identified, not added (section 13) |
| The suspected jitter gap-handling divergence (finding F-1) | Not confirmed; needs the Kotlin run |
| Whether an all-late UDP train deserves a distinct outcome from "no response" (finding F-4) | Not resolved; a protocol and contract question |

## 16. Sources

### 16.1 Fetched and read in this session (VERIFIED FACT basis)

| Topic | Source |
|---|---|
| Emulator command-line start options, including `-netdelay` and `-netspeed` | https://developer.android.com/tools/help/emulator |
| Emulator console commands, including `network speed`, `network delay`, `network capture`, and the auth-token connection procedure | https://developer.android.com/studio/run/emulator-console |
| The console's `network` command covers ethernet and cellular only | https://developer.android.com/studio/run/emulator-console?hl=pl (help text also present in the untranslated page) |
| Android Test Orchestrator and the `clearPackageData` flag | https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner |
| Batterystats and Battery Historian commands (`dumpsys batterystats`, `--reset`, `--enable full-wake-history`) | https://developer.android.com/topic/performance/power/setup-battery-historian |
| Bug reports contain device logs, stack traces, dumpsys, dumpstate and logcat from all apps | https://developer.android.com/studio/debug/bug-report |
| RFC 3550's interarrival jitter formula (used only as a cross-check against the contract's own IPDV and PDV citations; not adopted as AERIVA's definition, which remains the mean absolute successive difference per the cross-cutting contract's D4-3) | Multiple IETF-derived secondary sources reproducing the RFC 3550 Appendix A.8 formula consistently; the primary RFC text itself was not independently re-fetched in this session |

### 16.2 Reused from the cross-cutting contract's own research (not re-fetched here)

`NetworkCapabilities` validated, captive-portal and metered semantics; `LinkProperties` Private DNS accessors; `Network.bindSocket` and related APIs; Doze and App Standby behavior and `adb` commands; Android 16 and 17 behavior changes; RFC 2681, RFC 5481, RFC 8762. See `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md`, section 17, for the full list and URLs.

### 16.3 Not verified this session

Whether the emulator's delay and speed shaping is genuinely lossless and jitter-free by design (ASSUMPTION, from a secondary source only, flagged in the device test matrix); exact behavior of `dumpsys connectivity` output format across Android versions; whether `cmd connectivity` is available on the API levels this project targets; Firebase Test Lab or any cloud device farm's network fidelity, which this document does not recommend for cellular validation, consistent with the real-device validation plan's own position.

## 17. Validation performed on this checkpoint

Documentation-appropriate, offline checks only. No Gradle build, no Android tool, no device or emulator was invoked, and no CircleCI run was triggered.

| Check | Tool | Result |
|---|---|---|
| Evidence schema is a valid JSON Schema (draft 2020-12) | `jsonschema.Draft202012Validator.check_schema` inside `validate_validation_assets.py` | Reported in this checkpoint's final report |
| Every fixture's `expected` values match an independent reference oracle written from the documented semantics | Same script, per-fixture comparison, 60 fixtures across six files | Reported in the final report, including the two flagged divergence notes for FIX-LAT-009 and FIX-LAT-010 |
| Every synthetic evidence example validates against the schema | Same script | Reported in the final report |
| Fifteen deliberate mutations of valid examples (an SSID field, a URL in an endpoint label, a PASS without execution, an emulator record on a physical layer, a bug-report attachment, a cancelled result carrying samples, a non-synthetic record with a FIXTURE- ID, a failed result with no failure object, a short commit hash, a location field, a production endpoint role, an absolute log path, an executed record with no start time, an over-long carrier label, a parent-directory log path) are each rejected by the schema | Same script | Reported in the final report; a schema that accepted any of these would be a finding, not a pass |
| No em dash or en dash in any file under `validation/` or in the three Phase 5 documents | Same script | Reported in the final report |
| No URL host outside an allowlist appears in any `validation/` file, and no URL at all appears in any fixture JSON file (so a production hostname cannot slip into test data even accidentally) | Same script | Reported in the final report |
| Every fixture ID mentioned in the three Phase 5 documents exists in a fixture file, and every fixture ID in a fixture file is described in at least one document | Same script | Reported in the final report |
| Every `NC-` scenario letter A through M has a row in this document's section 3, and every `DM-` row referenced elsewhere is defined in the device test matrix | Same script | Reported in the final report |

The checker (`validation/tools/validate_validation_assets.py`) is itself test infrastructure, not production code, and was run from a plain Python 3 environment with the `jsonschema` package installed. It does not require Android, Gradle, or network access, and none of its checks are claimed as evidence about the Kotlin implementation, CircleCI, or any device.
