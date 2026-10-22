PHASE 4 TEST-GATE SPECIFICATION

Branch: phase-4-test-gate
Base: phase-4-measurement-foundation @ 545a9612157f (AI 1, slice S1 plus CI fixes)
Owner: AI 4 (testing layer)
Built against: PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md (branch phase-4-cross-cutting-decisions), PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md, PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md, and the code on the base commit.
Status of this document: specification plus a first tranche of executable tests. main and every other branch are untouched. Nothing here starts slice S2 to S8 or any Phase 3C work.

---

## 1. Purpose and scope

This document defines what must be proven, by which test, at which layer, and by when, for the measurement stack established in Phase 3A, 3B and 4. It covers:

domain-model invariants, classifier behavior, engine behavior, the NetworkClient contract, the failure taxonomy, timing calculations, measureSeries, cancellation, timeout, DNS failures, redirects, blocked connections, unanswered probes, captive portal, VPN, metered networks, stale context, fabricated-result prevention, protocol validation, and the future throughput and jitter extensions.

It is not a claim that anything listed as Blocked or Planned works. It does not add an endpoint, a permission, a dependency, a UI, a scheduler, VPN support or any Phase 3C item. VPN and captive-portal rows are observation tests only.

## 2. Vocabulary

Status values (from the repository documentation standard):

| Status | Meaning |
|---|---|
| Implemented | Test exists on the base commit and runs in CI. |
| Added | Test added by this branch, verified locally as described in section 10. |
| Added, red | Added by this branch and deliberately failing on the base commit because it exposes a defect. It turns green when the defect is fixed. |
| Blocked (Sx) | Cannot be written or cannot pass until slice Sx (or a named decision) lands. The row says what it needs. |
| Planned | Can be written now but is not in this tranche. |
| Device-validation-required | Only meaningful on real hardware. Emulator results do not count. |
| Production-configuration-required | Needs an owner decision or production infrastructure that does not exist yet. |

Layers:

| Layer | Meaning |
|---|---|
| JVM | Pure Kotlin, no Android, no sockets. Runs in `:core:model:test` or `:network:monitor:test`. |
| JVM-fake | JVM with FakeNetworkClient, MutableClock and virtual time. |
| JVM-loopback | JVM with real sockets against the loopback fixture server (LFS, decision D2-11, D3-10). No Android. |
| STATIC | Source or manifest scan in CI. |
| EMU | Emulator instrumented run. |
| DEV | Physical device. |
| REL | Release-variant build inspection. |

Gate tiers (section 8 gives the full checklists):

| Tier | Name | Rule in one line |
|---|---|---|
| G1 | Before merge | Green in CI on the branch being merged, no ignored tests, and the slice's own Blocked rows become real tests in the same change. |
| G2 | Before physical-device testing | G1 for S1 to S4 plus every G2 row green, with the loopback and emulator suites passing and the device protocol prepared. |
| G3 | Before production | G2 plus device matrix executed on real hardware, pilots replace provisional thresholds, and the owner decisions in section 8.3 recorded. |

## 3. Baseline audit (evidence for this document)

Base commit 545a9612157f. Method: read of every production and test file involved, CI logs from CircleCI, and execution of tests locally (section 10). Findings are listed with how each was established.

| ID | Finding | How established | State |
|---|---|---|---|
| F-1 | CI failed on 9a28cc6: test fixtures built `NetworkState(...)` without the three new required fields (captivePortalReported, vpnPresent, blockedByDevicePolicy). | CircleCI job log, `NetworkHistoryRepositoryTest.kt:31`. The engine test fixture was predicted to fail next from source reading. | Closed by AI 1 in 31231323, 89337fc, 545a961. CI on 545a961 succeeded (CircleCI run 0642d7ee), so the base of this branch is green. |
| F-2 | `DerivedJitterStats.from` omits the first sample of every resumed run from `sampleCount`, `sourceMeasurementIds` and `pdvRangeMillis`. Example: `[ok 40, ok 44, fail, ok 10, ok 12]` reports lineage `[1,2,5]`, sampleCount 3, range 32 instead of `[1,2,4,5]`, 4, 34. Cause: the previous sample is added only while the accumulator is empty. | Read from source, then reproduced by execution (tests JT-04, JT-05, JT-06, JT-09, JT-10). | Open. Fix in Appendix A. |
| F-3 | `DerivedJitterStats.method` is the method of the first Succeeded sample in the series, not of the pairs actually counted. | Reproduced by execution (JT-07). | Open. Fix in Appendix A. |
| F-4 | Jitter accepts NaN, infinite and negative values. A NaN sample yields NaN mean. `DerivedLatencyStats` rejects the same inputs, so the two derived values disagree about what a reading is. Decision D4.8 requires a non-finite case. | Reproduced by execution (JT-11, JT-12). | Open. Fix in Appendix A. |
| F-5 | Engine samples `startNanos` before the dispatcher hop, so queueing on the IO dispatcher is added to the measured latency. | Read from `LatencyMeasurementEngine.kt`. | Open, closes with S4 (D3-6). Test TM-03 blocked on S4. |
| F-6 | Engine catches only `TimeoutCancellationException`. Any exception from the client propagates out of `measure`. Contradicts D5-9. | Read from source. | Open, closes with S3 and S4. Tests EN-08, NC-01 blocked. |
| F-7 | Engine default method is the free-text `"tcp-round-trip"`, which is not in the registry. Contradicts D4-2. Existing MeasurementMethodTest only proves the registry rejects it, not that the engine stops emitting it. | Read from source. | Open, closes with S4. Test EN-10 blocked on S4. |
| F-8 | `measureSeries` has no inter-sample interval and no cap, and discards failed outcomes before aggregation, so the ordered series with gaps that jitter needs (D4-3) is not available to callers. | Read from source. | Open, closes with S4 and S5. Tests SR-02 to SR-07 blocked. |
| F-9 | Every existing engine test uses `elapsedNanos = { 0L }`, so the nanoseconds to milliseconds arithmetic is never asserted. | Read from tests. | Open. Tests TM-01, TM-02 blocked on S4 because interval ownership moves to the client. |
| F-10 | `NetworkStateMapper` ignores `RawCapabilitiesSnapshot.hasInternet`. A network without the INTERNET capability maps to `available = true`. | Read from source. | Decision point DP-3. |
| F-11 | Classifier reason text for a missing INTERNET permission says it is "not yet declared in this repository's manifest". That becomes false when S3 declares it. | Read from source. | Minor. Text must change with S3. |
| F-12 | `FailureMapper` and the portal interpreter listed in S1 of the slice plan are not in the tree. The notes document them as deferred. | File listing. | Tests FT-03 to FT-06, FT-08, FT-10, CP-02 blocked. |

