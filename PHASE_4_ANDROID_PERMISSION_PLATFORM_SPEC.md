# AERIVA Phase 4 S3 — Android Permission and Platform Integration Specification

Specification only. This document does not add INTERNET, does not modify any manifest, does not add a dependency, does not implement PermissionAdapter, and does not modify MeasurementCapabilityClassifier's behavior. main is untouched. Every claim is labeled VERIFIED FACT (confirmed this session against the actual repository or a current official source, both named), REPO FACT (read directly from this repository), PROPOSED DESIGN, ASSUMPTION, LIMITATION, or OPEN DECISION.

Base: `phase-4-measurement-foundation` @ `9a28cc62883349de1a0e2c5734ed2e943b176269`, as pinned by this task. All repository reads for this specification (Section 2 and throughout) were performed directly against that exact commit via the GitHub API. Note on that pin (REPO FACT, checked this session): by the time this document was written, the branch tip had moved to `89337fc0` (two further commits, both CI-driven fixes for `MeasurementFailure.Timeout`/`NetworkState` constructor call sites the first push missed in `ReferenceLatencyProbeExecutor.kt` and `ReferenceLatencyProbeExecutorTest.kt`, plus one in `core:database`'s `NetworkHistoryRepositoryTest.kt`). Neither fix touches a manifest, a permission, `AndroidNetworkMonitor.kt`, `MeasurementCapabilityClassifier.kt`, or `LatencyMeasurementEngine.kt`'s permission-related surface — confirmed by reading both fix commits directly. Nothing in this document depends on anything the pinned commit lacks and the moved tip has; the pin and the current tip are identical for every file this spec reads. **Tooling note**: the branch this document is committed to, `phase-4-android-platform-spec`, was created from the branch tip (`89337fc0`) rather than the exact pinned SHA, because the GitHub interface available in this session creates a new branch only from another branch's current tip, not from an arbitrary commit SHA. Given the confirmed-identical content above, this has no effect on anything this document claims or designs.

---

## 1. Executive summary

Today, permission state reaches `MeasurementCapabilityClassifier.classify()` as a bare `Set<String>` (REPO FACT — `LatencyMeasurementRequest.grantedPermissions`, supplied by whatever calls the engine). Nothing in this repository currently populates that set from a real `Context.checkSelfPermission()` call — no such call exists anywhere in the codebase at the pinned commit. This is the exact gap Decision D1-4 in the cross-cutting contract already named ("`grantedPermissions` passed to the classifier must come from an Android-edge adapter that calls `checkSelfPermission` at request time... never a hardcoded set") without designing it. This document designs it.

Five findings drive the design:

1. **Runtime permission grants are not stable for the life of a process**, let alone a set constructed once at startup. Auto-reset (Android 11+, and Android 6.0+ via Google Play services since December 2021) can silently revoke `ACCESS_FINE_LOCATION` and `READ_PHONE_STATE` for an unused app between one measurement attempt and the next (VERIFIED FACT, Section 4.9). A `PermissionAdapter` that snapshots permissions once and reuses the snapshot is wrong by construction.
2. **"Fine location" and "denied" are not the only two states for `ACCESS_FINE_LOCATION` on API 31+.** A user can grant only `ACCESS_COARSE_LOCATION` while `ACCESS_FINE_LOCATION` reads denied, and Android 12+ requires both permissions to be *requested* together even though AERIVA's classifier only ever *needs* the fine one (VERIFIED FACT, Section 4.10). The adapter must report this as a real, named state, not collapse it into a bare boolean.
3. **A missing permission and an unsupported platform capability are different failure shapes that this repository already keeps separate at the domain-failure level** (`AerivaError.PermissionRequired`/`Unsupported` are distinct cases — REPO FACT, read from `core:result`), but nothing today enforces that a `PermissionAdapter` cannot blur them together at the edge. Section 6 designs the adapter's own output type so this distinction survives from the OS boundary to the classifier.
4. **`core:model` and `MeasurementCapabilityClassifier` are both deliberately Android-import-free today** (REPO FACT, confirmed no `android.*` import in either file this session) — this is a load-bearing existing property (testability without instrumentation, stated in both files' own KDoc), and the adapter design must not be the change that breaks it.
5. **The manifest-merge question (where INTERNET goes) has a definite, checkable answer**, but nothing in the repository today checks it. Section 8 designs the verification, not just the placement.

---

## 2. Repository state read for this specification

REPO FACT, all read directly from the pinned commit this session (not assumed from Decision-contract summaries, though cross-checked against them):

| File | Relevant content |
|---|---|
| `app/src/main/AndroidManifest.xml` | Zero `<uses-permission>` entries. No launcher activity. |
| `network/monitor/src/main/AndroidManifest.xml` | Exactly one entry: `ACCESS_NETWORK_STATE`, with a comment citing it as a normal, install-time permission. |
| `app/build.gradle.kts` | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`. Dependencies block is empty except plugin aliases — `:app` does not depend on `:network:monitor` or `:core:model`. |
| `network/monitor/build.gradle.kts` | Same SDK levels. `implementation(project(":core:common"))` present in the main dependencies block (added specifically because `LatencyMeasurementEngine.kt` uses `AerivaDispatchers` in production code — REPO FACT from that dependency's own comment). No permission-related dependency (no AndroidX Security, no Play Services Location) anywhere. |
| `AndroidNetworkMonitor.kt` | Uses `ConnectivityManager.registerDefaultNetworkCallback` (a single callback, registered once per `observe()` collector, unregistered in `awaitClose`). Reads `NET_CAPABILITY_INTERNET`, `NET_CAPABILITY_VALIDATED`, `NET_CAPABILITY_NOT_METERED`, `NET_CAPABILITY_NOT_VPN` (name only, not yet acted on), `NET_CAPABILITY_TRUSTED`, `NET_CAPABILITY_NOT_RESTRICTED`, `NET_CAPABILITY_CAPTIVE_PORTAL`. Does **not** call `checkSelfPermission` anywhere — it needs only the already-declared `ACCESS_NETWORK_STATE`, which is a normal permission requiring no runtime check. Does not call `onBlockedStatusChanged` (confirmed absent; matches the Phase 4 foundation notes' own "deferred" item 4). |
| `MeasurementCapabilityClassifier.kt` | Pure function `classify(capability, sdkInt, grantedPermissions: Set<String>)`. No Android import. Four permission constants defined as plain strings (`PERMISSION_ACCESS_FINE_LOCATION`, `PERMISSION_READ_PHONE_STATE`, `PERMISSION_ACCESS_NETWORK_STATE`, `PERMISSION_INTERNET`). `grantedPermissions` is documented in its own KDoc as "not every permission declared in the manifest... a declared runtime permission the user denied must classify the same as not declared at all" — this is already the right contract for an adapter to fulfill; nothing about the classifier's own signature needs to change for this spec's design to slot in. |
| `LatencyMeasurementEngine.kt` | `LatencyMeasurementRequest.sdkInt: Int` and `.grantedPermissions: Set<String>` are supplied by the caller, passed through unchanged to `classify()`. The engine itself never reads `Build.VERSION.SDK_INT` or calls `checkSelfPermission`. |
| `core:result/AerivaError.kt` (unchanged since Phase 1) | `PermissionRequired` and `Unsupported` are separate sealed cases — REPO FACT, confirms finding 3 above at the type level, independent of anything this spec adds. |
| `libs.versions.toml` | `androidx.core:core-ktx` 1.18.0 present and already in use in `core:security/build.gradle.kts`; not yet a dependency of `network:monitor`. No Play Services Location. |

No other file in the repository references `checkSelfPermission`, `ContextCompat.checkSelfPermission`, `shouldShowRequestPermissionRationale`, `ActivityResultContracts.RequestPermission`, or any permission-request API — confirmed by reading every file this session's prior audits already catalogued plus the two new commits on the moved tip. **This repository has never asked the platform for a runtime permission decision.** Everything in this spec is greenfield design against that fact, not a refinement of an existing adapter.

---

## 3. Scope and non-goals

In scope: an audit of the twenty platform topics this task names, a `PermissionAdapter` interface design (signatures and types, not bodies), the `INTERNET` manifest-placement and merge-verification design.

Out of scope, per this task's explicit instructions, confirmed not done: no `INTERNET` permission added anywhere; no manifest edited; no `libs.versions.toml` or `build.gradle.kts` edited; no `PermissionAdapter` implementation (no class body, no `Context` reference written as compilable code — Section 6's sketches are contracts, not implementations, matching the decision contract's own "sketch, not compiled" convention); no change to `MeasurementCapabilityClassifier.kt`'s behavior (its existing four-way classification, its permission constants, and its per-capability `when` are all treated as fixed inputs this spec designs around, not decisions this spec revisits).

---

## 4. Platform audit

### 4.1 INTERNET permission

REPO FACT: not declared anywhere. VERIFIED FACT: `INTERNET` is a normal permission — granted automatically at install time provided it is declared in the manifest, with no runtime prompt and no entry in the runtime permission model at all (Android's permission-groups documentation; also consistent with this repository's own existing `ACCESS_NETWORK_STATE` comment, which correctly describes the same normal-permission behavior). A `PermissionAdapter` therefore never needs to *request* `INTERNET` — only to report whether it is *held*, which for a normal permission means "declared in the merged manifest," checkable via `PackageManager.checkPermission()` or `Context.checkSelfPermission()` (both work for normal permissions, returning `PERMISSION_GRANTED` once the manifest entry exists, without any prompt) rather than the runtime request flow described in Section 4.7.

### 4.2 ACCESS_NETWORK_STATE

REPO FACT: already declared in `network:monitor`'s manifest, already in production use by `AndroidNetworkMonitor`. Normal permission, same install-time behavior as INTERNET. Nothing to design here beyond confirming the adapter treats it identically to INTERNET (Section 6's `NormalPermission` case), since both already work today without any adapter existing.

### 4.3 ACCESS_WIFI_STATE

Not currently declared, not currently required by anything in `MeasurementCapabilityClassifier`'s table (REPO FACT — the `WIFI_CHARACTERISTICS` branch gates on `ACCESS_FINE_LOCATION` only). Normal permission (VERIFIED FACT, same permission-group documentation). **ASSUMPTION, carried forward from the Phase 4 architecture document and not independently re-verified this session**: the current, non-deprecated Wi-Fi connection-info API path (`NetworkCapabilities.getTransportInfo()` as `WifiInfo`, Section 4.11) does not itself require `ACCESS_WIFI_STATE` — it requires location permission and the location-info callback flag instead. This spec does not change the classifier's existing permission table, so this assumption is inherited, not newly relied upon.

### 4.4 ACCESS_FINE_LOCATION

REPO FACT: required by the classifier for `WIFI_CHARACTERISTICS` (alone) and `CELLULAR_CHARACTERISTICS` (jointly with `READ_PHONE_STATE`). Runtime, dangerous permission (VERIFIED FACT). See Section 4.10 for the approximate-vs-precise complication this permission specifically introduces, which is the single most consequential platform behavior this spec's adapter design has to account for.

### 4.5 READ_PHONE_STATE

REPO FACT: required by the classifier for `CELLULAR_CHARACTERISTICS`, jointly with `ACCESS_FINE_LOCATION`. Runtime, dangerous permission (VERIFIED FACT).

### 4.6 Android API-level behavior (general)

Covered per-topic throughout this section and consolidated in Section 4.20. This repository's `minSdk = 26` (REPO FACT) means an adapter must behave correctly from API 26 through the current platform version without assuming any API-level-gated behavior is universal.

### 4.7 Runtime vs. normal permissions

VERIFIED FACT, general Android permission model: normal permissions (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) are granted automatically at install time once declared — `checkSelfPermission` on one of these returns `PERMISSION_GRANTED` as soon as the manifest entry exists, with no user interaction and no possibility of "denied" in the runtime-permission sense. Dangerous/runtime permissions (`ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`) additionally require an explicit user grant via a runtime prompt (`ActivityResultContracts.RequestPermission` or the older `requestPermissions` API) — declaring them in the manifest is necessary but not sufficient. **This is why a single `PermissionAdapter.isGranted(name): Boolean` signature is insufficient by itself**: the adapter's caller-visible contract must distinguish "not declared in the manifest at all" from "declared but not yet granted" from "declared and granted" for a dangerous permission, since only the second and third are meaningfully different at the runtime-check level, but the manifest-declaration question matters for the INTERNET-placement design in Section 8.

### 4.8 Permission denial

VERIFIED FACT: `checkSelfPermission` returns `PERMISSION_DENIED` both when the user has explicitly denied a runtime permission and when the app never requested it at all — the API does not distinguish these two cases by return value alone; `shouldShowRequestPermissionRationale()` is the separate signal an app uses to tell "denied once, can ask again" from "denied with don't-ask-again" (a UI-flow concern, not a measurement-classification one). For this spec's purposes: **denial and "never asked" are the same state from the classifier's point of view** — both mean the permission is not currently held, and `MeasurementCapabilityClassifier`'s own KDoc already says exactly this ("a declared runtime permission the user denied must classify the same as not declared at all"). The adapter does not need to distinguish denial from never-requested; it needs to distinguish both of those from granted, and (Section 4.10) from partially-granted.

### 4.9 Permission revocation

VERIFIED FACT: Android 11 (API 30) introduced automatic permission reset for unused apps; this was extended via a Google Play services update, rolling out from December 2021, to devices as far back as Android 6.0 (API 23), for apps targeting API 23+. Auto-reset applies to **runtime (dangerous) permissions only** — `ACCESS_FINE_LOCATION` and `READ_PHONE_STATE` are subject to it; `INTERNET` and `ACCESS_NETWORK_STATE`, being normal permissions, are not (normal permissions have no "unused" revocation concept — VERIFIED FACT, consistent with Section 4.7's install-time-only grant model). Apps targeting API 30+ have this enabled by default; the user can also manually re-enable it for apps targeting API 23-29. An app can ask the user to disable auto-reset for itself, but this repository's `targetSdk = 36` means auto-reset applies by default and no such request exists in the codebase (REPO FACT).

**Direct consequence for the adapter design**: `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE` grant state can change **between two measurement attempts in the same app process**, without any user action inside the app — the OS can revoke it in the background if AERIVA goes unused for months, and (separately, ordinarily) the user can revoke it at any time via system settings, which is a plain, always-available action independent of auto-reset. A `PermissionAdapter` that caches a grant snapshot at construction time, or that is fed a `Set<String>` built once at app startup, will silently go stale. Section 6's design requires every `PermissionAdapter` query to re-check the live platform state, not memoize it.

### 4.10 Approximate vs. precise location implications

VERIFIED FACT, current official documentation (`developer.android.com/develop/sensors-and-location/location/permissions/runtime`, checked this session): on Android 12 (API 31) and higher, an app requesting `ACCESS_FINE_LOCATION` must also declare and request `ACCESS_COARSE_LOCATION` in the same runtime request — requesting fine alone is ignored by the system on some Android 12 releases and logs an explicit error on apps targeting 31+ ("`ACCESS_FINE_LOCATION` must be requested with `ACCESS_COARSE_LOCATION`"). The resulting system dialog offers the user a **Precise** or **Approximate** choice independent of which permissions the app declared were "the ones it really wants." A user can choose Approximate even when the app only cares about Precise, and can later change this choice in system settings at any time, for any app, regardless of that app's target SDK.

**This directly affects `WIFI_CHARACTERISTICS` and `CELLULAR_CHARACTERISTICS`**, both of which the classifier gates on `ACCESS_FINE_LOCATION` specifically (REPO FACT). When a user grants only Approximate:
- `checkSelfPermission(ACCESS_FINE_LOCATION)` returns `PERMISSION_DENIED`.
- `checkSelfPermission(ACCESS_COARSE_LOCATION)` returns `PERMISSION_GRANTED`.
- The classifier's existing logic (unchanged by this spec) already produces the correct outcome from this: `PERMISSION_ACCESS_FINE_LOCATION in grantedPermissions` is `false`, so `WIFI_CHARACTERISTICS`/`CELLULAR_CHARACTERISTICS` classify `NotReliablyAvailable` — the same as an outright denial. **The classifier does not need to change.** What the adapter must get right is not feeding it a stale or incorrectly-merged permission set that would make an approximate-only grant look like a fine-location grant (Section 6.4).
- **Consequence for the future manifest**: if/when `ACCESS_FINE_LOCATION` is ever added (a decision this spec does not make — it belongs to the same owner-gated category as `OD-1`/`OD-7` in the cross-cutting contract), `ACCESS_COARSE_LOCATION` must be declared alongside it in the same manifest and requested in the same runtime call, per this platform requirement — not as a separate, later decision, and not because AERIVA has any use for coarse location on its own.

### 4.11 Wi-Fi information APIs

VERIFIED FACT (already independently confirmed in this repository's own prior research, re-confirmed this session against the classifier's own current comment, which cites current official reference docs directly): the non-deprecated path to a device's current Wi-Fi connection info is `ConnectivityManager.registerNetworkCallback(request, callback, flags)` with `NetworkCallback.FLAG_INCLUDE_LOCATION_INFO` (added API 31 — VERIFIED FACT, fetched this session, `developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback`), then reading `NetworkCapabilities.getTransportInfo()` cast to `WifiInfo`. `WifiManager.getConnectionInfo()` is marked deprecated (VERIFIED FACT, per the classifier's own already-cited source). Both the deprecated and current paths gate on the same permission (`ACCESS_FINE_LOCATION`) — the classifier's existing `WIFI_CHARACTERISTICS` branch is already correct and this spec does not revise it. **LIMITATION, carried forward, not re-derived**: real SSID/RSSI values are real-device-only; this spec's adapter cannot and does not change that.

### 4.12 Cellular information APIs

REPO FACT/VERIFIED FACT (as already encoded in the classifier's own comment, independently consistent with the general `TelephonyManager` API shape): `TelephonyManager.getAllCellInfo()` (poll) or `TelephonyCallback.CellInfoListener` (push, API 31+) require **both** `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` — the classifier's `CELLULAR_CHARACTERISTICS` branch already requires both, correctly, per its own comment's correction history (an earlier draft required fine location alone; this was fixed before the pinned commit). On API 29+, a plain `getAllCellInfo()` call without a paired `requestCellInfoUpdate()` may return a cached rather than live result — a fidelity caveat the classifier already states, not a permission-gating question this spec's adapter needs to resolve.

### 4.13 VPN detection

REPO FACT: `AndroidNetworkMonitor` already reads `NET_CAPABILITY_NOT_VPN` by name into `RawCapabilitiesSnapshot.rawCapabilityNames`, and the Phase 4 measurement-foundation slice (S1, already landed) computes `NetworkState.vpnPresent` from `TransportType.VPN in snapshot.transports` (REPO FACT, `NetworkStateMapper.kt`). No permission is required to detect VPN presence this way — `NetworkCapabilities`/transport reads require only `ACCESS_NETWORK_STATE`, already held. **This topic requires no new adapter work**; it is included in this audit for completeness since the task named it explicitly, but VPN detection is already implemented and does not go through a `PermissionAdapter` at all — it is an observation-tier fact from data `ACCESS_NETWORK_STATE` already unlocks, not a permission-gated capability.

### 4.14 Captive portal detection

REPO FACT: same situation as VPN detection — `NET_CAPABILITY_CAPTIVE_PORTAL` is already read by `AndroidNetworkMonitor` and `NetworkState.captivePortalReported` is already computed by the landed S1 slice. No permission beyond `ACCESS_NETWORK_STATE`. No `PermissionAdapter` involvement. Included for completeness only.

### 4.15 Validated network detection

REPO FACT: `NET_CAPABILITY_VALIDATED` is already read into `NetworkState.validated` (Phase 2/3A, unchanged). No permission beyond `ACCESS_NETWORK_STATE`. No `PermissionAdapter` involvement.

### 4.16 Metered network detection

REPO FACT: `NET_CAPABILITY_NOT_METERED` is already read into `NetworkState.metered` (inverted — REPO FACT, `AndroidNetworkMonitor.toSnapshot()`). VERIFIED FACT: `ConnectivityManager.isActiveNetworkMetered()` is an alternative, coarser API (active network only, not a specific `Network` object) that this repository does not use and this spec does not recommend adopting — the existing per-network-object `NetworkCapabilities` read is strictly more precise for a callback-driven monitor tracking a specific network, and switching would be an unrelated refactor this task does not authorize. No permission beyond `ACCESS_NETWORK_STATE`. No `PermissionAdapter` involvement.

### 4.17 Network binding

VERIFIED FACT (fetched this session, `developer.android.com/reference/android/net/Network` and `.../ConnectivityManager`): `Network.bindSocket(Socket)` (API 21), `Network.bindSocket(DatagramSocket)` (API 22), and `Network.bindSocket(FileDescriptor)` bind one specific socket to that `Network`, irrespective of any process-wide binding — the socket must not already be connected. `ConnectivityManager.bindProcessToNetwork(Network)` (API 23) binds the *entire current process*: every future socket not explicitly bound elsewhere, and every future hostname resolution, is confined to that network; if the bound network disconnects, those sockets and resolutions fail outright by design, rather than silently falling back to another network. **No permission is required for either binding mechanism** — both are gated by holding a valid `Network` object (itself obtained via a `NetworkCallback`, which requires only `ACCESS_NETWORK_STATE`), not by any additional permission. This topic is relevant to a future production `NetworkClient`'s design (Decision D3-2/D3-4 in the cross-cutting contract) but has no `PermissionAdapter` dimension — included here for completeness per the task's explicit list, not because it changes this spec's adapter design.

### 4.18 NetworkCallback lifecycle

VERIFIED FACT (fetched this session, current official reference): a `NetworkCallback` is registered via `requestNetwork(...)`, `registerNetworkCallback(...)`, or `registerDefaultNetworkCallback(...)`, and unregistered via `unregisterNetworkCallback(...)`. "A `NetworkCallback` should be registered at most once at any time. A `NetworkCallback` that has been unregistered can be registered again." REPO FACT: `AndroidNetworkMonitor` already follows this correctly — one callback instance created per `callbackFlow` collector, registered on flow start, unregistered in `awaitClose`. No permission beyond `ACCESS_NETWORK_STATE`. No `PermissionAdapter` involvement — included for completeness.

### 4.19 Callback registration limits

VERIFIED FACT (fetched this session, current official reference, `ConnectivityDiagnosticsManager` page, consistent with the general `ConnectivityManager` documentation): the platform limits outstanding requests to **100 per app, identified by UID**, shared across `registerNetworkCallback`/`requestNetwork` (all variants) and `ConnectivityDiagnosticsManager.registerConnectivityDiagnosticsCallback` — exceeding it throws `ConnectivityManager.TooManyRequestsException`. REPO FACT: `AndroidNetworkMonitor` registers exactly one callback at a time (a fresh one per `observe()` collection, unregistered before the next), nowhere near this limit. No permission dimension. Relevant to a future `PermissionAdapter` only in the sense that the adapter itself must not register its own `NetworkCallback` for any location-info read it might someday need (Section 4.11's `FLAG_INCLUDE_LOCATION_INFO` path) without unregistering it promptly — a design note carried into Section 6, not a currently-live risk given today's single-callback usage.

### 4.20 Android 12–17 behavior differences (consolidated)

REPO FACT + VERIFIED FACT, consolidating what is specifically permission/platform-integration relevant to this spec (the Phase 2 audit and the cross-cutting decision contract already cover the broader per-version matrix; this table adds nothing that contradicts either and narrows to this spec's scope):

| Version (API) | Change relevant to this spec |
|---|---|
| 12 (31) | Approximate-vs-precise location choice (Section 4.10) — the single most consequential item in this table. `TelephonyCallback` becomes the preferred push mechanism for cellular info (Section 4.12). `FLAG_INCLUDE_LOCATION_INFO` added (Section 4.11). |
| 13 (33) | `NEARBY_WIFI_DEVICES` (`neverForLocation`) introduced as a location-free alternative for *some* Wi-Fi APIs — but per the Phase 2 audit's own already-verified finding, it does not cover the specific Wi-Fi connection-info read AERIVA's classifier needs, and does not touch cellular at all. Does not change this spec's design. |
| 14 (34) | No permission-model change relevant to this spec's scope (foreground-service-type requirements are unrelated to permission *checking*, only to service *declaration*). |
| 15 (35) | No permission-model change relevant to this spec's scope. |
| 16 (36) | This repository's current `compileSdk`/`targetSdk`. No additional permission-model change found relevant to this spec. |
| 17 (37) | Per the cross-cutting decision contract's own already-verified correction (Section 2.3, X-2, of that document): Android 17's official text states a *plan* to deprecate `usesCleartextTraffic` and steer apps toward Network Security Configuration — it does not state a new default. Not itself a permission-checking change. `ACCESS_LOCAL_NETWORK` (new in 17, applies only to apps targeting 37 and to LAN destinations) is out of scope for this spec — AERIVA's measurement targets are not LAN destinations, and this repository targets 36, not 37. |

**LIMITATION, applying to this whole table**: this repository targets API 36 today (REPO FACT). Every 17-specific item above is forward-looking context for a future targetSdk migration (already flagged as an undecided item elsewhere in this repository's own documents), not a behavior this spec's adapter must handle today.

---

## 5. What "missing permission" vs. "unsupported capability" must mean, precisely

This distinction already exists at two different layers of this repository, for two different reasons, and a `PermissionAdapter` sits exactly at the seam between them:

- **`AerivaError`** (REPO FACT, `core:result`, unchanged): `PermissionRequired` and `Unsupported` are separate cases. This is the general-purpose, repository-wide result type for any operation, not specific to measurement.
- **`CapabilityClassification`** (REPO FACT, `MeasurementCapabilityClassifier`): `NotReliablyAvailable(reason)` is the single case that already covers *both* "the permission isn't held" and (hypothetically, though nothing in the current table exercises it) "no combination of permissions would make this work on this SDK level" — the classifier's own `reason` string is where the distinction currently lives, as prose, not as a structural type difference.

**PROPOSED DESIGN**: a `PermissionAdapter` must be able to report a third, distinct thing the classifier's current `CapabilityClassification` type does not yet structurally separate: *"the permission check itself could not be performed"* (for example, a permission name unrecognized by the running platform version, or a `PackageManager` query failure) versus *"the permission was checked and is not held."* This is not the same axis as `AerivaError.PermissionRequired` vs. `.Unsupported` (those describe an operation's outcome; this describes the adapter's own query result), and conflating the two would be a real design error — Section 6.2 gives this its own type rather than overloading either existing one.

**OPEN DECISION, explicitly not resolved here**: whether `MeasurementCapabilityClassifier`'s own `CapabilityClassification` type should eventually gain a structural (not just prose) distinction between "permission denied" and "platform cannot support this regardless of permissions" is a classifier-behavior change, which this task explicitly prohibits this document from making. This spec's `PermissionAdapter` output (Section 6) is deliberately richer than what the classifier's current `Set<String>` input can express, and Section 6.5 states exactly how the adapter's caller collapses that richer output down to the `Set<String>` shape the unmodified classifier still expects — the richer information is not lost, only not yet consumed by the classifier, which is a decision for whoever next revisits the classifier's own signature.

---

## 6. PermissionAdapter design

Contract sketches only, per this task's explicit "do NOT implement PermissionAdapter" instruction — no method body, no `Context` reference in compilable form, matching the cross-cutting decision contract's own established "sketch, not compiled" convention (its Section 7 and Section D5.11 use the identical device).

### 6.1 Placement

**PROPOSED DESIGN**: `PermissionAdapter` lives in `network:monitor`, alongside `MeasurementCapabilityClassifier` and `AndroidNetworkMonitor` — not in `core:model` (which must stay Android-free, Section 1 finding 4) and not in a new module (Section 9's rejected-alternatives reasoning, consistent with the architecture document's own Decision D1's precedent against creating a module with no evidence one is needed).

### 6.2 The adapter's own result type (Android-free)

```kotlin
// contract sketch, not compiled
/**
 * One permission's state, as the platform can currently answer it.
 * Deliberately not a Boolean -- see Section 5's distinction between
 * "not held" and "could not be checked," and Section 4.10's
 * approximate-location state, neither of which a Boolean can carry.
 */
sealed interface PermissionState {
    /** Held right now -- normal permission (always this, once declared)
     *  or runtime permission the user has actually granted. */
    data object Granted : PermissionState

    /** Not held right now. Denial and "never requested" are
     *  deliberately not distinguished here (Section 4.8) -- both mean
     *  the same thing to a caller deciding whether a capability is
     *  usable. */
    data object Denied : PermissionState

    /** ACCESS_FINE_LOCATION specifically, on a device where the user
     *  has granted only ACCESS_COARSE_LOCATION (Section 4.10). Distinct
     *  from Denied so a future caller that only needs coarse location
     *  is not forced through the same code path as a caller needing
     *  fine location -- today's MeasurementCapabilityClassifier table
     *  has no coarse-location-satisfied capability, so this state
     *  currently collapses to "not sufficient" wherever it is consumed
     *  (Section 6.5), but the adapter itself must not discard the
     *  distinction. */
    data object GrantedApproximateOnly : PermissionState

    /** The platform could not answer the query itself (Section 5) --
     *  never silently treated as Denied, since that would misreport a
     *  platform/query problem as a user decision. */
    data class CheckFailed(val reason: String) : PermissionState
}

/**
 * The Android-edge seam Decision D1-4 (cross-cutting contract) named.
 * No Android type appears in this interface's signature -- an
 * implementation holds a Context internally, but nothing here leaks
 * that type outward, preserving the same no-Android-import boundary
 * MeasurementCapabilityClassifier and core:model already keep (Section
 * 1, finding 4).
 */
interface PermissionAdapter {
    /**
     * @param permission a manifest permission string (for example
     *   "android.permission.ACCESS_FINE_LOCATION"). Never a
     *   MeasurementCapability -- this interface answers "is this
     *   specific Android permission held," not "is this AERIVA
     *   capability usable" (that composition happens at Section 6.5,
     *   above this interface, not inside it).
     *
     * Always re-checks the live platform state (Context.checkSelfPermission
     * or equivalent) -- never returns a cached/memoized value from an
     * earlier call, per Section 4.9's revocation finding. A caller that
     * wants to avoid repeated checks within one measurement attempt is
     * responsible for calling this once per attempt and holding the
     * result only for that attempt's duration, not across attempts.
     */
    fun check(permission: String): PermissionState

    /** The running device's Build.VERSION.SDK_INT, as a plain Int --
     *  the one piece of Android-derived data MeasurementCapabilityClassifier
     *  already accepts directly (its own sdkInt parameter), so this
     *  method exists on the same adapter rather than requiring a second
     *  seam for it. */
    fun currentSdkInt(): Int
}
```

### 6.3 Why `check(String)` and not `check(MeasurementCapability)`

**PROPOSED DESIGN, with explicit reasoning**: keeping `PermissionAdapter` capability-agnostic — it knows about Android permission strings and SDK levels, nothing about `MeasurementCapability` — preserves the same separation of concerns `MeasurementCapabilityClassifier`'s own KDoc already draws between "what the platform permits" and "what AERIVA should request" (Section 3.2 of the earlier architecture document, restated in the classifier's own comment). An adapter that took a `MeasurementCapability` and returned a classification would duplicate `MeasurementCapabilityClassifier`'s own per-capability `when` logic in a second place — exactly the "duplicated contract" this task's own quality rules (inherited from the prior slice's own instructions) prohibit. The composition belongs one layer up (Section 6.5).

### 6.4 Approximate-location correctness (the one subtle case)

**PROPOSED DESIGN**: an implementation's `check("android.permission.ACCESS_FINE_LOCATION")` must return `GrantedApproximateOnly`, not `Granted`, when `checkSelfPermission(ACCESS_FINE_LOCATION)` itself returns `PERMISSION_DENIED` but `checkSelfPermission(ACCESS_COARSE_LOCATION)` returns `PERMISSION_GRANTED` — i.e., the adapter must check *both* location permissions whenever asked about fine location specifically, not just the one named in the call, because Android's own API surface answers "do you have fine location" and "do you have coarse location" as two independent booleans with no single call that reports "the user chose approximate" directly. Getting this wrong in the obvious way — checking only `ACCESS_FINE_LOCATION` and returning bare `Denied` — would still produce the *correct* classifier outcome today (both `Denied` and `GrantedApproximateOnly` currently collapse to "not sufficient" for `WIFI_CHARACTERISTICS`/`CELLULAR_CHARACTERISTICS," Section 6.5), but would silently discard information a future capability (or a future diagnostic/logging need) might need to distinguish "user has never engaged with location at all" from "user deliberately chose the more private option." **This is why `GrantedApproximateOnly` is part of the adapter's contract now, even though nothing downstream consumes the distinction yet** — adding it later would be a breaking change to every caller's `when`, while including it now, unused, costs nothing (Kotlin's exhaustiveness check on a sealed type forces every caller to at least acknowledge the case, per Section 6.6).

### 6.5 Composition into the classifier's existing `Set<String>` input

**PROPOSED DESIGN**, deliberately outside `PermissionAdapter` itself (a separate, small pure function — Android-free, since it operates only on `PermissionState` values, not a `Context`):

```kotlin
// contract sketch, not compiled
/**
 * Maps this adapter's richer per-permission states down to the plain
 * Set<String> MeasurementCapabilityClassifier.classify() already
 * accepts -- the classifier's own signature is not changed by this
 * spec (explicit instruction). Granted and GrantedApproximateOnly for
 * ACCESS_COARSE_LOCATION would both count as "coarse location held" if
 * a future capability ever needed that; today, only ACCESS_FINE_LOCATION
 * is checked, so GrantedApproximateOnly for it is correctly excluded --
 * the classifier's own table has no case where approximate location is
 * sufficient (Section 4.10).
 */
fun buildGrantedPermissionSet(
    adapter: PermissionAdapter,
    permissionsToCheck: Set<String>
): Set<String> = permissionsToCheck.filter { permission ->
    adapter.check(permission) == PermissionState.Granted
}.toSet()
```

`CheckFailed` is deliberately excluded from the granted set (a query failure must never be silently treated as a grant) but is also deliberately not surfaced as an exception from this function — Section 6.7 explains why.

### 6.6 Exhaustiveness as the enforcement mechanism

**PROPOSED DESIGN**: `PermissionState` is sealed with no open/unknown case, following this repository's own established convention (`MeasurementFailure`, `CapabilityClassification`, `NetworkClientOutcome` are all the same shape — REPO FACT). A future addition to this type (for example, if Android ever introduces a third location tier) will fail to compile at `buildGrantedPermissionSet` and any other exhaustive `when` over it, the same discipline `MeasurementFailureTaxonomyTest` already proves for the failure taxonomy (REPO FACT, that test exists on the pinned branch).

### 6.7 Error handling: `CheckFailed` never becomes a fabricated grant or a crash

**PROPOSED DESIGN**: a `PermissionAdapter` implementation must never let a `PackageManager` query failure propagate as an uncaught exception out of `check()` — this would crash a measurement attempt over a platform-query problem, not a real permission or network condition, which is exactly the class of failure Decision D5-9 in the cross-cutting contract ("never throws") already establishes as the wrong shape for the measurement layer generally. `CheckFailed(reason)` is the designed outcome for this case; `reason` follows the same sanitization discipline `MeasurementFailure.Unclassified` already established (REPO FACT — exception class name only, never a raw message that might contain platform-internal detail). This spec does not invent a second convention where the existing one already fits.

### 6.8 What this spec does not design

**OPEN DECISION, explicitly deferred**: the actual runtime permission *request* flow (`ActivityResultContracts.RequestPermission`, rationale UI, `shouldShowRequestPermissionRationale`) is a UI-layer concern this repository has no UI module for yet (REPO FACT — `:app` has no launcher activity). `PermissionAdapter` as designed here only *reports* state; it does not request permissions or manage any request lifecycle. This is a deliberate scope boundary, not an oversight — requesting a permission belongs wherever AERIVA's eventual UI layer lives, once one exists, and that UI layer would call an activity-result API directly rather than through this adapter, which exists specifically for the Android-free measurement layer's read-only need.

---

## 7. Where PermissionAdapter's implementation would live (not built here)

**OPEN DECISION / forward note, not a design commitment**: an implementation would hold an Android `Context` (application context, matching `AndroidNetworkMonitor`'s own existing constructor pattern — REPO FACT, `context.applicationContext.getSystemService(...)`) and call `ContextCompat.checkSelfPermission(context, permission)` (VERIFIED FACT — the AndroidX-compat entry point, safe across `minSdk 26`, unlike the raw platform `Context.checkSelfPermission` which behaves identically on this SDK range but has no compelling reason to be preferred over the already-established AndroidX-compat convention). `androidx.core` (via `core-ktx` 1.18.0, transitively bringing in `androidx.core:core`, which contains `ContextCompat`) is already a version-pinned, in-use project dependency (Section 2) — adding it to `network:monitor` specifically is a normal one-line build-file change once an implementation exists, not an owner-gated dependency admission, and is not in the same family as `OD-7`'s OkHttp admission (which involves an unpinned version with no prior compatibility research). This paragraph is deliberately not code — implementing it is Section 3's explicit non-goal.

---

## 8. INTERNET placement and manifest-merge verification design

### 8.1 Placement (restates and grounds Decision D1-1, does not revise it)

**PROPOSED DESIGN, unchanged from the cross-cutting contract**: `INTERNET` is declared exactly once, in `network:monitor/src/main/AndroidManifest.xml`, in the same change that introduces the first production `NetworkClient` implementation — not in `:app`, not in a new module, not separately from that client. This spec adds the grounding the contract's own Decision D1 section did not have space to fully verify: **VERIFIED FACT**, Android's manifest-merger tool combines the app module's manifest with every library module's manifest (and every AAR dependency's manifest) into one merged manifest at build time — a permission declared in a library module's manifest becomes part of the final app's merged manifest automatically, with no separate declaration needed in `:app`'s own manifest (official manifest-merging documentation). This is exactly why Option B (declare in `network:monitor`) works at all, and this spec confirms the mechanism rather than assuming it.

### 8.2 The real gap: `:app` does not depend on `:network:monitor` yet

**REPO FACT, restated because it is the actual current blocker for *verification*, not just for placement**: manifest merging only pulls in a library module's manifest if `:app` actually depends on that library module (directly or transitively). Today, `:app`'s dependencies block is empty (Section 2). **This means declaring `INTERNET` in `network:monitor`'s manifest today would have no effect on any manifest `:app` actually produces** — the permission would exist in a manifest that never gets merged into anything installable, until `:app` gains a dependency on `:network:monitor` (Decision D1-5 in the cross-cutting contract already names this as a separate integration step; this spec confirms *why* that step is not optional, not just that it exists). **OPEN DECISION, not resolved by this spec**: exactly when `:app` gains that dependency is itself part of slice S3's scope, not this specification's.

### 8.3 Manifest-merge verification design

**PROPOSED DESIGN**, matching Decision D1-6's requirement ("A merged-manifest allowlist check... generated from the real merged manifest") with the mechanism this spec adds:

1. **Source of truth**: AGP's stable Variant API exposes the merged manifest directly, without relying on a guessed or version-dependent intermediate file path -- `SingleArtifact.MERGED_MANIFEST` (available since AGP 7.0, well below this repository's pinned 8.13.2), accessed via `androidComponents.onVariants { variant -> variant.artifacts.get(SingleArtifact.MERGED_MANIFEST) }` in the module's `build.gradle.kts` (VERIFIED FACT, AGP's `com.android.build.api.artifact` package/`Artifact`/`SingleArtifact` reference documentation). This is the file a CI check reads -- never a hand-maintained expectation of what merging *should* produce, which is exactly the failure mode Decision D1-6 exists to prevent, and never a guessed `build/intermediates/...` path, which is exactly the version-fragility this correction removes.
2. **What the check asserts**: the merged manifest for the `:app` release variant contains exactly the allow-listed `<uses-permission>` entries (today: none once `:app` has no dependency on a permission-declaring module; `ACCESS_NETWORK_STATE` and `INTERNET` once slice S3 lands, per Decision D1-1) **and exactly the allow-listed `<provider>` entries**, per Decision D1-6's actual stated scope (which names providers, not permissions alone) -- and no more of either, catching both an accidentally-added entry (from a new transitive dependency, per Decision D1's own security note about OkHttp's AndroidX Startup provider addition, which is exactly a `<provider>` entry, not a permission) and a silently-missing one.
3. **Ordering**: this check has no purpose until `:app` depends on `network:monitor` (Section 8.2) — it is designed here so slice S3 has a ready specification to implement against, not because it can run correctly before that dependency exists. **This spec does not add the check itself** (a CI/build-script change, out of this task's scope as a specification document) — Section 6's non-implementation boundary applies here identically.

### 8.4 Why this is a design question at all, and not just "add the line"

Restating Decision D1's own reasoning, now grounded in this session's manifest-merge verification (Section 8.1) rather than asserted: because `network:monitor` already correctly demonstrates the pattern (its own `ACCESS_NETWORK_STATE` declaration, REPO FACT, already works this way for the instrumented tests that exist), placing `INTERNET` the same way is not a new pattern to invent — it is applying an already-proven pattern to a permission that happens to need `:app` to actually consume the module for the effect to reach a real installable manifest, which `ACCESS_NETWORK_STATE` today does not yet need to prove either, since `network:monitor`'s own instrumented tests exercise its manifest directly without going through `:app` at all (REPO FACT — Gradle builds each module's own test APK from that module's own merged manifest, independent of `:app`).

---

## 9. Rejected alternatives (for this spec's own two design questions)

| Question | Alternative | Reason rejected |
|---|---|---|
| Where does `PermissionAdapter` live | `core:model` | Would violate the Android-free boundary Section 1 (finding 4) names as load-bearing; `core:model` has zero Android imports today by design and this spec does not become the change that breaks it. |
| Where does `PermissionAdapter` live | A new `core:permissions` module | No evidence this task or the cross-cutting contract identifies that a second module is needed. Decision D1-9 rejects a new module specifically for the `INTERNET` manifest declaration, not for `PermissionAdapter`'s placement (which had not been designed when D1-9 was written) -- this conclusion rests on the same absence-of-evidence reasoning as D1-9's, not on D1-9 itself having already settled this question. |
| `PermissionAdapter` result type | Plain `Boolean` | Cannot represent `GrantedApproximateOnly` (Section 4.10) or `CheckFailed` (Section 5) — both real, distinguishable platform states this spec's own audit surfaced. |
| `PermissionAdapter` result type | Reuse `CapabilityClassification` | Conflates "is this Android permission held" (adapter's job) with "is this AERIVA capability usable" (classifier's job, per capability) — exactly the duplicated-responsibility problem Section 6.3 avoids by keeping the adapter capability-agnostic. |
| Caching permission state | Snapshot once per app process/session | Directly contradicted by Section 4.9's revocation finding — a snapshot cannot reflect a mid-session auto-reset or a user's mid-session settings-app revocation. |
| INTERNET placement | `:app`'s own manifest | Same reasoning Decision D1-9 already gives: divorces the permission from the code (`network:monitor`) that actually needs it, and duplicates effort once `:app` does depend on `network:monitor` anyway. |

---

## 10. Open decisions surfaced by this spec (not resolved here)

1. Exactly when `:app` gains a dependency on `network:monitor` (Section 8.2) — named by the cross-cutting contract (Decision D1-5) as separate integration work, confirmed here as a real precondition for the manifest-merge check (Section 8.3) to mean anything, not newly decided.
2. Whether `MeasurementCapabilityClassifier`'s `CapabilityClassification` type should eventually gain a structural permission-denied-vs-platform-unsupported distinction (Section 5) — explicitly a classifier-behavior change this task prohibits this document from making.
3. OD-1 (INTERNET/outbound access) and, if `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE` are ever pursued, their own individually-justified requests per `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 10 — both pre-existing owner gates this spec does not revisit or attempt to satisfy.

Two items previously listed here are resolved as of the corrections below and no longer open: whether `androidx.core`/`ContextCompat` needs explicit admission (resolved -- Section 7, it is already a project dependency and adding it to `network:monitor` is normal build-file work, not an owner decision) and the exact AGP merged-manifest output mechanism (resolved -- Section 8.3, point 1, via `SingleArtifact.MERGED_MANIFEST`).

---

## 11. Explicit non-goals and what this document did not do

No `INTERNET` permission was added to any manifest. No manifest was modified. No `build.gradle.kts` or `libs.versions.toml` was modified — no dependency was added, including `androidx.core` (Section 7's open question is recorded, not resolved by adding anything). No `PermissionAdapter` class was written — Section 6 contains contract sketches only, none compiled, matching the cross-cutting contract's own established convention for this kind of document. `MeasurementCapabilityClassifier.kt` was read in full and not modified — its existing four-way classification, its permission constants, and its per-capability logic are all unchanged. `main` was not touched; it remains `e3f70a4a13e63b782c61601abadc2c36f65dbd73`. No build, test, or CI run was performed for this document — it is a specification, and no code changed for there to be anything to run.

---

## 12. Sources

### 12.1 Fetched and read this session (VERIFIED FACT basis)

| Topic | Source |
|---|---|
| Normal vs. runtime permission model, install-time grant for normal permissions | Android permission-groups/overview documentation, cross-checked against this repository's own existing `ACCESS_NETWORK_STATE` manifest comment |
| Permission auto-reset (Android 11 native; Android 6.0+ via Play services from December 2021; runtime permissions only; default-on for apps targeting API 30+) | Google's own auto-reset announcement and rollout documentation, fetched this session |
| Approximate-vs-precise location (Android 12+, must request `ACCESS_COARSE_LOCATION` alongside `ACCESS_FINE_LOCATION`, user can choose Approximate regardless of app intent, changeable anytime in system settings) | `developer.android.com/develop/sensors-and-location/location/permissions/runtime`, fetched this session |
| `ConnectivityManager.NetworkCallback` lifecycle (`registerNetworkCallback`/`requestNetwork`/`registerDefaultNetworkCallback`/`unregisterNetworkCallback`; "registered at most once at any time... can be registered again"); `FLAG_INCLUDE_LOCATION_INFO` (API 31) | `developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback`, fetched this session |
| 100-outstanding-request-per-UID limit shared across `registerNetworkCallback`/`requestNetwork`/`ConnectivityDiagnosticsManager`, `TooManyRequestsException` | `developer.android.com/reference/android/net/ConnectivityDiagnosticsManager`, fetched this session; cross-checked against a real-world crash report already on file from this repository's own Phase 2 audit |
| `Network.bindSocket(Socket)` (API 21), `Network.bindSocket(DatagramSocket)` (API 22), `ConnectivityManager.bindProcessToNetwork` (API 23), and their independence from each other | `developer.android.com/reference/android/net/Network` and the `.NET`-binding platform documentation, fetched this session |
| Manifest merging combines library and app manifests into one merged output | Android's build/manage-manifests documentation, already fetched and cited in the cross-cutting decision contract, re-confirmed as still governing this spec's Section 8 reasoning |

### 12.2 Reused from this repository's own prior, already-cited research (not re-fetched this session, spot-checked against the classifier's own current comments for consistency)

Wi-Fi connection-info API path (`NetworkCapabilities.getTransportInfo()` as `WifiInfo`, `WifiManager.getConnectionInfo()` deprecation), cellular info API permission requirements (`READ_PHONE_STATE` + `ACCESS_FINE_LOCATION`, API 29+ caching caveat), `NEARBY_WIFI_DEVICES` (API 33, `neverForLocation`, does not cover AERIVA's actual Wi-Fi or cellular needs), Android 17's cleartext-deprecation-is-a-plan-not-a-default-change correction — all previously fetched from `developer.android.com` and cited with specific page references in `PHASE_2_ANDROID_PLATFORM_AUDIT.md` and `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md`, both read in full this session as part of this spec's own preparation (Section 2).

### 12.3 Not verified this session

The official `Manifest.permission#INTERNET`/`ACCESS_WIFI_STATE` reference pages themselves (relied on secondary/cross-referenced confirmation plus this repository's own already-correct existing `ACCESS_NETWORK_STATE` precedent instead). The AGP merged-manifest mechanism (Section 8.3) and the `androidx.core`/`ContextCompat` availability question (Section 7) were previously listed here as unverified; both are now resolved -- see Section 12.1's `SingleArtifact.MERGED_MANIFEST` entry and Section 2's `libs.versions.toml` row, respectively.
