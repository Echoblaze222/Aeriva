# Network Advisor, Phase A specification

Branch `network-advisor-phase-a-spec`, based on `phase-4-reducer-stale-callback-fix` @ `ebee5f6fbd18`.
Documentation only. No Kotlin, manifest, Gradle, dependency, permission, endpoint, credential or CI file is changed.

## 1. Status (read this first)

| Item | Status |
|---|---|
| This document | DESIGN ONLY. Nothing described here is implemented. |
| Android platform facts in section 3 | CONFIRMED from current official documentation, except where marked UNCONFIRMED. |
| Repository facts in section 3 | CONFIRMED by reading the tree at `ebee5f6`. |
| Any behavior on real devices | NOT VERIFIED. No device or emulator was used. |
| Owner approval | The owner approved the three step Network Advisor plan (Phase A selector, Phase B Wi-Fi suggestions, Phase C VPN steering). Only Phase A is specified here. |

## 2. Product statement

Today a user with a weak connection moves around looking for signal. Network Advisor reverses that: AERIVA watches every network the device can currently use, and tells the user which one is working best right now and why, so the user does not have to hunt.

Technical truth that bounds this feature (from the AERIVA skills package):
* AERIVA cannot create signal, amplify radio, or connect to networks the user has no credentials for.
* AERIVA must not claim an improvement it did not measure.
* A recommendation must show evidence, confidence and freshness, and must say when evidence is insufficient.

### Non-goals for Phase A
* No automatic switching of any network.
* No `VpnService`, no `WifiNetworkSuggestion`, no SIM or mobile data control.
* No new permission in A1. No dependency in A1.
* No upload of any data. Everything stays on the device.
* No single generic quality score.

## 3. Verified facts

### 3.1 Platform (official Android documentation)
1. `ConnectivityManager.bindProcessToNetwork` binds only the calling process, and its documentation states it does not support multiple connected networks of the same type. Consequence: AERIVA can steer its own probe traffic, not other apps, and dual SIM (two cellular networks) cannot be separated by transport type alone. CONFIRMED.
2. `WifiNetworkSuggestion` (API 29 and later) lets an app add credentials for auto connect, but the platform makes the final selection, and a network the user manually disconnects from is ignored while in range. Out of scope here, relevant to Phase B. CONFIRMED.
3. `VpnService.Builder.setUnderlyingNetworks` exists since API 22. Relevant to Phase C only. CONFIRMED.
4. Changing the default data subscription or toggling mobile data requires carrier privileges or the system permission `MODIFY_PHONE_STATE`. A normal app cannot do this. AERIVA may only recommend and deep link to system settings. CONFIRMED.
5. `NetworkCallback` documentation warns against synchronous `ConnectivityManager` lookups inside callback bodies. The existing monitor already follows this and A1 must too. CONFIRMED (cited in `AndroidNetworkMonitor` KDoc).
6. Whether a device keeps a cellular network connected while Wi-Fi is the default network varies by device and settings. UNCONFIRMED. This decides how often two candidate networks are visible at once, and must be measured on real devices before any claim about how often the advisor can help.
7. `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` (API 29) as the deep link target for the user to change network. UNCONFIRMED against the pinned SDK and minSdk 26 fallback. Verify before implementation.

### 3.2 Repository (`ebee5f6`)
1. `AndroidNetworkMonitor` uses `registerDefaultNetworkCallback`. It observes only the default network. It cannot see a second available network. CONFIRMED.
2. `app` and `network:monitor` declare no `INTERNET` permission. `ACCESS_NETWORK_STATE` is declared. CONFIRMED.
3. `MeasurementEndpointConfig.current` is `null`. No endpoint exists. `NetworkClient` has no production implementation (only a fake). CONFIRMED.
4. `NetworkState` already carries `validated`, `metered`, `captivePortalReported`, `vpnPresent`, `blockedByDevicePolicy`, `transport`. CONFIRMED.
5. `ConnectivityRecommendation.Recommended` has `target` and `activityProfile` as `String`. It is a Phase 3A skeleton. CONFIRMED.
6. `NetworkQuality.Measured(score, label)` is an open Phase 3A risk (a generic score). Network Advisor must not use it. CONFIRMED (owner decision on record).
7. Project owner decisions on record: `INTERNET` approved in principle for the future outbound measurement phase only. OkHttp conditionally approved for that phase only, after official compatibility verification. Neither is to be added now. CONFIRMED.