## 4. Ownership and conflict rules

1. AI 4 adds tests as new files only. No existing file is edited on this branch. AI 1's active files (`LatencyMeasurementEngine.kt`, `NetworkClient.kt`, `FakeNetworkClient.kt`, `ReferenceLatencyProbeExecutor*.kt`, build files) are not touched.
2. Tests that depend on the NetworkClient or ProbeRequest shape wait for S3, because decision D5-11 changes that interface. Writing them now would force AI 1 to edit AI 4 files during the migration.
3. A defect found by a test is reported with the failing test and a verified fix proposal. AI 4 does not edit production code.
4. Guards are policy, not snapshots. They must not need editing when a legitimate slice lands. Example: the INTERNET guard allows the permission in `network:monitor` and nowhere else, so S3 passes without touching it.
5. Every scan-based test asserts it scanned something, so a moved directory cannot turn a guard into a silent pass.
6. Test-double types live in test source sets only (guard AG-10).
7. A slice that adds behavior lists the test IDs it delivers in its notes. A Blocked row is closed by the slice, not by AI 4 later.

## 5. Test catalog

Columns: ID, what it proves, layer, status, gate, source. "Gate" is the latest tier by which the row must be green. For S4 to S8 rows, G1 means "before that slice merges".

### 5.1 DM: domain-model invariants

| ID | Proves | Layer | Status | Gate | Source |
|---|---|---|---|---|---|
| DM-01 | Exhaustive `when` over LatencyMeasurement, LatencyEstimation, LatencyPrediction, ConnectivityRecommendation, MeasurementFailure, CapabilityClassification. Adding a case breaks compilation. | JVM | Implemented | G1 | D4.8, D5.8 |
| DM-02 | Every new sealed family gets its exhaustive `when` test in the slice that adds it: L1 transport outcome family, UdpProbeTrainMeasurement, ThroughputMeasurement, derived HTTPS state. | JVM | Blocked (S2, S6, S7, S8) | G1 | D4.8 |
| DM-03 | A Failed measurement exposes no numeric value. An estimation with no evidence is InsufficientEvidence. No recommendation without evidence. | JVM | Implemented | G1 | Phase 3A |
| DM-04 | Prediction carries lineage to its source measurement ids. | JVM | Implemented | G1 | Phase 3A |
| DM-05 | Method registry: every constant registered, no duplicates, retired free-text default not registered. | JVM | Implemented | G1 | D3-7, D4-2 |
| DM-06 | `Unclassified` carries the exception class simple name only, never a message. | JVM | Implemented | G1 | D5-4 |
| DM-07 | `Timeout` carries a stage and stages are distinct. | JVM | Implemented | G1 | D5-3 |
| DM-08 | `LatencyMeasurement.Succeeded.valueMillis` is finite and not negative, and `sampleCount` is at least 1, enforced at construction or rejected by every consumer. | JVM | Blocked (DP-4) | G1 | D4.8 |
| DM-09 | `ProbeEvidence` numeric fields (bytes, phase durations) are not negative. Phases are null when unobservable, never zero-filled. | JVM | Blocked (DP-4) | G1 | D4-1 |
| DM-10 | Freshness: stale strictly after validUntil, equal is fresh, no expiry never stale. `validUntil` before `producedAt` is rejected or documented. | JVM | Implemented for boundaries, blocked for the invalid range (DP-4) | G1 | Phase 3A |

### 5.2 JT: jitter derived value (D4-3, D4-4)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| JT-01 | Empty, single sample, failure breaks adjacency, cold never paired, method break, handle break, null equals null, out-of-order rejected, Insufficient below 3 pairs, lineage in send order. | JVM | Implemented (DerivedJitterStatsTest) | G1 |
| JT-02 | Differential oracle written from D4-3 over 600 seeded random series: result present exactly when the oracle says so. | JVM | Added | G1 |
| JT-03 | Same oracle: pair count and mean absolute consecutive difference. | JVM | Added | G1 |
| JT-04 | Same oracle: lineage lists every member of every counted pair, once, in send order. | JVM | Added, red (F-2) | G1 |
| JT-05 | Same oracle: `sampleCount` equals the distinct lineage size. | JVM | Added, red (F-2) | G1 |
| JT-06 | Same oracle: range is max minus min over every counted sample. | JVM | Added, red (F-2) | G1 |
| JT-07 | Same oracle: reported method is the method the counted pairs used. Explicit case included. | JVM | Added, red (F-3) | G1 |
| JT-08 | Structural: `pairs + 1 <= sampleCount <= 2 * pairs`, statistics finite and not negative. | JVM | Added | G1 |
| JT-09 | Hand-checkable break-then-resume series `[40, 44, fail, 10, 12]`. | JVM | Added, red (F-2) | G1 |
| JT-10 | Same with a cold sample as the break. | JVM | Added, red (F-2) | G1 |
| JT-11 | NaN and infinite samples never produce a non-finite statistic and never appear in lineage. | JVM | Added, red (F-4) | G1 |
| JT-12 | Negative latency is not treated as a reading, consistent with DerivedLatencyStats. | JVM | Added, red (F-4) | G1 |
| JT-13 | Equal timestamps are accepted as non-decreasing. | JVM | Added | G1 |
| JT-14 | An out-of-order Failed sample still rejects the whole series. | JVM | Added | G1 |
| JT-15 | Warm sample without evidence never pairs. Null handle versus non-null handle never pairs. Different non-null handles never pair. | JVM | Added | G1 |
| JT-16 | `calculatedAt` is the caller's value, definition is the named one, input list is not mutated. | JVM | Added | G1 |
| JT-17 | Mixed-method series policy: partition, reject or label. Property tests skip such series until decided. | JVM | Blocked (DP-1) | G1 |
| JT-18 | Null-handle pairs across a real transport change are not bridged. | JVM | Blocked (DP-2) | G1 |
| JT-19 | Jitter confidence is its own function and its 3 and 10 pair cutoffs are replaced by pilot-derived values before release. | JVM plus pilot | Provisional today | G3 |
| JT-20 | UDP-train jitter (S7): late, duplicate and out-of-order answers are excluded from consecutive-difference pairs and counted separately. | JVM | Blocked (S7) | G2 |

