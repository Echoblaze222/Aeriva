# AERIVA Phase 3B -- Android Measurement Implementation Contract

Precise, prescriptive contract for whoever implements Android
measurement integration next. Converts the validated Android platform
research (`PHASE_2_ANDROID_PLATFORM_AUDIT.md`,
`PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md`) into a form a
future engineer can follow without re-deriving it, while independently
re-verifying every load-bearing claim against current official
documentation rather than assuming the prior report is still correct.
No networking implementation, no manifest change, no `WorkManager`, no
foreground service, no Supabase, no UI, no VPN, and no background
measurement are added by this document.

## 0. Repository state re-verified this pass

- `main` HEAD: `e3f70a4a13e63b782c61601abadc2c36f65dbd73` (unchanged
  since the prior Phase 3B report).
- Branched from `phase-3b-android-measurement` at commit
  `fc004b61bfb28829f0b231b800ae365784bf419f` -- the exact commit this
  task names as prior work, confirmed present in this checkout's history
  before any new commit was made.
- `network:monitor`'s manifest still declares only
  `ACCESS_NETWORK_STATE`. `INTERNET` is still not declared. Neither is
  touched by this document.
- `core:model`'s `measurement` package (Phase 3A) is unchanged.

## 1. Fresh research performed this pass

Independently re-fetched, current as of this writing (September 2026),
specifically to avoid trusting the prior capability report on the
strength of it having existed:

- `ConnectivityManager.registerNetworkCallback`/`requestNetwork`
  reference docs and the 100-outstanding-requests-per-UID limit --
  re-confirmed, unchanged from the prior report's own citations.
- `TelephonyCallback.CellInfoListener` reference documentation --
  re-confirmed: requires both `READ_PHONE_STATE` and
  `ACCESS_FINE_LOCATION`. Unchanged from the prior report's own Section
  3 finding.
- `ConnectivityDiagnosticsManager`/`registerConnectivityDiagnosticsCallback`
  reference documentation -- re-confirmed: eligibility restricted to
  carrier apps, active VPNs, and Wi-Fi Suggesters; callbacks from any
  other app are silently never invoked. Unchanged from the prior
  report's own finding.
- Foreground-service-type documentation
  (`develop/background-work/services/fgs/service-types`,
  `.../fgs/changes`, `about/versions/15/behavior-changes-15`,
  `about/versions/16/behavior-changes-16`,
  `about/versions/16/behavior-changes-all`) -- re-confirmed the
  Android 15 `dataSync`/`mediaProcessing` six-hour cap and the Android
  16 extension of background-job runtime quotas to jobs started from a
  foreground service (including `WorkManager` jobs).
- `LinkProperties` reference documentation (`getDnsServers()`,
  `isPrivateDnsActive()`, `getPrivateDnsServerName()`) -- **newly
  researched this pass**, not covered in the prior capability report.
  See Section 3 (DNS) and Section 9 for what this adds.
- `PeriodicWorkRequest` reference documentation -- re-confirmed the
  15-minute minimum periodic interval
  (`MIN_PERIODIC_INTERVAL_MILLIS`), unchanged.
- **Android 16 Local Network Protections (LNP)** -- **newly researched
  this pass**, not covered in either prior document. See Section 8.
- `develop/connectivity/network-ops/reading-network-state` (current
  official guide) -- confirms the `NetworkCapabilities`/`LinkProperties`
  observation-vs-measurement distinction this contract's Task 2 states
  formally (Section 2 below).

### Corrections/additions found this pass, beyond what the prior report already stated

1. **Android 16 Local Network Protections is a genuinely new
   consideration** (Section 8) -- gates local-network socket traffic
   (broadcast/multicast/LAN addresses) behind a new runtime permission,
   phased in 25Q2-26Q2. **Does not affect any capability in this
   contract's Section 3 table**, because none of AERIVA's measurement
   probes (capabilities 1-4, 9-10) target local-network addresses --
   they target remote, internet-routable endpoints. Recorded here
   because this task's own instructions require identifying Android
   16+ changes and not overstating (or, equally, silently omitting)
   what was found -- omitting a real but inapplicable finding would be
   as much a research gap as inventing an applicable one.
2. **`LinkProperties.getDnsServers()`/`isPrivateDnsActive()`/
   `getPrivateDnsServerName()`** are a real, no-extra-permission
   observation of *which* DNS resolver is configured and whether
   private DNS (DoH/DoT) is active -- genuinely useful context data for
   a future DNS-responsiveness feature, and genuinely distinct from
   *measuring* that resolver's responsiveness (Section 2, Section 3 row
   9). Not mentioned in either prior document.
3. **A concrete internal inconsistency was found in the existing
   `MeasurementCapabilityClassifier` code itself, not in the prior
   report's prose**: `DNS_RESPONSIVENESS` classified as `Supported` --
   the same tier as latency/jitter/HTTPS reachability -- while both the
   capability report's own table (row 9) and this task's own explicit
   instruction ("Do not label an estimate as a direct measurement")
   require it to be `Estimated`. This is not a new platform-documentation
   finding; it is a defect this review's re-inspection of the actual
   code (not just the prose) surfaced. Fixed -- see Section 10 and the
   commit accompanying this document.
