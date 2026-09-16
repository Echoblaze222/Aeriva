# AERIVA Phase 2 -- Android Platform Capability Audit

Research and engineering-planning document only. Nothing in this file
implements Phase 2, and nothing here modifies production code. Every
platform claim below is either cited to a specific current source
(researched fresh for this document, not recalled from training data)
or explicitly marked as an engineering recommendation rather than a
verified fact.

## 1. Repository state as actually found

(Verified by inspecting the checked-out repository directly. Not
assumed, and not carried over from any prior audit -- this document
was written from a clean read of the current repo.)

| | |
|---|---|
| compileSdk | 36 |
| minSdk | 26 |
| targetSdk | 36 |
| AGP | 8.13.2 |
| Kotlin | 2.3.21 |
| KSP | 2.3.11 |
| Gradle wrapper | 8.14.2 |
| Java (compile target) | 17 |
| Room | 2.8.4 |
| DataStore Preferences | 1.2.1 |
| androidx.security:security-crypto | 1.1.0 |
| WorkManager | **not a dependency yet** |
| Play Services Location / FusedLocationProviderClient | **not a dependency yet** |
| Modules | `app`, `core:common`, `core:model`, `core:result`, `core:logging`, `core:database`, `core:preferences`, `core:security`, `network:monitor` |

**Manifest / permissions currently declared, repo-wide:**
`network:monitor`'s manifest declares exactly one permission --
`android.permission.ACCESS_NETWORK_STATE` (a normal, install-time
permission; see Section 4). No other module declares any permission.
There is no `ACCESS_WIFI_STATE`, no location permission, no
`PACKAGE_USAGE_STATS`, no foreground-service permission, and no
`INTERNET` permission anywhere in the repository yet. `app`'s manifest
has no launcher activity -- it exists only so `:app` is buildable, not
because the app is installable/usable yet (its own manifest comment
says as much).