### 5.3 LS: latency derived statistics

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| LS-01 | Empty, all invalid, negative excluded, minimum samples, confidence tiers, huge values give no NaN or infinity, `calculatedAt` is a parameter. | JVM | Implemented | G1 |
| LS-02 | A series mixing methods or connection states is never averaged into one unlabeled figure. `DerivedLatencyStats` has no method field today. | JVM | Blocked (DP-1) | G1 |
| LS-03 | Lineage lists only the samples actually used. | JVM | Implemented in part | G1 |

### 5.4 FT: failure taxonomy (D5-3 to D5-8)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| FT-01 | Every D5-4 case distinct and exhaustively handled. | JVM | Implemented | G1 |
| FT-02 | Kind parameters default to UNSPECIFIED. | JVM | Implemented | G1 |
| FT-03 | `FailureMapper` table, one case per D5-7 row: UnknownHostException to DnsFailed(NOT_RESOLVED), DNS timer to CLIENT_TIMED_OUT, ConnectException to ConnectFailed(UNSPECIFIED), socket timeout to TimedOut(stage), peer-unverified to HOSTNAME_MISMATCH, certificate path to CERTIFICATE_INVALID, other TLS to PROTOCOL_OR_CIPHER or UNSPECIFIED, SecurityException to a decline, cleartext IOException to Unclassified("CleartextNotPermitted"), cancellation rethrown, anything else to Unclassified(class). | JVM | Blocked (S1 remainder, S3) | G1 |
| FT-04 | No-throw property: every Throwable other than cancellation maps to some outcome (scope in DP-5). Fuzz over at least 30 exception types. | JVM | Blocked | G1 |
| FT-05 | Message parsing is prohibited: identical mapping for the same exception type with different messages. | JVM | Blocked | G1 |
| FT-06 | Portal interpreter truth table (see CP-02). | JVM | Blocked | G1 |
| FT-07 | Derived HTTPS state (D5-8) is total over Succeeded, every failure case and every decline, and matches the table. | JVM | Blocked (S6) | G1 |
| FT-08 | L1 to L2 mapping (D5-6) one case per row. | JVM | Blocked (S1 remainder, S3) | G1 |
| FT-09 | `Unclassified` rate is zero in every harness run. A nonzero rate is a defect closed by adding a specific case (D5.8). | EMU, DEV | Blocked | G2 and G3 |
| FT-10 | Sanitization: no failure reason or evidence string contains a URL, hostname, IP literal or nonce. Fuzz with hostile exception messages. | JVM | Blocked | G1 |

### 5.5 CL, CX and MN: classifier, context, mapper

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| CL-01 | Per-capability classification examples. | JVM | Implemented | G1 |
| CL-02 | Full truth table: 10 capabilities, all 16 permission subsets, 3 API levels (480 evaluations) against a contract written independently of the implementation. | JVM | Added (MeasurementCapabilityClassifierMatrixTest) | G1 |
| CL-03 | Every non-Supported answer carries a readable reason. | JVM | Added | G1 |
| CL-04 | Honesty floor: DNS, packet loss and throughput never plain Supported for any permission set. | JVM | Added | G1 |
| CL-05 | Permissions are isolated: ACCESS_NETWORK_STATE does not unlock probes, INTERNET does not unlock location readings. | JVM | Added | G1 |
| CL-06 | Look-alike permission strings grant nothing. | JVM | Added | G1 |
| CL-07 | `grantedPermissions` comes from `checkSelfPermission` at request time, never from the manifest declaration (D1-4). | EMU | Blocked (S3) | G2 |
| CX-01 | `vpnPresent` is true exactly when the VPN transport is present, for all 32 transport subsets. VPN over Wi-Fi reports both facts. | JVM | Implemented for examples, Added for all subsets | G1 |
| CX-02 | Captive-portal flag is recognized only by the exact name CAPTIVE_PORTAL. Near-misses and case variants do not match. | JVM | Added | G1 |
| CX-03 | Portal flag is independent of validation. | JVM | Added | G1 |
| CX-04 | `metered` is the negation of NOT_METERED both ways. | JVM | Implemented, Added | G1 |
| CX-05 | Blocked flag passes through in both branches and defaults to false. | JVM | Implemented, Added | G1 |
| CX-06 | The offline shape leaks no flags from a stale snapshot. Available with no snapshot is offline. | JVM | Added | G1 |
| CX-07 | Transport priority VPN, ETHERNET, WIFI, CELLULAR, OTHER, NONE for every subset. | JVM | Added | G1 |
| CX-08 | The mapper never invents a quality score or diagnostics, and `lastChangedAt` is the caller's instant. | JVM | Added | G1 |
| CX-09 | A network without the INTERNET capability is not reported as attemptable. | JVM | Blocked (DP-3) | G1 |

### 5.6 EN: engine behavior

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| EN-01 | Valid payload gives Measured Succeeded. | JVM-fake | Implemented | G1 |
| EN-02 | No network: NoNetwork, zero client calls. | JVM-fake | Implemented | G1 |
| EN-03 | Missing permission: CapabilityUnavailable, zero client calls. Injected classifier is consulted. | JVM-fake | Implemented | G1 |
| EN-04 | Blocked by device policy at start: BlockedByDevicePolicy decline, zero client calls (D5-2). | JVM-fake | Blocked (S4) | G1 |
| EN-05 | No endpoint configured: NoEndpointConfigured decline, zero client calls. Release builds may not carry a production endpoint before OD-2 and OD-3 (D2-6). | JVM-fake | Blocked (S2, S4) | G1 |
| EN-06 | SecurityException from the client is a decline with a defect log, never a recorded Failed measurement. | JVM-fake | Blocked (S3, S4) | G1 |
| EN-07 | Refused, TLS, network-changed, malformed and timeout outcomes map to distinct domain failures. | JVM-fake | Implemented | G1 |
| EN-08 | `measure` never throws for any client outcome or Throwable except cancellation (D5-9). Today it rethrows (F-6). | JVM-fake | Blocked (S3, S4) | G1 |
| EN-09 | `measuredAt` is the probe start instant from the injected clock, ids are unique. | JVM-fake | Planned | G1 |
| EN-10 | Every produced measurement carries a registered method (D4-2). Today's default is unregistered (F-7). | JVM-fake | Blocked (S4) | G1 |
| EN-11 | Two calls make exactly two client calls, no caching or dedup. | JVM-fake | Implemented | G1 |
| EN-12 | Client call runs on the IO dispatcher. | JVM-fake | Implemented | G1 |

