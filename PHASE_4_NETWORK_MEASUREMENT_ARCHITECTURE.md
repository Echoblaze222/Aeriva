# AERIVA Phase 4 — Real Network Measurement Architecture

Architecture/design document only. Nothing in this document implements Phase 4, modifies Phase 3A/3B/3C code, adds a dependency, requests a permission, or merges anything. Every claim is labeled **VERIFIED FACT**, **PROPOSED DESIGN**, **ASSUMPTION**, **LIMITATION**, or **OPEN DECISION**. VERIFIED FACT means confirmed this session, directly, either against this repository's actual current state (via the GitHub Project connector) or against a specific, current, cited source — never recalled from training data or trusted from a prior AI report without independent confirmation.

---

## 1. Executive Summary

AERIVA has a working **observation** layer (`AndroidNetworkMonitor`) and a designed-but-unmerged **measurement** layer for a single illustrative metric, latency. Three Phase 3B branches, taken together, already contain most of a working, tested latency measurement path:

- `phase-3b-android-contract-review` — `MeasurementCapabilityClassifier`, a pure permission/SDK-level classifier for all ten measurement capabilities named in this task.
- `phase-3b-measurement-tests` — the `NetworkClient` transport-seam interface, `DerivedLatencyStats.from()` aggregation, and a fully-tested (but test-only) `ReferenceLatencyProbeExecutor` proving the concurrency/timeout/cancellation pattern works.
- `phase-3b-measurement-engine` — `LatencyMeasurementEngine`, a well-reasoned production engine built on the above two, but **currently unintegrable**: it depends on files that were never copied onto its own branch and fails CircleCI's `build` job with 20+ unresolved-reference errors (**VERIFIED FACT**, confirmed independently this session — see Section 3).

Phase 4's job is not to invent this path from scratch. It is to: (1) specify how these three branches integrate into one buildable measurement path, (2) extend that path from latency-only to the other nine named capabilities, (3) replace the reference/engine's placeholder 8-byte protocol and undecided endpoint with a real design, (4) add the battery/data/concurrency/security architecture a *production* engine needs that a test-proving-the-pattern engine does not, and (5) be explicit about what Android will not reliably let AERIVA measure at all.