4. Everything else re-checked this pass (the 100-request ceiling,
   `dataSync`'s six-hour cap, Android 14's foreground-service-type
   requirement, the 15-minute `WorkManager` floor) matches the prior
   report. Stated as independently re-confirmed, not carried over on
   trust.

## 2. Task 2 -- Observation vs. measurement

Stated explicitly and generally, per this task's requirement not to
imply `ConnectivityManager` itself measures anything it does not
document measuring:

- **`ConnectivityManager`/`NetworkCapabilities`/`LinkProperties`
  together answer "what does the platform currently believe about this
  network's properties" -- observation, not measurement.** Current
  official documentation
  (`develop/connectivity/network-ops/reading-network-state`) describes
  `NetworkCapabilities` as reporting "whether the network is capable of
  sending MMS, is behind a captive portal, or is metered" and
  `LinkProperties` as reporting "the list of DNS servers, local IP
  addresses, and network routes" -- all platform-reported facts about
  the current network's configuration, none of them a live-performed
  probe. Nowhere in current official documentation for either class is
  there a method that performs or returns a latency, jitter,
  packet-loss, or throughput result. This contract does not, and the
  prior capability report did not, claim otherwise.
- **`TelephonyManager`/`TelephonyCallback`** similarly report
  platform-observed state (signal strength as the platform currently
  reports it, cell identity) -- not an AERIVA-performed measurement.
  Real radio behavior underlying that reported state is still
  real-device/OEM-dependent (Section 6), but the *API itself* is
  observation, matching this contract's Section 3 table.
- **`ConnectivityDiagnosticsManager`** is closer to measurement in
  spirit (it reports connectivity-check results and data-stall
  suspicion), but Section 1's re-confirmed finding means this is moot
  for AERIVA specifically -- its callbacks are never invoked for an app
  that isn't a carrier app, active VPN, or Wi-Fi Suggester.
- **Latency, jitter, packet loss, throughput, DNS timing, and HTTPS
  reachability (capabilities 1-4, 9-10) are the only entries in Section
  3's table that are actual AERIVA-performed measurements** -- app-level
  socket/HTTP operations, not a platform-provided reading. This is the
  single clearest line this task's Task 2 asks for: everything platform-
  observed sits on one side of it; everything AERIVA actively probes
  sits on the other, and the two must never be presented as
  equivalent-confidence results.

## 3. Task 1 -- Platform capability contract

For every intended measurement capability. "Reliability limitations"
states what genuinely cannot be known even with the permission granted
and the API called correctly -- not what a bug would cause.