### 5.7 TM: timing calculations

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| TM-01 | Nanosecond to millisecond arithmetic is exact: 12,500,000 ns gives 12.5 ms, 1 ns gives 0.000001 ms, values near `Long.MAX_VALUE / 2` do not overflow (F-9). | JVM-fake | Blocked (S4) | G1 |
| TM-02 | Only the monotonic clock is used. A wall-clock step of one hour backward or forward mid-probe changes nothing. | JVM-fake | Blocked (S4) | G1 |
| TM-03 | Engine overhead is excluded: dispatcher queueing in virtual time does not inflate `valueMillis` (F-5). | JVM-fake | Blocked (S4) | G1 |
| TM-04 | A non-monotonic or negative elapsed value never becomes a Succeeded measurement (DP-4). | JVM-fake | Blocked (S4, DP-4) | G1 |
| TM-05 | Phase timings are non-negative and their sum does not exceed total elapsed beyond tolerance. Unobservable phases are null. | JVM-loopback | Blocked (S3) | G2 |
| TM-06 | Cold versus warm: first call on a fresh client is Cold with the cold-total method and connection setup phases present. A second call on the same connection is Warm and setup phases are absent. | JVM-loopback | Blocked (S3, S4) | G2 |
| TM-07 | Server-reported processing time is recorded as evidence, never silently subtracted. | JVM-loopback | Blocked (S2, S4) | G2 |
| TM-08 | Calibration against the calibration reference server (CRS): reported RTT versus injected delay, systematic offset recorded. | CRS | Production-configuration-required | G2 offset, G3 thresholds |
| TM-09 | Series interval is measured on the monotonic clock and honors the configured minimum. | JVM-fake | Blocked (S4) | G1 |

### 5.8 SR: measureSeries (D4-5)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| SR-01 | All succeed gives Aggregated. None succeed gives InsufficientEvidence. Empty request list gives InsufficientEvidence with zero client calls. | JVM-fake | Implemented | G1 |
| SR-02 | Outcomes and lineage keep request order. | JVM-fake | Blocked (S4) | G1 |
| SR-03 | Failed samples stay in the ordered series handed to jitter (D4-3), and failure counts are visible, not silently dropped (F-8). | JVM-fake | Blocked (S4, S5) | G1 |
| SR-04 | Hard sample cap enforced before any client call. The cap value is owner-set (OD-6). | JVM-fake | Blocked (S4, OD-6) | G1 |
| SR-05 | Inter-sample interval honored in virtual time. No back-to-back probes. | JVM-fake | Blocked (S4) | G1 |
| SR-06 | A network change mid-series ends or splits the series. Jitter does not bridge it. | JVM-fake | Blocked (S4, S5) | G1 |
| SR-07 | Cancellation mid-series propagates, publishes no partial series as complete, and fires no further probe. | JVM-fake | Planned | G1 |
| SR-08 | Total bytes per series stay under the byte budget. | EMU, DEV | Blocked (OD-6) | G2 |

### 5.9 CN: cancellation (D3-5)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| CN-01 | Caller cancellation propagates and emits no outcome. | JVM-fake | Implemented | G1 |
| CN-02 | A cancelled hang counts as cancelled, not completed. | JVM-fake | Implemented | G1 |
| CN-03 | Cancellation is rethrown by the mapper, never turned into a failure. | JVM | Blocked (S3) | G1 |
| CN-04 | Bounded-time unblocking: a real socket blocked in read returns after cancel within a stated budget (proposal: 250 ms on loopback). | JVM-loopback | Blocked (S3) | G1 |
| CN-05 | Cancel during each stage: DNS hang, connect to a blackhole, stalled TLS handshake, stalled request, slow-drip response. | JVM-loopback | Blocked (S3) | G2 |
| CN-06 | No leaked resources after cancel: sockets closed, thread count returns to baseline. | JVM-loopback | Blocked (S3) | G2 |
| CN-07 | A cancelled call does not corrupt the client for the next probe. | JVM-loopback | Blocked (S3) | G2 |

### 5.10 TO: timeout (D3-4, D5-10)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| TO-01 | A hanging fake client yields a recorded Timeout and the client call is cancelled. | JVM-fake | Implemented | G1 |
| TO-02 | Client-owned deadline: TimedOut(stage) for a server stalled at each stage. The engine backstop never fires and no defect log is emitted. | JVM-loopback | Blocked (S3, S4) | G1 |
| TO-03 | Backstop: when the client ignores its deadline, the engine yields Timeout(Unknown) and a defect signal. | JVM-fake | Blocked (S4) | G1 |
| TO-04 | Deadline covers DNS: client DNS timer expiry gives DnsFailed(CLIENT_TIMED_OUT). | JVM-loopback | Blocked (S3) | G1 |
| TO-05 | Zero or negative deadline is rejected or clamped, never infinite. | JVM-fake | Blocked (S3, S4) | G1 |
| TO-06 | A slow drip cannot extend the deadline: the deadline is total, not per read. | JVM-loopback | Blocked (S3) | G2 |

### 5.11 DN, RD, BC: DNS, redirects, blocked connections

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| DN-01 | UnknownHostException gives DnsFailure(NOT_RESOLVED). | JVM | Blocked (S3) | G1 |
| DN-02 | Client DNS timeout gives CLIENT_TIMED_OUT, distinct from NOT_RESOLVED. | JVM-loopback | Blocked (S3) | G1 |
| DN-03 | A DNS failure is never labeled TLS or endpoint. | JVM | Blocked (S3) | G1 |
| DN-04 | No code path claims to separate NXDOMAIN from resolver failure, and no DNS measurement type exists (D4-10). | STATIC | Added (AG-11) | G1 |
| DN-05 | Resolution goes through the pinned network (`Network.getAllByName`), including with Private DNS on and off. | EMU, DEV | Blocked (S3) | G2 |
| DN-06 | DNS responsiveness stays Estimated because caching confounds a timed lookup. | JVM | Implemented, Added (CL-04) | G1 |
| RD-01 | Each of 301, 302, 303, 307 and 308 gives UnexpectedRedirect(statusCode). | JVM-loopback | Blocked (S3) | G1 |
| RD-02 | Redirects are never followed: the fixture sees zero requests to the Location target. | JVM-loopback | Blocked (S3) | G1 |
| RD-03 | An https to http downgrade redirect is never followed. | JVM-loopback | Blocked (S3) | G1 |
| RD-04 | Redirect plus portal flag gives CaptivePortalSuspected. Redirect without the flag stays UnexpectedRedirect. | JVM | Blocked (interpreter) | G1 |
| RD-05 | A non-redirect unexpected status gives InvalidResponse(UNEXPECTED_STATUS). | JVM-loopback | Blocked (S3) | G1 |
| BC-01 | The mapper passes the blocked flag through. | JVM | Implemented, Added | G1 |
| BC-02 | Blocked at start: BlockedByDevicePolicy decline, no probe (EN-04). | JVM-fake | Blocked (S4) | G1 |
| BC-03 | Blocked during a probe: client returns Blocked, recorded as Failed(BlockedByDevicePolicy). | JVM-fake | Blocked (S3) | G1 |
| BC-04 | The platform blocked-status callback updates `blockedByDevicePolicy`. Data Saver can be toggled on an emulator; real behavior needs a device. | EMU, DEV | Blocked (wiring deferred in S1 notes) | G2 |
| BC-05 | Blocked maps to UNKNOWN in the derived HTTPS state (D5-8). | JVM | Blocked (S6) | G1 |
| BC-06 | NoNetwork and BlockedByDevicePolicy stay distinct outcomes and are never both reported as "offline". | JVM-fake | Blocked (S4) | G1 |

