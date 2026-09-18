# AERIVA Phase 3B -- Android Contract Final Fact Check

Narrow, final fact-check of
`PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md` against
current official Android documentation. Not a redo of AI 4's
independent Phase 3B validation, not a new capability analysis, and not
a rewrite of the contract -- a targeted verification of every load-bearing
claim in that document, with corrections only where a concrete factual
defect was found.

## Repository state

- Branched from `phase-3b-android-contract` at commit
  `a67b74db27dc4cbb8a0ff959c9cc0491f5de74f7` -- confirmed present in
  this checkout's history before any new commit.
- `main` unchanged: `e3f70a4a13e63b782c61601abadc2c36f65dbd73`.

## Method

Every one of the 20 items this task lists was checked against current
official documentation (`developer.android.com` reference and guide
pages, cross-checked against AOSP source and independent citations of
the same official pages where useful) fetched fresh this pass -- not
answered from pretrained knowledge. Where a fetch reconfirmed something
the contract already stated correctly, that is recorded as CONFIRMED
with its own citation, not silently assumed correct because the prior
document said so.

## Finding requiring correction

**`WifiManager.getConnectionInfo()` is deprecated since API level 31.**
Current official reference documentation
(`developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo()`,
cross-confirmed via multiple independent citations of that exact page)
states plainly: "This method was deprecated in API level 31. Starting
with `Build.VERSION_CODES#S`, `WifiInfo` retrieval is moved to
`ConnectivityManager` API surface. `WifiInfo` is attached in
`NetworkCapabilities#getTransportInfo()` which is available via
callback in `NetworkCallback#onCapabilitiesChanged(Network,
NetworkCapabilities)` or on-demand from
`ConnectivityManager#getNetworkCapabilities(Network)`."

The contract's Section 3 Wi-Fi-characteristics row, Section 4
permission-contract table, and Section 7 API-lifecycle table all cited
`WifiManager.getConnectionInfo()` as the mechanism, with Section 7 in
particular characterizing it as "a single synchronous call" needing "no
persistent registration." That characterization was wrong in a way that
matters for exactly the API-lifecycle discipline Section 7 exists to
document: the current recommended path is callback-registration-based
(`ConnectivityManager.registerNetworkCallback(...)` with
`NetworkCallback.FLAG_INCLUDE_LOCATION_INFO`, reading
`NetworkCapabilities.getTransportInfo()` cast to `WifiInfo`), which
means it needs explicit registration, explicit unregistration, and
counts toward the same 100-outstanding-requests-per-UID pool as every
other `NetworkCallback`/`requestNetwork` registration -- none of which
the deprecated single-call description surfaced.

