PHASE 4 NETWORK-STATE INTEGRATION AUDIT

Branch audited: phase-4-android-network-state @ c0ad893b3ffd939a9aa6be8beddfbf55fc17de4d
Audit role: AI 3 (Android platform specialist), independent self-audit of AI 3's own prior work.
Audit branch: phase-4-network-state-audit, parent = the audited commit.
Status: audit only. No production code, test, permission, dependency or endpoint was changed. Nothing merged, nothing force-pushed. main untouched.

Built against: PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md and PHASE_4_IMPLEMENTATION_READINESS.md (phase-4-cross-cutting-decisions), PHASE_4_NETWORK_MEASUREMENT_ARCHITECTURE.md (phase-4-measurement-architecture), PHASE_4_MEASUREMENT_FOUNDATION_NOTES.md (phase-4-measurement-foundation @ fdf5440), PHASE_4_ENGINE_HARDENING_NOTES.md (phase-4-engine-hardening), PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md (phase-4-engine-foundation-reconciliation), and PHASE_4_TEST_GATE_SPECIFICATION.md plus ArchitectureGuardTest.kt (phase-4-test-gate). All were read in full for this audit, not summarized from memory.

---

## 0. Verdict

**Not safe to merge as-is.** One Critical defect (CF-1) makes the monitor unreliable for its core purpose -- reporting current network state -- in ordinary use, not just an edge case. It was not caught by CI because the one test that exercises the real pipeline (the instrumented test) asserts too little to notice it. Everything else audited (B through G, and A's own reducer logic in isolation) is sound, and the fix is small, scoped to one file, and does not require touching the well-tested reducer at all. Section 8 gives the exact required fix and the tests that would have caught this and should be added before merge.

---

## 1. Critical Finding CF-1: debounce runs before the fold, and silently drops the platform's guaranteed connect burst

**Severity: Critical. Blocks merge.**

**File/line:** `network/monitor/src/main/kotlin/com/aeriva/network/monitor/AndroidNetworkMonitor.kt:67-94`, specifically the operator order `platformEvents().debounce{...}.map(::toRawNetworkEvent).scan(...)` at lines 67-76.

### The defect

`observe()`'s pipeline applies `.debounce{}` to the **raw, pre-fold** `PlatformEvent` stream, then feeds only what survives debounce into `NetworkEventReducer` via `.scan`. But `NetworkEventReducer` needs `Available` (or `CapabilitiesChanged`) to arrive for a network **before** a `BlockedStatusChanged` for that same network can do anything useful -- its guard is `if (state.network != null && event.network != state.network) state else state.copy(blocked = event.blocked, ...)`. If `state.network` is still null (nothing established it yet) or belongs to a different, older network, the `BlockedStatusChanged` event either lands on an empty state or is dropped as foreign.

This repository's own `AndroidNetworkMonitor.kt` KDoc (lines 42-47) cites the VERIFIED FACT that `onAvailable` is *always immediately* followed by `onCapabilitiesChanged` then `onBlockedStatusChanged`, for every SDK level minSdk 26 supports. That is true and was correctly researched. What was not accounted for: **"immediately" means these three callbacks fire within microseconds to low milliseconds of each other on the platform's own dispatch thread** -- far below any realistic `debounceMillis` (default 300, `AndroidNetworkMonitor.kt:212`). Per `kotlinx.coroutines.flow.debounce`'s own documented semantics (confirmed against the current kotlinlang.org reference this session): "filters out values that are followed by the newer values within the given timeout. **The latest value is always emitted**" -- earlier values in a fast burst are not buffered, not merged, they are **dropped**, unconditionally.

So for the platform's guaranteed onAvailable -> onCapabilitiesChanged -> onBlockedStatusChanged burst (three `PlatformEvent`s arriving inside a few milliseconds of each other, all keyed to `debounceMillis` in the debounce selector at lines 68-73 whenever `blocked = false`, which is the ordinary case): debounce collapses the burst to its **last** member only. `Available` and `CapabilitiesChanged` -- the two events that establish `state.network`/`state.snapshot`/`state.available` -- are silently discarded before `NetworkEventReducer` ever sees them. Only the lone `BlockedStatusChanged` reaches the reducer.

### Two concrete consequences

1. **First connect (no network previously tracked).** `state.network` is still `null` (the reducer's initial seed). The guard's first clause (`state.network != null`) is false, so the `else` branch runs: `state.copy(blocked = event.blocked, changedAt = changedAt)` applied to the still-empty initial `MonitorState`. Result: `network = null, snapshot = null, available = false`. `NetworkStateMapper.buildNetworkState` maps that straight to the canonical offline `NetworkState` (`NetworkStateMapper.kt:44-56`). **A freshly connected, fully validated Wi-Fi or cellular network is reported as offline.**

2. **A network switch (Wi-Fi -> cellular or back), the actual scenario this branch's own commit message claims to fix.** `state.network` already holds the old network (e.g. `wifi-net`). The new network's `Available`/`CapabilitiesChanged` are debounced away exactly as in case 1, and the lone surviving `BlockedStatusChanged(cellular-net, false)` now has `event.network != state.network` -- the reducer's guard treats it as **foreign** and drops it outright (`NetworkEventReducer.kt:117-121`, the guard `state.network != event.network -> state`, which `CF-1`'s own KDoc justifies as protection against out-of-order events, not against this case). Result: **the monitor keeps reporting the old network's last-known state forever**, with no further event able to correct it, because a `Network` object is never reused -- nothing will ever again equal `state.network` for that stale entry. This is a permanent stuck state, not a transient one, and it is the direct opposite of what section A of this audit's own instructions asked me to verify ("Wi-Fi -> cellular transitions are represented correctly").

Case 1 can partially self-heal: if some *later*, non-burst `onCapabilitiesChanged` fires on its own well after the debounce window has already elapsed (e.g. `NET_CAPABILITY_VALIDATED` flipping true some time after connect, which commonly happens hundreds of milliseconds to a couple of seconds later), that lone event arrives with `state.network == null`, so the reducer's `CapabilitiesChanged` guard (`state.network != null && ...`) is false and it is accepted, finally establishing the real state. This is timing-dependent and not guaranteed -- some connections validate fast enough that no such straggler event ever arrives. Case 2 has **no** self-healing path at all, by construction (see above).

### Why this was not caught

- The 16 `NetworkEventReducerTest` tests call `NetworkEventReducer.reduce` directly. They never go through `AndroidNetworkMonitor.observe()`'s actual `debounce -> map -> scan` chain, so they cannot see this: the reducer itself is correct in isolation (confirmed independently in section 4 below), which is exactly why this bug is invisible to that suite.
- The one test that does exercise the real pipeline, `AndroidNetworkMonitorInstrumentedTest.observe_emitsAStateWithoutCrashing` (`AndroidNetworkMonitorInstrumentedTest.kt:41-70`), asserts only `assertNotNull(firstState)` and `assertFalse(firstState.blockedByDevicePolicy)`. The second assertion **passes whether the bug is present or not**: `NetworkStateMapper`'s offline branch also sets `blockedByDevicePolicy = blocked` (`NetworkStateMapper.kt:44-56`), so a wrongly-offline first state and a correctly-observed-and-unblocked first state produce the identical value for the one field this test checks. The test never asserts `firstState.available`, so CircleCI's connected_android_test job (run `2f5066e0`, all four jobs green, cited in the branch's own hand-off report) gives **zero evidence** against this defect -- it would pass exactly the same whether the emulator's real network was correctly detected or not.

### Root cause, demonstrated by diff

`git diff 545a9612 c0ad893` for this file shows the actual regression mechanism plainly: the pre-existing code (`rawEvents().debounce{...}.map { event -> toNetworkState(event) }`) mapped each surviving event to a **complete, independent** `NetworkState` -- `Available` fetched its own fresh capabilities snapshot on the spot, `CapabilitiesChanged` carried its own. Losing an earlier event in a debounced burst was harmless there, because the surviving event was always self-sufficient. This branch's own change (introducing `NetworkEventReducer`'s **stateful** fold so that `blockedByDevicePolicy` can be combined with capability data from a *different* event) is what created the dependency on seeing every event in order -- and the debounce operator was left exactly where it was, upstream of that new dependency, instead of being moved downstream of it.

---

## 2. A. Network identity

| Check | Reducer alone (`NetworkEventReducer.reduce`) | `AndroidNetworkMonitor.observe()` pipeline |
|---|---|---|
| Never mixes state between identities | **Pass.** Every branch except `Available`/`Unavailable` guards `event.network == state.network` before touching state (`NetworkEventReducer.kt:117-152`). Verified by `capabilitiesChanged_forForeignNetwork_isDroppedNotAppliedToTrackedState` and `blockedStatusChanged_forForeignNetwork_isDropped`. | **Fails in the transition case, see CF-1.** The guard is correct; what reaches it is not. |
| Foreign events cannot mutate active state | **Pass**, same guards, same tests, plus `lost_forForeignNetwork_isDroppedNotAppliedToTrackedState`. | Same caveat as above -- the guard does its job on what it is given, but CF-1 causes a *genuine* new-network event to be misclassified as foreign. |
| Loss resets the correct network | **Pass.** `RawNetworkEvent.Lost` only resets when `event.network == state.network` (`NetworkEventReducer.kt:117-122`); a foreign `Lost` is dropped, tested by `lost_forForeignNetwork_isDroppedNotAppliedToTrackedState`, and a genuine `Lost` resets `blocked` too (not just `network`/`snapshot`), tested by `lost_forTrackedNetwork_resetsToCanonicalOffline_includingBlockedFlag`. Consistent with the researched platform contract that a default-network callback's `onLost` "will only be invoked against the last network returned by `onAvailable()`". | Not independently affected by CF-1: `Lost`/`Unavailable` are exempted from debounce (`debounceMillis` selector line 70, `0L`), so loss events are not subject to the burst-collapse problem. This path is correct. |
| Wi-Fi -> cellular transitions represented correctly | **Pass** at the reducer level: `wifiToCellularTransition_freshAvailable_replacesTrackedNetworkCleanly` and `cellularToWifiTransition_freshAvailable_replacesTrackedNetworkCleanly` both construct the events directly and confirm a fresh `Available` cleanly replaces the tracked network, with no old-network leakage. | **Fails, see CF-1 case 2.** The reducer's correct behavior is never reached because debounce removes the `Available` event that would trigger it. |

**Conclusion for A:** the identity-handling *logic* is correct and well-tested in isolation. The *wiring* that is supposed to deliver real platform events to that logic does not reliably do so. This is the same conclusion as CF-1, restated against this section's specific checklist.

---

## 3. B. Callback lifecycle

- **onAvailable, onCapabilitiesChanged, onBlockedStatusChanged, onLost** are each overridden exactly once, only in `AndroidNetworkMonitor.kt:98-129`. Confirmed by a repository-wide `git grep` on the audited commit: no other file overrides any of these four methods, and no duplicate `ConnectivityManager.NetworkCallback` subclass exists anywhere in the tree.
- **onUnavailable** is also handled (line 127), correctly exempted from debounce.
- **onLinkPropertiesChanged** is not overridden. This is in scope -- the task's read list named it -- and it is correctly out of scope for this slice: nothing in the audited requirements (validated/metered/VPN/captive-portal/blocked/transport) needs `LinkProperties`; DNS state, which does, is explicitly deferred (see the branch's own hand-off report, and Decision D4-10 / the test-gate's `noCodePathClaimsToTellNxdomainApartFromResolverFailure_andNoDnsMeasurementTypeExists` guard, which this branch does not violate since it declares no DNS type).
- **Registration/unregistration:** `platformEvents()` registers exactly once per `callbackFlow` collection (`registerDefaultNetworkCallback(callback)`, line 133) and unregisters the *same* callback instance in `awaitClose` (line 138). No leak, no double-registration within one collection. `ConnectionStateRepository.kt` (unchanged by this branch) shares one collection process-wide via `stateIn(..., started = SharingStarted.Eagerly, ...)`, so in the app's actual usage there is exactly one live registration for the process lifetime, not one per subscriber -- confirmed by reading that file directly, not assumed.
- **Stale callback protection:** the callback object is a local `val` inside `platformEvents()`, captured by both the registration call and the `awaitClose` block via closure, so the exact instance that was registered is the one unregistered -- no risk of unregistering the wrong callback or leaking a reference across collections. Each `callbackFlow` invocation gets its own fresh instance.
- One gap worth naming precisely, not a defect: `connectivityManager?.registerDefaultNetworkCallback(callback) ?: logger.w(...)` (lines 133-134) -- if `connectivityManager` is null, `platformEvents()` never emits anything and `observe()` produces a Flow that simply never completes its first emission, which is a defensible "unknown" representation (matches the "unknown/absent state, do not invent" requirement) but means `ConnectionStateRepository.state` would sit at its own `NetworkState.unknown(...)` `initialValue` forever with no log-level escalation beyond the one warning. Not a defect against this task's stated scope (no behavior change was requested here), but worth a note for a future hardening pass.

**Conclusion for B: Pass**, independent of CF-1.

---

## 4. C. Observation correctness (reducer level) and its interaction with CF-1

Read directly against `NetworkStateMapper.kt` (owned by AI1/foundation, touched by this branch only in KDoc) and `NetworkEventReducer.kt`:

| Field | Mapping | Reducer-level test | Affected by CF-1? |
|---|---|---|---|
| validated | `snapshot.isValidated`, straight passthrough, pre-existing and untouched by this branch | `NetworkStateMapperTest` (foundation, unmodified) | Only insofar as the whole snapshot may be missing/stale under CF-1 |
| metered | `!snapshot.isNotMetered`, pre-existing | same | same |
| VPN | `TransportType.VPN in snapshot.transports` -> `vpnPresent`; `vpnAppearingOverExistingTransport_isJustACapabilitiesChangeOnTheSameNetwork` and `vpnDisconnecting_capabilitiesChangeDropsVpnTransportOnSameNetwork` both confirm a VPN transport change on the *same* tracked network flows through correctly | `NetworkEventReducerTest` (this branch), `NetworkStateMapperTest.vpnOverWifi_reportsVpnTransport` (foundation) | Yes -- if the network was never correctly established per CF-1 case 1, or is stuck on a stale identity per case 2, a real VPN toggle is invisible until/unless it happens to be the straggler event that self-heals case 1 |
| captive portal | exact-name match on `"CAPTIVE_PORTAL"` in `rawCapabilityNames`, pre-existing, unaffected by this branch's changes | `NetworkStateMapperTest` (foundation) | same as VPN |
| blocked status | this is the field this branch actually wires up; correctly folds via `BlockedStatusChanged`, preserved across capability updates via `.copy()` (verified by reading `NetworkEventReducer.kt:141-152`: the `CapabilitiesChanged` branch's `state.copy(...)` does not name `blocked`, so Kotlin's `copy` retains the previous value) | `blockedStatusChanged_forTrackedNetwork_setsBlockedWithoutTouchingSnapshot` proves the *reverse* direction (blocked update preserves snapshot). **The forward direction -- that a `CapabilitiesChanged` preserves an already-set `blocked = true` -- has no direct test.** Verified correct by code reading only. See section 7, missing test T-2. | This is the field most directly broken by CF-1, since it is precisely the field whose reducer-level correctness depends on receiving both an `Available`/`CapabilitiesChanged` *and* a `BlockedStatusChanged` for the same burst -- exactly what CF-1 prevents. |
| transport type | `resolveTransport`, VPN > ETHERNET > WIFI > CELLULAR > OTHER > NONE priority, pre-existing, unaffected | `NetworkStateMapperTest.noRecognizedTransport_fallsBackToNone`, `ethernetOverWifi_reportsEthernetTransport` (foundation) | same as VPN |
| unknown values | `Available` with a null snapshot is passed through as `network` set, `snapshot = null` rather than fabricating capabilities (`NetworkEventReducer.kt:127-140`); `NetworkStateMapper`'s own null-snapshot branch then produces the canonical offline shape | `available_withNullSnapshot_representsUnknownRatherThanInventingCapabilities` (this branch), `NetworkStateMapperTest.availableWithNullSnapshot_alsoProducesOfflineState` (foundation) | No new issue here: this path already existed and is correctly unaffected |

**Conclusion for C:** every individual mapping rule is correct, matches the contract (Decision D4-8), and is genuinely tested at the reducer level. The correctness is nullified for real users by CF-1, because the reducer only ever sees a fraction of the events the platform actually sends.

---

## 5. D. Race safety

- **No synchronous `ConnectivityManager` query from inside a callback body.** Verified by reading every one of the five overridden methods (`AndroidNetworkMonitor.kt:98-129`): each does exactly one thing, `trySend(...)`. The one synchronous call in the whole file, `connectivityManager?.getNetworkCapabilities(event.network)` (line 163), is inside `toRawNetworkEvent`, called only from the `.map(::toRawNetworkEvent)` operator (line 75) -- i.e. from the Flow's own downstream collection, never from the `NetworkCallback`'s call stack. This is exactly what the platform's own documented warning on `ConnectivityManager.NetworkCallback` ("Do NOT call getNetworkCapabilities(Network) ... in this callback as this is prone to race conditions") requires, and the file's own KDoc at lines 142-152 states this correctly and accurately.
- **Reducer remains deterministic and pure.** `NetworkEventReducer.reduce` (`NetworkEventReducer.kt:105-153`) takes its `changedAt: Instant` as an explicit parameter rather than reading a clock itself, performs no I/O, allocates only immutable `MonitorState`/data-class copies, and has no shared mutable state. Confirmed by reading the whole file: there is no `var`, no companion-object mutable field, no external call other than `==` comparisons and `.copy()`. This makes it safe to call from any thread and trivially replayable, which is exactly what makes the 16 JVM tests possible without Robolectric.
- **Callback thread does not perform expensive work.** All five overrides are `trySend` one-liners (see above); no logging, no blocking, no CPU-bound work happens on the platform's callback-dispatch thread.

**Conclusion for D: Pass**, and unrelated to CF-1 -- CF-1 is a data-flow/ordering defect in the Flow operator chain, not a threading or synchronization defect. The two should not be conflated when planning the fix: fixing CF-1 does not require touching anything audited in this section.

---

## 6. E. Domain integration

- **NetworkStateMapper remains the canonical mapper.** A repository-wide search for `NetworkState(` (the constructor, not `NetworkStateMapper.buildNetworkState(`) on the audited commit finds exactly 6 call sites, none of them in `AndroidNetworkMonitor.kt` or `NetworkEventReducer.kt`: `NetworkState.kt:51` (`unknown()`), `NetworkStateMapper.kt:44` and `:59` (the two branches), and three test fixtures (`NetworkHistoryRepositoryTest.kt:23`, `LatencyMeasurementEngineTest.kt:44`, `ReferenceLatencyProbeExecutorTest.kt:51`). This branch adds none and removes none.
- **`NetworkStateMapper.buildNetworkState(` call sites:** exactly one in production code, `AndroidNetworkMonitor.kt:83-88` -- down from the three separate call sites (one per event type) the pre-existing code had, because this branch's refactor collapsed per-event mapping into a single call after the fold. This is worth recording precisely because `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` section 7.1 cites "`AndroidNetworkMonitor.kt:84, 92, 100`" as the call sites -- that citation was accurate against the pre-refactor file and is now stale; nothing in this branch's own behavior is wrong because of it, but a reader of that reconciliation document following those line numbers against this branch's tree will not find three call sites.
- **No duplicated `NetworkState` model.** `MonitorState` (`NetworkEventReducer.kt:14-31`) is not a parallel `NetworkState`: it carries `network: N?` (identity), `snapshot: RawCapabilitiesSnapshot?`, `available`, `blocked`, `changedAt` -- an intermediate fold accumulator needed only because `blockedByDevicePolicy` and capability data arrive as separate platform events, not a second representation of the same domain concept. `RawCapabilitiesSnapshot` is pre-existing (untouched by this branch).
- **No fabricated measurements.** Neither file constructs `LatencyMeasurement`, `MeasurementFailure`, or anything under `core.model.measurement`. Confirmed absent from both files by direct read.
- **No `NetworkQuality` mutation.** `NetworkStateMapper.buildNetworkState` sets `estimatedQuality = NetworkQuality.Unavailable` unconditionally in both branches (`NetworkStateMapper.kt:47`, `:66`; unchanged by this branch's KDoc-only edit). `phase-4-test-gate`'s `ArchitectureGuardTest.networkQualityMeasured_isNeverConstructedByProductionCode` guard (which scans all of `src/main` for `NetworkQuality.Measured(`) was independently re-run by inspection against this branch's two files: no match.
- **No conflict with Phase 4 foundation fields.** `captivePortalReported`, `vpnPresent`, `blockedByDevicePolicy` are all supplied explicitly at both `NetworkStateMapper.buildNetworkState` call sites in this branch's `AndroidNetworkMonitor.kt` -- never relying on `NetworkState`'s own constructor defaults. This matters because `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` section 5.C recommends *removing* those constructor defaults (Decision DD-5, to make "not observed" distinguishable from "observed false"); that document's own section 12 table already confirms this branch is compatible with that removal, and this audit independently confirms the same by reading the only two call sites this branch's code has to `buildNetworkState` (`AndroidNetworkMonitor.kt:83-88`): both name `blocked` explicitly, matching the pattern used by `NetworkStateMapper.buildNetworkState`'s own `blocked: Boolean = false` parameter, which is a function default, not a data-class constructor default, and stays either way.

**Conclusion for E: Pass.**

---

## 7. F. API compatibility

Re-verified this session against current official Android reference documentation (not carried over from the implementation session's notes without re-checking):

| API | Used at | Added | Compatible with minSdk 26 / compileSdk 36 / targetSdk 36? |
|---|---|---|---|
| `ConnectivityManager.registerDefaultNetworkCallback(NetworkCallback)` | `AndroidNetworkMonitor.kt:133` | API 24 | Yes. Pre-existing, unchanged by this branch. |
| `ConnectivityManager.NetworkCallback#onAvailable(Network)` | line 98 | API 21 | Yes. |
| `#onCapabilitiesChanged(Network, NetworkCapabilities)` | line 102 | API 21 (this two-argument overload) | Yes. |
| `#onBlockedStatusChanged(Network, boolean)` | line 119 | **API 29** | Yes at minSdk 26: this is an added-in-a-later-release override. Confirmed safe by the standard Android pattern of overriding a later-added `NetworkCallback` method with no `Build.VERSION.SDK_INT` guard -- the framework on an older OS simply never invokes a method it does not know about; nothing in this file calls `super.onBlockedStatusChanged(...)`, so no `@RequiresApi` is needed. This reasoning is stated in the file's own KDoc (lines 109-118) and independently re-confirmed in this audit. |
| `#onLost(Network)` | line 123 | API 21 | Yes. |
| `#onUnavailable()` | line 127 | API 21 | Yes. |
| `ConnectivityManager.unregisterNetworkCallback(NetworkCallback)` | line 138 | API 21 | Yes. |
| `NetworkCapabilities.hasTransport(int)` / `.hasCapability(int)` | `toSnapshot`, lines 180, 185, 190-192 | API 21 | Yes. |
| `NetworkCapabilities.TRANSPORT_*` constants referenced (`ALL_TRANSPORT_CONSTANTS`, lines 214-223) | -- | `TRANSPORT_WIFI_AWARE`/`TRANSPORT_LOWPAN` API 26/27, rest API 21 | Yes, and moot regardless: these are `public static final int` constants, inlined by the Kotlin compiler at the reference site, not method calls -- referencing a constant added after minSdk does not require the constant's declaring release to be present on the running device (this exact reasoning is already documented in the pre-existing, unmodified `TransportConstantMapper.kt`'s own file comment, and applies identically here). |
| `NetworkCapabilities.NET_CAPABILITY_*` constants (`NAMED_CAPABILITIES_OF_INTEREST`, lines 229-237) | -- | all API 21-23 | Yes, same reasoning. |

**onAvailable's documented guarantee re-verified this session, current reference:** "Starting with `Build.VERSION_CODES.O`, this will always immediately be followed by a call to `onCapabilitiesChanged(...)` then ... `onLinkPropertiesChanged(...)`, and a call to `onBlockedStatusChanged(...)`." True for every SDK level minSdk 26 (`= O`) supports. This citation, already in the code's KDoc, was re-checked against the current official page for this audit and confirmed accurate.

**onLost's documented guarantee re-verified this session, current reference:** for `registerDefaultNetworkCallback`, "it will only be invoked against the last network returned by `onAvailable()` when that network is lost and no other network satisfies the criteria of the request." Also re-confirmed accurate, and this audit's own section 2 (A) confirms the reducer's guards correctly rely on it.

**Compile warning, pre-existing, not introduced by this branch:** `network:monitor:compileDebugKotlin`/`compileReleaseKotlin` both emit `w: ... AndroidNetworkMonitor.kt:68:10 This declaration is in a preview state ... kotlinx.coroutines.FlowPreview` for the `.debounce{}` call. This warning exists identically on the pre-existing code at `545a9612` (same API, same lack of `@OptIn`), confirmed by reading that revision's equivalent line -- not a regression, but worth fixing alongside CF-1 since that line is already being touched.

**Conclusion for F: Pass.** Every API used is correctly verified against its actual minimum SDK level and is safe across this project's minSdk 26 / compileSdk 36 / targetSdk 36 range.

---

## 8. G. Test quality

**The 16 `NetworkEventReducerTest` tests genuinely exercise behavior**, not implementation details. Each test constructs a real event sequence and asserts on the resulting `MonitorState`'s observable fields (`network`, `snapshot`, `available`, `blocked`, `changedAt`) -- never on which private helper ran, never on call counts, never on internal representation choices unrelated to output. The foreign-event and transition tests in particular each assert a *negative* (the foreign network's data must **not** appear) as well as a positive, which is the right shape for this kind of guard-clause logic. The use of a generic `N` type parameter (`String` in tests, `android.net.Network` in production, explained in the test file's own KDoc and independently confirmed correct in section 5/D above) is a legitimate and uncommonly rigorous way to get real behavioral coverage of Android-callback-adjacent logic without Robolectric.

**Missing tests, in priority order:**

- **T-1 (would have caught CF-1; add before merge).** No test exercises `AndroidNetworkMonitor.observe()`'s actual operator chain -- `debounce -> map -> scan -> drop -> map -> distinctUntilChanged` -- with a synthetic burst of events arriving with near-zero delay between them, the way the platform's own guaranteed `onAvailable`/`onCapabilitiesChanged`/`onBlockedStatusChanged` sequence does. This is not currently possible as a plain JVM test, because `PlatformEvent.Available`/`CapabilitiesChanged` carry real `android.net.Network`/`NetworkCapabilities` instances, which cannot be constructed under the stub android.jar. The fix in section 9 below resolves this by making the debounced/folded pipeline itself generic over the event and network-identity types, the same technique already used for `NetworkEventReducer`/`RawNetworkEvent`, so a JVM test can supply a synthetic zero-delay burst and assert the final `NetworkState` reflects real availability, not the collapsed-to-one-event failure mode.
- **T-2 (medium; the reducer's headline design rationale is asymmetrically tested).** `blockedStatusChanged_forTrackedNetwork_setsBlockedWithoutTouchingSnapshot` proves a `BlockedStatusChanged` event preserves the existing snapshot. No test proves the reverse: that a `CapabilitiesChanged` event preserves an already-set `blocked = true`. This audit verified by reading (`NetworkEventReducer.kt:141-152`: the `CapabilitiesChanged` branch's `state.copy(...)` omits `blocked`, so Kotlin retains the prior value) that this is currently correct, but it is exactly the kind of thing a future edit to that `.copy(...)` call could silently break with no red test to catch it -- and it is the specific property this reducer's own KDoc (`NetworkEventReducer.kt:73-83`) gives as its reason for existing.
- **T-3 (low).** No test covers a `Lost` event arriving when `state.network` is still `null` (i.e. a stray `Lost` before any network was ever tracked, as opposed to `lost_forForeignNetwork_isDroppedNotAppliedToTrackedState`'s already-tracked-different-network case). The guard (`event.network == state.network` where `state.network` is `null`) is correctly false either way, so this is very unlikely to be a real gap in behavior, but it is an untested branch of the same conditional.
- **T-4 (low, instrumented).** `AndroidNetworkMonitorInstrumentedTest` asserts only `assertNotNull` and one field's default value (see CF-1's "why this was not caught"). At minimum it should assert `firstState.available` matches the actual expected connectivity of the CI emulator (which does have network access), so a future regression of this exact kind fails CI immediately instead of passing silently.

**Conclusion for G:** the reducer suite itself is high quality. The gap is structural -- there was no test at the one layer (the actual `observe()` Flow pipeline) where CF-1 lives, and the one test at that layer does not assert the one thing that would have revealed it.

---

## 9. Required fixes (not applied -- audit only)

1. **CF-1, required before merge.** Move `.debounce{}` so it applies to the **folded output**, not the raw pre-fold events -- i.e. reorder to `platformEvents().map(::toRawNetworkEvent).scan(...).drop(1).map { NetworkStateMapper.buildNetworkState(...) }.debounce { state -> ... }.distinctUntilChanged()`, with the debounce selector rewritten against `NetworkState` (`state.available`/an equivalent "did this just become unusable" signal) in place of today's `PlatformEvent`-keyed selector. This guarantees the reducer always observes every raw platform event -- nothing is ever dropped before folding -- while still coalescing rapid *output* changes for slow consumers, which was debounce's actual intent per the architecture doc's own Section 6.2 citation already in this file's KDoc. This fix touches only `AndroidNetworkMonitor.kt`; `NetworkEventReducer.kt` needs no change, since section 2/4 of this audit confirm the reducer itself is already correct.
2. **Accompanying T-1.** Once (1) is done, factor the debounce/fold/map chain into a form a JVM test can drive with a synthetic zero-delay `PlatformEvent` burst -- most directly by making `platformEvents()`'s element type and the surrounding pipeline generic the same way `NetworkEventReducer` already is, so a test can substitute a `String`-keyed fake event source. Add the burst-collapse regression test described in T-1 using that seam.
3. **T-2, T-3, T-4** as described in section 8, at whatever priority the owner assigns; none of them block merge on their own the way CF-1/T-1 do, since they cover already-correct behavior rather than a live defect.
4. **Stale reconciliation-doc citation (documentation only, not this branch's file).** `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` section 7.1's "`AndroidNetworkMonitor.kt:84, 92, 100`" is stale against this branch's actual single call site (`:83-88`); flagged for whoever next edits that document, not fixed here since it is a foreign branch's file.

---

## 10. Compatibility assessment

- **Build:** confirmed clean at `c0ad893` -- CircleCI run `3f81fb89` / workflow `2f5066e0`, all four jobs (`build`, `unit_tests`, `static_checks`, `connected_android_test`) succeeded, independently re-confirmed this session by reading the same CircleCI run rather than trusting the branch's own hand-off report.
- **API:** fully compatible across minSdk 26 / compileSdk 36 / targetSdk 36, see section 7.
- **No new permission, dependency, or endpoint.** Confirmed by re-running the same class of check `ArchitectureGuardTest.internetPermission_isDeclaredOnlyInNetworkMonitor_andNeverInAppOrOtherLibraries` performs: no `AndroidManifest.xml` under this branch's changed files, and no manifest anywhere in the tree gained a new permission. No new Gradle dependency: `network/monitor/build.gradle.kts` is untouched by this branch (confirmed by the file-level diff against `545a9612`).
- **No conflict with the engine-hardening/foundation reconciliation work.** `PHASE_4_ENGINE_FOUNDATION_RECONCILIATION.md` section 4's own file-by-file plan explicitly lists `AndroidNetworkMonitor.kt` as "Not touched by the reconciliation," and section 12's downstream-branches table already accounts for this branch's existence and expects no overlap with the engine files it merges. This audit's own reading of both branches' file sets confirms no shared file exists between `phase-4-android-network-state`'s changes and `phase-4-engine-hardening`'s changes.

---

## 11. Integration dependencies

- **Base has moved.** This branch's actual parent (`545a9612`) is itself 3 commits ahead of `9a28cc6`, and `phase-4-measurement-foundation` has since moved further, to `fdf5440` (which added, then per the reconciliation doc's own recommendation may remove, the `NetworkState` constructor defaults -- see section 6 above for why this branch is compatible either way). Before this branch is integrated anywhere, it should be rebased onto whatever the actual current integration base becomes (the reconciliation plan's own recommended base is `fdf5440` plus the hardening merge), not onto `545a9612`.
- **BC-04 (phase-4-test-gate's own spec, `PHASE_4_TEST_GATE_SPECIFICATION.md` line 255) still reads "Blocked (wiring deferred in S1 notes)"** for "the platform blocked-status callback updates `blockedByDevicePolicy`." That document was written against this branch's base (`545a9612`) before this branch existed, so it does not yet know the wiring landed -- its own gate tier for that row is G2 (device-validation-required) regardless, so nothing in that document needs to change to stay accurate, but a reader relying on that spec alone would not know this branch attempted BC-04's wiring. This audit is the record of that.
- **The `phase-4-engine-hardening` notes mention** that closing the measurement engine's "stale context" gap (its own section E, `PHASE_4_ENGINE_HARDENING_NOTES.md`) would need "a context provider or network-monitor dependency" as a future design change. `AndroidNetworkMonitor`/`ConnectionStateRepository` are the natural candidate for that dependency once it is designed, which is a reason to fix CF-1 before that future work begins, not after -- an engine-side consumer built against the currently-broken pipeline would inherit CF-1 silently.
- **No dependency in the other direction:** nothing in `phase-4-measurement-foundation`, `phase-4-engine-hardening`, or `phase-4-engine-foundation-reconciliation` reads from `AndroidNetworkMonitor` or `NetworkEventReducer` today (confirmed by the reconciliation document's own complete call-site inventory, section 7, cross-checked against this branch's tree in its section 12 table).

---

## 12. Whether the branch is safe to merge later

**Not yet, but close.** The identity-handling logic, the callback lifecycle, the race-safety properties, the domain-model boundary, and the API-level compatibility are all sound and independently verified in this audit (sections 3, 5, 6, 7). The one blocking defect (CF-1) is narrow in scope -- a single operator-ordering mistake in one function, `observe()` -- and its fix does not require any change to the well-tested `NetworkEventReducer`, does not touch any file owned by another branch, and does not require a new permission, dependency, or endpoint. Once CF-1 is fixed and T-1's regression test is added and passes (ideally alongside T-2 through T-4), this branch should be re-audited at the same scope as this document before it is considered for integration; a full re-run of the existing 16 reducer tests plus the corrected instrumented test on CI is the minimum gate for that follow-up.

---

*End of audit.*