### 5.12 UP: unanswered probes (D4-6, slice S7)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| UP-01 | Zero answers is always Failed(NoResponseFromEndpoint). Never a Succeeded with a 100 percent figure. `answeredInWindow` is at least 1 for Succeeded. | JVM | Blocked (S7) | G1 |
| UP-02 | Unanswered equals `probesSent - answeredInWindow`. Late answers are counted separately and never as unanswered. | JVM | Blocked (S7) | G1 |
| UP-03 | Duplicate and out-of-order answers are counted separately and never push answered above sent. | JVM | Blocked (S7) | G1 |
| UP-04 | No type, field or string says "packet loss" or "loss" in the result model. | STATIC | Added (AG-02) for type names | G1 |
| UP-05 | UI copy avoids "loss" before stage D calibration (OD-8). | REL, review | Production-configuration-required | G3 |
| UP-06 | Control probe: when the same-window HTTPS control fails, the result is inconclusive, not lossy. | JVM | Blocked (S7) | G1 |
| UP-07 | Train bounds: probes, window and payload capped, bytes within budget. | JVM | Blocked (S7, OD-6) | G1 |
| UP-08 | A UDP-filtering network yields NoResponseFromEndpoint, not loss. | DEV | Device-validation-required | G2 |
| UP-09 | Stage D calibration: measured unanswered fraction versus injected loss on the CRS. The word "loss" is allowed only after this. | CRS, DEV | Production-configuration-required | G3 |

### 5.13 CP, VP, MT: captive portal, VPN, metered

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| CP-01 | Mapper reports the portal flag by exact name (CX-02). | JVM | Implemented, Added | G1 |
| CP-02 | Interpreter truth table. Interception signals: UnexpectedRedirect, TlsFailure(HOSTNAME_MISMATCH), TlsFailure(CERTIFICATE_INVALID), InvalidResponse(NONCE_MISMATCH), InvalidResponse(MALFORMED). Non-signals: Timeout, DnsFailure, EndpointFailure, TlsFailure(PROTOCOL_OR_CIPHER), InvalidResponse(WRONG_LENGTH). Flag observed at start, end, both or neither. CaptivePortalSuspected only when a signal and a flag coincide (D5-5). | JVM | Blocked (interpreter) | G1 |
| CP-03 | Evidence records which signal and which flag observation. | JVM | Blocked | G1 |
| CP-04 | A Succeeded result is never rewritten to a portal result by a stale flag. | JVM | Blocked | G1 |
| CP-05 | Loopback fixture serving a redirect plus a portal-flagged network state gives CaptivePortalSuspected. | JVM-loopback | Blocked (S3) | G2 |
| CP-06 | Real portal (hotel, airport, or a lab portal). After login the next probe succeeds and history is not edited. | DEV | Device-validation-required | G2 |
| VP-01 | VPN facts (CX-01). | JVM | Implemented, Added | G1 |
| VP-02 | Every measurement made under a VPN carries `vpnPresent = true`. Derived statistics never merge VPN and non-VPN samples silently. | JVM-fake | Blocked (S4, S5, DP-2) | G1 |
| VP-03 | Always-on VPN with lockdown, and per-app VPN, produce BlockedByDevicePolicy or a connect failure, never a fabricated success. | DEV | Device-validation-required | G2 |
| MT-01 | Metered mapping (CX-04). | JVM | Implemented, Added | G1 |
| MT-02 | Throughput requires opt-in and is unmetered-aware. Latency-scale probes on metered networks follow the owner policy (OD-6). | JVM-fake | Blocked (S8, OD-6) | G1 |
| MT-03 | Metered and roaming behavior. | DEV | Device-validation-required | G2 |
| MT-04 | Bytes sent and received are recorded per probe and reconcile with the TrafficStats tag within tolerance (D3-8). | EMU, DEV | Blocked (S3) | G2 |

### 5.14 SC: stale context

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| SC-01 | Freshness boundaries (DM-10). | JVM | Implemented | G1 |
| SC-02 | A measurement whose supplied context no longer matches the live network at probe start is refused or relabeled. Needs a seam for the current network identity (DP-6). | JVM-fake | Blocked (DP-6) | G1 |
| SC-03 | Estimates and predictions built from evidence older than their validity window are downgraded or marked stale. | JVM | Planned | G1 |
| SC-04 | Evidence from a different network identity never contributes to an estimate for the current network. | JVM | Blocked (S5) | G1 |
| SC-05 | The mapper stamps the caller's instant and does not read a clock (CX-08). | JVM | Added | G1 |
| SC-06 | A cached NetworkState is not reused after process restart. | EMU, DEV | Blocked | G2 |

### 5.15 FR: fabricated-result prevention

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| FR-01 | Declines make zero client calls, and Measured outcomes equal client calls. | JVM-fake | Implemented in part | G1 |
| FR-02 | Failed carries no number. No estimate or recommendation without evidence. | JVM | Implemented | G1 |
| FR-03 | No derived statistic is NaN, infinite or negative. | JVM | Implemented for latency, Added red for jitter (F-4) | G1 |
| FR-04 | `NetworkQuality.Measured` is never constructed (D4-11). | STATIC | Added (AG-04) | G1 |
| FR-05 | Only `network:monitor` constructs `Succeeded` results. | STATIC | Added (AG-09) | G1 |
| FR-06 | No Fake, Mock or Stub type in production sources. | STATIC | Added (AG-10) | G1 |
| FR-07 | Phases and bytes come from the client event listener. When unobservable they are null, not zero. | JVM-loopback | Blocked (S3) | G2 |
| FR-08 | Prediction and recommendation are never produced from a single sample or Insufficient confidence. | JVM | Implemented | G1 |