| Capability | Android API / source | Required permission | Runtime restriction | Min SDK considerations | What can be observed | What cannot be observed | Reliability limitations | Physical device required? | Emulator testing meaningful? |
|---|---|---|---|---|---|---|---|---|---|
| **Latency** | App-level socket/`HttpURLConnection` round-trip timing | `INTERNET` (normal, not yet declared) | None -- install-time only | None; works at minSdk 26 | A completed round-trip's duration to a specific chosen endpoint | Latency to any endpoint not actually probed; "the network's" latency in general | Result is endpoint-specific and method-specific (Section 2's own point); a single sample is not representative | Yes, for a real-world-meaningful number (Section 6) | Partially -- mechanism yes, real-world value no |
| **Jitter** | Derived (statistical) from repeated latency samples -- no distinct API | Same as latency | None | None | Variance/spread across a series of latency samples already taken | Anything beyond what the underlying latency series already captured | Meaningless below a minimum sample count (Section 4's own point); must not be computed from too few samples | Yes, for a meaningful real-world value | Partially, same as latency |
| **Packet loss** | App-level UDP probe train (send N, count responses within a timeout) -- no distinct API | Same as latency | None | None | The fraction of a specific probe train that received a response within its own timeout | Whether an unanswered probe was actually dropped in transit vs. merely delayed past the timeout, without root/raw sockets | Cannot cleanly distinguish "lost" from "arrived after the deadline" on stock Android -- see Task 6 discipline below | Yes | Partially -- mechanism yes, real loss behavior no |
| **Throughput** | App-level timed transfer of a known payload against a controlled endpoint | Same as latency | None (but see metered-network note below) | None | Actual transfer rate achieved for that specific payload/endpoint/moment | A device's or network's throughput ceiling in general; a single transfer says nothing about sustained capacity | Highest cost of any capability here (real data moved); small-payload runs are dominated by connection-setup overhead, not link speed | Yes, for a real-world-meaningful number | Partially |
| **Network stability** | `ConnectivityManager.registerDefaultNetworkCallback` (already implemented) + derived analysis over time | `ACCESS_NETWORK_STATE` (already declared) | None | Present since API 24 (`registerDefaultNetworkCallback`); minSdk 26 already exceeds this | Frequency/pattern of platform-reported availability and capability changes over an observation window | Root cause of instability (radio, OEM, congestion) | A derived signal, inheriting the confidence of whatever it's derived from (Section 2) | No | Yes -- this is pure callback-driven observation, already exercised by `AndroidNetworkMonitorInstrumentedTest` |
| **Network transitions** | Same callback (`onAvailable`/`onLost`/`onCapabilitiesChanged`), already implemented | Same as above | None | Same as above | Individual transition events as the platform reports them, already debounced (loss: 0ms; capability bursts: configurable) | Transitions the platform itself does not surface (e.g. sub-radio-level handoffs) | None beyond "reports what the platform reports" | No | Yes |
| **Wi-Fi characteristics** (SSID/BSSID/RSSI) | `ConnectivityManager.registerNetworkCallback(...)` with `NetworkCallback.FLAG_INCLUDE_LOCATION_INFO`, reading `NetworkCapabilities.getTransportInfo()` as `WifiInfo` (current recommended path; `WifiManager.getConnectionInfo()` is deprecated since API 31 -- corrected in `PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md`) | `ACCESS_FINE_LOCATION` at every currently-supported API level (current official Wi-Fi-permissions docs), plus device location services enabled | Foreground-only without additionally requesting background location (not planned) | Available since API 1, restricted progressively since 26/29; current recommended path needs API 31+ for `FLAG_INCLUDE_LOCATION_INFO` | SSID/BSSID/RSSI for the currently-connected network, when granted | Scan results for networks not currently connected (needs the separate, more-throttled `startScan()` path, not designed here) | Real SSID/RSSI behavior is real-radio-dependent; user can deny permission or disable location entirely | **Yes, specifically** -- emulator Wi-Fi is virtual | No, not meaningfully |
| **Cellular characteristics** (signal strength/cell info) | `TelephonyCallback.CellInfoListener` (API 31+, push-preferred) or `TelephonyManager.getAllCellInfo()` (poll, API 29+ may return a cached result without `requestCellInfoUpdate()`) | **Both** `READ_PHONE_STATE` **and** `ACCESS_FINE_LOCATION` (Section 1 correction 3 in the prior report, re-confirmed this pass) | Callback-driven (push) or explicit poll; poll may be rate-limited and stale on API 29+ | `TelephonyCallback` needs 31+; `getAllCellInfo()` works from lower API levels but this project's minSdk (26) already exceeds `getAllCellInfo()`'s own floor | Currently-visible cell(s) and their platform-reported signal strength, when granted | True signal quality independent of OEM/radio reporting quirks; carrier-internal network state | Real values are real-device/OEM/radio-dependent in ways no documentation substitutes for | **Yes, specifically** -- emulators report synthetic/absent cellular signal | No, not meaningfully |
| **DNS responsiveness** | App-level timed `InetAddress`/socket-level resolution; `LinkProperties.getDnsServers()`/`isPrivateDnsActive()` for which resolver is configured (Section 1 finding 2) | `INTERNET` for the timed lookup; nothing extra for `LinkProperties` (part of the already-declared `ACCESS_NETWORK_STATE` surface) | None | None | Timing of one specific resolution attempt; which resolver/whether private DNS is active | The resolver's true live responsiveness, independent of OS/carrier/resolver caching | Confounded by caching at multiple layers -- Section 2's ESTIMATION-tier classification, not MEASUREMENT | Not required for the mechanism; real carrier-cache behavior is a real-device nuance worth a spot check (Section 6) | Partially -- mechanism yes, real caching behavior no |
| **HTTPS reachability** | Timed HTTPS request/response against a known, controlled endpoint | `INTERNET` | None | None | Whether a specific endpoint responded, its HTTP status, and the round-trip time, for that one attempt | General "internet is reachable" beyond the one endpoint actually probed | An HTTP 5xx from a reachable server is not the same failure mode as an unreachable server or a DNS failure -- Task 9/HTTPS discipline (Section 3-of-the-hardening-analog below) applies to whoever implements this | Not required for the mechanism | Yes, for the mechanism; real carrier/proxy interception behavior is a real-device nuance |

**Metered-network note (throughput row):** `NetworkCapabilities.NET_CAPABILITY_NOT_METERED`
is already read by the existing `AndroidNetworkMonitor`/`NetworkStateMapper`
(`isNotMetered`, `RawCapabilitiesSnapshot`) -- a future throughput
provider should consult the already-exposed `NetworkState.metered` field
rather than querying `NetworkCapabilities` a second time, per this
codebase's existing single-source-of-truth convention.

## 4. Task 3 -- Permission contract

| Permission | Why required | Exact API requiring it | Dangerous/runtime? | Avoidable? | Graceful degradation without it |
|---|---|---|---|---|---|
| `INTERNET` | Opens any socket/HTTP connection | Underlies capabilities 1-4, 9-10 (Section 3) | No -- normal, install-time, no user dialog | No -- there is no active-measurement capability that doesn't need it | Without it, capabilities 1-4 and 9-10 are entirely unavailable (compile-time/manifest-level, not a runtime denial) -- app should not attempt any active probe and should surface this as a build/config issue, not a user-facing permission-denied state, since the user is never asked for this one |
| `ACCESS_NETWORK_STATE` | Read connectivity state / register network callbacks | Already used by `AndroidNetworkMonitor` | No -- normal, install-time | No | N/A -- already declared and working |
| `ACCESS_FINE_LOCATION` | Required by the platform for Wi-Fi `WifiInfo` access and as half of the cellular-info requirement, because both can reveal the user's location (location-bound Wi-Fi networks; cell-tower-based positioning) | `ConnectivityManager.registerNetworkCallback(...)` with `FLAG_INCLUDE_LOCATION_INFO` (current path; `WifiManager.getConnectionInfo()` is deprecated since API 31 -- see fact-check); `TelephonyCallback.CellInfoListener`/`TelephonyManager.getAllCellInfo()` (jointly with `READ_PHONE_STATE`) | **Yes** -- dangerous/runtime, highest-scrutiny category in this whole contract | Yes -- capabilities 7-8 (Section 3) can simply not be implemented; nothing else in this contract depends on it | Capabilities 7-8 degrade to `NotReliablyAvailable` (this document's own classifier already returns exactly this when the permission is absent) -- everything else in Section 3 is entirely unaffected |
| `READ_PHONE_STATE` | Required jointly with fine location for `TelephonyCallback.CellInfoListener` | `TelephonyCallback.CellInfoListener` | Yes -- dangerous/runtime | Yes -- same as above, tied to capability 8 only | Same as above -- capability 8 alone degrades; nothing else affected |
| `ACCESS_WIFI_STATE` | Most `WifiManager` calls require it in addition to (not instead of) the location permission above, for the same Wi-Fi feature | `WifiManager` generally | No -- normal, install-time | Only avoidable by not implementing capability 7 at all (same as fine location) | Same as `ACCESS_FINE_LOCATION`'s row -- tied to capability 7 |
| `ACCESS_BACKGROUND_LOCATION` | Would only ever be needed if a future feature reads Wi-Fi/cellular info *while the app is backgrounded* | N/A -- not needed by anything in Section 3 as scoped (all captures are foreground/on-demand) | Yes -- highest-scrutiny of all, separate runtime request, Play-policy-reviewed | **Yes, fully avoidable** -- nothing in this contract's scope needs background capture | Not applicable -- this permission should not be requested until a specific, written, Play-policy-reviewed justification exists (unchanged from `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 10) |
| Android 16+ local-network runtime permission (Section 8) | Only if a future feature ever targets local-network broadcast/multicast/LAN addresses | Sending to local-network address ranges on a device with Local Network Protections enforced | Yes, per current documentation (phased rollout) | **Yes, fully avoidable** -- none of capabilities 1-10 as designed target local-network addresses; they all target remote endpoints | Not applicable to anything in this contract's current scope; recorded so a future local-network feature (e.g. "diagnose my router") does not silently assume `INTERNET` alone suffices |