## 4. Scope split

The research found that active measurement cannot start yet, so Phase A is split.

| Part | What it does | New permission | Blocked by |
|---|---|---|---|
| A1 Passive advisor | Observes every candidate network, compares observable facts, recommends only when the facts justify it | None beyond `ACCESS_NETWORK_STATE` | Nothing. Can start after PR #3 is resolved. |
| A2 Active advisor | Probes each candidate network for latency, jitter, loss, and ranks per activity | `INTERNET` | Owner approval of the outbound phase, endpoint decision (OD-2, OD-3, OD-4), a real `NetworkClient`, OkHttp compatibility check or a socket decision, `NetworkQuality.Measured` resolution |

A1 is useful alone. Example: "Wi-Fi is connected but has no validated internet. Mobile data is validated." That is a real, verifiable fact, needs no probing, and solves a common daily problem.

## 5. A1 design

### 5.1 Candidate discovery
New interface in `network:monitor` (no `android.net` types in its signature):

```kotlin
interface AvailableNetworksMonitor {
    fun observe(): Flow<List<CandidateNetwork>>
}
```

Backed by `registerNetworkCallback` with a `NetworkRequest` requiring `NET_CAPABILITY_INTERNET`, one registration covering all transports. It is separate from the existing default network monitor. The existing `NetworkMonitor` and its reducer are not modified.

`CandidateNetwork` (in `core:model`):
* `id`: opaque, process local identifier. Never persisted, never uploaded. Derived from the platform network handle inside the Android implementation only.
* `transport: TransportType`
* `validated`, `metered`, `captivePortalReported`, `blockedByDevicePolicy`, `vpnPresent`: same meaning as in `NetworkState`.
* `isDefault: Boolean`
* `observedAt: Instant`

Not included: SSID, BSSID, carrier name, cell id, signal strength, location. Reading these needs location permission, which A1 must not request. Two candidates of the same transport (dual SIM, two Wi-Fi) are distinguished only by `id`, and the UI must label them neutrally ("Mobile data 1", "Mobile data 2") because AERIVA cannot name them without extra permission.

### 5.2 Event ordering
The candidate tracker must reuse the guarantees of `NetworkEventReducer` for every tracked network: after `Lost(n)`, a late `CapabilitiesChanged(n)` or `BlockedStatusChanged(n)` must not resurrect `n`. A pure Kotlin reducer, JVM tested, written before the Android adapter, same as the existing monitor. No blocking sends in callbacks. Delivery failure handling follows whatever the pending reliability audit decides for `trySend`, and A1 must not copy the current unhardened pattern without that decision.

### 5.3 Selection rules (deterministic, explainable, no scores)
Evaluated in order. Each rule names its reason in the output.

1. Remove candidates with `blockedByDevicePolicy = true`. Reason: the device policy blocks traffic.
2. If the default network has `validated = false` or `captivePortalReported = true`, and at least one other candidate is `validated = true` and not blocked: recommend that candidate. Criteria text: "Current network has no validated internet; this network does.".
3. If the default network is validated and there is only one candidate: `NoRecommendation("Only one network is available")`.
4. If two or more candidates are validated: `NoRecommendation("Not enough measured evidence to compare")` plus an informational line when `metered` differs ("Wi-Fi is not metered, mobile data is metered"). No ranking without measurement. This is the A2 boundary.
5. If a VPN is present: state that the underlying transport comparison may not reflect VPN performance. No recommendation that implies the VPN is the cause.

Confidence for any A1 recommendation is stated as based on platform reported validation, not on AERIVA measurement, and is never shown as High or as a number. It uses the existing `Confidence` type only if the owner accepts mapping "platform validated" to a tier; otherwise A1 shows no confidence tier and says "Based on Android's own connectivity check". This is an open decision (OD-A1-1).

### 5.4 Anti flapping
* A recommendation requires the triggering condition to hold continuously for a minimum dwell time (initial proposal: 10 seconds, to be tuned on device).
* After a recommendation is shown, the same recommendation is not re shown for a cooldown (initial proposal: 5 minutes) unless the facts change.
* A recommendation is withdrawn immediately if its condition ends.
* All timing uses the injected clock, tested with a controllable clock.
* The values above are placeholders, not validated numbers.