### 5.16 PV: protocol validation and client configuration (D2, D3-3)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| PV-01 | Payload validation: right length and nonce succeeds. WRONG_LENGTH, NONCE_MISMATCH, MALFORMED, TOO_LARGE and UNEXPECTED_STATUS each map to their own InvalidResponse kind. | JVM-loopback | Blocked (S2, S3). Malformed payload at the legacy level is Implemented. | G1 |
| PV-02 | Every probe uses a fresh nonce. A replayed old nonce is rejected. | JVM | Blocked (S2) | G1 |
| PV-03 | Response size cap enforced while streaming, not after the body is read. | JVM-loopback | Blocked (S3) | G1 |
| PV-04 | An unknown protocol version gives InvalidResponse. | JVM | Blocked (S2) | G1 |
| PV-05 | The conformance suite runs unchanged against the loopback fixture, the CRS and the production endpoint (D2.8). | JVM-loopback, CRS, REL | Blocked (S2, OD-2) | G1 fixture, G2 CRS, G3 production |
| PV-06 | Certificate failures map to their kinds: expired, self-signed, wrong host, unknown CA. No trust override exists (AG-05). | JVM-loopback | Blocked (S3) | G1 |
| PV-07 | Production endpoint configuration is HTTPS only, checked at build or test time. | STATIC, REL | Blocked (S2, S3) | G1 and G3 |
| PV-08 | The system proxy is honored and `proxyUsed` is recorded. | JVM-loopback, EMU | Blocked (S3) | G2 |
| PV-09 | HTTP/1.1 only for latency, jitter and reachability, and the negotiated protocol is recorded. | JVM-loopback | Blocked (S3) | G1 |
| PV-10 | No retries (exactly one connection attempt per probe), no cache (repeat requests reach the server), redirects off (RD-02). | JVM-loopback | Blocked (S3) | G1 |
| PV-11 | Release variant contains only production hosts and no test or fixture endpoint. | REL | Production-configuration-required | G3 |

### 5.17 NC: NetworkClient contract suite (D3.8, D5.8)

One abstract test class, parameterized by a client factory, run against FakeNetworkClient, the loopback-backed production client, and any later client. It cannot be written until D5-11 fixes the interface (S2, S3).

| ID | Proves | Status | Gate |
|---|---|---|---|
| NC-01 | No exception escapes except CancellationException, for every scripted fault. | Blocked (S2, S3) | G1 |
| NC-02 | Every L1 outcome in D5-6 is producible and maps to its L2 failure. | Blocked | G1 |
| NC-03 | The supplied deadline is honored. | Blocked | G1 |
| NC-04 | Cancellation unblocks in bounded time (CN-04). | Blocked | G1 |
| NC-05 | No state is visible to the next call other than the labeled connection reuse. | Blocked | G1 |
| NC-06 | Concurrent calls stay independent. | Implemented at executor level | G1 |
| NC-07 | A success carries ProbeEvidence with a non-null network handle for pinned active probes, address family, negotiated protocol, connection state, proxy flag and byte counts. This also settles DP-2 for production data. | Blocked (S3) | G1 |
| NC-08 | Fake and production clients satisfy the same suite. | Blocked (S2) | G1 |

### 5.18 TH: throughput extension (D4-7, slice S8)

| ID | Proves | Layer | Status | Gate |
|---|---|---|---|---|
| TH-01 | Zero bytes by the time box gives Failed(Timeout(stage)). | JVM | Blocked (S8) | G1 |
| TH-02 | A stop at the byte cap or time box is a valid, labeled result. | JVM | Blocked (S8) | G1 |
| TH-03 | Rate is a pure function of bytes and duration, never stored, and excludes connection setup. Zero, tiny and huge durations and byte counts give no NaN or infinity. | JVM | Blocked (S8) | G1 |
| TH-04 | The interval list is bounded. | JVM | Blocked (S8) | G1 |
| TH-05 | Off by default. Requires consent. Never scheduled without it. | JVM-fake | Blocked (S8, OD-6) | G1 |
| TH-06 | Server-enforced byte caps hold even if the client is buggy. | CRS, production server | Production-configuration-required | G3 |
| TH-07 | Bytes accounted against TrafficStats. | DEV | Device-validation-required | G2 |

### 5.19 AG: architecture and policy guards (source and manifest scans)

| ID | Proves | Status | Gate |
|---|---|---|---|
| AG-01 | `core:model` imports only java, kotlin and itself. | Added | G1 |
| AG-02 | No type named `*PacketLoss*` in any module. | Added | G1 |
| AG-03 | No hard-coded third-party measurement host (D2-2, D2-9). | Added | G1 |
| AG-04 | `NetworkQuality.Measured` never constructed (D4-11). | Added | G1 |
| AG-05 | No custom trust manager or hostname verifier (D3-9). | Added | G1 |
| AG-06 | INTERNET declared only in `network:monitor` (D1-1). | Added | G1 |
| AG-07 | No cleartext permitted in main manifests or main network security config (D1-7). | Added | G1 |
| AG-08 | Every scan found files. | Added | G1 |
| AG-09 | Only `network:monitor` constructs `Succeeded` results. | Added | G1 |
| AG-10 | No Fake, Mock or Stub in main sources. | Added | G1 |
| AG-11 | No NXDOMAIN claim, no DNS measurement type (D4-10). | Added | G1 |
| AG-12 | Merged-manifest allowlist for permissions and providers, generated from the first real merged manifest (D1-6). | Blocked (S3) | G1 |

## 6. Decision points that need an owner

These are places where the documents are silent or ambiguous and a test cannot choose for the implementer. Each has a proposal. None is decided here.

| ID | Question | Proposal | Blocks |
|---|---|---|---|
| DP-1 | A series mixes methods or connection states. Should derived statistics reject it, partition it, or label it as mixed? | Partition by method and connection state, one statistic per partition, add a `method` field to DerivedLatencyStats. Never blend. | JT-17, LS-02 |
| DP-2 | Pairing treats two null network handles as equal, so a Wi-Fi to cellular switch with null handles is bridged. | Keep D4-3 as written and make the S3 client always supply a non-null handle for pinned active probes (NC-07). Optionally also compare `context.networkState.transport`. | JT-18, VP-02 |
| DP-3 | The mapper ignores `hasInternet`. Which layer owns "connected but no INTERNET capability": mapper, engine decline, or neither? | Engine declines with CapabilityUnavailable, mapper unchanged. | CX-09 |
| DP-4 | Value invariants (finite, non-negative) for Succeeded, ProbeEvidence, Freshness: enforce at construction or reject in every consumer? | Enforce at construction with `require`, and map a bad elapsed value in the engine to Failed(Unclassified("NonMonotonicClock")) so it never throws. | DM-08, DM-09, DM-10, TM-04 |
| DP-5 | D5.8 says any Throwable except cancellation maps to an outcome. Catching `VirtualMachineError` (for example out of memory) is generally unsafe. | Map Exception and non-fatal Error. Let VirtualMachineError propagate. Record this as an amendment to D5-9. | FT-04, EN-08 |
| DP-6 | The engine trusts the context the caller supplies. Nothing compares it with the live network at probe start. | Client compares the request's network identity with the network it is bound to at call start and returns NetworkChangedMidCall if different. | SC-02 |