**Explicitly not requested by this document, per this task's
instructions:** `INTERNET`, `ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`,
`ACCESS_WIFI_STATE` are all documented above as eventually required, but
none is added to the manifest by this commit.

## 5. Task 4 -- Physical device contract

What must eventually be validated on real hardware, and why an
emulator cannot substitute, restated specifically for this contract
(not a verbatim repeat of `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section
13's full list):

- **Wi-Fi**: real SSID/RSSI values and real scan-throttling behavior --
  emulator Wi-Fi is virtual and does not exercise the same code paths.
- **Cellular**: real `CellInfo`/signal-strength values, OEM/radio
  reporting quirks, and real-world `requestCellInfoUpdate()`
  rate-limiting -- emulators report synthetic or absent cellular signal
  entirely.
- **Network transitions**: real Wi-Fi<->cellular handoffs, including
  cases where both are briefly simultaneously available -- not reliably
  reproducible with a single virtual network.
- **Poor connectivity**: real packet loss, real high-latency conditions,
  and real captive-portal interception -- an emulator's host-machine
  network path does not reproduce carrier-grade degradation or portal
  behavior.
- **Metered network**: real user-configured or carrier-signaled metered
  status, and real behavior of any future feature respecting
  `NetworkState.metered` under real data-cap pressure.
- **Location permission states**: real user flows through
  foreground/approximate/precise/denied location, including the
  Android-12+ "only this time"/approximate-only paths -- an emulator can
  simulate the permission dialog but not real user behavior around it.
- **SIM/carrier behavior**: real multi-SIM handling, real carrier-specific
  `CellInfo` reporting differences, real eSIM behavior.
- **VPN**: real interaction between an active third-party VPN and
  AERIVA's own socket probes (AERIVA does not implement `VpnService`
  itself -- `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 11, unchanged --
  but a user's own active VPN affects every socket-level capability in
  Section 3, and that interaction is real-device-only).