**Existing network/connectivity architecture:**
`network:monitor`'s `AndroidNetworkMonitor` wraps
`ConnectivityManager.registerDefaultNetworkCallback`, exposing a
`Flow<NetworkState>` built from `onAvailable` / `onCapabilitiesChanged`
/ `onLost` / `onUnavailable`, with debouncing on capability-change
bursts but zero debounce on loss events (a documented, deliberate
choice -- see that file's own comment). It reads standard
`NetworkCapabilities` transport and capability flags (INTERNET,
VALIDATED, NOT_METERED, transport type) via
`getNetworkCapabilities(network)`. It does **not** currently read
signal strength, Wi-Fi SSID/RSSI, or cellular signal info, and does not
perform any active measurement (no ping, no throughput probe). This is
**observation only** (see Section 6's OBSERVATION/MEASUREMENT/
ESTIMATION/PREDICTION/RECOMMENDATION distinction) -- there is no
measurement engine in the repository yet. It registers exactly one
callback for the lifetime of its `callbackFlow`, unregistered in
`awaitClose` -- nowhere near the 100-outstanding-request-per-app-UID
ceiling covered in Section 3a, but that ceiling becomes directly
relevant the moment a future measurement engine registers additional
per-network or per-request callbacks (e.g. one per active probe target)
rather than reusing a single default callback.

**Existing Phase 2/Phase 3 boundary already established in code:**
Several files in `core:model` and `core:database`
(`NetworkQuality.kt`, `DiagnosticsStatus.kt`,
`NetworkStateHistoryEntity.kt`) contain their own doc comments
describing a boundary where connectivity **observation/state** is
"Phase 2" and the active **measurement engine** (latency, jitter,
packet loss, throughput, quality scoring) is explicitly deferred to
"Phase 3", with `NetworkQuality` currently hard-coded to
`Unavailable` pending that engine. This predates this audit and this
audit did not invent it -- it is a fact about the existing codebase,
found by reading it, not a recommendation this document is making. It
matters directly for scoping this audit: this task's own instructions
list latency/jitter/packet-loss/throughput measurement as things to
cover, but the codebase's own prior decision already treats active
measurement as a later phase than the "Phase 2" this audit is titled
after. This document covers what Android *permits* for all of those
capabilities (the instructions' own scope), while flagging every place
that finding is more relevant to the codebase's own "Phase 3" than to
whatever "Phase 2" ends up meaning in implementation -- this document
does not attempt to resolve that naming question itself.

**Referenced-but-absent docs:** Code comments in several files (e.g.
`ConnectionStateRepository.kt`) reference `08_DEVELOPMENT_ROADMAP.md`
and `PHASE_0_PLATFORM_VALIDATION.md` as authoritative sources. Neither
file exists in this repository as checked out for this audit. This
audit could not cross-check against them and notes this as a real gap,
not a finding about their contents.

## 2. Research sources used

All fetched fresh for this document (not recalled from training data),
current as of this writing:

- developer.android.com: Wi-Fi permissions (`wifi-permissions`),
  background location access and permissions
  (`sensors-and-location/location/background`,
  `.../location/permissions`), VPN guide (`develop/connectivity/vpn`),
  foreground service launch/type/background-start-restriction guides
  (`develop/background-work/services/fgs/launch`,
  `.../fgs/service-types`, `.../fgs/restrictions-bg-start`,
  `about/versions/12/foreground-services`)
- Official `ConnectivityManager.registerNetworkCallback`/`requestNetwork`
  reference documentation (the 100-outstanding-request-per-app-UID limit
  and `TooManyRequestsException`, cross-checked against a real
  production crash report exhibiting that exact exception, not just the
  reference-doc wording alone)
- Official `TelephonyManager.getAllCellInfo()` reference documentation
  (permission requirement, and the Android-Q-and-later cached-vs-live
  behavior change)
- Play Console Help: "Permissions and APIs that Access Sensitive
  Information" (current and April-2026-preview versions), "Understanding
  location in the background permissions"
- AOSP source / commit history: `WifiManager.getConnectionInfo()`
  permission requirements
- Prior research already performed earlier in this same working session
  and reused here rather than re-fetched: `NetworkStatsManager` /
  `PACKAGE_USAGE_STATS` requirements; Android foreground-service-type
  requirements and the `dataSync` type's execution-time limit and Play
  policy posture (both confirmed against current official/Play sources
  at the time)
- Direct repository inspection (this session): source files, Gradle
  config, manifests, as cited throughout Section 1

Where a claim below is standard, long-stable Android platform behavior
(Doze/App Standby's existence since Android 6, `ConnectivityManager`
callback semantics) rather than something that plausibly changed
recently, it is stated without a fresh citation but flagged as such --
this document does not pretend every sentence required a new search to
produce.

## 3. Capability matrix

Columns: **Capability | API/Mechanism | Min API | Permission |
Background Support | Physical Device Required | Battery Cost | Data
Cost | Reliability | AERIVA Decision**

| Capability | API/Mechanism | Min API | Permission | Background | Physical Device Required | Battery Cost | Data Cost | Reliability | AERIVA Decision |
|---|---|---|---|---|---|---|---|---|---|
| Network available/lost/type | `ConnectivityManager.NetworkCallback` | 21 (24 for `registerDefaultNetworkCallback`) | `ACCESS_NETWORK_STATE` (normal) | Yes, callback-driven | No -- reliable on emulator | Negligible | None | High | **Already implemented** (`AndroidNetworkMonitor`) |
| Network capabilities (metered, validated, transport) | `NetworkCapabilities` via the same callback | 21+ | Same as above | Yes | No | Negligible | None | High | **Already implemented** |
| Network-callback/request registration ceiling | `ConnectivityManager.registerNetworkCallback`/`requestNetwork`/`ConnectivityDiagnosticsManager.registerConnectivityDiagnosticsCallback` share one pool | 21+ | N/A -- not permission-gated | N/A | No -- this is a fixed per-app-UID count, not device-dependent | N/A | N/A | High confidence, precisely documented | **Confirmed via official reference docs**: outstanding requests across all three APIs are capped at 100 per app UID; exceeding it throws `ConnectivityManager.TooManyRequestsException` (a real, observed production failure mode per a cross-checked crash report, not just reference-doc wording). `AndroidNetworkMonitor` today registers exactly one and unregisters it in `awaitClose`, nowhere near this ceiling -- relevant to *future* measurement-engine code that might register one callback per probe target instead of reusing a shared one |
| Current Wi-Fi SSID/BSSID | `WifiManager.getConnectionInfo()` / `WifiInfo` | 1, but restricted since 26, further since 29 | `ACCESS_FINE_LOCATION` **+** `ACCESS_WIFI_STATE` **+** location services enabled on-device (confirmed: AOSP commit gating `WifiInfo` fields behind the same location permission as scan results; developer.android.com `wifi-permissions`) | Foreground only without background location | **Yes** -- SSID/RSSI behavior is real-radio-dependent; emulator Wi-Fi is virtual/unreliable for this | Low (single read) | None | Medium -- user can deny, location can be off | Requires permission; defer until justified (see Section 10) |
| Wi-Fi scan results | `WifiManager.startScan()` / `SCAN_RESULTS_AVAILABLE_ACTION` | 1, throttled since 26/28 | Same location requirement as above, plus scans are rate-limited by the OS (long-standing platform behavior, not newly verified here) | Foreground only without background location | **Yes** | Low-medium | None | Medium -- OS-throttled scan frequency | Not currently planned; would need the same location justification as SSID access |
| Nearby Wi-Fi info without location (API 33+) | `NEARBY_WIFI_DEVICES` with `usesPermissionFlags="neverForLocation"` | 33 | `NEARBY_WIFI_DEVICES` (runtime, but not classified as location-sensitive when the `neverForLocation` flag is declared -- developer.android.com `wifi-permissions`) | Foreground | **Yes** | Low | None | Medium, and API-33+ only (minSdk here is 26) | Worth evaluating as the *lower-friction* alternative to fine location for whatever subset of Wi-Fi info doesn't need true fine-location semantics -- API-33+-only, so still needs a fallback path down to minSdk 26 |
| Cellular signal strength / cell info | `TelephonyManager.getAllCellInfo()` (poll) and `TelephonyCallback`/`PhoneStateListener` (`SignalStrength`, push) | `getAllCellInfo()` since 17; `TelephonyCallback` since 31 (replaces the deprecated `PhoneStateListener` path) | `ACCESS_FINE_LOCATION` -- confirmed this pass via `TelephonyManager.getAllCellInfo()`'s own official reference documentation (not `READ_PHONE_STATE`, contrary to an earlier, weaker guess in this audit's first draft); same location-permission pattern as Wi-Fi SSID access (Section 3's Wi-Fi rows) | Yes, callback-driven for `TelephonyCallback`; poll-based for `getAllCellInfo()` | **Yes** -- emulators report synthetic/absent cellular signal, and this is real-radio/OEM-dependent behavior no emulator reproduces | Low | None | Medium -- confirmed platform-level caveat: apps targeting Android 10 (API 29)+ no longer get a live refresh from `getAllCellInfo()` on every call -- they receive the last *cached* result, and must call `requestCellInfoUpdate()` for a fresh read, which is itself rate-limited and not guaranteed to return promptly. This means "cellular signal reading" is not a simple synchronous poll on current Android; it is push-preferred (`TelephonyCallback`) with poll as a fallback that may return stale data | Feasible with a correctly-scoped `ACCESS_FINE_LOCATION` request and awareness of the API-29+ caching behavior; still needs physical-device testing for OEM/radio-specific accuracy (Section 13) |
| DNS resolution timing | App-level timed `InetAddress`/socket resolution, not a dedicated Android API | N/A | `INTERNET` (normal) | Yes | No | Depends on frequency | Small, per lookup | Medium -- affected by OS/carrier DNS caching | Feasible as an ESTIMATION-tier signal (Section 6), not a platform-privileged measurement |
| Latency (ping/round-trip) | App-level active probe: raw ICMP needs root on stock Android, so realistically a TCP/UDP/HTTP round-trip timing via sockets | N/A (app-level) | `INTERNET` (normal) | Yes, but see background-execution rows below | **Yes**, for real network conditions | Depends heavily on frequency (Section 8) | Small per probe, adds up if frequent | Medium-high, if done carefully | Feasible; belongs to the codebase's own "Phase 3" measurement engine, not to what's built today |
| Jitter | Derived from repeated latency probes (statistical, not a distinct API) | N/A | Same as latency | Yes | Yes | Same driver as latency | Same driver as latency | Depends on sample count | Same as latency: a measurement-engine concern |
| Packet loss | App-level: requires sending many small probes and counting responses (UDP-based approaches are the practical option without root) | N/A | `INTERNET` | Yes | Yes | Higher than single-ping latency (needs a probe *train*) | Higher than single-ping latency | Medium -- distinguishing "packet lost" from "packet slow" is inherently imprecise without root/raw sockets | Feasible but the costliest of the four active-measurement types; needs the most conservative frequency policy |
| Download/upload throughput | App-level timed transfer against a known endpoint | N/A | `INTERNET` | Yes | Yes | High if done often (data-heavy) | **High** -- a real throughput test transfers real data | Medium-high | Highest-cost measurement by far; must be infrequent, user-consented, and Wi-Fi/unmetered-aware by default |
| Own app's data usage | `NetworkStatsManager.querySummaryForDevice`/per-UID APIs for the calling app's own UID | 23 | **None required for the app's own usage** -- confirmed via official Android developer documentation earlier this session: `PACKAGE_USAGE_STATS` (a special, Settings-granted access, not a runtime dialog) is only needed for *other apps'* or device-wide usage, not the calling app's own | Yes | No | Negligible | None (reads existing stats) | High for own-app data | **Directly usable now** for "how much data has AERIVA itself used" |
| Other apps' / device-wide data usage | Same API, broader query scope | 23 | `PACKAGE_USAGE_STATS` -- special access, user must manually enable in Settings, cannot be requested via a runtime permission dialog | Yes | No | Negligible | None | High | Not available without a manual Settings trip the user must be walked through; a meaningfully higher-friction ask than a runtime permission -- treat as its own, separately-justified decision, not bundled with the rest of "data usage monitoring" |
| Background periodic work | `WorkManager` | 14 (AndroidX artifact, works down to minSdk here) | None inherently (permissions depend on what the work *does*) | Yes, this is the currently-recommended mechanism | No | Low if intervals are conservative | Depends on what the work does | High -- this is the maintained, Doze-aware abstraction Android itself recommends over manual `AlarmManager`/services | **Not yet a dependency** (Section 1) -- needed before any "run useful monitoring without the user opening the app" goal (AERIVA goal G) can be built |
| Foreground service (general) | `Service` + `startForeground()` | 26+ for the notification requirement; **31+ (Android 12) forbids starting a FGS from the background at all, with a short, specific exception list** (confirmed this pass against `about/versions/12/foreground-services` and `restrictions-bg-start` -- violating this throws `ForegroundServiceStartNotAllowedException`); **34+ additionally requires declaring a foreground-service *type*** (confirmed this session against current official Android docs) | `FOREGROUND_SERVICE` + a type-specific permission (e.g. `FOREGROUND_SERVICE_DATA_SYNC`) | Yes, that is the point of a FGS | No | Medium-high while running | Depends on the work | High while alive, but OS can still kill/restrict it | Only justified for something genuinely continuous and user-visible; not a default answer for periodic monitoring (`WorkManager` is) |
| `connectedDevice`-type foreground service | Same, with `android:foregroundServiceType="connectedDevice"` / `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 34+ for the type declaration | `FOREGROUND_SERVICE_CONNECTED_DEVICE`, plus (per official current foreground-service-types documentation) at least one Bluetooth/NFC/USB/network-adjacent permission depending on which condition the service satisfies | Yes | No | Medium-high while running | Depends | High while alive | Confirmed this pass: this type's own official description is "interactions with external devices that require a Bluetooth, NFC, IR, USB, or network connection" -- notably closer to AERIVA's actual domain (network connectivity) than `dataSync`, which is the type an earlier draft of this audit implicitly reached for. If a genuinely continuous, user-visible FGS is ever justified for AERIVA (not the default background mechanism -- Section 6), `connectedDevice` is the type that actually matches the domain, not `dataSync` |
| `shortService`-type foreground service | `android:foregroundServiceType="shortService"` | 34+ | `FOREGROUND_SERVICE` (no additional type-specific runtime permission per current docs) | No -- bounded, brief | No | Low (bounded duration) | Depends | High for its short window | Confirmed this pass: this is the type `androidx.work.impl.foreground.SystemForegroundService` itself declares when a `WorkManager` worker needs to run as a foreground service on Android 14+ -- relevant context for Section 6's WorkManager discussion, not a mechanism AERIVA would declare directly |
| `dataSync`-type foreground service specifically | Same, with `FOREGROUND_SERVICE_DATA_SYNC` | 34+ requires the type; confirmed this session that Android 15+ caps `dataSync` FGS execution at roughly 6 hours and that current Play policy discourages using it for routine background sync in favor of `WorkManager` / user-initiated data transfer jobs | Same as above | Time-capped | No | High | Depends | Explicitly discouraged by current platform guidance for this project's likely use case | **Do not use** as the default background-monitoring mechanism; only a fit for a genuinely user-initiated, bounded transfer -- and per the `connectedDevice` row above, not even the best-fitting *type* for AERIVA's domain if a FGS is ever genuinely justified |
| Doze / App Standby | Platform-wide, automatic (stable since Android 6/API 23; App Standby buckets since API 28) | 23+ | N/A -- not permission-gated, it's a scheduler behavior | N/A -- it restricts background work, it doesn't grant it | **Yes** -- Doze enforcement is inconsistently reproducible on emulators | N/A (this *saves* battery) | N/A | High-impact on anything not routed through `WorkManager`/`AlarmManager`'s Doze-aware APIs | Design around it (Section 5), don't fight it |
| OEM battery-optimization lists (manufacturer-specific, e.g. aggressive Chinese-OEM background killers) | Not a single Android API -- OEM-specific `Settings` deep links, `ACTION_IGNORE_BATTERY_OPTIMIZATIONS` at most | Varies | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for the one *standard* whitelist path; anything beyond that is OEM-specific and not part of AOSP | N/A | **Yes, and specifically on real OEM hardware/ROMs**, not just "a physical device" generically | N/A | N/A | **Low and non-uniform** -- this is the single least-standardized area in this whole matrix | Do not assume any fix here generalizes across OEMs; treat as an ongoing, device-by-device risk, not a solvable-once problem |
| Background location | `ACCESS_BACKGROUND_LOCATION` (separate runtime request since API 29, requested *after* foreground location per current docs) | 29+ for the distinct permission | `ACCESS_BACKGROUND_LOCATION`, and **current Play Console policy requires a specific, reviewed justification** -- confirmed this session: approved core-use-cases are narrowly stated (e.g. constant location-sharing with other users, Geofencing API use), background location purely to *infer connectivity-by-location* is not obviously one of the stated qualifying cases, and Play is actively tightening this further (a "location button" minimum-scope model is being phased in through 2026-2027 per Play Console Help's own preview article) | By definition | **Yes** | Medium-high if polled | None directly | High friction: user consent dialog, OS reminder notification, Play policy review | **High policy risk for AERIVA's stated goal D** ("detecting better connectivity conditions/locations") as currently scoped; see Section 10 |
| VpnService | `android.net.VpnService` | 21+ | User must accept an OS-level VPN consent dialog; no manifest permission substitutes for that consent | Yes, while the VPN is active | **Yes** | Continuous cost while active (it's an always-on interception layer) | Can add overhead to *every* packet, not just probes | High capability, but high user-trust cost | **Not necessary for any current AERIVA goal** (Section 11) -- see the explicit reasoning below. Confirmed this pass, directly from developer.android.com's own VPN guide: Android permits **only one active `VpnService` per user/profile** -- "starting a new service automatically stops an existing service." If AERIVA ever did add a VPN, installing it would silently disconnect whatever VPN (a commercial VPN app, a corporate client) the user already had running, with no Android-level way to run alongside it in the same profile. This is an additional, concrete cost beyond the general trust-surface argument in Section 10, not a restatement of it |
| Binding process to a specific already-available network | `ConnectivityManager.bindProcessToNetwork()` / per-request binding via `requestNetwork()` | 21+ (23+ for the process-wide variant) | None beyond `ACCESS_NETWORK_STATE`/`CHANGE_NETWORK_STATE` as applicable | Yes | Preferable on real device (multiple simultaneous networks e.g. Wi-Fi+cellular are not guaranteed on an emulator) | Low | None | Medium -- depends on the device actually having two networks up at once | Real, legitimate capability for "let AERIVA *use* a specific already-connected network for its own probes" -- **not** the same as "make Android switch the user's default network" or "improve carrier signal", which are not things a normal app can do |

## 4. Android version considerations

- **minSdk 26 (Android 8.0):** already past the point where background
  *service* execution was first meaningfully restricted (Android 8
  introduced background-execution limits that motivated `WorkManager`'s
  existence in the first place -- long-standing, stable platform
  history, not independently re-verified this session). Nothing in
  this project's minSdk choice is unusually permissive or unusually
  strict relative to current common practice.
- **targetSdk 36:** puts this project on the *current* foreground-service-type
  enforcement and the *current* Play policy posture described throughout
  this document -- both are more restrictive than they were even two or
  three API levels ago. Any implementation plan should assume the
  strictest version of these rules, not an older, looser one found in
  an outdated tutorial.
- **API-level-gated capabilities relevant to this audit specifically:**
  `TelephonyCallback` (31+) vs. the older `PhoneStateListener` path;
  Android 12 (31+)'s outright ban on starting a foreground service from
  the background outside a short exception list (confirmed this pass);
  `NEARBY_WIFI_DEVICES` (33+); foreground-service *type* declarations,
  including the `connectedDevice` and `shortService` types (34+, both
  confirmed this pass and both more relevant to AERIVA's domain than
  the `dataSync` type this audit's first draft focused on -- Section
  3's foreground-service rows). Each of these needs either a
  minSdk-26-compatible fallback or an explicit "not supported below API
  X" decision -- this audit does not make that decision, it surfaces
  that the decision exists.

**Version-by-version rundown, Android 12 through current (16), for the
specific behaviors this audit found relevant** (each individually cited
above and in Section 3; collected here as the explicit "12, 13, 14, 15,
and newer" breakdown this task's instructions asked for):

| Android version (API) | What changed, relevant to this audit |
|---|---|
| 12 (31) | Apps can no longer start a foreground service from the background at all, outside a short, specific exception list (Section 3's foreground-service rows). `TelephonyCallback` replaces the deprecated `PhoneStateListener` path. |
| 13 (33) | `NEARBY_WIFI_DEVICES` introduced as a non-location-classified alternative for some Wi-Fi APIs, when declared with `neverForLocation` (Section 3). |
| 14 (34) | Foreground services must declare a type in the manifest and request that type's specific permission, or the system throws `ForegroundServiceTypeNotAllowedException`/`InvalidForegroundServiceTypeException`; `WorkManager`'s own foreground-execution path is affected by this too (Section 6). The `connectedDevice` and `shortService` types both appear at this level. |
| 15 (35) | The `dataSync` foreground-service type gains an approximately-6-hour execution cap (confirmed earlier this session); current Play policy steers routine background sync toward `WorkManager`/user-initiated data transfer instead of `dataSync` FGS use. |
| 16 (36, this project's compileSdk/targetSdk) | This audit did not find a 16-specific platform change beyond what's already listed above that materially affects AERIVA's stated goals; compileSdk/targetSdk 36 means this project is built against and enforces the *current* state of everything in this table, not an older, looser one. |

## 5. Permission requirements

Already itemized per-capability in Section 3's own column; the
project-wide summary:

- **Currently declared:** `ACCESS_NETWORK_STATE` only.
- **Will be needed soon, low-friction (normal/install-time or narrowly-scoped runtime):**
  `INTERNET`, `ACCESS_WIFI_STATE`.
- **Will be needed for specific, individually-justified features, higher-friction:**
  `ACCESS_FINE_LOCATION` (Wi-Fi SSID/scan), `NEARBY_WIFI_DEVICES` (33+
  alternative), foreground-service type permissions if a genuine FGS
  case emerges, `PACKAGE_USAGE_STATS` (only if device-wide/other-app
  data usage is ever pursued -- own-app usage needs nothing extra).
- **Should remain out of scope pending explicit, written justification
  per feature:** `ACCESS_BACKGROUND_LOCATION`, given the current Play
  policy posture documented in Section 3 and Section 10.

## 6. Background-execution strategy implications

`WorkManager` is the current, officially-recommended mechanism for
deferrable and periodic background work under Doze/App Standby, and it
is **not yet a dependency of this project** (Section 1). Any Phase 2/3
plan for "run useful monitoring without requiring the user to keep
opening the app" (AERIVA goal G) needs `WorkManager` added before that
goal is buildable at all -- this is a prerequisite, not an optional
enhancement.

`WorkManager`'s actual role, not just "add the dependency" (its real
operational constraints, relevant to designing around it rather than
just depending on it):

- Periodic work has a documented **minimum repeat interval of roughly
  15 minutes** (long-standing, stable `PeriodicWorkRequest` behavior,
  not independently re-verified this session but not newly asserted
  either) -- it is not a mechanism for anything needing tighter
  cadence than that; a design calling for more frequent checks needs a
  different justification, not a shorter `WorkManager` interval.
- It does not guarantee exact-time execution -- it is explicitly
  deferrable and Doze-aware, which is the entire reason it's the
  recommended mechanism (Section 6's own premise), but that same
  property means AERIVA cannot treat a `WorkManager`-scheduled check as
  "will run at time X," only as "will run eventually, respecting system
  constraints."
- On Android 14+, `WorkManager`'s own foreground-execution path
  (`SystemForegroundService`) is itself subject to the foreground-
  service-type rules covered in Section 3 -- it declares the
  `shortService` type for this purpose (confirmed this pass). This
  means adding `WorkManager` does not sidestep the Android 14+
  foreground-service rules entirely for expedited/urgent work; it
  already handles that compliance internally, but the constraint still
  exists and still bounds what "immediate" work can look like.

A `dataSync`-type foreground service is explicitly **not** the right
default mechanism for that same goal, per Section 3's foreground-service
rows -- both because of its execution-time cap on current Android
versions and because current Play policy steers routine background
sync toward `WorkManager` / user-initiated data transfer instead. A
foreground service should only enter the design for something genuinely
continuous and user-visible (e.g., an explicit "live monitoring" session
the user started and can see is running), not as the default background
mechanism -- and if that case ever does arise, Section 3's
`connectedDevice`-type row is the better-fitting type for AERIVA's own
domain, not `dataSync`.

## 7. Measurement-engine implications

This is where Section 1's Phase-2-vs-Phase-3 naming tension matters
most directly. Every active-measurement capability in Section 3
(latency, jitter, packet loss, throughput) is app-level work built on
top of plain sockets, not a privileged or specially-gated Android API
-- Android does not restrict an app's ability to open a socket and time
a round trip. What *does* need careful design is:

- **OBSERVATION vs. MEASUREMENT vs. ESTIMATION vs. PREDICTION vs.
  RECOMMENDATION**, per this task's own required distinction:
  - *Observation*: what `AndroidNetworkMonitor` already does today --
    reporting what `ConnectivityManager` says is true right now.
  - *Measurement*: an active probe with a directly-observed numeric
    result (a completed round-trip time, a completed transfer's actual
    throughput).
  - *Estimation*: inferring a value from indirect signals (e.g., using
    DNS-lookup timing as a rough proxy for path responsiveness without
    a dedicated latency probe).
  - *Prediction*: projecting forward from historical measurements
    (e.g., "connectivity here is usually poor in the evening").
  - *Recommendation*: an action suggested to the user, which must be
    traceable back to actual measurements/estimations it's grounded in,
    not presented with unearned certainty.
  - AERIVA must never present an estimate or prediction as a measured
    fact -- this is a requirement from this task's own instructions,
    and it is also the correct engineering position independent of that:
    conflating these categories in the data model or the UI would be a
    design defect, not just a communication issue.
- **Frequency, battery, and data cost are the actual constraint**, not
  platform permission (Section 8).

## 8. Data-usage monitoring implications

Section 3's `NetworkStatsManager` rows are the key finding here: reading
**AERIVA's own** historical data usage needs no special permission
beyond what's already implicitly available to any app (API 23+), and is
directly usable today with no new permission-request friction. Reading
*other apps'* or device-wide usage needs `PACKAGE_USAGE_STATS`, a
special access the user must grant manually in Settings -- meaningfully
higher friction than a runtime permission dialog, and worth treating as
a separate, later decision rather than bundling it into "add data-usage
monitoring" by default.

## 9. Battery/data-cost implications

Every active-measurement capability in Section 3 has a real, non-zero
cost, and the more expensive ones (packet-loss trains, throughput
tests) are the ones this task's own instructions specifically warn
against running aggressively. A concrete, engineering-level (not
platform-verified) recommendation: throughput tests should default to
opt-in/infrequent and Wi-Fi-or-unmetered-only; latency/jitter can run
more often since a single round-trip probe is cheap; packet-loss
measurement should sit between the two, both in frequency and in
requiring more justification for why a given check needs to run at
that moment. None of this is a platform requirement -- it's this
project's own responsible-use design, informed by what Section 3's cost
column actually shows.

## 10. Security/privacy implications

- Location permission (fine location, for Wi-Fi SSID/scan; background
  location, for any location-tied connectivity feature) is the single
  highest-friction, highest-scrutiny permission category in this whole
  audit, and current Play policy is **tightening**, not loosening, over
  the 2026-2027 window per the Play Console Help preview article cited
  in Section 2. AERIVA goal D ("detecting better connectivity
  conditions/locations") is the goal most directly implicated by this;
  it should be scoped as narrowly as possible (foreground/coarse first,
  background only if a specific, reviewable justification is written
  down) rather than assumed to be a straightforward permission ask.
- `PACKAGE_USAGE_STATS` (device-wide data usage) reveals which apps the
  user runs and how much data each consumes -- a real privacy-sensitive
  surface even though it isn't gated by a runtime-permission dialog the
  way location is; it should get the same "specifically justified, not
  bundled" treatment as background location, not less scrutiny just
  because the request mechanism looks less scary than a permission
  popup.
- `VpnService` would put AERIVA in a position to observe *all* device
  traffic, not just its own probes -- a materially larger trust/privacy
  surface than anything else in this matrix. Section 11 covers why
  nothing currently on AERIVA's goal list actually requires it.

## 11. Architecture implications

- Add `WorkManager` as a dependency before attempting AERIVA goal G;
  nothing else in Section 1's current dependency list covers that need.
- Do not add a `dataSync` foreground service as the default background
  mechanism (Section 6).
- **VpnService is not necessary for any current AERIVA goal (A through
  I), and should not be added speculatively.** Reasoning, addressing
  this task's own explicit instruction to investigate this rather than
  assume either answer:
  - Goals A-C (network-change detection, connectivity-quality
    measurement, data-usage monitoring) are all achievable via
    `ConnectivityManager` callbacks (already built), app-level active
    probes (Section 7), and `NetworkStatsManager` (Section 8) -- none of
    which need traffic interception.
  - Goal D (location-based connectivity intelligence) is a location-
    permission question (Section 10), not a VPN question.
  - Goal E (helping users choose a better connection) can be built on
    `ConnectivityManager.requestNetwork()`/`bindProcessToNetwork()`
    (Section 3's last row) for AERIVA's *own* traffic, without needing
    to intercept or redirect the *device's* traffic, which is what a
    VPN is actually for.
  - Goal F (gaming connectivity analysis) is a latency/jitter/packet-loss
    measurement question against AERIVA's own probes, not a need to
    inspect the traffic of other apps/games.
  - Goal I (future community measurements) is about *aggregating
    AERIVA's own measurements* across users, not about capturing
    traffic from other apps on-device.
  - A VPN would be the right tool only for a goal this project does not
    currently have: passively analyzing *other apps'* traffic on the
    device. Nothing in AERIVA's stated goals asks for that, so this
    audit's answer is not "VpnService is theoretically powerful" (true
    but beside the point, per this task's own instruction not to
    recommend it just because it could theoretically help) -- it is
    "no current goal requires it."
  - A further, concrete cost beyond "no goal needs it": confirmed this
    pass from Android's own VPN guide, only one `VpnService` can be
    active per user/profile at a time, and starting a new one silently
    stops whatever VPN the user already had running. Adding a VPN to
    AERIVA would not just be unnecessary scope -- it would actively
    conflict with any VPN the user already relies on.
- Network-selection capability is real but narrow (Section 3's last
  row): AERIVA can direct *its own* traffic onto a specific already-up
  network when more than one is simultaneously available. It cannot
  make Android switch the device's default network, cannot force a
  carrier tower handoff, and cannot strengthen signal -- this task's own
  explicit prohibitions are all confirmed, not just assumed, by how
  `ConnectivityManager` actually works.

## 12. Known limitations

- This audit could not cross-check against `08_DEVELOPMENT_ROADMAP.md`
  or `PHASE_0_PLATFORM_VALIDATION.md` -- both referenced by existing
  code comments, neither present in this checkout (Section 1).
- Cellular `TelephonyManager`/`getAllCellInfo()`/`TelephonyCallback`
  *permission* requirements are now verified this pass (Section 3:
  `ACCESS_FINE_LOCATION`, plus the Android-10+ caching caveat) --
  what remains unverified is real-device *behavior* (actual signal
  values, OEM/radio-specific quirks, real-world `requestCellInfoUpdate()`
  rate-limiting), which is a physical-device-testing question, not a
  research question this audit could resolve by reading documentation.
  Listed again in Section 13 under that distinction.
- OEM-specific battery-optimization behavior (Section 3) is, by its own
  nature, not something a single audit pass can enumerate exhaustively
  -- it is the one area here that stays an ongoing risk rather than a
  one-time finding.

## 13. Known unknowns requiring physical-device testing

None of the following were tested this session -- this audit is
research and repository inspection only, and this section is an honest
list of what remains unverified, not a claim that testing occurred:

- Real Wi-Fi SSID/RSSI/scan behavior, since emulator Wi-Fi is virtual
  and does not exercise the same code paths as a real radio.
- Real cellular signal-strength/`CellInfo` *values* and OEM/radio-specific
  reporting quirks, and real-world `requestCellInfoUpdate()` rate-limiting
  behavior -- the *permission* requirement itself is now resolved
  (Section 3, Section 12), this is specifically about behavior no amount
  of documentation research can substitute for actual hardware.
- Real Doze/App Standby enforcement timing -- emulator behavior here is
  known to be inconsistent with real devices (a long-standing,
  widely-documented characteristic of the Android emulator, not
  independently re-verified this session, but not newly asserted
  either).
- Real OEM battery-optimization behavior on at least one aggressively-
  restrictive ROM (commonly-cited examples include several Chinese-OEM
  skins), since AOSP-level battery optimization APIs do not capture
  OEM-specific killing behavior.
- Real background-location reminder-notification and Play Console
  review behavior, if that path is ever pursued.
- Real multi-network-simultaneously-available scenarios (Wi-Fi +
  cellular both up) for `bindProcessToNetwork()`, since this is not
  reliably reproducible on an emulator with a single virtual network.

## 14. Explicit Phase 2 exit criteria

Stated as criteria this audit recommends, not as claims that they are
already met. Grouped under the explicit categories this task's
instructions named, so each is visibly addressed rather than folded
silently into a general list:

**API/platform verification**

1. This document's own Section 1 finding -- that the existing codebase
   already treats "connectivity observation" and "active measurement
   engine" as two different phases -- is either explicitly reconciled
   with whatever "Phase 2" comes to mean in implementation, or
   explicitly superseded in writing. This audit does not resolve that
   naming question; it should not silently stay unresolved either.

**Permission behavior**

2. A written, specific justification exists for any location permission
   request before it is added to the manifest -- not a placeholder
   comment, an actual answer to "which Play-policy-approved use case
   does this serve."
3. A decision is made and recorded on whether `PACKAGE_USAGE_STATS`
   (device-wide usage) is in scope at all, separate from and later than
   own-app usage (which needs no such decision to proceed).

**Network transition behavior**

4. `AndroidNetworkMonitor`'s existing debounce behavior (zero debounce
   on loss, `debounceMillis` on capability-change bursts -- Section 1)
   is re-validated against whatever new event types a measurement
   engine introduces, so a burst of measurement-triggered events doesn't
   silently change the loss-event latency guarantee that debounce
   behavior currently protects.

**Measurement correctness**

5. The OBSERVATION/MEASUREMENT/ESTIMATION/PREDICTION/RECOMMENDATION
   distinction (Section 7) is reflected in the actual data model for
   whatever measurement-engine work follows this audit, not just in
   this document.

**Battery/data budget**

6. The frequency tiers this audit recommends as engineering judgment,
   not platform fact (Section 9: latency/jitter more frequent,
   packet-loss and throughput progressively more conservative) are
   translated into actual, written numeric budgets before
   implementation, not left as this document's qualitative language.

**Background behavior**

7. `WorkManager` is added as a dependency and at least one real,
   scheduled unit of background work runs successfully under Doze on a
   physical device (not just compiles).

**Physical-device testing**

8. At least the highest-risk findings in Section 13 (real Wi-Fi
   behavior, real cellular `CellInfo` values, real Doze behavior, real
   OEM battery-optimization behavior on one concretely-named device/ROM)
   have been tested on physical hardware, with results recorded, before
   any measurement-engine or background-monitoring feature building on
   them is considered validated.

**Emulator testing**

9. Whatever subset of the measurement engine genuinely does not depend
   on real-radio behavior (e.g. the OBSERVATION/MEASUREMENT data-model
   distinction itself, socket-level latency-probe logic against a known
   test endpoint) has JVM-unit or emulator-level test coverage, so
   physical-device testing (item 8) is reserved for what actually needs
   it rather than becoming the only validation this project has at all.

**Failure/offline behavior**

10. The measurement engine's behavior when a probe target is
    unreachable, when the device is fully offline, and when a network
    transition happens mid-measurement is explicitly designed and
    tested -- not just the happy path where every probe succeeds.

**Security/privacy behavior**

11. Every permission this audit flags as "individually justified, not
    bundled" (location above all, Section 10) has that justification
    written down per-feature before the corresponding manifest entry is
    added, and the same discipline applies to `PACKAGE_USAGE_STATS` if
    it is ever pursued (item 3).

## Executive conclusion

Nothing AERIVA's stated goals (A through I) require is platform-
impossible for a normal third-party Android app. The real constraints
are not "Android forbids this" but "Android gates this behind a
permission/policy cost that must be individually justified"
(location, above all) or "this needs a dependency that isn't in the
project yet" (`WorkManager`). No current goal justifies `VpnService`.
No current goal justifies a `dataSync` foreground service as the
default background mechanism. The existing `AndroidNetworkMonitor`
architecture (callback-driven, `Flow`-based, debounced) is a sound
foundation to build the measurement-engine and background-execution
work on top of -- this audit found no reason to recommend rearchitecting
it, and this task's own instructions explicitly disallow doing so
"without evidence." None was found.