## 7. Test infrastructure required

Most Blocked rows need shared pieces. Building them belongs to the slice that owns the interface.

1. A `NetworkState` test builder with defaults for every field, in one place, so a new domain field never again breaks every fixture (F-1 was exactly this). Needs a small change to how test fixtures are shared, so it is proposed, not added.
2. FakeNetworkClient extended to script thrown exceptions of any type, hangs at each stage, and blocked, no-reply and network-changed outcomes. This is an edit to AI 1's file, so AI 1 makes it with S3.
3. An injected monotonic clock with settable and backward-moving values, separate from MutableClock's wall clock (existing helper).
4. The loopback fixture server (LFS): TLS with test certificates, stall at DNS, connect, TLS, request and response stages, redirect, wrong length, wrong nonce, oversize, slow drip, close mid-response. D3-10 says it uses the HTTP client's own test server artifact, which is subject to OD-7.
5. The abstract NetworkClient contract suite (section 5.17).
6. A merged-manifest dump task and allowlist file (AG-12).
7. A device-run harness that logs exact environment (model, OS build, network type, DNS mode, VPN, proxy) with every result, and reports the Unclassified count.

## 8. Gate checklists

### 8.1 G1: must pass before merge

Applies to any change merging into a phase branch. Every item is checked in CI, never asserted by hand.

1. `./gradlew test testDebugUnitTest` is green, plus the existing static checks. No `@Ignore`, no skipped test, no test disabled to get green.
2. All rows marked G1 whose status is Implemented or Added are green. Rows Added, red are the merge gate for the jitter fix (F-2, F-3, F-4). Merging jitter consumers (S5) or integrating the phase branch while these are red is not allowed.
3. The change lists the IDs it delivers, and every Blocked row for its slice becomes Implemented in the same change:
   - S1 remainder: FT-03 to FT-06, FT-08, FT-10, CP-02, DM-08 to DM-10 once DP-4 is decided.
   - S2: NC-01 to NC-08 against the fake, PV-02, PV-04, PV-07, EN-05, LFS skeleton.
   - S3: RD-01 to RD-05, CN-03 to CN-04, TO-02, TO-04, DN-01 to DN-03, PV-03, PV-06, PV-09, PV-10, AG-12, EN-06.
   - S4: EN-04, EN-08, EN-10, TM-01 to TM-04, TM-09, SR-02 to SR-07, TO-03, TO-05, BC-02, BC-06.
   - S5: JT-17, JT-18, SR-06, SC-04, VP-02.
   - S6: FT-07, BC-05.
   - S7: UP-01 to UP-03, UP-06, UP-07, JT-20.
   - S8: TH-01 to TH-05, MT-02.
4. The architecture guards (AG-01 to AG-12) pass without being edited. If a guard has to change, the change says which policy was amended and cites the decision.
5. No new permission, dependency, cleartext setting or third-party host appears without the owner decision that authorizes it (D1-8: OD-1 before INTERNET, OD-7 before OkHttp).

### 8.2 G2: must pass before physical-device testing

Real-device time is scarce and results on an unready build are wasted. Start device testing only when all of the following hold.

1. Every G1 row for S1 to S4 is green.
2. Loopback suites are green: CN-04 to CN-07, TO-02, TO-04, TO-06, TM-05, TM-06, RD-01 to RD-05, PV-01, PV-03, PV-06, PV-09, PV-10, FR-07, CP-05.
3. Emulator suites are green: INTERNET-removed negative test asserting a decline (D1.8), CL-07, DN-05, BC-04 with Data Saver, PV-08, MT-04, SC-06.
4. The merged-manifest allowlist (AG-12) is in CI.
5. The Unclassified count is zero across all loopback and emulator runs (FT-09).
6. Calibration offset against the CRS is measured and recorded (TM-08), or the device run is labeled "uncalibrated" in every result.
7. The device protocol is written: device model and OS build, network conditions to cover (section 9), exact commands, data budget for the session, where results are stored. Emulator validation is never reported as device validation.
8. Owner decisions that block the device run are recorded: OD-1 (INTERNET), OD-6 (data budgets), and an endpoint that exists (LFS or CRS) that is not a third-party host.

### 8.3 G3: must pass before production

1. All G2 rows green, and the device matrix in section 9 executed on real hardware with results recorded and reviewed. Rows marked Device-validation-required are green on real devices.
2. Pilot data replaces the provisional jitter thresholds (JT-19) and the illustrative latency confidence cutoffs, with the derivation recorded.
3. Unclassified rate is zero across the pilot. Any nonzero rate was closed by a specific mapper case.
4. Production endpoint conformance passes (PV-05) and the release-variant inspection confirms only production hosts (PV-07, PV-11). Owner decisions OD-2 and OD-3 are recorded.
5. Server-enforced byte caps verified (TH-06). Data budgets from OD-6 are enforced by tests SR-04, SR-08, TH-05 and MT-02.
6. Release build tests run against the shrunk and obfuscated build, including the OkHttp consumer rules and permission set, and the merged manifest equals the allowlist.
7. UI and store wording reviewed: no "packet loss" before stage D calibration (UP-05, UP-09, OD-8). Privacy policy and Play data-safety declarations match the recorded behavior (OD-1, OD-3).
8. Battery and data cost audit for a representative day on a metered network.

## 9. Device matrix mapping

From the repository testing skill, each condition names the rows that must be observed on hardware.

| Condition | Rows |
|---|---|
| Wi-Fi validated | TM-06, PV-08, CL-07 |
| Mobile data, metered | MT-02, MT-03, MT-04, SR-08 |
| No validated internet | CX-06, EN-02, FT-09 |
| Captive portal | CP-06, CP-05, RD-04 |
| Slow or intermittent connectivity | TO-02, TO-06, UP-08 |
| Network transitions (Wi-Fi to cellular, roaming) | SR-06, JT-18, SC-04, SC-06 |
| VPN, always-on lockdown, per-app | VP-02, VP-03, BC-04 |
| Data Saver or blocked | BC-03, BC-04, BC-06 |
| Backend outage and DNS failure | DN-01, DN-02, DN-05, PV-05 |
| Airplane mode | EN-02, CN-05 |
| Permission denial | CL-07, EN-03, EN-06 |
| Process restart | SC-06 |