The single most consequential open decision this document surfaces and does **not** resolve (per this task's own scope limits) is the **endpoint strategy** (Section 11): AERIVA has no measurement endpoint of any kind today, and every downstream timing number (Section 8) is meaningless without one.

---

## 2. Current Repository State

**VERIFIED FACT**, from direct inspection of `Echoblaze222/Aeriva` via the GitHub Project connector this session (not from any prior report).

| | |
|---|---|
| `main` HEAD | `e3f70a4a13e63b782c61601abadc2c36f65dbd73` ("Merge pull request #2 from Echoblaze222/phase-3a-domain-model") |
| Phase 3B/3C code on `main` | None |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| AGP / Kotlin / KSP | 8.13.2 / 2.3.21 / 2.3.11 |
| Modules | `app`, `core:common`, `core:model`, `core:result`, `core:logging`, `core:database`, `core:preferences`, `core:security`, `network:monitor` |
| HTTP/socket library | **None.** No OkHttp, Ktor, or Retrofit anywhere in `gradle/libs.versions.toml`. |
| WorkManager | Not a dependency (confirmed absent, matches `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own finding) |
| Manifest permissions, repo-wide | **Only `ACCESS_NETWORK_STATE`**, declared in `network:monitor`'s manifest. `app/src/main/AndroidManifest.xml` declares zero permissions and has no launcher activity. There is no `INTERNET` permission declared anywhere in the repository, on any branch inspected this session. |
| `network:monitor` production dependency on `core:common` | Present on `phase-3b-measurement-engine` only (`implementation(project(":core:common"))`, correctly scoped to main, not test); **absent** on `main` and on `phase-3b-measurement-tests` (there it is `testImplementation` only). |

**Branch inventory** (all 15 branches listed via `list_branches`; the eight Phase-3-relevant ones, with each HEAD's actual file tree independently checked, not assumed from commit messages):

| Branch | HEAD | State |
|---|---|---|
| `phase-3a-domain-model` | `6e8cd86` | Merged into `main` |
| `phase-3b-android-measurement` → `phase-3b-android-contract` → `phase-3b-android-contract-review` | `fc004b6` → `a67b74d` → `27c0f2f` | One linear lineage (each is a direct child of the last — **VERIFIED FACT**, not three competing versions). `phase-3b-android-contract-review` is current. CI green (run `e84ebeac`, all 4 jobs). |
| `phase-3b-measurement-tests` | `8cbab34` | CI green at HEAD (run `9fee886f`), after two earlier CI-driven fix commits. |
| `phase-3b-measurement-engine` | `d1ea16f` | **CI build fails** (runs `666d57af` and `5aeed0db`, both `build`-job failures at `:network:monitor:compileDebugKotlin`). |
| `phase-3c-android-validation-matrix` | `07c0ab4` | Documentation only (`PHASE_3C_ANDROID_REAL_DEVICE_VALIDATION_MATRIX.md`), based on unmodified `main`, not merged, no code. |

A separate, existing document already on this repository — `phase-3b-repository-state-reconciliation` branch, `PHASE_3B_REPOSITORY_STATE_RECONCILIATION.md` (dated 2026-09-18, the most recent commit in the repository at the time of this audit) — performed exactly the "verify against source, not against prior AI claims" reconciliation this task also asks for, independently, against the GitHub and CircleCI APIs directly. This document's own Section 3 findings were cross-checked against that reconciliation and against direct `get_file_contents` reads of every file named below; where they agree it is because both independently confirmed the same underlying repository state, not because one was taken on faith from the other.

---

## 3. Verified Phase 3 Foundation

All code excerpts below were read in full, this session, at each branch's current HEAD.

### 3.1 Phase 3A domain types (`core:model`, merged on `main`)

**VERIFIED FACT.** Nine files under `core/model/.../measurement/`, each a deliberately minimal, single-metric (latency) illustration of one tier:

- `Confidence` — sealed `Insufficient | Low | Medium | High`, with an illustrative `of(sampleCount, consistent)` tiering (thresholds explicitly marked illustrative in the type's own KDoc).
- `Freshness` — `producedAt`/`validUntil`, `isStaleAt(now)` and `ageAt(now)` both take an explicit `now: Instant` parameter (never call `Instant.now()` internally) — this is the seam Phase 4 must preserve, not just for this type but for every duration/staleness calculation added.
- `MeasurementNetworkContext` — wraps the existing Phase 2 `NetworkState`; `wifiRssi`/`cellularSignalStrength` are nullable and **deliberately unpopulated** by anything in Phase 3A (both require `ACCESS_FINE_LOCATION`, not currently requested).
- `MeasurementFailure` — closed sealed type: `Timeout | Cancelled | EndpointFailure(reason) | TlsFailure(reason) | InvalidResponse(reason) | NetworkChangedDuringMeasurement`. Deliberately does **not** duplicate `AerivaError` (permission/unsupported-capability failures stay there) and does **not** represent "no network" (that is an observation-tier fact on `NetworkState.available`, not a measurement failure).
- `LatencyMeasurement` — sealed `Succeeded(valueMillis, sampleCount, …) | Failed(failure, …)`, structurally distinct so a failed probe can never be misread as "succeeded with a low value."
- `DerivedLatencyStats` — `averageMillis/minMillis/maxMillis/sourceMeasurementIds/calculatedAt/confidence`, deterministic arithmetic only, no inference.
- `LatencyEstimation` — sealed `InsufficientEvidence | Estimated(valueMillis, method, sourceEvidenceIds, confidence, freshness)`. `InsufficientEvidence` is a first-class outcome, never silently replaced by a fabricated `Estimated`.
- `LatencyPrediction` — sealed `InsufficientEvidence | Predicted(targetDescription, predictedAt, valueMillis, sourceHistoryIds, methodId, confidence, freshness)`.
- `ConnectivityRecommendation` — sealed `NoRecommendation(reason) | Recommended(target, activityProfile, supportingEvidenceIds, decisionCriteria, confidence, freshness, generatedAt, explanation)`.

Supporting Phase 2 types reused, not duplicated: `NetworkState` (transport/available/validated/metered/capabilities/estimatedQuality/diagnosticsStatus/lastChangedAt), `NetworkQuality` (`Unavailable | Measured(score, label)` — **currently only `Unavailable` is ever produced**, by explicit design, pending a real measurement engine), `AerivaResult`/`AerivaError` (`Unsupported | PermissionRequired | DataCorrupted | Unknown`).

### 3.2 `MeasurementCapabilityClassifier` (`network:monitor`, `phase-3b-android-contract-review`)

**VERIFIED FACT** — full source read. A pure, no-Android-import object mapping `(MeasurementCapability, sdkInt, grantedPermissions) → CapabilityClassification`, where `CapabilityClassification` is `Supported | SupportedWithLimitations(reason) | Estimated(reason) | NotReliablyAvailable(reason)`. Its actual classification, capability by capability (this is the single most load-bearing artifact for Section 6 below):

| `MeasurementCapability` | Gate | Classification when gate is held |
|---|---|---|
| `LATENCY`, `JITTER`, `HTTPS_REACHABILITY` | `INTERNET` | `Supported` |
| `DNS_RESPONSIVENESS` | `INTERNET` | **`Estimated`** — a timed lookup is confounded by OS/carrier/resolver DNS caching; this is a corrected classification (an earlier draft on this same branch lineage incorrectly called it `Supported`, fixed per `PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md` Task 10 — the classifier's own comment documents this correction) |
| `PACKET_LOSS` | `INTERNET` | `SupportedWithLimitations` — no root/raw sockets on stock Android; "lost" vs. "slow" is inherently imprecise |
| `THROUGHPUT` | `INTERNET` | `SupportedWithLimitations` — a real transfer moves real data; must default to infrequent, consented, unmetered-aware |
| `NETWORK_STABILITY`, `NETWORK_TRANSITIONS` | `ACCESS_NETWORK_STATE` | `Supported` (already implemented, observation-tier) |
| `WIFI_CHARACTERISTICS` | `ACCESS_FINE_LOCATION` | `SupportedWithLimitations` — also needs device location services on, real behavior is real-device-only |
| `CELLULAR_CHARACTERISTICS` | `READ_PHONE_STATE` **and** `ACCESS_FINE_LOCATION` | `SupportedWithLimitations` — real values are real-device/OEM-dependent; API 29+ `getAllCellInfo()` without `requestCellInfoUpdate()` may return a cached result |

Each `NotReliablyAvailable` branch names the specific missing permission. This is a **classification of what the platform currently permits given currently-held permissions** — it is explicitly not a decision about whether AERIVA *should* request a given permission (that remains, per the classifier's own KDoc, "the individually-justified, per-feature decision `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 10 requires").

### 3.3 `NetworkClient` (`network:monitor`, `phase-3b-measurement-tests`)

**VERIFIED FACT.** An interface, no implementation on any branch:

```kotlin
interface NetworkClient {
    suspend fun probe(target: String): NetworkClientOutcome
}
sealed interface NetworkClientOutcome {
    data class Success(val payload: ByteArray) : NetworkClientOutcome
    data class ConnectionRefused(val reason: String) : NetworkClientOutcome
    data class TlsHandshakeFailed(val reason: String) : NetworkClientOutcome
    data object NetworkChangedMidCall : NetworkClientOutcome
}
```

`target` is opaque (host:port or URL — the interface takes no position). `probe` does not itself enforce a timeout; that is deliberately left to the caller (`withTimeout`). This is **narrower than `MeasurementFailure`** by design — `NetworkClientOutcome` describes what happened at the transport level; mapping it to a domain-tier `MeasurementFailure` is the calling provider/engine's job. **No production implementation of this interface exists anywhere in this repository.** This is the single largest concrete gap Phase 4 must close (Section 5).

### 3.4 `DerivedLatencyStats.from()` (`core:model`, `phase-3b-measurement-tests`, additive to the merged Phase 3A type)

**VERIFIED FACT.** Filters to `LatencyMeasurement.Succeeded` only, rejects negative/non-finite values, computes average/min/max, and derives `Confidence` from an illustrative "spread ≤ 50% of average" consistency heuristic. Returns `null` (not NaN, not a division-by-zero) when nothing valid remains to aggregate.

### 3.5 `LatencyMeasurementEngine` (`network:monitor`, `phase-3b-measurement-engine`) — exists, does not compile

**VERIFIED FACT**, full source read. This is a genuinely well-designed production engine, and Phase 4 should treat its shape as the reference design to integrate, not redo:

- Checks `MeasurementCapabilityClassifier.classify(LATENCY, sdkInt, grantedPermissions)` before attempting a probe; short-circuits to `CapabilityUnavailable` if `NotReliablyAvailable`.
- Short-circuits to `NoNetwork` if `context.networkState.available == false` (observation-tier fact, not a `MeasurementFailure` — matches Phase 3A's own Section 12 distinction).
- Uses `withContext(dispatchers.io) { withTimeout(timeoutMillis) { … } }`, catching only `TimeoutCancellationException` — a genuine caller `CancellationException` propagates uncaught and produces no result (correct per Phase 3's test strategy's own required shape).
- **Resolves an open question from Phase 3A explicitly**: duration (`valueMillis`) is measured via an injectable `elapsedNanos: () -> Long = System::nanoTime` (monotonic), while the persisted timestamp (`measuredAt`) uses the existing wall-clock `now: () -> Instant` seam — because a wall-clock adjustment (NTP sync, DST) mid-probe would corrupt an `Instant`-subtraction duration even though real elapsed time was normal. This resolution is sound and Phase 4 should adopt it for every future metric's duration, not just latency's.
- `measureSeries()` runs probes **sequentially**, not concurrently, by explicit design ("do not introduce unnecessary coroutine scopes"), and derives `DerivedLatencyStats` via the existing `.from()` function — no second aggregation implementation.
- **Why it fails to compile**: its own commit message claims to bring in "AI 3's and AI 4's dependency code," but per direct `get_file_contents` inspection of its `network/monitor/src/main/kotlin/com/aeriva/network/monitor/` tree, it contains **neither** `MeasurementCapabilityClassifier.kt` **nor** `NetworkClient.kt` — only the pre-existing files plus its own new `measurement/LatencyMeasurementEngine.kt`. CircleCI's `build` job (runs `666d57af`, `5aeed0db`) fails with 20+ "Unresolved reference" errors naming exactly these two files' types. This is confirmed independently in this session (direct file-tree read), not merely repeated from the reconciliation branch's own finding.
- A referenced companion document, `PHASE_3B_MEASUREMENT_ENGINE_IMPLEMENTATION.md`, is named in the branch's commit message but **does not exist anywhere on the branch** — a real discrepancy between commit message and diff content, not resolved by this document.

### 3.6 Existing test infrastructure

**VERIFIED FACT.** No mocking library (no MockK/Mockito), no Robolectric, no Turbine (named as an unadopted candidate). The established, consistent convention is **hand-written fakes implementing the same production interface** (`FakeSecureKeyValueStore`, `FakeNetworkStateHistoryDao`, `TestAerivaDispatchers`, `FakeNetworkClient`) living in `src/test` or, for `TestAerivaDispatchers`, a `testFixtures` source set. `ReferenceLatencyProbeExecutor` (test-only, `phase-3b-measurement-tests`) already proves — with real passing tests, not by inspection — that `NetworkClient` + `FakeNetworkClient` + `AerivaDispatchers` + the injected-clock seam are jointly sufficient to deterministically test timeout, cancellation, concurrency, and malformed-response handling. Its own KDoc explicitly states the real engine is expected to follow its pattern, not reuse the class itself — and `LatencyMeasurementEngine` (Section 3.5) does exactly that.

A previously-learned, still-binding lesson (documented in `AndroidNetworkMonitorInstrumentedTest`'s own comment and generalized in `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`): a test using `runTest`'s virtual-time scheduler **hangs and produces a misleading timeout message** when waiting on a real Android system callback (a real `ConnectivityManager` callback, a real socket). The fix already adopted is `runBlocking` (real time) for any test waiting on a real system event, `runTest` (virtual time) only otherwise. Phase 4's instrumented-test design (Section 18) must follow this rule for real-socket tests.

---

## 4. Current Architecture (as it exists, not as designed)

```
ConnectivityManager (Android platform)
        │  onAvailable / onCapabilitiesChanged / onLost / onUnavailable
        ▼
AndroidNetworkMonitor (network:monitor, MERGED, production)
        │  Flow<NetworkState>  [observation tier — no active probing]
        ▼
NetworkState  (core:model, MERGED)
        │  wrapped by
        ▼
MeasurementNetworkContext  (core:model, MERGED — Phase 3A)
        │  consumed by (UNMERGED, and non-compiling as a set — Section 3)
        ▼
MeasurementCapabilityClassifier ──gates──▶ LatencyMeasurementEngine ──uses──▶ NetworkClient (NO IMPLEMENTATION)
        (phase-3b-android-contract-review)   (phase-3b-measurement-engine)      (phase-3b-measurement-tests, interface only)
                                                       │
                                                       ▼
                                              LatencyMeasurement (core:model, MERGED type; unmerged producer)
                                                       │
                                                       ▼
                                     DerivedLatencyStats.from()  (phase-3b-measurement-tests, unmerged)
                                                       │
                                                       ▼
                                    LatencyEstimation / LatencyPrediction / ConnectivityRecommendation
                                              (core:model types exist, MERGED — nothing populates them; FUTURE)
```

This diagram is the dependency graph the reconciliation document (Section 2) already derived independently; this document confirms it against the same evidence and uses it as Section 4's baseline.

---

## 5. Proposed Phase 4 Architecture — Overview

Phase 4 does **not** propose rearchitecting anything above. Per this task's own instruction and per the Phase 2 audit's own precedent ("this audit found no reason to recommend rearchitecting it... this task's own instructions explicitly disallow doing so 'without evidence'"), the proposed architecture is additive:

1. **Integration, not reinvention** (Section 5.1): land the three Phase 3B branches, in dependency order, with the two missing files actually copied onto whatever branch/PR does the integration — this is a **mechanical fix**, not a design problem. **PROPOSED DESIGN.**
2. **A production `NetworkClient` implementation** (Section 10): the single largest real gap. **PROPOSED DESIGN.**
3. **Generalize the single-metric pattern** (Section 5.2) from `Latency*` to the other nine capabilities, without collapsing the OBSERVATION/MEASUREMENT/ESTIMATION/PREDICTION/RECOMMENDATION boundary (per this task's own explicit prohibition and Phase 3A's own flagged open risk that `NetworkQuality.Measured(score, label)` is itself a latent instance of that exact anti-pattern — Section 18 open decision #1 in the Phase 3A domain doc, carried forward here unresolved). **PROPOSED DESIGN.**
4. **Add what a test-proving-the-pattern engine deliberately omits**: retries/backoff, de-duplication, persistence, scheduling, and battery/data budgets (Sections 7, 12). **PROPOSED DESIGN.**
5. **Do not add** anything Section 15 of the assignment prohibits, and do not silently resolve any of Section 3's open discrepancies (the missing `.md`, the unresolved scheduler-seam decision) — surface them as open decisions instead (Section 21).

### 5.1 Integration order (mechanical, not architectural)

**PROPOSED DESIGN**, directly extending the reconciliation document's own Section 16 finding rather than re-deriving it independently:

1. Land `phase-3b-android-contract-review` (classifier) — no dependencies on the other two.
2. Land `phase-3b-measurement-tests` (`NetworkClient` interface, `DerivedLatencyStats.from()`) — no dependency on the classifier.
3. Rebase `phase-3b-measurement-engine` onto the result of (1)+(2), and **at that point, and not before, copy `MeasurementCapabilityClassifier.kt` and `NetworkClient.kt` into its tree** (or, more precisely: once (1) and (2) are merged, `phase-3b-measurement-engine`'s existing `implementation(project(":core:common"))` and its source already assume both files exist on its own compile classpath — they will, once it depends on the merged result rather than needing its own copies). This resolves the CI failure (Section 3.5) as a natural consequence of merge order, not a separate fix.
4. Only then does a production `NetworkClient` implementation (Section 10) become buildable against a compiling engine.

This order is a direct, mechanical consequence of the dependency graph in Section 4 — it is not a new architectural judgment call, and Phase 4 does not need to justify it beyond restating what already blocks what.

### 5.2 Generalizing beyond latency

**PROPOSED DESIGN.** The existing pattern (`LatencyMeasurement` / `DerivedLatencyStats` / `LatencyEstimation` / `LatencyPrediction`) is metric-specific by design (Phase 3A's own stated reason: prove the tier boundary compiles for one metric before building it for all of them). Phase 4 should:

- Introduce the equivalent sealed families for **jitter**, **packet loss**, and **throughput** only — the three other capabilities classified `SupportedWithLimitations`/`Supported` at the MEASUREMENT tier (Section 3.2's table). **Not** for DNS responsiveness (ESTIMATION tier only — see Section 4's capability matrix — there is no `DnsMeasurement` type to design, only `DnsEstimation`), and **not** for Wi-Fi/cellular characteristics as raw "measurements" — those are **observations of platform-reported state** (an `WifiInfo`/`CellInfo` reading), not active probes, and should extend `MeasurementNetworkContext` (already has nullable `wifiRssi`/`cellularSignalStrength` fields reserved for exactly this) rather than get their own `*Measurement` sealed type.
- Each new sealed family follows `LatencyMeasurement`'s exact shape (`Succeeded`/`Failed`, `MeasurementFailure`, own `Derived*Stats.from()`), so a future `when` over any of them stays exhaustive at the type level — this is what keeps the five-tier boundary enforceable at compile time (Phase 3A's own stated purpose) rather than by convention.
- **Explicitly out of scope for this phase**: a generic `Measurement<T>` supertype/interface unifying latency/jitter/packet-loss/throughput. **OPEN DECISION**: whether such a unification ever happens, and if so how it avoids becoming the "one generic score" anti-pattern Phase 3A's own Section 18 already flagged as a live risk with `NetworkQuality.Measured`. Not resolved here — deliberately, since resolving it now would be exactly the kind of speculative pre-building this task's instructions prohibit ("do not invent replacement domain types unless necessary").

---

## 6. Component Diagram (proposed, additive to Section 4)

```
                        ┌─────────────────────────────┐
                        │   MeasurementScheduler        │  OPEN DECISION (Sec. 14) —
                        │   (cooldowns, budgets, dedup)│  depends on WorkManager decision
                        └───────────────┬──────────────┘
                                        │ requests
                                        ▼
┌───────────────┐   gates    ┌─────────────────────────┐   probes via   ┌──────────────────────┐
│ Measurement-   │──────────▶│  {Latency,Jitter,Packet- │────────────────▶│  NetworkClient        │
│ CapabilityClass│           │  Loss,Throughput}        │                 │  (production impl —   │
│ ifier (EXISTS) │           │  MeasurementEngine        │                 │  Section 10, NEW)     │
└───────────────┘           │  (Latency EXISTS but      │                 └──────────┬───────────┘
                             │   non-compiling; others   │                            │ opens
                             │   NEW, same shape)        │                            ▼
                             └────────────┬──────────────┘                 ┌──────────────────────┐
                                          │ produces                       │  Endpoint(s)          │
                                          ▼                                │  (Section 11 —        │
                             ┌─────────────────────────┐                   │  OPEN DECISION)       │
                             │ {Metric}Measurement       │                   └──────────────────────┘
                             │ (EXISTS for Latency;      │
                             │  NEW, same shape, for     │
                             │  Jitter/PacketLoss/       │
                             │  Throughput)               │
                             └────────────┬──────────────┘
                                          │ aggregated by
                                          ▼
                             ┌─────────────────────────┐
                             │ Derived{Metric}Stats.from │  EXISTS for Latency; NEW for others
                             └────────────┬──────────────┘
                                          │ persisted via (extends existing DAO pattern,
                                          │  NetworkStateHistoryEntity precedent)
                                          ▼
                             ┌─────────────────────────┐
                             │ MeasurementHistory (Room) │  NEW — Section 10 of this document
                             └────────────┬──────────────┘
                                          │ read by (FUTURE, not this phase)
                                          ▼
                          {Metric}Estimation / Prediction / ConnectivityRecommendation
                                   (types EXIST, MERGED; nothing populates them yet)
```

---

## 7. Data-Flow Diagram: One Measurement Lifecycle

**PROPOSED DESIGN**, generalizing `LatencyMeasurementEngine.measure()`'s already-verified shape (Section 3.5) to any of the four MEASUREMENT-tier capabilities:

```
1. Scheduler decision            [NEW — Section 14]
   → cooldown elapsed? budget remaining? user-triggered or automatic?
   → NO  → declined, no probe attempted, nothing recorded (not even a Failed)
   → YES ↓
2. Capability check              [EXISTS — MeasurementCapabilityClassifier]
   → NotReliablyAvailable → CapabilityUnavailable(reason), no probe attempted
   → otherwise ↓
3. Observation check             [EXISTS — MeasurementNetworkContext.networkState.available]
   → false → NoNetwork, no probe attempted
   → true ↓
4. Probe execution               [EXISTS pattern (Latency); NEW impl (NetworkClient) — Section 10]
   → withContext(dispatchers.io) { withTimeout(timeoutMillis) { networkClient.probe(target) } }
   → TimeoutCancellationException → {Metric}Measurement.Failed(Timeout)
   → genuine CancellationException → propagates, NO result recorded for this attempt
5. Outcome mapping                [EXISTS pattern — NetworkClientOutcome → MeasurementFailure]
   → ConnectionRefused → Failed(EndpointFailure)
   → TlsHandshakeFailed → Failed(TlsFailure)
   → NetworkChangedMidCall → Failed(NetworkChangedDuringMeasurement)
   → Success but payload doesn't match protocol → Failed(InvalidResponse)   [see Sec. 9 — protocol undecided]
   → Success and valid → {Metric}Measurement.Succeeded(valueMillis / count / bytes, …)
6. Persistence                    [NEW — Section 10]
   → append to MeasurementHistory (Room), regardless of Succeeded/Failed (a Failed result is still
     evidence — Phase 3A's own MeasurementFailure design implies failures are worth recording, not discarding)
7. Aggregation (on demand or on schedule) [EXISTS pattern for Latency; NEW for others]
   → Derived{Metric}Stats.from(recent Succeeded measurements, now) → stats or null (InsufficientEvidence)
8. Estimation / Prediction / Recommendation  [FUTURE — types exist, no producer designed here or elsewhere yet]
```

Step 6 (persistence) and step 8 (estimation/prediction/recommendation production) are the two points where this document's proposed design goes beyond what any existing branch currently does — both are new work, not integration of existing work.

---

## 8. Capability Matrix

This is the assignment's own required per-capability determination. Where a row restates a `MeasurementCapabilityClassifier` finding (Section 3.2), it is marked **VERIFIED FACT** (confirmed against that code); where it goes beyond what the classifier encodes (battery/data cost, confidence caveats), it is marked **PROPOSED DESIGN** or **ASSUMPTION** as appropriate — the classifier itself does not encode cost or confidence, only permission-gated classification.

| Capability | What can actually be measured | Required API(s) | Permission(s) | Direct measurement or estimate? | Battery cost | Data cost | Confidence/reliability limitation |
|---|---|---|---|---|---|---|---|
| **Latency** | Round-trip time of one probe against a chosen endpoint | Sockets (`java.net.Socket`/`HttpURLConnection`, or a chosen library — Section 10) | `INTERNET` | **Direct measurement** (VERIFIED FACT: classifier says `Supported`) | Low per probe; adds up if frequent | Small per probe (bytes, not kilobytes, if payload is kept minimal) | Endpoint choice (Section 11) dominates accuracy more than anything client-side; a nearby endpoint vs. a distant one changes the number by an order of magnitude, independent of "true" network quality |
| **Jitter** | Variance in round-trip time across repeated latency probes | Same as latency, repeated | `INTERNET` | **Direct measurement**, derived arithmetically from multiple latency samples — not a separate probe type | Same driver as latency, ×N samples | Same driver as latency, ×N | Needs enough samples for the variance figure to mean anything (Phase 3's own test strategy already flags sample-count boundary testing as required) |
| **Packet loss** | Fraction of a probe train that never returns a response within a bound | UDP datagrams, no root required | `INTERNET` | **`SupportedWithLimitations`** (VERIFIED FACT, classifier) — cannot reliably distinguish "packet lost" from "packet arriving very late" without raw sockets/root, which stock Android does not grant | Higher than single-ping latency — needs a probe *train*, not one round trip | Higher than latency, still small in absolute bytes for a UDP train | Medium at best; must be reported with the limitation stated, never as a precise, root-equivalent loss percentage |
| **Throughput** | Bytes transferred over a timed window, converted to a rate | A real, timed transfer (download and/or upload) against an endpoint that can serve/accept real payload | `INTERNET` | **`SupportedWithLimitations`** (VERIFIED FACT) — a real transfer moves real data; this is the highest-cost measurement in the matrix | **High** if run often | **High** — the only capability in this table where the measurement's own cost is comparable to normal app data use | Medium-high; must default to infrequent, explicitly consented, and Wi-Fi/unmetered-aware (classifier's own stated reason) |
| **Network stability** | Frequency/pattern of `NetworkState` transitions over time | `ConnectivityManager` callbacks (already implemented) | `ACCESS_NETWORK_STATE` | **Observation**, not a probe — already-implemented `AndroidNetworkMonitor` output, aggregated over a window | Negligible (already running) | None | High — this is the one row with no new platform risk; the work is aggregation logic, not new measurement |
| **Network transitions** | Discrete Wi-Fi↔cellular↔offline change events | Same as above | `ACCESS_NETWORK_STATE` | **Observation** | Negligible | None | High for detecting *that* a transition happened; correlating an in-flight probe's failure with a transition that caused it is a **real, unresolved concurrency question** (Section 13) |
| **Wi-Fi characteristics** (SSID/RSSI) | Currently-connected Wi-Fi network's signal strength and identity | `ConnectivityManager.registerNetworkCallback` + `FLAG_INCLUDE_LOCATION_INFO`, reading `NetworkCapabilities.getTransportInfo()` as `WifiInfo` — **not** the deprecated `WifiManager.getConnectionInfo()` (VERIFIED FACT, per the classifier's own corrected KDoc, itself citing current official API deprecation status) | `ACCESS_FINE_LOCATION` **and** device location services enabled | **`SupportedWithLimitations`** (VERIFIED FACT) — an observation of platform-reported radio state, not an active probe | Low (single read) | None | Medium — user can deny the permission or disable location; real RSSI values are real-device-only (no emulator fidelity) |
| **Cellular characteristics** | Current cell signal strength/type | `TelephonyManager.getAllCellInfo()` (poll) or `TelephonyCallback` (31+, push) | `READ_PHONE_STATE` **and** `ACCESS_FINE_LOCATION` (VERIFIED FACT — stricter than an earlier draft's guess of fine-location-alone, per the classifier's own corrected KDoc) | **`SupportedWithLimitations`**, observation of platform-reported radio state | Low | None | Medium — API 29+ `getAllCellInfo()` may return a cached, not live, result without an explicit `requestCellInfoUpdate()` call (VERIFIED FACT, current official reference docs); real values are real-device/OEM-dependent |
| **DNS responsiveness** | Timed resolution of a hostname via `InetAddress`/socket-level lookup | No dedicated Android API — app-level timed lookup | `INTERNET` | **Estimation, not measurement** (VERIFIED FACT, classifier, corrected classification) — OS/carrier/resolver DNS caching (see `LinkProperties.isPrivateDnsActive()`/`getPrivateDnsServerName()`/`getDnsServers()`, current official API — VERIFIED FACT) confounds a raw timed lookup; it observes *which* resolver is configured, not that resolver's live responsiveness | Low | Small per lookup | Medium — must never be labeled a direct measurement (per this task's own explicit instruction and the classifier's own stated reasoning) |
| **HTTPS/network reachability** | Whether a TLS handshake + HTTP round trip to a known endpoint succeeds | `HttpsURLConnection`/TLS socket | `INTERNET` | **Direct measurement** (VERIFIED FACT, classifier: `Supported`) of "can AERIVA currently reach this specific endpoint over HTTPS" — not a general "is the internet reachable" claim; a captive portal can make this fail while `NET_CAPABILITY_VALIDATED` is also false for the same underlying reason (Section 9) | Low | Small | High for what it actually claims (reachability of one endpoint); must not be generalized to "internet is down" without cross-referencing `NetworkState.validated` (already-observed) |

**LIMITATION**, applying across the whole table: none of these classifications change what a *real device* actually reports — every `SupportedWithLimitations`/observation-tier row still requires physical-device validation (Section 9, Section 18) before its numbers can be trusted, independent of the permission question this table (and the classifier) answers.

---

## 9. Platform Limitations — What Android Does Not Reliably Let AERIVA Determine

Restated explicitly and separately, per this task's own Section 9 requirement, rather than left implicit in Section 8's table:

- **Packet loss is fundamentally ambiguous without root.** (VERIFIED FACT, classifier's own stated reasoning, Section 3.2/8.) A UDP probe train can tell AERIVA "this response never arrived within N seconds," never "this specific packet was dropped by this specific hop." AERIVA must report this as an estimate of loss-like behavior, not authoritative packet-level loss.
- **Wi-Fi/cellular signal APIs require location permission at every currently-supported API level** (VERIFIED FACT — `ACCESS_FINE_LOCATION` for Wi-Fi `WifiInfo` via the current, non-deprecated API path; `READ_PHONE_STATE` + `ACCESS_FINE_LOCATION` for cellular). There is no lower-friction path to real signal-strength numbers on minSdk 26 today; `NEARBY_WIFI_DEVICES` (33+, `neverForLocation`) is API-33+-only and does not cover cellular at all (carried forward from `PHASE_2_ANDROID_PLATFORM_AUDIT.md`, independently confirmed as still current this session).
- **OEM differences are not enumerable in a document.** Signal-strength reporting, background-execution killing, and Doze enforcement all vary by OEM/ROM in ways AOSP-level documentation does not capture (carried forward from the Phase 2 audit; this remains true and is not resolved by anything in Phase 4).
- **Emulators cannot validate radio-dependent capabilities at all.** Wi-Fi/cellular signal values, real Doze timing, and real OEM battery behavior are emulator-blind by nature — this is a testing-architecture constraint (Section 18), not a Phase-4-solvable problem.
- **VPN presence is invisible to a normal app in the way that matters most**: AERIVA can detect `NET_CAPABILITY_NOT_VPN`'s absence (i.e., know a VPN is active) via the already-implemented capability read, but cannot know what that VPN does to timing — a VPN can add arbitrary latency/jitter of its own, and AERIVA's probe measures the VPN-augmented path, not the underlying physical network. **ASSUMPTION**: users running a VPN represent a real but currently un-sized fraction of AERIVA's target audience (poor/expensive connectivity contexts, where a VPN is less common but not absent) — not verified against any AERIVA user data, since none exists yet.
- **Captive portals confound reachability and validation together.** (VERIFIED FACT — `NET_CAPABILITY_CAPTIVE_PORTAL` and `NET_CAPABILITY_VALIDATED` are mutually exclusive per current official docs: a captive-portal network has `INTERNET` and `CAPTIVE_PORTAL` but not `VALIDATED`.) A latency/HTTPS probe against AERIVA's own endpoint will simply fail (connection succeeds to the portal's intercept, not to AERIVA's real endpoint, or the TLS handshake fails against the portal's own certificate) while the device *looks* connected. AERIVA must check `NetworkState.validated` (and, if exposed, the `CAPTIVE_PORTAL` capability) before interpreting a probe failure as "the network is bad" rather than "the network is behind a login page."
- **Private DNS (DNS-over-TLS) affects the DNS-responsiveness estimate's meaning, not just its number.** (VERIFIED FACT — `LinkProperties.isPrivateDnsActive()`/`getPrivateDnsServerName()`, current official API.) A timed lookup on a network with strict-mode private DNS active is timing a DoT resolver, not the OS's normal cache/carrier path; AERIVA's estimation-tier output should record whether private DNS was active for that sample, not silently average DoT and plain-DNS timings together as if they were the same signal.
- **DNS caching at every layer (OS, carrier, resolver) makes even the caveat above incomplete** — a "fast" DNS estimate may reflect a warm cache, not resolver responsiveness, and there is no Android API to force a genuinely uncached lookup.
- **Background execution restrictions mean a scheduled measurement is never guaranteed to run at a specific time** (`WorkManager`'s own documented ~15-minute minimum periodic interval and non-exact-time execution — carried forward from the Phase 2 audit, independently still current) — Phase 4's scheduler design (Section 14) must not promise cadence tighter than this without a `WorkManager` alternative this document does not propose adding speculatively.

---

## 10. NetworkClient Architecture

This is the single largest concrete gap identified in Section 3.3. **PROPOSED DESIGN** throughout this section.

### 10.1 Interface ownership — keep as-is

The existing `NetworkClient`/`NetworkClientOutcome` interface (Section 3.3) should **not** be redefined or forked. It is already reviewed, tested (via `FakeNetworkClient`/`ReferenceLatencyProbeExecutor`), and CI-green on its own branch. Phase 4's job is to add exactly one thing: a production class implementing it.

### 10.2 Socket vs. HTTP/HTTPS approach

**OPEN DECISION**, with a recommendation:

- **Raw TCP/UDP sockets** (`java.net.Socket`/`DatagramSocket`) give the most control over exactly what's measured (connection-establishment time separable from data-transfer time) and require no new dependency — every needed class is in the JDK/Android standard library.
- **`HttpsURLConnection`** (also dependency-free) is simpler for the HTTPS-reachability capability specifically, but conflates DNS + TCP + TLS + HTTP-response timing into one number unless individually instrumented (via `HttpURLConnection`'s own limited timing hooks, which are coarser than a raw-socket approach).
- **OkHttp** (a new dependency — none currently present, Section 2) offers built-in connection pooling, HTTP/2, and `EventListener` hooks that separate DNS/connect/TLS/request/response timing precisely — the single best-fit library for exactly the per-phase timing breakdown Section 8's timing rows imply AERIVA needs, but it is a real new dependency this document is not authorized to add (Section 15).

**Recommendation** (not a decision — see above): raw sockets for latency/jitter/packet-loss (a bare TCP handshake or a small UDP exchange is the entire measurement; no HTTP semantics needed), and defer the HTTPS-reachability and throughput capabilities' client choice to whoever makes the OkHttp-vs-`HttpsURLConnection` decision, since those two specifically benefit most from OkHttp's timing granularity.

### 10.3 Request/response protocol and payload design

**OPEN DECISION**, explicitly inherited unresolved from `LatencyMeasurementEngine`'s own `EXPECTED_PAYLOAD_BYTES = 8` placeholder (Section 3.5 — that engine's own KDoc calls this "not a real wire format... pending a real protocol decision"). Phase 4 does not resolve this either, because the answer depends entirely on the endpoint decision (Section 11): a self-hosted endpoint can define any protocol AERIVA wants; a third-party endpoint (Section 11.A) constrains AERIVA to whatever that endpoint already speaks (e.g. an HTTP 204 response for a Cloudflare/Google-style connectivity-check endpoint, or M-Lab's own NDT7 WebSocket-based protocol for throughput). This document names the dependency explicitly rather than guessing a protocol ahead of the endpoint decision.

### 10.4 Timeout, cancellation, connection establishment, and clock architecture

**Adopt `LatencyMeasurementEngine`'s existing shape unchanged** (Section 3.5): `withTimeout` at the call site (not inside `NetworkClient.probe` itself, per that interface's own documented contract), only `TimeoutCancellationException` caught, genuine cancellation propagates, monotonic `elapsedNanos()` for duration, wall-clock `now()` for the persisted timestamp. This is a **VERIFIED FACT** about what already works (proven by `ReferenceLatencyProbeExecutorTest`'s real passing tests), not a new proposal.

### 10.5 DNS timing, TLS timing, server processing time, and round-trip timing as separable components

**PROPOSED DESIGN**: a production `NetworkClient` implementation should expose these as separate fields on its outcome (or a richer `Success` payload) rather than one opaque total, *if and only if* the chosen protocol (Section 10.3) and client library (Section 10.2) actually make each phase separately observable — raw-socket TCP connect time is trivially separable from application-level round-trip time; DNS and TLS phase separation is materially easier with OkHttp's `EventListener` than with raw sockets or `HttpsURLConnection`. This is stated as a design goal, not a committed design, because it is downstream of the still-open 10.2/10.3 decisions.

### 10.6 Connection reuse, pooling, network binding

- **Connection pooling/reuse**: **not needed** for latency/jitter/packet-loss (each probe is deliberately a fresh, timed connection — reusing a pooled connection would measure "time to reuse an existing connection," a materially different and less useful number for AERIVA's stated purpose of characterizing current path quality). **Recommendation, not requirement**: throughput measurement may benefit from a fresh connection per test for the same reason.
- **Network binding**: `Network.bindSocket(Socket)` / `Network.bindSocket(DatagramSocket)` (VERIFIED FACT, current official API, both since API 21/22) let AERIVA direct a specific probe's socket onto a specific already-connected `Network` object — this is the mechanism `ConnectivityManager`'s existing multi-network awareness (already partially observed by `AndroidNetworkMonitor`) would use if AERIVA ever needs to probe "the Wi-Fi network specifically" while cellular is also up, without needing `bindProcessToNetwork()`'s more disruptive process-wide effect. **PROPOSED DESIGN** for a later phase, not required for a first `NetworkClient` implementation that only ever probes over whatever network is currently the OS-selected default.
- **VPN behavior**: no special handling proposed beyond what Section 9 already states as a limitation — AERIVA measures whatever path is currently active, VPN-augmented or not, and does not attempt to detect-and-bypass a VPN for its own probes (bypassing would itself require `bindProcessToNetwork` gymnastics with real user-trust implications this document does not recommend).
- **Captive portal behavior**: per Section 9, a probe response received from a captive portal's intercept (rather than AERIVA's real endpoint) will typically fail TLS validation (wrong certificate) or return an unexpected payload — both already map cleanly onto existing `MeasurementFailure` cases (`TlsFailure`, `InvalidResponse`). No new failure case is needed; what *is* needed (Section 7) is checking `NetworkState.validated`/the captive-portal capability before interpreting that failure.
- **Redirects/proxy behavior**: **OPEN DECISION**, downstream of 10.2 — a raw-socket approach has no HTTP redirect semantics to configure at all; an HTTP-client-based approach needs an explicit "do not follow redirects for a probe" setting (an unexpected redirect during a latency probe is itself diagnostic — of a captive portal or a proxy — and should surface as a distinguishable outcome, not be silently followed and timed as if it were the real endpoint).
- **IPv4/IPv6**: **OPEN DECISION**. Android does not let an app force one over the other without controlling DNS resolution order itself; a dual-stack network may resolve either, and a probe endpoint (Section 11) that is not itself dual-stack-reachable would silently bias measurements toward whichever address family it happens to support. Not resolved here — depends on the endpoint decision.
- **Failure mapping**: already fully designed (`NetworkClientOutcome` → `MeasurementFailure`, Sections 3.3/3.5/7) and requires no new design, only implementation.

---

## 11. Endpoint Strategy

**OPEN DECISION** — the assignment's own Section 6 flags this as important and unresolved; this document does the factual tradeoff analysis it asks for and proposes a direction, but does not implement or commit to one, per Section 15's explicit prohibition on adding production measurement endpoints.

| Option | Accuracy | Availability | Cost | Geographic relevance (incl. Nigerian/African conditions) | Control/abuse prevention | Privacy |
|---|---|---|---|---|---|---|
| **A. Third-party public endpoints** (e.g. Cloudflare's `speed.cloudflare.com` edge endpoints, Google's `generate_204`-style connectivity-check endpoints, M-Lab NDT7 via its public Locate API) | High for endpoints on a large anycast/CDN network (Cloudflare has points of presence in numerous African markets, including Nigeria — **ASSUMPTION**, based on Cloudflare's publicly-stated network footprint, not independently verified against a live PoP list this session); M-Lab NDT7 explicitly locates the nearest available M-Lab server via its own Locate API (VERIFIED FACT, `locate.measurementlab.net/v2/nearest/ndt/ndt7`), which may or may not have low-latency African coverage | High — these are operated by large, well-resourced third parties with no dependency on AERIVA's own uptime | **Lowest** — no infrastructure to run or pay for | Depends entirely on the specific third party's PoP footprint in the region; not guaranteed to match AERIVA's specific user base | AERIVA has no abuse-prevention control at all — a third party could rate-limit or block AERIVA's traffic pattern unilaterally, with no recourse | **M-Lab NDT explicitly retains and discloses IP addresses for research purposes as a matter of stated policy** (VERIFIED FACT, M-Lab's own published data-governance/privacy statement) — a real privacy cost if AERIVA routes user probes through it without disclosing this to users. A generic Cloudflare/Google connectivity-check-style endpoint has a different (commercial, not research-open-data) privacy posture, not itself verified in depth this session |
| **B. AERIVA-controlled measurement endpoint** (a single server AERIVA operates) | Medium — accurate for users near that one server, progressively less representative of "the user's internet quality in general" the farther they are from it | AERIVA is fully responsible for uptime — a single point of failure | Ongoing hosting cost, scales with usage | Poor by default for a geographically distributed user base unless deliberately located near it | Full control — AERIVA can rate-limit, authenticate, and design its own abuse prevention | Full control — AERIVA decides exactly what is logged and can state that policy plainly to users, rather than inheriting a third party's |
| **C. Multiple geographically distributed AERIVA endpoints** | High, if genuinely distributed to match the user base (e.g., a West African region alongside others) | Same operational responsibility as B, multiplied by the number of regions | Highest ongoing cost of any self-operated option | Best fit *if* a region is actually deployed near the target user base — this is the only option in this table that can be deliberately optimized for "Nigerian/African network conditions" specifically, rather than inheriting whatever footprint a third party happens to have | Full control, same as B | Full control, same as B |
| **D. CDN-backed endpoints** (AERIVA's own logical endpoint, fronted by a CDN/anycast provider such as Cloudflare) | High — combines AERIVA's own control over what's measured/logged with a CDN's actual edge-server geographic density (Cloudflare's `@cloudflare/speedtest` module, VERIFIED FACT, is architected exactly this way: it measures against the Cloudflare edge network using the browser/client's `PerformanceResourceTiming`-equivalent precise timing) | High — inherits the CDN's uptime/DDoS-resilience rather than AERIVA's own | Lower than C, higher than A — a CDN plan cost plus AERIVA's own backend logic, not full multi-region infrastructure | Good, if the chosen CDN has strong regional PoP density | Full control over what AERIVA's own backend logs, while the CDN handles raw abuse/DDoS at the edge | Full control over AERIVA's own logging; the CDN operator still sees raw connection metadata, same as any CDN-fronted service |
| **E. Hybrid** (start on A for zero infrastructure cost, move to D or C once AERIVA has enough users/funding to justify it) | Starts medium, improves over time | Starts fully dependent on a third party, transitions to AERIVA-controlled | Lowest upfront cost, cost grows with the transition | Starts as good/bad as whichever third party is chosen; improves once a deliberately-placed endpoint exists | Starts with no control, gains control over time | Starts with a third party's privacy posture (must be disclosed to users if choosing e.g. M-Lab); improves once self-hosted |
| **F. Local/self-hosted future infrastructure** (community-run or user-device-adjacent measurement nodes) | Speculative — no such infrastructure exists yet for AERIVA | N/A | Unknown, likely highest complexity | Best theoretical fit for hyperlocal conditions, entirely unproven | Full control, if built | Full control, if built; also the most novel privacy design surface (peer-to-peer-adjacent measurement raises questions A-E don't) |

**Proposed direction (not a decision)**: Option E (hybrid), starting with a well-understood, clearly-disclosed third-party endpoint (favoring a connectivity-check-style endpoint with a clear, non-research-disclosure privacy posture over M-Lab specifically, given M-Lab's stated IP-retention policy) for latency/jitter/HTTPS-reachability, while treating throughput and packet-loss — the two most data/infrastructure-sensitive capabilities — as **not yet buildable** until an endpoint decision that accounts for their higher cost is made. This is a direction, not a commitment; Section 15 of this task's own instructions prohibits adding a production measurement endpoint in this phase, and this document does not do so.

---

## 12. Permission Matrix

Consolidated from Section 8/3.2, plus the currently-declared state (Section 2):

| Permission | Currently declared? | Type | Needed for | Individually justified in writing yet? |
|---|---|---|---|---|
| `ACCESS_NETWORK_STATE` | **Yes** (only permission in the repo) | Normal | Network stability/transitions (already implemented), and as a prerequisite context for every other capability's `MeasurementNetworkContext` | Yes — already in use |
| `INTERNET` | **No** | Normal | Latency, jitter, packet loss, throughput, DNS estimation, HTTPS reachability — i.e., every active-probe capability | **Not yet** — this is a same-phase prerequisite gap: no active measurement of any kind is possible until this is added, and it is currently absent even though Phase 3B code already assumes it (Section 2's finding) |
| `ACCESS_FINE_LOCATION` | No | Runtime, dangerous | Wi-Fi characteristics; half of cellular characteristics' requirement | No — per `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own exit criteria, requires a specific written Play-policy-aligned justification before being added, not a placeholder comment |
| `READ_PHONE_STATE` | No | Runtime, dangerous | Cellular characteristics (the other half of its requirement) | No |
| `ACCESS_WIFI_STATE` | No | Normal | Not currently required by anything in the classifier's table (the corrected, current Wi-Fi API path gates on location, not this permission specifically) — **ASSUMPTION**, not independently re-verified this session beyond what the classifier already encodes | N/A |

**LIMITATION**: this document does not add any of these permissions to the manifest (Section 15 explicitly prohibits it). The `INTERNET` gap specifically means **no capability in this entire document's scope is currently buildable end-to-end** until that one normal permission is added — a fact worth stating plainly rather than leaving implicit in a table.

---

## 13. Android-Version Matrix

Extending `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own version table (independently confirmed still accurate this session, plus Android 17 — released after that audit was written) with the measurement-specific implications this document adds:

| Android version (API) | Relevant to Phase 2 (already documented) | **New, measurement-specific implication (this document)** |
|---|---|---|
| 12 (31) | FGS background-start ban; `TelephonyCallback` replaces `PhoneStateListener` | `TelephonyCallback`-based push cellular-signal reads (vs. polling `getAllCellInfo()`) become available as the preferred mechanism from this level up |
| 13 (33) | `NEARBY_WIFI_DEVICES` (`neverForLocation`) | Not usable as a Wi-Fi-characteristics substitute below API 33; minSdk 26 means a fallback to the location-gated path is required regardless, so this alternative adds complexity without removing the location requirement project-wide |
| 14 (34) | FGS type declarations required | Only relevant if a foreground service is ever added for measurement (not proposed here — Section 14) |
| 15 (35) | `dataSync` FGS execution cap | Same — not directly relevant unless a FGS-based design is chosen |
| 16 (36, this project's current compileSdk/targetSdk) | No additional measurement-specific change found | Confirmed still current baseline |
| **17 (37) — VERIFIED FACT, released June 16, 2026, after `PHASE_2_ANDROID_PLATFORM_AUDIT.md` was written** | N/A (post-dates that audit) | Two changes with direct relevance to this document, confirmed this session against current official release notes: **(1)** `android:usesCleartextTraffic` is being deprecated with a default-to-blocking-cleartext posture, gated on a future target-SDK level — any Network Security Config AERIVA writes for its measurement endpoint(s) should assume cleartext HTTP probes will not be a safe fallback path going forward, and should be HTTPS-only from the start rather than treating this as a later migration; **(2)** cross-profile loopback traffic is no longer permitted by default on any app running on Android 17+, regardless of target SDK — irrelevant to AERIVA's own probes (which target real remote endpoints, not loopback) but worth naming since a local-test-server testing approach (Section 18) that happens to run across a work-profile boundary on a 17+ device would be affected; a same-profile local test server is unaffected |

**OPEN DECISION**: whether/when AERIVA moves compileSdk/targetSdk to 37. Not addressed by this document — it is a project-wide decision with no unique measurement-engine dimension beyond the two points above.

---

## 14. Battery/Data Policy

**PROPOSED DESIGN** throughout — none of this exists in code today; `LatencyMeasurementEngine` explicitly has no scheduling, no budget enforcement, and no de-duplication (Section 3.5's own stated exclusions).

- **Maximum measurement frequency**: differentiated by capability cost (extending Section 8's cost column into actual policy, which the classifier itself does not do): latency/jitter — most frequent tier; packet loss — a materially longer cooldown between runs than latency, given its probe-train cost; throughput — least frequent, opt-in by default, and gated on Wi-Fi/unmetered per the classifier's own `SupportedWithLimitations` reasoning. **OPEN DECISION**: the actual numeric intervals — Phase 2's own exit criteria already call for this ("translated into actual, written numeric budgets before implementation, not left as qualitative language") and this document does not invent numbers it cannot justify from either platform fact or real usage data, neither of which exists yet.
- **Maximum payload size**: bounded by the still-undecided protocol (Section 10.3); as a design constraint rather than a number, latency/jitter probes should use the smallest payload that lets the client detect corruption (the existing engine's placeholder 8-byte payload is a reasonable order-of-magnitude starting point, not a final answer).
- **Daily data budget / per-measurement data budget**: **OPEN DECISION**, same reasoning as frequency — no numeric commitment without either a platform constraint or real usage data to ground it.
- **Battery budget**: latency/jitter/DNS/HTTPS-reachability probes are cheap enough (single short-lived socket operations) that a per-operation battery budget is likely unnecessary; packet-loss and throughput are the two capabilities where a battery budget is a real design requirement, not a formality.
- **Charging-state / metered-network / roaming behavior**: throughput should default to Wi-Fi-only, non-roaming; packet-loss should at minimum respect metered-network status (skip or reduce probe-train size on a metered connection); latency/jitter/DNS/HTTPS-reachability have low enough per-probe cost that gating them on charging state would likely under-serve AERIVA's own stated purpose (helping users on poor/expensive connections understand their connection *while on it*, not only while charging on Wi-Fi).
- **Low battery / battery saver / Doze**: any scheduled (non-user-triggered) measurement must respect Doze/App Standby's existing constraints — this is inherited from the Phase 2 audit's own `WorkManager` findings (Section 9's ~15-minute minimum periodic interval, non-exact-time execution) and is not something Phase 4 can override; a design that assumes tighter cadence than that needs an explicit, written justification for why `WorkManager` is insufficient, not a workaround.
- **User-triggered vs. automatic measurement**: a user-triggered "check my connection now" action should not be subject to the same cooldown as automatic background sampling — **PROPOSED DESIGN**: two separate cooldown/budget tracks, not one shared one, so a user who explicitly asks for a fresh reading is never told "try again later" by a budget meant to bound *unattended* background probing.
- **Cooldowns / duplicate prevention**: **PROPOSED DESIGN**: a per-capability last-run timestamp (using the existing injected-clock seam, Section 3.6) checked before step 1 of Section 7's lifecycle — this is the `MeasurementScheduler` component in Section 6's diagram, and its actual implementation is an **OPEN DECISION** pending the scheduler-mechanism question below.
- **Cancellation / backoff / failure recovery**: cancellation is already fully designed (Section 10.4). Backoff after repeated failures (e.g., an endpoint that's been unreachable for the last five attempts) is **not designed by any existing branch** and is a genuine Phase 4 gap — **PROPOSED DESIGN**: a simple exponential backoff on consecutive `EndpointFailure`/`Timeout` results per capability, reset on the next `Succeeded`, is a reasonable default; the exact multiplier/ceiling is an **OPEN DECISION**.
- **Scheduler mechanism itself**: **OPEN DECISION**, explicitly and deliberately inherited unresolved from `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`'s own Section 6 ("Deferred — explicitly not resolved by this document... depends entirely on the WorkManager-vs-something-else decision the Phase 2 audit deferred"). This document does not resolve it either, per Section 15's explicit prohibition on adding WorkManager in this phase. A user-triggered measurement needs no scheduler at all (it runs on request, subject only to the cooldown check above); only *automatic* background sampling needs one, and that need is exactly AERIVA goal G, which the Phase 2 audit already identified as blocked on this same undecided dependency.

---

## 15. Security/Privacy Architecture

**PROPOSED DESIGN.**

- **Measurement endpoint TLS**: every active probe against a real endpoint (latency, HTTPS-reachability, throughput) should use TLS, not cleartext — reinforced, not just recommended, by Android 17's own cleartext-deprecation direction (Section 13). Certificate validation should use the platform's default trust store; there is no stated need for certificate pinning specifically for a connectivity-measurement endpoint (unlike, say, a financial API), since a pinning failure would itself be indistinguishable from — and would incorrectly get reported as — the exact `TlsFailure` outcome a captive portal or MITM-intercepting network produces (Section 9), which is a case AERIVA specifically wants to detect and report, not hide behind a pin-mismatch error.
- **Abuse prevention / endpoint authentication**: relevant only for options B/C/D/F in Section 11 (an AERIVA-operated endpoint); a third-party endpoint (option A) has its own abuse-prevention AERIVA does not control. **OPEN DECISION**, downstream of the endpoint decision — not resolved here.
- **Replay concerns**: a connectivity probe has no meaningful "replay" attack surface in the traditional sense (there is no privileged action or credential to replay) — the more relevant concern is a malicious endpoint or on-path attacker feeding AERIVA fabricated timing/success responses to make a bad connection look good, or vice versa. This is a real but low-severity risk given AERIVA's own stated purpose (informing the user, not gating an authorization decision), and is not designed further here.
- **Malicious endpoints / DNS manipulation**: an on-path attacker or a malicious DNS resolver could redirect AERIVA's probe to an attacker-controlled server that responds instantly and validly, producing an artificially-good latency reading. TLS certificate validation against the expected hostname (not just "TLS succeeded") is the primary defense already implied by "use TLS, validate against the platform trust store" above; this document does not propose anything beyond standard TLS hostname validation, since a measurement app's threat model does not obviously justify certificate pinning's operational cost (key rotation coordination) for this specific risk.
- **Telemetry / measurement data collected**: per Section 11's endpoint analysis, whatever is collected depends entirely on the endpoint choice. **PROPOSED DESIGN, applying regardless of which endpoint option is chosen**: AERIVA's own client-side measurement history (Section 10, `MeasurementHistory` Room table) should store the metric values, timestamps, and `MeasurementNetworkContext` already designed in Phase 3A — it should **not** store precise device location (Phase 3A's `MeasurementNetworkContext` already deliberately excludes this, per its own KDoc, Section 3.1) or any device identifier beyond what's already implicit in "this is one installation's local history."
- **Device identifiers / IP addresses**: AERIVA's own client-side storage needs neither. Whatever the *endpoint* logs (Section 11) is outside AERIVA's client-side control for third-party options and fully within AERIVA's control for self-hosted options — this is exactly the accuracy/control tradeoff Section 11's table already states, not a separate new finding.
- **Location information**: already covered by Section 12 (permission matrix) and Phase 3A's existing design (`MeasurementNetworkContext` carries no precise location field). No change proposed.
- **Future Community Connectivity Intelligence**: explicitly out of scope (Section 15 of the assignment prohibits designing it in this phase). The one architectural note worth stating plainly so it isn't accidentally foreclosed: this document's `MeasurementHistory` design (Section 10) is per-installation, local-only storage — any future aggregation-across-users feature would need its own, separately-designed and separately-consented data path, not an assumed extension of local history into a shared table. Stated as a boundary to preserve, not a design for that future feature.

---

## 16. Error Architecture

Already substantially designed by existing code (Sections 3.3–3.5); this section consolidates and extends it to the generalized (Section 5.2) capability set. **VERIFIED FACT** for what exists, **PROPOSED DESIGN** for the extension.

| Failure category | Where it lives today | Extension needed for jitter/packet-loss/throughput |
|---|---|---|
| Transport failure (connection refused, TLS failure) | `NetworkClientOutcome` (EXISTS) | None — same interface, reused as-is |
| Measurement failure (timeout, cancelled, invalid response, network changed mid-call) | `MeasurementFailure` (EXISTS, Phase 3A) | None — same sealed type, reused as-is for every metric's `Failed` case |
| Permission failure / unsupported capability | `AerivaError.PermissionRequired`/`Unsupported`, and separately `CapabilityClassification.NotReliablyAvailable` (EXISTS, deliberately two different types per Phase 3A's own explicit design choice not to duplicate `AerivaError`) | None |
| Network unavailable | `NetworkState.available == false`, checked before attempting (EXISTS) | None |
| DNS failure | Currently folds into `EndpointFailure`/`ConnectionRefused` at the transport level (a DNS resolution failure surfaces as a `java.net.UnknownHostException` from the socket layer) | **PROPOSED DESIGN**: a production `NetworkClient` implementation should map `UnknownHostException` specifically to a distinguishable outcome (e.g. a new `NetworkClientOutcome.DnsResolutionFailed` case) rather than folding it into the generic `ConnectionRefused` — a DNS failure and a TCP-level refusal are diagnostically different (the former means the hostname didn't resolve at all; the latter means it resolved but nothing answered), and this task's own Section 11 explicitly asks for DNS failure to be its own category |
| TLS failure | `TlsHandshakeFailed`/`TlsFailure` (EXISTS) | None |
| HTTP failure | Not yet applicable — no HTTP-based probe exists yet | **OPEN DECISION**, downstream of Section 10.2/10.3 (only relevant if an HTTP-based client is chosen for HTTPS-reachability/throughput) |
| Captive portal | Not a distinct case today — currently surfaces indirectly as `TlsFailure`/`InvalidResponse` (Section 9) | **PROPOSED DESIGN**: do not add a dedicated `MeasurementFailure.CaptivePortal` case speculatively; instead, the *caller* (engine or orchestrator) should cross-reference `NetworkState`'s existing captive-portal-adjacent signal (`validated == false` while `available == true`) alongside a `Failed` result to *interpret* it as portal-related, rather than the transport/measurement layer trying to detect a portal itself. This keeps the existing closed `MeasurementFailure` set closed (per its own documented "add a case only when a real measurement method needs to distinguish it" convention) while still letting a caller correctly attribute the failure |
| VPN restriction | No distinct case; per Section 9, a VPN's effect on a probe is not distinguishable from the VPN's effect on any other traffic — not modeled as an error at all, since it isn't one (the probe fully succeeds, it simply measures the VPN-augmented path) | None proposed |
| Insufficient evidence | `LatencyEstimation.InsufficientEvidence`/`LatencyPrediction.InsufficientEvidence` (EXISTS, Phase 3A) | **PROPOSED DESIGN**: the same pattern (a first-class `InsufficientEvidence` case, never a fabricated value) extends directly to `JitterEstimation`/`PacketLossEstimation`/`ThroughputEstimation` if/when those are designed (Section 5.2) |

---

## 17. Concurrency Architecture

**VERIFIED FACT** for what `LatencyMeasurementEngine`/`ReferenceLatencyProbeExecutor` already prove works; **PROPOSED DESIGN** for generalizing it.

- **Coroutine boundaries**: one `measure()` call = one coroutine-scoped operation, using the caller's own structured-concurrency scope — the engine itself does not launch a detached/`GlobalScope` coroutine anywhere in the existing design, and Phase 4 should preserve that (no new engine should introduce one either).
- **Dispatcher usage**: `withContext(dispatchers.io)` for the actual probe, via the existing `AerivaDispatchers` seam — **no new dispatcher abstraction**, extending the existing one exactly as `LatencyMeasurementEngine` already does.
- **Timeout hierarchy**: `withTimeout` at the engine's `measure()` level (already proven); a scheduler (Section 14, not yet designed) would sit *above* this with its own, longer-lived coroutine lifecycle deciding *whether* to call `measure()` at all — the two timeout scopes are distinct and should not be merged into one.
- **Cancellation propagation**: already fully proven correct (Section 10.4) — genuine `CancellationException` propagates uncaught, `TimeoutCancellationException` specifically is caught and converted to a `Failed(Timeout)` result. This exact distinction must be preserved in every new metric's engine, not reimplemented ad hoc per metric.
- **Concurrent measurements**: `LatencyMeasurementEngine.measureSeries()` runs sequentially by explicit design (Section 3.5) — **PROPOSED DESIGN**: preserve this default (simplicity, avoiding unnecessary coroutine scopes, per the existing engine's own stated reasoning) for the generalized engines too. A caller that specifically wants concurrent sampling across *different* capabilities (e.g., latency and DNS-estimation at "the same time") can already achieve it by calling multiple engines' `measure()` from its own coroutines — proven safe for the identical underlying pattern by `ReferenceLatencyProbeExecutorTest`'s own concurrent-calls test (VERIFIED FACT, that specific test exists and passes). No new concurrency primitive is needed for this.
- **Sequential measurements within one capability**: already the default (above).
- **Measurement isolation**: each `measure()` call is a fresh, independent operation with no shared mutable state between calls (VERIFIED FACT — `LatencyMeasurementEngine`'s only per-call state is local to the function; the engine instance itself holds no mutable fields). This property must be preserved by every new metric's engine — it is what makes "concurrent calls don't corrupt shared state" true without needing a lock/mutex anywhere.
- **Callback lifecycle / resource cleanup**: `NetworkClient.probe()`'s production implementation (Section 10) must guarantee socket cleanup on every exit path (success, every failure branch, and cancellation) — this is a standard `try`/`finally` (or Kotlin `use {}`) requirement on whatever socket type Section 10.2 ultimately chooses, not a novel concern, but worth stating because the current interface/reference implementation has no real socket to clean up yet (the reference and engine both call an injected `NetworkClient`, never touching a real socket themselves).

---

## 18. Testing Architecture

**PROPOSED DESIGN**, extending `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`'s already-designed (Section 3.6 above) strategy from latency-only to the full capability set, and to production-`NetworkClient`-specific concerns that document could not yet address (since no implementation existed when it was written).

| Test category | What it covers here | Deterministic? |
|---|---|---|
| **Unit tests** (JVM, no Android) | Pure arithmetic: `Derived{Jitter,PacketLoss,Throughput}Stats.from()` (once designed, Section 5.2) against hand-constructed sample lists, following `DerivedLatencyStats.from()`'s own existing edge-case coverage (empty input, all-invalid input, negative/non-finite rejection, single-sample "trivially consistent" case) as the template, not a new philosophy | Fully deterministic |
| **Integration tests** (JVM, `NetworkClient` seam) | Engine-level logic (capability check → observation check → probe → outcome mapping → aggregation) against `FakeNetworkClient`, extended with new configurable outcomes if the production client's real behavior (Section 16's proposed `DnsResolutionFailed` case, for instance) needs a fake counterpart to test against | Fully deterministic — no real network, per the existing test strategy's own explicit "no dependency on the public internet, anywhere in this layer" requirement |
| **Emulator tests** | `NetworkStateMapper`/capability-classifier-level logic that needs a real Android runtime but not real radio behavior (e.g., confirming `ConnectivityManager` callback wiring still works end-to-end) — largely already covered by existing `AndroidNetworkMonitorInstrumentedTest`-style coverage; a production `NetworkClient` against a **local test server bound to `127.0.0.1`** (per the existing test strategy's own Section 3 recommendation) belongs here too, since it exercises real socket I/O and real timing without needing real radio hardware or the public internet | Deterministic for the local-server portion; must use `runBlocking`, not `runTest`, for any test waiting on a real socket/callback (the already-learned, binding lesson from Section 3.6) |
| **Real device tests** | Everything Section 9 lists as platform-limited: real Wi-Fi/cellular signal values, real packet-loss behavior on a real lossy network, real Doze interaction with an in-flight measurement, real captive-portal encounter, real multi-network (Wi-Fi+cellular simultaneously up) `bindSocket` behavior | Not deterministic by nature — this is exactly why it's a separate category, per the existing test strategy's own explicit split |
| **Network condition tests** | Deliberately degraded conditions (added latency, packet loss, bandwidth throttling) — **OPEN DECISION**: whether this uses a real degraded network (e.g., a physical test rig with a lossy Wi-Fi AP), an Android emulator's built-in network-shaping options (`-netdelay`/`-netspeed`, or the newer emulator network-quality controls), or a local test server that deliberately delays/drops responses per Section 3's fixture-based approach. Not resolved here — the existing test strategy did not need to resolve it either, since no real client existed to test conditions against |
| **Performance tests** | Confirming a measurement's own overhead (CPU, memory, socket count) stays within the (still-undecided, Section 14) battery/data budget — genuinely new, since no production client exists yet to profile | Deterministic for repeatable overhead assertions (e.g. "does not leak sockets across N repeated calls"); not deterministic for absolute timing numbers, which vary by device |
| **Battery tests** | Real, on-device battery draw measurement for a defined sequence of probes — **physical-device-only**, per Section 9's own general limitation on battery-behavior testing | Not deterministic; requires real hardware and real time elapsed |
| **Data-usage tests** | Confirming actual bytes-on-the-wire match the designed payload size (Section 10.3) — testable via `NetworkStatsManager`'s own-app-usage API (already confirmed needing no special permission, Section 8 of `PHASE_2_ANDROID_PLATFORM_AUDIT.md`) cross-checked against a local test server's own received-byte count | Deterministic against a local test server; real-world data usage also depends on TCP/TLS overhead outside the payload itself, which a local-server test captures more accurately than a unit test could |

**Explicitly not claimed**: no tests were run as part of producing this document. This is an architecture document; per this task's own instructions, it does not claim CI passed or tests ran unless it observed that directly (Section 22 restates this).

---

## 19. Implementation Phases

**PROPOSED DESIGN**, not a claim that this exact ordering is the only correct one — justified against the actual dependency graph (Section 4) and the actual current gaps (Sections 2, 10, 11), not assumed from the assignment's own suggested ordering.

1. **Add `INTERNET` permission.** Prerequisite for literally everything else in this document (Section 12's finding) — this is normal-permission, zero-friction work, but it is a real, currently-missing first step, not a formality to skip.
2. **Land the three existing Phase 3B branches, in the order Section 5.1 already derives mechanically from the dependency graph** (classifier → `NetworkClient` interface/`DerivedLatencyStats.from()` → engine, resolving the current CI failure as a natural consequence of merge order).
3. **Endpoint decision** (Section 11) — nothing past this point can be *tested against a real endpoint* (though JVM/fake-client tests, Section 18, do not need one) without this being at least provisionally decided, even if the final production endpoint changes later.
4. **Production `NetworkClient` implementation** (Section 10) for latency specifically — the metric with an already-compiling engine waiting for exactly this.
5. **DNS-responsiveness estimation** — no new sealed-type family needed (it's ESTIMATION-tier only, Section 5.2), and it reuses the same `NetworkClient`-adjacent socket/timing infrastructure item 4 builds, so it is cheap to add once item 4 exists.
6. **HTTPS reachability** — same reasoning as item 5: reuses item 4's client infrastructure, adds one more `MeasurementCapability` case using the already-`Supported` classification.
7. **Network-context enrichment** (Wi-Fi/cellular characteristics into `MeasurementNetworkContext`'s already-reserved nullable fields) — this is the point where `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE` actually need to be requested, so it is deliberately sequenced *after* the lower-friction, `INTERNET`-only capabilities (items 4–6), not before — matching `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own repeated point that location permission is the highest-friction, highest-scrutiny ask in the whole capability set and should not be requested speculatively.
8. **Jitter** — reuses item 4's client and latency's own repeated-probe pattern; the least new infrastructure of the three remaining MEASUREMENT-tier metrics.
9. **Packet loss** — needs a UDP probe-train capability item 4 (if built around a single TCP/HTTPS-style probe) may not yet have; sequenced after jitter because its cost/complexity is higher (Section 8) and its own classification (`SupportedWithLimitations`) already signals it needs more careful frequency/battery design (Section 14) than a straightforward `Supported` capability does.
10. **Throughput** — deliberately last among the four MEASUREMENT-tier metrics: highest cost (Section 8), highest data-budget sensitivity (Section 14), and the option most affected by the still-open endpoint decision's data-serving capacity (an endpoint adequate for a handful of latency bytes may not be adequate for repeated throughput transfers at scale — Section 11).
11. **Battery/data safeguards** (Section 14's scheduler/cooldown/budget design) — sequenced after the metrics exist (items 4–10) rather than before, because a budget designed against capabilities that don't exist yet would be speculative; **however**, the *user-triggered* cooldown check (Section 14) should exist from item 4 onward, not deferred this far — this item specifically covers the *automatic/scheduled* budget work, which does depend on the still-undecided scheduler mechanism (Section 14's own open decision).
12. **Real-device validation** (Section 18's real-device-test category) — ongoing throughout, not a single terminal step; specifically gates any claim that a given metric's *values* (not just its code) are trustworthy, per Phase 2's own exit criteria precedent.
13. **Higher-level intelligence** (estimation/prediction/recommendation producers) — explicitly last, and explicitly not designed by this document beyond the type-level scaffolding that already exists (Phase 3A) — this matches both this task's own Section 15 prohibition and the existing codebase's own repeated, deliberate pattern of not building a tier until the tier below it is real.

This ordering is not the assignment's own suggested list (Section 14 of the assignment) verbatim — it diverges in two justified ways: `INTERNET` permission is surfaced as an explicit item 1 (the assignment's list assumes a working `NetworkClient` is buildable without naming this prerequisite), and network-context enrichment (Wi-Fi/cellular, item 7 here) is deliberately sequenced *after* the low-friction capabilities rather than in the assignment's own implied middle position, specifically because of the location-permission friction Section 12 documents.

---

## 20. Risks

- **The endpoint decision (Section 11) blocks real validation of everything else.** Every timing number this document's design produces is only as meaningful as the endpoint it's measured against; deferring this decision indefinitely would leave the entire measurement engine untestable against real conditions indefinitely, not just architecturally incomplete.
- **Location-permission friction (Section 12) could stall Wi-Fi/cellular-characteristics work indefinitely** if the "specific, written, Play-policy-aligned justification" `PHASE_2_ANDROID_PLATFORM_AUDIT.md` already requires is never produced — this is a real project-management risk, not just a technical one.
- **The scheduler-mechanism gap (Section 14) has now been deferred across three separate documents** (`PHASE_2_ANDROID_PLATFORM_AUDIT.md`, `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`, and this one) without being resolved — deferring a decision three times in a row is itself a signal that it needs a deliberate resolution step, not a fourth deferral.
- **Packet-loss and throughput's genuinely higher cost/complexity could tempt a future implementer to cut corners** (e.g., mislabeling a low-effort estimate as a direct measurement) — this task's own explicit instruction ("do not label an estimate as a direct measurement") and the classifier's own existing, correct `SupportedWithLimitations`/`Estimated` distinctions are the safeguard; the risk is a future change silently weakening that classification under schedule pressure, not a design gap in what exists today.
- **The unresolved `NetworkQuality.Measured(score, label)` risk, flagged by Phase 3A itself and restated here (Section 5.2), remains live** — nothing in Phase 3B or this document populates it, but the type still exists, unpopulated, as a standing invitation for a future change to collapse the five-tier boundary into it without the type-level safeguards Phase 3A's own tests specifically check for (Section 3.6/18).
- **Two branches independently touching `network:monitor`'s `build.gradle.kts`** (Section 2's finding: `phase-3b-measurement-tests` adds `core:common` as `testImplementation` only, `phase-3b-measurement-engine` correctly upgrades it to `implementation`) is a small but real merge-conflict risk during Section 5.1's integration step — not a design flaw, but worth flagging so whoever performs the actual merge does not silently drop the `implementation`-scoped version in favor of the `testImplementation`-only one from the other branch.

---

## 21. Open Decisions

Consolidated from throughout this document, so none are silently left implicit:

1. Whether/how a unifying `Measurement<T>` type ever gets built across latency/jitter/packet-loss/throughput, without recreating the "one generic score" anti-pattern (Section 5.2).
2. Socket-vs-HTTP-client approach, and whether OkHttp is ever added as a dependency (Section 10.2).
3. The wire protocol for any active probe (Section 10.3) — blocked on the endpoint decision.
4. Redirect/proxy handling specifics (Section 10.6) — blocked on 10.2.
5. IPv4-vs-IPv6 handling (Section 10.6) — blocked on the endpoint decision.
6. **The endpoint strategy itself** (Section 11) — the single highest-priority open decision in this document.
7. Whether/when AERIVA moves compileSdk/targetSdk to API 37 (Section 13).
8. Every numeric battery/data budget value (Section 14) — deliberately left as "needs real data or explicit platform justification," not invented.
9. The scheduler mechanism for automatic/background measurement (Section 14) — deferred a third time, flagged as a risk (Section 20), not resolved.
10. Backoff multiplier/ceiling after repeated measurement failures (Section 14).
11. Endpoint-side abuse prevention/authentication design (Section 15) — blocked on the endpoint decision.
12. HTTP-failure-category granularity (Section 16) — blocked on 10.2.
13. Network-condition-test methodology — real degraded network vs. emulator network-shaping vs. local-server fixture injection (Section 18).
14. Whether commit `phase-3b-measurement-engine`'s missing `PHASE_3B_MEASUREMENT_ENGINE_IMPLEMENTATION.md` should be reconstructed, or the commit message's claim simply corrected — a documentation-hygiene question, not an architecture one, but unresolved as of this session (Section 3.5).

---

## 22. Explicit Non-Goals

Restated from this task's own Section 15, confirmed not done by this document or by any action taken while producing it:

- No socket implementation was created.
- No `INTERNET` permission (or any other permission) was added to any manifest.
- No `WorkManager`, Supabase, or UI dependency/code was added.
- No production measurement endpoint was added or contacted.
- No telemetry or location-aggregation code was added.
- Community Connectivity Intelligence was not designed beyond the one boundary-preserving note in Section 15.
- No Phase 3A domain type was modified.
- No branch was merged; `main` was not touched (still `e3f70a4a13e63b782c61601abadc2c36f65dbd73` as of this session).
- No tests were run and none are claimed to have passed by this document; all test-run/CI-outcome claims in Sections 2–3 are restatements of results independently confirmed via the GitHub Project connector's own recorded CI run outcomes for those specific, named commits — not new runs performed for this document.

---

## 23. Research Sources

**Fetched fresh this session** (not recalled from training data), with the specific facts each supported:

- `developer.android.com/reference/kotlin/android/net/LinkProperties` — `isPrivateDnsActive()`, `getPrivateDnsServerName()`, `getDnsServers()` (Sections 8, 9).
- `developer.android.com/reference/android/net/Network` and `developer.android.com/reference/kotlin/android/net/Network` — `bindSocket(Socket)`/`bindSocket(DatagramSocket)`, `openConnection(URL)` (Section 10.6).
- `developer.android.com/develop/connectivity/network-ops/reading-network-state` (and its Italian-locale mirror, cross-checked for consistency) — `NET_CAPABILITY_CAPTIVE_PORTAL`/`NET_CAPABILITY_VALIDATED` mutual-exclusivity behavior (Section 9).
- `developer.android.com/about/versions/17/release-notes` and the Android 17 Beta/GA announcement posts (`android-developers.googleblog.com`, June 2026) — Android 17's cleartext-traffic-deprecation direction and cross-profile-loopback restriction (Section 13); `en.wikipedia.org/wiki/Android_17` cross-checked for the GA date (June 16, 2026) and API level (37).
- Cloudflare's own `@cloudflare/speedtest` module documentation — architecture of a CDN-edge-backed measurement approach, its configurable endpoint URLs and measurement-type structure (Section 11, option D).
- Measurement Lab (M-Lab) — `kb.mlab-sandbox.measurementlab.net` (NDT/NDT7 mechanism, Locate API) and `ooni.org/nettest/ndt` (M-Lab's own stated IP-retention/data-governance policy) (Section 11, option A).

**Reused from this repository's own prior, already-cited research**, independently spot-checked rather than assumed correct: `PHASE_2_ANDROID_PLATFORM_AUDIT.md`'s own citations to `developer.android.com` pages on Wi-Fi permissions, background location, the VPN guide, foreground-service launch/type/background-start-restriction guides, `ConnectivityManager.registerNetworkCallback`/`requestNetwork` reference docs (the 100-outstanding-request ceiling), `TelephonyManager.getAllCellInfo()` reference docs, and Play Console Help's permissions/background-location policy pages — all confirmed, by this session's own independent reading of that document in full, to be specifically and individually cited there (not vague appeals to "official docs"), and not re-fetched line-by-line a second time in this session where this document's own conclusions do not depend on a detail beyond what that audit already established.

**Repository sources** (GitHub Project connector, this session): full `list_branches`, `list_commits` (main, `phase-3b-repository-state-reconciliation`), `get_commit`, and `get_file_contents` reads of every file cited in Sections 2–3, at each cited branch's actual current HEAD — not assumed from any commit message alone.