- **Captive portal**: real portal-interception behavior distinct from
  genuine unreachability -- directly relevant to Task 9's HTTP-vs-DNS
  distinction (Section 3, HTTPS row) and not reproducible without a real
  portal-gated network.
- **OEM battery restrictions**: real background-killing behavior on at
  least one aggressively-restrictive OEM skin -- `PHASE_2_ANDROID_PLATFORM_AUDIT.md`
  Section 13's own finding that this is the single least-standardized
  area in the whole platform, restated here because it applies equally
  to any future on-demand measurement that happens to run just as the
  OS decides to restrict the app.

**None of the above is claimed as validated by this document.** This is
a specification of what must be tested, not a report that testing
occurred -- consistent with this task's own instruction and with
`PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md` Section 19's identical
discipline.

## 6. Task 5 -- Android -> domain boundary

Reviewed `PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md` and the
`core:model` measurement package (`Confidence`, `Freshness`,
`MeasurementFailure`, `MeasurementNetworkContext`, `LatencyMeasurement`,
`LatencyEstimation`, `LatencyPrediction`, `DerivedLatencyStats`,
`ConnectivityRecommendation`) directly, this pass, not from memory of
having read them once before.

**What an Android adapter should provide to the domain/measurement
layer**, per capability:

- **Latency/jitter/packet-loss/throughput (1-4)**: a
  `LatencyMeasurement.Succeeded`/`Failed` (or the equivalent type once
  jitter/packet-loss/throughput get their own `core:model` types beyond
  Phase 3A's illustrative latency-only skeleton), populated with a
  `MeasurementNetworkContext` built from the already-existing
  `NetworkState` (via `NetworkMonitor`/`ConnectionStateRepository`) --
  an Android adapter should never construct its own competing
  connectivity snapshot, per `PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md`
  Section 6's already-established "Connectivity state: not a new seam"
  rule.
- **Wi-Fi/cellular characteristics (7-8), if ever implemented**: exactly
  the `MeasurementNetworkContext.wifiRssi`/`cellularSignalStrength`
  fields Phase 3A already scaffolded and deliberately left null --
  **no incompatibility found**; the domain type already has the correct
  optional slot waiting for this data, gated on the permission decision
  Phase 2 Section 10 defers.
- **DNS responsiveness (9)**: should populate an ESTIMATION-tier type
  (not yet defined in `core:model` beyond the illustrative
  `LatencyEstimation` example), carrying an explicit "estimation method"
  field naming the caching confound (Section 3, Section 1 finding 3) --
  this is exactly the domain model's own required field
  (`PHASE_3A_NETWORK_MEASUREMENT_DOMAIN_MODEL.md` Section 7: "estimation
  method... a named, specific method"), not a generic label.
- **HTTPS reachability (10)**: fits the same MEASUREMENT-tier shape as
  latency (a directly-observed, timestamped result with a validity/
  failure state), with its own `MeasurementFailure`-shaped distinction
  between "unreachable," "DNS failure," and "reached but returned an
  error status" -- see Section 3's HTTPS row and the hardening-analog
  discipline it references. `MeasurementFailure`'s existing cases
  (`Timeout`, `Cancelled`, `EndpointFailure`, `TlsFailure`,
  `InvalidResponse`, `NetworkChangedDuringMeasurement`) already cover
  this distinction structurally -- **no incompatibility found**; an
  HTTP 5xx maps to `InvalidResponse` (a response was received and was
  itself the failure signal) while true unreachability maps to
  `EndpointFailure` or `Timeout`, and these must not be conflated by
  whoever implements the provider.

**No concrete incompatibility between Section 3's Android capabilities
and Phase 3A's existing domain types was found.** Per this task's own
instruction, the domain model is therefore not touched by this
document. The one open item Phase 3A itself already flagged and left
open (`NetworkQuality.Measured(score, label)`'s relationship to this
richer lineage, Phase 3A Section 18 item 1) remains open, unchanged by
this review.

## 7. Task 6 -- API lifecycle

| API | Registration | Unregistration | Cancellation | Process-lifetime concern | Leak/duplicate risk | Rapid-transition behavior |
|---|---|---|---|---|---|---|
| `ConnectivityManager.registerDefaultNetworkCallback` | Already implemented -- `AndroidNetworkMonitor`'s `callbackFlow`, on `Flow` collection start | Already implemented -- `awaitClose`, on `Flow` cancellation | Coroutine-native, via `Flow` cancellation | Held via `ConnectionStateRepository`'s `SharingStarted.Eagerly` -- deliberately kept alive process-lifetime, per that class's own documented rationale (a transition during the gap between last-unsubscribe and next-subscribe must not be missed) | Low -- one registration for the app's entire lifetime, far from the 100-per-UID ceiling (Section 1's re-confirmed limit) | Deliberately zero-debounced on loss events specifically so a rapid transition is never silently coalesced away (existing `AndroidNetworkMonitor` documentation) |
| `TelephonyCallback` (if capability 8 is ever implemented) | Must be registered via `TelephonyManager.registerTelephonyCallback` with an `Executor`, scoped to the lifetime of whatever needs cellular data | Must be explicitly unregistered via `TelephonyManager.unregisterTelephonyCallback` -- **not** implicit on garbage collection | Should be tied to a coroutine scope/`callbackFlow`, following the exact `AndroidNetworkMonitor` pattern already established in this codebase, not a new pattern | Should **not** default to process-lifetime like the network callback above -- cellular info is higher-sensitivity data (Section 4) and should be registered only while actively needed, then unregistered, not held eagerly | A future implementation must not register a second `TelephonyCallback` without unregistering the first -- same discipline as the network-callback 100-per-UID ceiling, though `TelephonyCallback` is not itself part of that specific shared pool |
| `WifiInfo` via `ConnectivityManager.registerNetworkCallback(...)`/`FLAG_INCLUDE_LOCATION_INFO` (if capability 7 is ever implemented) | **Corrected in `PHASE_3B_ANDROID_CONTRACT_FINAL_FACT_CHECK.md`**: the current recommended path is callback-registration-based (`onCapabilitiesChanged`), not the single synchronous call this row previously described for the now-deprecated `WifiManager.getConnectionInfo()`. Register a dedicated `NetworkCallback` (with `FLAG_INCLUDE_LOCATION_INFO`) scoped to the lifetime of whatever needs Wi-Fi data | Must be explicitly unregistered via `unregisterNetworkCallback`, same discipline as every other `NetworkCallback` in this table -- **not** implicit, and **not** a fire-and-forget single call as previously stated | Should be tied to a coroutine scope/`callbackFlow`, following the exact `AndroidNetworkMonitor` pattern already established in this codebase | Should not default to process-lifetime -- register only while Wi-Fi data is actively needed, then unregister, same reasoning as the `TelephonyCallback` row above | **Now shares the same 100-outstanding-requests-per-UID pool as every other `registerNetworkCallback`/`requestNetwork` registration (Section 1)** -- a real lifecycle/leak consideration this row's prior (incorrect) single-call description did not surface at all; a future implementation must not register a second Wi-Fi-observing callback without unregistering the first | A rapid Wi-Fi transition (e.g. AP roam) fires `onCapabilitiesChanged` again with fresh `WifiInfo` -- unlike the deprecated single-call form, the callback-based path is not vulnerable to reading stale info between a connectivity change and a separate query, since the same callback delivers both |
| Any future measurement request (sockets for capabilities 1-4, 9-10) | Per-invocation -- no persistent registration; each measurement opens and fully closes its own socket(s) | Must close in a `finally`/`use`-equivalent block guaranteeing closure on both success and failure/cancellation paths -- this is the primary hardening concern a future implementer must get right, and is explicitly out of this document's scope to implement (Task 10) | Must respect `kotlinx.coroutines` structured concurrency -- a cancelled measurement coroutine must not leave a socket open; `withTimeout`/`withContext(NonCancellable)` boundaries need explicit design when implemented | Per-measurement, not process-lifetime -- unlike the network callback, there is no reason a socket-level probe should ever outlive its own coroutine | N/A (no registration) but socket-leak risk is the direct analog -- a provider implementation must be reviewed for exactly this the way the hardening-analog task (referenced in Section 3) does for a measurement-engine branch |

## 8. Task 7 -- Background execution

Restating and sharpening the already-established position
(`PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 6/11,
`PHASE_3_NETWORK_MEASUREMENT_TEST_STRATEGY.md` Section 6's "scheduler
seam: explicitly not resolved"), not deciding it:

- **User-initiated measurement** (user opens the app, taps "check now"):
  requires nothing beyond what Section 3/4 already describes -- no
  `WorkManager`, no foreground service, no special background-execution
  handling, because it runs while the app is foregrounded.
- **Foreground measurement** (app is in the foreground but the user did
  not explicitly trigger this specific check, e.g. an auto-refresh on
  screen open): same as above -- foreground execution has none of the
  restrictions Sections below describe.
- **Periodic measurement** (repeats on a schedule without the user
  reopening the app): requires `WorkManager` (still not added -- Section
  0), and **`WorkManager` does not provide real-time or exact-interval
  scheduling** -- current official documentation states periodic work
  has a 15-minute floor (`PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS`,
  re-confirmed this pass) and is explicitly deferrable/Doze-aware, never
  a "runs at time X" guarantee. A design that needs tighter-than-15-minute
  cadence cannot use periodic `WorkManager` at all and needs a
  different, separately-justified mechanism (chained `OneTimeWorkRequest`
  is a known community pattern for this, with its own tradeoffs -- not
  evaluated further here, as doing so would be scheduling design, which
  this task excludes).
- **Background measurement** (app is not foregrounded at all): subject
  to Doze/App Standby, and, if ever running from within a foreground
  service's own worker (uncommon for this use case -- no current AERIVA
  goal needs a foreground service, Phase 2 audit Section 11, unchanged),
  now also subject to the Android 16 background-job-quota extension
  (Section 1 finding 1) even for `WorkManager`-scheduled work started
  from such a service. This is a real, current-platform fact this
  contract records; it does not change the already-correct conclusion
  that no current AERIVA goal needs a foreground service.

**Not decided by this document:** which scheduling mechanism, if any,
periodic measurement should eventually use, and at what cadence -- both
remain the explicitly deferred decisions Phase 2/Phase 3 already
correctly left open.

## 9. Task 8 -- Android 16+ platform changes relevant to AERIVA

Stated precisely, not overstated:

- **Local Network Protections (LNP)** -- Section 1 finding 1. Real,
  current, phased-rollout (25Q2-26Q2) restriction on socket traffic to
  local-network broadcast/multicast/LAN address ranges, gated behind a
  new runtime permission once enforced. **Does not affect any capability
  in Section 3** as currently scoped -- every probe in this contract
  targets a remote, internet-routable endpoint, never a local-network
  address. Recorded as a forward-looking constraint only: if a future
  AERIVA feature ever probes the local gateway/router directly (a
  plausible future request, e.g. "is my router the problem"), that
  feature would need this permission and is out of this document's
  scope to design.
- **Background-job runtime quotas extended to jobs started from a
  foreground service** -- Section 1 finding 1, Section 8. Relevant only
  if a foreground service is ever justified (currently: never, per
  unchanged Phase 2 Section 11 reasoning); does not affect on-demand,
  foreground, or ordinary `WorkManager`-scheduled work.
- **No Android 16 change was found that alters any permission
  requirement, API availability, or classification in Section 3's
  table.** This is stated as an explicit negative finding, not a gap in
  research -- Section 1 lists exactly what was checked.

## 10. Task 9 -- Implementation checklist

For a future engineer to satisfy before implementing Android
measurement integration:

**Permissions**
- [ ] `INTERNET` added to the manifest, as its own isolated change,
  before or alongside the first provider that needs it (Section 4 --
  still not added by this document).
- [ ] `ACCESS_FINE_LOCATION`/`READ_PHONE_STATE`/`ACCESS_WIFI_STATE`
  added only if/when capabilities 7-8 are individually, explicitly
  justified in writing (Phase 2 audit Section 10, unchanged).

**API lifecycle**
- [ ] Every registered callback (`TelephonyCallback`, any future
  `WifiManager` scan registration) has a matching, guaranteed
  unregistration path, following the exact pattern
  `AndroidNetworkMonitor` already establishes (Section 7).
- [ ] No new callback registration risks approaching the 100-per-UID
  ceiling shared across `registerNetworkCallback`/`requestNetwork`/
  `ConnectivityDiagnosticsManager` (Section 1).

**Network availability**
- [ ] Every provider consults `NetworkState`/`ConnectionStateRepository`
  (the existing single source of truth) rather than querying platform
  connectivity state a second time.
- [ ] Every provider consults this contract's
  `MeasurementCapabilityClassifier` (Section 12) before attempting a
  probe, so a capability already classified `NotReliablyAvailable` is
  never silently attempted.

**Timeouts**
- [ ] DNS timeout, connection timeout, and read/overall-measurement
  timeout are distinct, separately-configured values -- not one
  timeout reused for all three (a direct requirement of the
  measurement-engine hardening task this contract is adjacent to, not
  invented here).

**Cancellation**
- [ ] A cancelled measurement coroutine closes any open socket and does
  not produce a `Succeeded` result.

**Resource cleanup**
- [ ] Every socket/stream opened by a provider is closed on every exit
  path (success, failure, cancellation, exception during close).

**Battery**
- [ ] Latency/jitter run more frequently than packet-loss; packet-loss
  more frequently than throughput -- Section 8's cost ordering, applied
  as an actual, written numeric budget before implementation (Phase 2
  audit Section 14 exit criterion, still open).

**Data usage**
- [ ] Throughput defaults to infrequent/opt-in/Wi-Fi-or-unmetered-aware,
  consulting the already-exposed `NetworkState.metered` field.

**Privacy**
- [ ] `MeasurementNetworkContext.wifiRssi`/`cellularSignalStrength`
  remain null until the corresponding permission decision (Section 4)
  is made and documented in writing.

**Physical-device validation**
- [ ] Every item in Section 5 is tested on real hardware before being
  treated as validated -- not assumed from emulator or documentation
  research alone.

**OEM behavior**
- [ ] No single fix is assumed to generalize across OEM
  battery-optimization behavior (Phase 2 audit Section 13, unchanged --
  the single least-standardized area in this entire platform).

**Failure handling**
- [ ] "No response," "slow response," "connection failure,"
  "measurement timeout," and "known loss" (where the protocol actually
  provides evidence of loss) are represented as distinct outcomes, never
  collapsed into one generic failure -- the same discipline Task 6 of
  the measurement-engine hardening review requires, restated here as
  the Android-adapter-side half of that same requirement.

## 11. Task 10 -- Code

**One concrete correctness problem was found and fixed, as this task's
instructions require ("only change the classifier if a concrete
correctness problem is identified... explain exactly why, cite the
authoritative documentation... add/update only directly relevant
tests").**

- **Defect:** `MeasurementCapabilityClassifier.classify()` classified
  `MeasurementCapability.DNS_RESPONSIVENESS` as `Supported` --
  grouped with `LATENCY`/`JITTER`/`HTTPS_REACHABILITY` under the same
  `if (INTERNET granted) Supported` branch.
- **Why this is wrong:** `PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md`'s
  own capability table (row 9) classifies DNS responsiveness as
  ESTIMATED, explicitly because a timed lookup is confounded by
  OS/carrier/resolver caching and is therefore an indirect proxy, not a
  direct measurement. This task's own instructions state the same
  principle directly: "Do not label an estimate as a direct
  measurement." The code contradicted the report and this task's own
  stated principle -- a real internal inconsistency, not a matter of
  interpretation.
- **Authoritative documentation cited for the fix:**
  `LinkProperties.getDnsServers()`/`isPrivateDnsActive()`/
  `getPrivateDnsServerName()` current official reference documentation
  (Section 1) -- confirms these APIs expose *which* resolver is
  configured (an observation) with no method anywhere in
  `LinkProperties`'s current API surface that reports resolver
  *responsiveness*, reinforcing that any responsiveness figure must come
  from an app-level timed operation subject to caching, matching the
  ESTIMATION-tier reasoning.
- **Change made:** `DNS_RESPONSIVENESS` now has its own `when` branch,
  returning `CapabilityClassification.Estimated(reason)` (INTERNET
  granted) or `NotReliablyAvailable` (not granted), instead of being
  grouped with the `Supported`-tier capabilities. No other branch was
  touched. `MeasurementCapability`, `CapabilityClassification`, and
  every other capability's classification are unchanged -- the
  classifier's responsibilities were not expanded, per this task's own
  instruction.
- **Tests added:** two new tests in
  `MeasurementCapabilityClassifierTest.kt` --
  `dnsResponsiveness_withInternetPermission_isEstimated_notSupported()`
  (the direct regression test for this defect) and
  `dnsResponsiveness_withoutInternetPermission_isNotReliablyAvailable()`
  (parity with every other INTERNET-gated capability's existing
  no-permission test). No existing test was modified or removed; the
  existing `everyCapability_hasAClassification_exhaustiveWhenCompiles()`
  structural test continues to pass unchanged against the now-fixed
  `when`.

No other code change was made. No new file was added beyond this
document.

## Validation

**No Gradle build or test run was executed.** Per this repository's
own unchanged, already-documented constraint (`PHASE_1_VALIDATION_REPORT.md`,
re-confirmed in `PHASE_3B_ANDROID_MEASUREMENT_CAPABILITY_REPORT.md`'s
own Validation section, re-confirmed again this session): this
sandbox's network egress does not reach Maven Central or Google's Maven
repository, so no Gradle invocation here can resolve dependencies. This
is unrelated to the repository now having a committed Gradle wrapper.

What was actually done to reduce risk given that constraint, this pass:

- Both modified files were re-read in full after editing.
- Brace/parenthesis balance was mechanically re-checked after the edit
  (main file: 18 open/18 close, 53/53 parens; test file: 13/13,
  55/55) -- a weak, honestly-reported signal, not a substitute for
  compilation.
- The `when (capability)` in `classify()` was manually re-verified to
  still cover all 10 `MeasurementCapability` enum cases with no `else`
  branch, after splitting `DNS_RESPONSIVENESS` into its own arm --
  confirming the exhaustiveness property the accompanying structural
  test exists to protect did not silently break during the edit.
- **TESTS NOT EXECUTED.** This project's own CI
  (`.circleci/config.yml`) will run against this branch once pushed.
  This document does not claim to have seen that result --
  `circleci.com` is outside this sandbox's network allowlist. A human or
  a session with CI access must confirm the actual result before this
  code, including the fix in Section 11, is considered validated.

## Executive summary

Every claim in the prior capability report that this pass re-checked
against current official documentation held up unchanged. Two genuinely
new, current-technology findings were surfaced this pass: Android 16's
Local Network Protections (real, but inapplicable to any capability
AERIVA currently designs for, since none targets local-network
addresses) and `LinkProperties`'s DNS-observation surface (real, useful
context data, and itself confirming why DNS timing belongs in the
ESTIMATION tier rather than MEASUREMENT). One concrete defect was found
by re-inspecting the actual prior code rather than only its prose: the
classifier had silently classified DNS responsiveness as a direct
measurement, contradicting both the report it shipped alongside and
this task's own explicit instruction -- fixed, minimally, with a
regression test. No domain-model incompatibility was found between
Phase 3A's existing types and what an Android adapter for any of the ten
capabilities would need to provide. No permission, dependency, UI, or
scheduling decision was made -- every one of those remains exactly as
open as Phase 2/3A/3B's own prior documents left it, restated here as a
checklist (Section 10) rather than resolved.