## 10. Verification performed for this tranche

Environment: Ubuntu 24 sandbox, OpenJDK 21.0.10, Kotlin compiler 2.3.21 (JetBrains release, same version the repository pins), JUnit 4.13.2. The Gradle wrapper could not be run in the sandbox, so the local run compiles `core:model` sources, plus the classifier, mapper and snapshot for `network:monitor`, directly with kotlinc and runs them with `org.junit.runner.JUnitCore`. CircleCI on this branch is the authoritative result. Nothing below is a claim about the Android modules or Gradle.

| Check | Result |
|---|---|
| Existing `core:model` suite on 9a28cc6 | 42 of 42 pass |
| New tests on 545a961 | `core:model` 73 run, 9 fail (JT-04 to JT-07, JT-09 to JT-12, and the explicit method case). `network:monitor` new tests 19 of 19 pass. |
| New tests with the Appendix A patch applied to a scratch copy | 73 of 73 pass, and the 42 existing tests still pass. |
| Mutation check of guards | 10 injected violations (Android-style import, PacketLoss type, Measured construction, HostnameVerifier, third-party host, INTERNET in app, cleartext in app, Succeeded construction outside network:monitor, Fake type in main, NXDOMAIN and DNS type). Each was caught by its own guard. INTERNET in `network:monitor` was accepted. |
| Mutation check of classifier and mapper matrices | 4 injected defects (DNS made Supported, cellular needing either permission, captive portal by substring, wrong transport priority). Each was caught. |

Run it yourself (from the repository root, after the wrapper works):
`./gradlew :core:model:test --tests '*DerivedJitterStatsInvariantTest' --tests '*ArchitectureGuardTest'` and
`./gradlew :network:monitor:testDebugUnitTest --tests '*MatrixTest'`.

## 11. Files added by this branch

| File | Tests | Status on base |
|---|---|---|
| `core/model/src/test/.../gates/ArchitectureGuardTest.kt` | 11 | green |
| `core/model/src/test/.../measurement/DerivedJitterStatsInvariantTest.kt` | 20 | 9 red (F-2, F-3, F-4) |
| `network/monitor/src/test/.../MeasurementCapabilityClassifierMatrixTest.kt` | 7 | green |
| `network/monitor/src/test/.../NetworkStateMapperMatrixTest.kt` | 12 | green |
| `PHASE_4_TEST_GATE_SPECIFICATION.md` | not applicable | not applicable |

No existing file is modified.

---

## Appendix A: proposed fix for F-2, F-3 and F-4 (verified locally, not applied)

This changes `DerivedJitterStats.kt`, an AI 1 file, so it is proposed here and not committed. Applied to a scratch copy of 545a961 it turns all 20 new jitter tests and the 42 existing tests green (section 10). It records the first counted pair's method, so DP-1 stays open for series that span methods.

```diff
--- a/core/model/src/main/kotlin/com/aeriva/core/model/measurement/DerivedJitterStats.kt
+++ b/core/model/src/main/kotlin/com/aeriva/core/model/measurement/DerivedJitterStats.kt
@@ -67,7 +67,6 @@
          */
         fun from(series: List<LatencyMeasurement>, calculatedAt: Instant): DerivedJitterStats? {
             if (series.isEmpty()) return null
-
             for (i in 1 until series.size) {
                 if (series[i].measuredAt.isBefore(series[i - 1].measuredAt)) {
                     return null
@@ -75,49 +74,45 @@
             }
 
             val differences = mutableListOf<Double>()
-            val delaysInValidPairs = mutableListOf<Double>()
-            val sourceIds = mutableListOf<Long>()
-
+            val members = linkedMapOf<Long, Double>()
+            var pairMethod: String? = null
             var previous: LatencyMeasurement.Succeeded? = null
+
             for (measurement in series) {
-                val succeeded = measurement as? LatencyMeasurement.Succeeded
+                val succeeded = (measurement as? LatencyMeasurement.Succeeded)?.takeIf { isRealReading(it) }
                 if (succeeded == null) {
                     previous = null
                     continue
                 }
-
                 val prev = previous
                 if (prev != null && isAdjacentPair(prev, succeeded)) {
                     differences += kotlin.math.abs(succeeded.valueMillis - prev.valueMillis)
-                    if (delaysInValidPairs.isEmpty()) {
-                        delaysInValidPairs += prev.valueMillis
-                        sourceIds += prev.id
-                    }
-                    delaysInValidPairs += succeeded.valueMillis
-                    sourceIds += succeeded.id
+                    members[prev.id] = prev.valueMillis
+                    members[succeeded.id] = succeeded.valueMillis
+                    if (pairMethod == null) pairMethod = prev.method
                 }
                 previous = succeeded
             }
 
             if (differences.isEmpty()) return null
 
-            val meanAbsIpdv = differences.sum() / differences.size
-            val pdvRange = (delaysInValidPairs.maxOrNull() ?: 0.0) - (delaysInValidPairs.minOrNull() ?: 0.0)
             val pairCount = differences.size
-
             return DerivedJitterStats(
                 definition = JitterDefinition.MEAN_ABS_CONSECUTIVE_DIFFERENCE,
-                method = series.first { it is LatencyMeasurement.Succeeded }.method,
-                meanAbsIpdvMillis = meanAbsIpdv,
-                pdvRangeMillis = pdvRange,
-                sampleCount = delaysInValidPairs.size,
+                method = pairMethod!!,
+                meanAbsIpdvMillis = differences.sum() / pairCount,
+                pdvRangeMillis = members.values.max() - members.values.min(),
+                sampleCount = members.size,
                 pairCount = pairCount,
-                sourceMeasurementIds = sourceIds.distinct(),
+                sourceMeasurementIds = members.keys.toList(),
                 calculatedAt = calculatedAt,
                 confidence = confidenceFor(pairCount)
             )
         }
 
+        private fun isRealReading(m: LatencyMeasurement.Succeeded): Boolean =
+            m.valueMillis.isFinite() && m.valueMillis >= 0.0
+
         private fun isAdjacentPair(
             a: LatencyMeasurement.Succeeded,
             b: LatencyMeasurement.Succeeded
```