### 5.5 Presentation and user action
* A card on the Home screen. No notification in A1.
* Copy follows the brand rules: factual, no em dash, no emoji, no "boost", no guarantee. Example: "Wi-Fi has no working internet right now. Mobile data does. Switch in system settings."
* The action opens the system network settings. AERIVA does not switch anything itself.
* States to implement: idle (no recommendation), recommendation shown, single network only, no network, evidence insufficient, permission not required, stale (candidate list not updated). No spinner is needed in A1 because nothing is measured.

### 5.6 Privacy
* No location, no identifiers, no upload, no persistence of candidate lists.
* Logs must not contain network handles beyond the process local `id`.

## 6. A2 design outline (gated, not for implementation yet)

Prerequisites, all required:
1. Owner approves the outbound measurement phase (`INTERNET`).
2. Endpoint decision recorded (OD-2, OD-3, OD-4).
3. Real `NetworkClient`. Per network probing uses the platform's per network socket binding, so each probe goes over the intended candidate and not the default network. Whether this works the same on all supported API levels is UNCONFIRMED.
4. OkHttp compatibility verified against the pinned toolchain, or a documented decision to use plain sockets.
5. `NetworkQuality.Measured` resolved, and `ConnectivityRecommendation.target` and `activityProfile` replaced with typed values.
6. Data cost rules: no probing of a metered network without explicit user consent and a stated byte budget per probe.

Outputs: per candidate, per activity profile (gaming, calls, streaming, downloads, general), with evidence ids, sample count, freshness and confidence, as the Community Connectivity Intelligence spec requires. Contradicting evidence must be shown, not hidden.

## 7. Test plan

JVM (no Android):
* Candidate reducer: lost then late capabilities does not resurrect; lost then late blocked does not resurrect; two candidates of the same transport stay distinct; default flag moves correctly.
* Selection rules: every rule in 5.3 has a positive and a negative test; rule ordering is tested; the VPN case is tested.
* Anti flapping with a controllable clock: dwell, cooldown, withdrawal.
* No test may assert a ranking between two validated candidates in A1.

Instrumented:
* Registration and unregistration of the callback; no leak after cancellation.

Real device matrix (required before calling A1 done; emulator results do not count):
Wi-Fi only; mobile only; both connected; Wi-Fi without internet; captive portal; airplane mode; dual SIM; VPN active; battery saver; transition mid recommendation. Record device model, Android version and OEM for each. Include the section 3.1 item 6 question: how often are two candidates visible.

## 8. Risks and open decisions

| Id | Item | Label |
|---|---|---|
| R1 | Devices may rarely keep two networks up, which would make A1 rarely visible | UNCONFIRMED |
| R2 | Platform validation can be slow or wrong, so a recommendation could be briefly misleading | LIKELY |
| R3 | Dual SIM networks cannot be named without extra permission | CONFIRMED limitation |
| R4 | Candidate callback delivery faces the same `trySend` reliability questions as the default monitor | LIKELY |
| OD-A1-1 | How to express confidence for a platform validation based recommendation | OPEN, owner |
| OD-A1-2 | Dwell and cooldown values | OPEN, tune on device |
| OD-A1-3 | Whether A1 may begin before PR #3 and the reliability hardening are resolved | OPEN, owner |

## 9. Definition of done for A1

* Section 5 behavior implemented with the tests in section 7 passing in CI.
* No new permission, no new dependency, no endpoint.
* Device matrix completed and reported with exact devices and results.
* Documentation states implemented, partial and not verified work separately.
* The final report uses: Changed, Decisions, Verification, Not verified, Risks, Next.

## 10. Sources

* Android `ConnectivityManager` reference, `bindProcessToNetwork` and `requestNetwork`: developer.android.com/reference/android/net/ConnectivityManager
* Wi-Fi suggestion API for internet connectivity: developer.android.com/develop/connectivity/wifi/wifi-suggest
* `VpnService.Builder.setUnderlyingNetworks`: Android reference, API 22
* `TelephonyManager` data control and opportunistic subscription methods, permission requirements: Android reference
* Repository files read at `ebee5f6`: `AndroidNetworkMonitor.kt`, `NetworkClient.kt`, `MeasurementEndpointConfig.kt`, `NetworkState.kt`, `NetworkQuality.kt`, `ConnectivityRecommendation.kt`, `Confidence.kt`, both manifests.