**The permission requirement itself is unaffected.** `ACCESS_FINE_LOCATION`
is still required for location-sensitive `WifiInfo` fields (SSID/BSSID)
under the current path -- confirmed via `WifiInfo`'s own current
reference documentation ("In the connected state, access to location
sensitive fields requires the same permissions as
`WifiManager.getScanResults`. If such access is not allowed, `getSSID()`
will return `WifiManager.UNKNOWN_SSID`...") and via a cross-checked
independent report of the exact current permission set for the
`ConnectivityManager`-based path (`ACCESS_FINE_LOCATION`, dangerous, plus
`ACCESS_WIFI_STATE`, normal) -- so Section 4's `ACCESS_WIFI_STATE` row
did not need correction and was re-confirmed as-is. Only the API
surface citation and the lifecycle characterization were wrong.

**Correction made (minimal, this branch):**
- `PHASE_3B_ANDROID_MEASUREMENT_IMPLEMENTATION_CONTRACT.md`: the three
  affected table cells (Section 3 Wi-Fi row, Section 4
  `ACCESS_FINE_LOCATION` row, Section 7 lifecycle row) were corrected in
  place to cite the current `ConnectivityManager`/`NetworkCallback`
  path and to re-describe the lifecycle implication accurately
  (registration/unregistration required; shares the 100-per-UID pool).
  No other content in the contract was changed.
- `MeasurementCapabilityClassifier.kt`: the code comment above the
  `WIFI_CHARACTERISTICS` branch cited `WifiManager.getConnectionInfo()`
  by name. Corrected to cite the current API surface. **No
  classification logic changed** -- the permission gate
  (`ACCESS_FINE_LOCATION`) and the returned `CapabilityClassification`
  are identical before and after this edit, because the permission
  requirement itself did not change (confirmed above). This is a
  comment-only correctness fix, not a behavioral one.
- **No regression test was added for this specific correction.** Per
  this task's own instruction ("add/update a regression test where
  appropriate"): a test asserts observable behavior, and this
  correction changed no observable behavior of `classify()` -- the
  input/output mapping for `WIFI_CHARACTERISTICS` is byte-for-byte
  identical before and after the edit (verified by re-reading the
  full file post-edit). Adding a test here would test that a comment
  says a particular string, which is not a meaningful regression
  test. The existing `wifiCharacteristics_withFineLocation_isSupportedWithLimitations()`
  and `wifiCharacteristics_withoutFineLocation_isNotReliablyAvailable()`
  tests (from the prior commit) already fully cover this branch's
  actual behavior and were re-verified to still pass conceptually
  against the corrected code (same permission check, same branches).

No other concrete factual defect was found in the contract this pass.

## Full claim table

| # | Claim (as stated in the contract) | Status | Current official source | Required action |
|---|---|---|---|---|
| 1 | `WifiManager.getConnectionInfo()` cited as the Wi-Fi-info API; described as a single synchronous call needing no registration | **CORRECTED** | `developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo()` -- deprecated since API 31; modern path is `ConnectivityManager.registerNetworkCallback`/`NetworkCapabilities.getTransportInfo()` | Contract Sections 3/4/7 and classifier comment corrected this pass (see above); no manifest, permission, or classification change |
| 2 | Wi-Fi SSID/BSSID/RSSI requires `ACCESS_FINE_LOCATION` at every currently-supported API level | CONFIRMED | `WifiInfo` current reference docs ("access to location sensitive fields requires the same permissions as `WifiManager.getScanResults`"); independently cross-checked permission summary for the current `ConnectivityManager` path | None |
| 3 | `ACCESS_FINE_LOCATION` gates location-sensitive Wi-Fi fields regardless of which API surface (deprecated or current) is used | CONFIRMED | Same as #2 -- permission requirement is orthogonal to the API-surface correction in #1 | None |
| 4 | `NEARBY_WIFI_DEVICES` is a distinct permission for Wi-Fi *management*/nearby-device APIs (`startLocalOnlyHotspot()`, `WifiP2pManager`, `WifiRttManager`), not a substitute for `ACCESS_FINE_LOCATION` when reading current-connection `WifiInfo` | CONFIRMED (this contract never claimed otherwise, but the item is explicitly re-verified per this task's checklist) | `developer.android.com/develop/connectivity/wifi/wifi-permissions` (current guide) | None -- worth stating explicitly if this contract is ever extended to Wi-Fi management APIs (out of scope here) |
| 5 | `TelephonyManager.getAllCellInfo()`/`TelephonyCallback.CellInfoListener` are the cellular-info APIs; API 29+ `getAllCellInfo()` without `requestCellInfoUpdate()` may return a cached result | CONFIRMED | `TelephonyManager`/`TelephonyCallback` current reference docs (re-checked this pass, unchanged from the prior report's own citation) | None |
| 6 | `TelephonyCallback.CellInfoListener` requires **both** `READ_PHONE_STATE` and `ACCESS_FINE_LOCATION` | CONFIRMED | `TelephonyCallback.CellInfoListener` current reference docs (re-checked this pass; this was itself a correction the prior capability report made to an earlier, less-precise Phase 2 audit claim -- re-verified still accurate) | None |
| 7 | `ACCESS_FINE_LOCATION` is required jointly (not alternatively) with `READ_PHONE_STATE` for cellular info | CONFIRMED | Same as #6 | None |
| 8 | `ConnectivityManager.registerNetworkCallback`/`requestNetwork` share a 100-outstanding-requests-per-UID ceiling, throwing `TooManyRequestsException` | CONFIRMED | `ConnectivityManager` current reference docs + cross-checked real-world crash reports (re-checked this pass, unchanged) | None |
| 9 | `ConnectivityDiagnosticsManager` callbacks are silently never invoked for an app that is not a carrier app, active VPN, or Wi-Fi Suggester | CONFIRMED | `ConnectivityDiagnosticsManager`/`registerConnectivityDiagnosticsCallback` current reference docs and AOSP source (re-checked this pass, unchanged) | None |
| 10 | `INTERNET` is required for every active-measurement capability (latency, jitter, packet loss, throughput, DNS timing, HTTPS reachability) and is not yet declared in the manifest | CONFIRMED | Normal Android permission model (install-time, no runtime dialog) + direct repository inspection this pass (`network:monitor`'s manifest still declares only `ACCESS_NETWORK_STATE`) | None -- still not added, per this task's instruction |
| 11 | Android 12 introduces `TelephonyCallback` as the modern replacement for `PhoneStateListener` for cellular signal | CONFIRMED | `TelephonyCallback` current reference docs (unchanged) | None |
| 12 | Android 14 makes foreground-service-type declaration mandatory for any FGS | CONFIRMED | `develop/background-work/services/fgs/changes` (re-checked this pass, unchanged) | None -- not currently triggered by any capability in this contract |
| 13 | Android 15 adds a `dataSync`/`mediaProcessing` FGS execution cap (~6 hours) | CONFIRMED | `about/versions/15/behavior-changes-15` (re-checked this pass, unchanged) | None -- not currently triggered |
| 14 | Android 16 extends background-job runtime quotas to jobs started from a foreground service, including `WorkManager` jobs | CONFIRMED | `about/versions/16/behavior-changes-16` / `behavior-changes-all` (re-checked this pass, unchanged) | None -- not currently triggered (no foreground service planned) |
| 15 | No Android 16 change alters any permission requirement, API availability, or classification for capabilities 1-10 | **REQUIRES DECISION (informational addendum, not a defect)** -- Android 16 Local Network Protections (LNP) is a real, current, phased-rollout (25Q2-26Q2) restriction on local-network socket traffic, gated behind a new runtime permission once enforced | `about/versions/16/behavior-changes-16` (re-checked this pass) | No action required for this contract as scoped -- none of capabilities 1-10 target local-network addresses. Flagged as a future decision point only if a local-network-diagnostic feature (e.g. "check my router") is ever proposed |
| 16 | Background measurement (app not foregrounded) is subject to Doze/App Standby; `WorkManager` periodic work has a 15-minute floor | CONFIRMED | `PeriodicWorkRequest` current reference docs (`MIN_PERIODIC_INTERVAL_MILLIS`, re-checked this pass, unchanged) | None |
| 17 | `WorkManager` does not provide real-time or exact-interval scheduling; periodic work is explicitly deferrable/Doze-aware | CONFIRMED | Same as #16 | None |
| 18 | A user's own active third-party VPN affects every socket-level capability (1-4, 9-10), since Android routes all app traffic through an active VPN by default unless the VPN app excludes the package or AERIVA explicitly binds to a different network via `ConnectivityManager.bindProcessToNetwork()`/`Network.bindSocket()` | CONFIRMED | `developer.android.com/develop/connectivity/vpn` (current guide: "To send traffic through a specific network, apps call methods such as `ConnectivityManager.bindProcessToNetwork()` or `Network.bindSocket()`"); VPN app-exclusion behavior confirmed via current Android Enterprise VPN documentation | None -- AERIVA still does not implement `VpnService` itself (unchanged, out of scope) |
| 19 | `LinkProperties.getDnsServers()`/`isPrivateDnsActive()`/`getPrivateDnsServerName()` expose which DNS resolver is configured and whether private DNS is active, as a no-extra-permission observation distinct from measuring that resolver's responsiveness | CONFIRMED | `LinkProperties` current reference docs (re-checked this pass, matches the prior contract's own Section 1 finding) | None |
| 20 | Real Wi-Fi RSSI, real cellular `CellInfo`, real network-transition, and real captive-portal/OEM-battery behavior cannot be produced or meaningfully substituted by an emulator | NOT VERIFIABLE WITHOUT HARDWARE | Consistent with `PHASE_2_ANDROID_PLATFORM_AUDIT.md` Section 13 and current Android emulator documentation's own description of virtualized radio state; this claim is about physical-device behavior, which by definition no documentation review can confirm or refute -- it can only be confirmed by the physical-device testing Section 5 of the contract already requires | Physical-device validation still required before this claim is treated as verified, per the contract's own Section 5 |

## Validation

**No Gradle build or test run was executed.** Same unchanged sandbox
network constraint as every prior Phase 3B document (Maven Central /
Google Maven outside this container's allowlist). Brace/paren balance
was re-checked mechanically after the comment-only classifier edit
(18/18 open/close, main file; test file unchanged at 13/13 since no
test was added or modified). The edit was manually re-read in full
against the surrounding code to confirm no classification logic changed.
**TESTS NOT EXECUTED.** CircleCI result on this branch still needs
confirming by a session with CI access.

## Summary

One concrete factual defect was found and corrected: `WifiManager.getConnectionInfo()`,
cited three times across the contract and once in the classifier's own
code comment, is deprecated since API 31. The permission requirement it
gates (`ACCESS_FINE_LOCATION`) is unchanged, so no manifest, permission,
or classification logic changed -- only the API-surface citation and
the Section 7 lifecycle characterization (which meaningfully changes
from "single call, no registration" to "callback-registration-based,
shares the 100-per-UID pool"). One informational addendum was added
(Android 16 Local Network Protections) without altering any conclusion,
since it does not apply to any of AERIVA's ten scoped capabilities.
Every other claim checked against current official documentation this
pass held up unchanged. Nineteen of twenty items are CONFIRMED or
correctly scoped as requiring future decision/hardware; one item was
corrected.
